package org.javacs.kt.classpath

import com.dynatrace.hash4j.hashing.Hashing

import org.javacs.kt.LOG

import java.nio.file.Files
import java.nio.file.Path

/**
 * Hashes a build file's content (e.g. `build.gradle.kts`, `pom.xml`) to a 64-bit value.
 *
 * Used by [ClassPathResolver.currentBuildFileVersion] to detect *content* changes rather
 * than just filesystem mtime changes. Hashing the content is robust to `git checkout`,
 * IDE safe-write, `touch`, and other operations that update mtime without changing bytes.
 *
 * Failures (file missing, unreadable, IO error) return [NO_BUILD_FILE] so the resolver
 * degrades to "no build file" rather than throwing into the cache layer.
 */
object BuildFileHashing {
    /** Sentinel meaning "no build file / unreadable / no contribution". `0L` is the identity for xor. */
    const val NO_BUILD_FILE: Long = 0L

    /**
     * Compute xxHash3-64 of the file's bytes.
     *
     * @return the 64-bit hash, or [NO_BUILD_FILE] on any IO error
     */
    fun hash(file: Path): Long {
        return try {
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                NO_BUILD_FILE
            } else {
                Hashing.xxh3_64().hashBytesToLong(Files.readAllBytes(file))
            }
        } catch (e: Exception) {
            LOG.warn("Failed to hash build file {}: {}", file, e.message)
            NO_BUILD_FILE
        }
    }
}
