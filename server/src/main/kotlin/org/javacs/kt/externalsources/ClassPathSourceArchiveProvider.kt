package org.javacs.kt.externalsources

import org.javacs.kt.CompilerClassPath
import org.javacs.kt.LOG
import org.javacs.kt.util.AsyncExecutor
import java.io.File
import java.nio.file.Path

class ClassPathSourceArchiveProvider(
    private val cp: CompilerClassPath
) : SourceArchiveProvider {
    private val async = AsyncExecutor

    override fun fetchSourceArchive(compiledArchive: Path): Path? {
        // First try: Check if we have a cached sourceJar in the classpath entry
        val cachedSource = cp.classPath.firstOrNull { it.compiledJar == compiledArchive }?.sourceJar
        if (cachedSource != null) {
            LOG.debug("Using cached source JAR for {}: {}", compiledArchive, cachedSource)
            return cachedSource
        }

        // Fallback: Search for source JAR at lookup time
        LOG.info("Source JAR not cached for {}, searching...", compiledArchive)
        return findSourcesJarAtLookupTime(compiledArchive)?.also {
            LOG.info("Found source JAR at lookup time: {}", it)
        }
    }

    /**
     * Searches for a source JAR in the same directory tree as the compiled JAR.
     * This handles cases where source JARs weren't associated at build time.
     */
    private fun findSourcesJarAtLookupTime(compiledJar: Path): Path? {
        val jarFile = compiledJar.toFile()
        val dir = jarFile.parentFile
        if (dir == null) {
            LOG.warn("No parent directory for {}", compiledJar)
            return null
        }

        val baseName = jarFile.nameWithoutExtension

        // Try patterns: -sources.jar, -sources.zip
        val patterns = listOf(
            "$baseName-sources.jar",
            "$baseName-sources.zip"
        )

        // First: Check same directory
        for (pattern in patterns) {
            val sourceFile = File(dir, pattern)
            if (sourceFile.exists()) {
                LOG.debug("Found source JAR in same directory: {}", sourceFile)
                return sourceFile.toPath()
            }
        }

        // Second: Check parent directory's subdirectories (Gradle cache layout)
        // Use parallel search with early termination via virtual threads
        val parentDir = dir.parentFile
        if (parentDir == null) {
            LOG.warn("No source JAR found for {} in {}", compiledJar, dir)
            return null
        }

        val subdirs = parentDir.listFiles { f -> f.isDirectory } ?: emptyArray()

        val result = if (subdirs.isEmpty()) {
            null
        } else {
            // Search subdirectories in parallel, stopping at first match
            async.ioMapFirstOrNull(subdirs.toList()) { subdir ->
                patterns.firstNotNullOfOrNull { pattern ->
                    val sourceFile = File(subdir, pattern)
                    if (sourceFile.exists()) sourceFile else null
                }
            }
        }

        if (result != null) {
            LOG.debug("Found source JAR in parent subdirectory: {}", result)
        } else {
            LOG.warn("No source JAR found for {} in {} or parent subdirectories", compiledJar, dir)
        }

        return result?.toPath()
    }
}
