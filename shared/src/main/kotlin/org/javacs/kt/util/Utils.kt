package org.javacs.kt.util

import org.javacs.kt.LOG
import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

fun execAndReadStdout(shellCommand: List<String>, directory: Path): String {
    val process = ProcessBuilder(shellCommand).directory(directory.toFile()).start()
    val stdout = process.inputStream
    return stdout.bufferedReader().use { it.readText() }
}

fun execAndReadStdoutAndStderr(shellCommand: List<String>, directory: Path): Pair<String, String> {
    val process = ProcessBuilder(shellCommand).directory(directory.toFile()).start()
    val stdout = process.inputStream
    val stderr = process.errorStream
    var output = ""
    var errors = ""
    val outputThread = Thread { stdout.bufferedReader().use { output += it.readText() } }
    val errorsThread = Thread { stderr.bufferedReader().use { errors += it.readText() } }
    outputThread.start()
    errorsThread.start()
    outputThread.join()
    errorsThread.join()
    return Pair(output, errors)
}

inline fun withCustomStdout(delegateOut: PrintStream, task: () -> Unit) {
    val actualOut = System.out
    System.setOut(delegateOut)
    try {
        task()
    } finally {
        System.setOut(actualOut)
    }
}

private const val WIN_PATH_MIN_LENGTH = 3

fun winCompatiblePathOf(path: String): Path {
    check(path.length >= WIN_PATH_MIN_LENGTH) { "Path too short for winCompatiblePathOf: $path" }
    return if (path[2] == ':' && path[0] == '/') {
        // Strip leading '/' when dealing with paths on Windows
        Paths.get(path.substring(1))
    } else {
        Paths.get(path)
    }
}

fun String.partitionAroundLast(separator: String): Pair<String, String> = lastIndexOf(separator)
    .let { idx ->
        check(idx >= 0) { "Separator '$separator' not found in '$this'" }
        Pair(substring(0, idx), substring(idx, length))
    }

fun Path.replaceExtensionWith(newExtension: String): Path {
	val oldName = fileName.toString()
	val dot = oldName.lastIndexOf(".")
	check(dot >= 0) { "No extension found in '$oldName'" }
	val newName = oldName.substring(0, dot) + newExtension
	return resolveSibling(newName)
}

inline fun <T, C : Iterable<T>> C.onEachIndexed(transform: (index: Int, T) -> Unit): C = apply {
    for ((i, element) in this.withIndex()) {
        transform(i, element)
    }
}

fun <T> noResult(message: String, result: T): T {
    LOG.info(message)
    return result
}

fun <T> noFuture(message: String, contents: T): CompletableFuture<T> = noResult(message, CompletableFuture.completedFuture(contents))

fun <T> emptyResult(message: String): List<T> = noResult(message, emptyList())

fun <T> nullResult(message: String): T? = noResult(message, null)

fun <T> firstNonNull(vararg optionals: () -> T?): T? {
    for (optional in optionals) {
        val result = optional()
        if (result != null) {
            return result
        }
    }
    return null
}

fun <T> nonNull(item: T?, errorMsgIfNull: String): T =
    item ?: throw NullPointerException(errorMsgIfNull)

inline fun <T> tryResolving(what: String, resolver: () -> T?): T? {
    try {
        val resolved = resolver()
        if (resolved != null) {
            LOG.info("Successfully resolved {} to {}", what, resolved)
            return resolved
        } else {
            LOG.info("Could not resolve {} as it is null", what)
        }
    } catch (e: Exception) {
        LOG.info("Could not resolve {}: {}", what, e.message)
    }
    return null
}
