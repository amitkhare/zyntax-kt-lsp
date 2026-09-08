package org.javacs.kt.util

import org.javacs.kt.LOG

import java.util.function.Supplier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object AsyncExecutor {
    // PSI analysis is bursty, not sustained. We keep a modest pool for concurrent
    // LSP requests (hover, diagnostics, completions) without starving the editor.
    private const val CPU_USAGE_FACTOR = 0.5
    private const val MIN_PARALLELISM = 2
    private const val MAX_PARALLELISM = 8

    private val maximumParallelism = (Runtime.getRuntime().availableProcessors() * CPU_USAGE_FACTOR)
        .toInt()
        .coerceIn(MIN_PARALLELISM, MAX_PARALLELISM)
    private val cpuBoundThread = Executors.newFixedThreadPool(maximumParallelism) {
        Thread(it, "async")
    }
    private val ioBoundThread = Executors.newVirtualThreadPerTaskExecutor()

    fun execute(task: () -> Unit) =
        CompletableFuture.runAsync(Runnable(task), cpuBoundThread)

    fun <R> compute(task: () -> R) =
        CompletableFuture.supplyAsync(Supplier(task), cpuBoundThread)

    fun <R> computeOr(defaultValue: R, task: () -> R?) =
        CompletableFuture.supplyAsync({
            try {
                task() ?: defaultValue
            } catch (_: Exception) {
                defaultValue
            }
        }, cpuBoundThread)

    fun <R> ioCompute(task: () -> R): CompletableFuture<R> {
        LOG.trace("Submitting I/O task to virtual thread executor...")
        return CompletableFuture.supplyAsync({
            val result = task()
            LOG.trace("I/O task completed in virtual thread")
            result
        }, ioBoundThread)
    }

    fun <R> ioComputeOr(defaultValue: R, task: () -> R?): CompletableFuture<R> =
            CompletableFuture.supplyAsync({
                try {
                    task() ?: defaultValue
                } catch (_: Exception) {
                    defaultValue
                }
            }, ioBoundThread)

    fun <T, R> ioMap(items: Collection<T>, transform: (T) -> R): List<R> {
        val futures = items.map { item -> ioCompute { transform(item) } }
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply { futures.map { it.join() } }
            .get()
    }

    /**
     * Maps items to results in parallel, returning the first non-null result.
     * Uses whenComplete callbacks for efficient non-blocking composition.
     * Returns immediately when first non-null result is found; other tasks are canceled.
     *
     * @param items Collection of items to process
     * @param transform Transform function that returns null if no match
     * @return First non-null result, or null if all items returned null
     */
    fun <T, R> ioMapFirstOrNull(items: Collection<T>, transform: (T) -> R?): R? {
        if (items.isEmpty()) return null

        val resultFuture = CompletableFuture<R?>()
        val remaining = AtomicInteger(items.size)

        val futures = items.map { item ->
            ioCompute { transform(item) }
                .whenComplete { value, ex ->
                    when {
                        ex != null -> LOG.debug("Transform failed", ex)
                        value != null -> resultFuture.complete(value)
                    }
                    // If all futures are done and none matched, resolve with null
                    if (remaining.decrementAndGet() == 0) {
                        resultFuture.complete(null)
                    }
                }
        }

        return try {
            resultFuture.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            futures.forEach { it.cancel(true) }
        }
    }

    /**
     * Lazily maps a sequence of items to results in parallel.
     * Returns a Sequence that yields results as they become available.
     * Allows early termination without processing all items.
     *
     * @param items Sequence of items to process
     * @param transform Transform function that returns null if item should be skipped
     * @return Sequence of non-null results as they become available
     */
    fun <T, R> ioMapStream(items: Sequence<T>, transform: (T) -> R?, batchSize: Int = 10): Sequence<R> = sequence {
        val iterator = items.iterator()
        if (!iterator.hasNext()) return@sequence

        // Submit first batch of tasks
        val pendingFutures = mutableListOf<Pair<T, CompletableFuture<R?>>>()

        repeat(batchSize) {
            if (iterator.hasNext()) {
                val item = iterator.next()
                pendingFutures.add(item to ioCompute { transform(item) })
            }
        }

        // Yield results as they complete, refilling the batch
        while (pendingFutures.isNotEmpty()) {
            // Find first completed future
            val completedIndex = pendingFutures.indexOfFirst { it.second.isDone }

            if (completedIndex >= 0) {
                val (_, future) = pendingFutures.removeAt(completedIndex)
                try {
                    val result = future.get()
                    if (result != null) {
                        yield(result)
                    }
                } catch (_: Exception) {
                    // Skip failed items
                }

                // Refill batch if more items available
                if (iterator.hasNext()) {
                    val newItem = iterator.next()
                    pendingFutures.add(newItem to ioCompute { transform(newItem) })
                }
            } else {
                try {
                    pendingFutures
                        .map { it.second }
                        .reduce { acc, f -> acc.applyToEither(f) { it } }
                        .join()
                } catch (_: Exception) {
                    // A future failed: the inner loop will handle it on the next iteration
                }
            }
        }
    }

    fun <T, R> ioRace(items: Collection<T>, transform: (T) -> R?): R? {
        val futures = items.map { item -> ioCompute { transform(item) } }
        return futures.asSequence()
            .map { it.get() }
            .firstOrNull { it != null }
    }

    fun <T> ioRace(tasks: Collection<() -> T>): T? {
        val futures = tasks.map { task -> ioCompute { task() } }
        return futures.asSequence()
            .map { it.get() }
            .firstOrNull { it != null }
    }

    fun shutdown(awaitTermination: Boolean) {
        cpuBoundThread.shutdown()
        ioBoundThread.shutdown()
        if (awaitTermination) {
            LOG.info("Awaiting async termination...")
            cpuBoundThread.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
            ioBoundThread.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
        }
    }
}
