package org.javacs.kt.externalsources

import org.javacs.kt.LOG

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Describes the JDK source archive that was located, relative to the running JVM.
 *
 * @property path absolute path to `lib/src.zip`.
 * @property majorVersion JDK major version parsed from the archive's parent directory name.
 * @property runningMajorVersion JDK major version of the running JVM (from `java.version`).
 * @property isExactMatch true iff [majorVersion] == [runningMajorVersion].
 * @property direction whether the chosen src.zip is the same, newer, or older than the running JVM.
 */
data class JdkSrcZipResult(
    val path: String,
    val majorVersion: Int,
    val runningMajorVersion: Int,
    val isExactMatch: Boolean,
    val direction: Direction,
) {
    enum class Direction { EXACT, NEWER, OLDER }
}

/**
 * Locates a JDK `lib/src.zip` to use as the source for `java.*` / `javax.*` hover, signature, and
 * source-archive lookups.
 *
 * Resolution order:
 * 1. [override] - if set and the file exists, return it as EXACT.
 * 2. Exact major match - walk OS-specific JDK install roots, find a directory whose major
 *    version equals the running JVM's, with `lib/src.zip` present.
 * 3. Closest newer - smallest major version strictly greater than the running JVM's, with src.zip.
 * 4. Closest older - largest major version strictly less than the running JVM's, with src.zip.
 * 5. None - return `null`.
 *
 * Non-exact matches log a `WARN` line so users can see why their hover output is annotated.
 */
object JdkSrcZipLocator {

    private val DEFAULT_SEARCH_ROOTS: List<String> = listOf(
        // Linux (Debian/Ubuntu/Arch/Fedora)
        "/usr/lib/jvm",
        "/opt/jdk",
        "/usr/java",
        // macOS
        "/Library/Java/JavaVirtualMachines",
        "/opt/homebrew/opt",
        // Windows
        "C:\\Program Files\\Java",
        "C:\\Program Files\\Eclipse Adoptium",
    )

    private val MAJOR_REGEX = Regex("""(\d+)""")
    // In the case we encounter a pre-Java-9 legacy naming (i.e., java-1.8.0-openjdk, jdk1.7.0_80),
    // we extract the real major, which is 8.x, not 1.8.x.
    private val LEGACY_MAJOR_REGEX = Regex("""[^.]1\.(\d{1,2})\.0""")
    private const val FALLBACK_RUNNING_MAJOR = Int.MAX_VALUE

    /** Convenience overload using the current process and platform-default search roots. */
    fun resolve(override: String?): JdkSrcZipResult? =
        resolve(detectRunningMajor(), override, DEFAULT_SEARCH_ROOTS, detectJvmHome())

    /**
     * Resolves a JDK `lib/src.zip` to use for `java.*` / `javax.*` source lookups.
     *
     * @param runningMajor JDK major version of the running JVM (from `java.version`).
     * @param override absolute path to a `lib/src.zip` that wins over auto-detection.
     * @param searchRoots directories to scan for JDK install subdirectories.
     * @param jvmHome the running JVM's home directory (`java.home`); its own `lib/src.zip` is
     * always considered so JDK sources resolve regardless of install root coverage. `null` disables it.
     */
    fun resolve(
        runningMajor: Int,
        override: String?,
        searchRoots: List<String>,
        jvmHome: String? = null,
    ): JdkSrcZipResult? {
        if (override != null) {
            val overrideResult = resolveOverride(override, runningMajor)
            if (overrideResult != null) return overrideResult
        }
        // Search roots first, then the running JVM home as a guaranteed fallback. Concatenating after
        // the roots preserves existing selection behavior while filling the gap where an actively-used
        // JDK lives outside the conventional installation roots (SDKMAN, IDE-bundled, CI).
        val candidates = (discoverCandidates(searchRoots) + runningJvmCandidate(jvmHome, runningMajor))
            .distinctBy { it.path }
        if (candidates.isEmpty()) return null
        return pickClosest(candidates, runningMajor)
    }

    /** Absolute path of the running JVM's home, or `null` if unavailable. */
    private fun detectJvmHome(): String? =
        System.getProperty("java.home")?.takeIf { it.isNotBlank() }

    /**
     * The running JVM's own `lib/src.zip` if present, tagged with [runningMajor] (exact by
     * construction). Returns empty when [jvmHome] is `null` or the archive is missing (JRE-only).
     */
    private fun runningJvmCandidate(jvmHome: String?, runningMajor: Int): List<Candidate> {
        if (jvmHome == null) return emptyList()
        val srcZip = File(jvmHome, "lib/src.zip")
        if (!srcZip.isFile) return emptyList()
        return listOf(Candidate(srcZip.absolutePath, runningMajor))
    }

    private fun pickClosest(candidates: List<Candidate>, runningMajor: Int): JdkSrcZipResult? {
        candidates.firstOrNull { it.majorVersion == runningMajor }?.let { exact ->
            return exact.toResult(JdkSrcZipResult.Direction.EXACT, runningMajor)
        }
        candidates.filter { it.majorVersion > runningMajor }
            .minByOrNull { it.majorVersion }
            ?.let { newer ->
                LOG.warn(
                    "JDK source archive for Java {} not found; using Java {} (newer) sources. Hover output will be annotated with the version mismatch.",
                    runningMajor, newer.majorVersion,
                )
                return newer.toResult(JdkSrcZipResult.Direction.NEWER, runningMajor)
            }
        candidates.filter { it.majorVersion < runningMajor }
            .maxByOrNull { it.majorVersion }
            ?.let { older ->
                LOG.warn(
                    "JDK source archive for Java {} not found; using Java {} (older) sources. Some symbols may be missing.",
                    runningMajor, older.majorVersion,
                )
                return older.toResult(JdkSrcZipResult.Direction.OLDER, runningMajor)
            }
        return null
    }

    private fun resolveOverride(override: String, runningMajor: Int): JdkSrcZipResult? {
        val overridePath = Paths.get(override)
        if (!Files.exists(overridePath)) {
            LOG.warn("Configured jdkSourceOverride '{}' does not exist; falling back to auto-detection", override)
            return null
        }
        LOG.info("Using configured JDK source archive: {}", override)

        val overrideMajor = parseMajorVersion(overridePath.parent?.parent?.fileName?.toString())
            ?: runningMajor

        return JdkSrcZipResult(
            path = overridePath.toString(),
            majorVersion = overrideMajor,
            runningMajorVersion = runningMajor,
            isExactMatch = overrideMajor == runningMajor,
            direction = JdkSrcZipResult.Direction.EXACT,
        )
    }

    private fun detectRunningMajor(): Int =
        parseMajorVersion(System.getProperty("java.version")) ?: FALLBACK_RUNNING_MAJOR

    private data class Candidate(val path: String, val majorVersion: Int)

    private fun discoverCandidates(searchRoots: List<String>): List<Candidate> {
        val results = mutableListOf<Candidate>()
        for (root in searchRoots) {
            scanRoot(root, results)
        }
        return results.distinctBy { it.path }
    }

    private fun scanRoot(root: String, results: MutableList<Candidate>) {
        val dir = File(root)
        if (!dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            val candidate = candidateFromJdkDir(child) ?: continue
            results.add(candidate)
        }
    }

    private fun candidateFromJdkDir(child: File): Candidate? {
        if (!child.isDirectory) return null
        val srcZip = File(child, "lib/src.zip")
        if (!srcZip.isFile) return null
        val major = parseMajorVersion(child.name) ?: return null
        return Candidate(srcZip.absolutePath, major)
    }

    private fun Candidate.toResult(direction: JdkSrcZipResult.Direction, runningMajor: Int) =
        JdkSrcZipResult(
            path = path,
            majorVersion = majorVersion,
            runningMajorVersion = runningMajor,
            isExactMatch = direction == JdkSrcZipResult.Direction.EXACT,
            direction = direction,
        )

    /**
     * Best-effort extraction of a JDK major version from a directory name.
     *
     * Handles common conventions: `java-25-openjdk`, `jdk-21.0.4`, `openjdk-17`, `temurin-21.jdk`,
     * and the pre-Java-9 legacy scheme `java-1.8.0-openjdk`.
     */
    fun parseMajorVersion(input: String?): Int? {
        if (input.isNullOrBlank()) return null

        // If we get pre-Java-9 legacy naming, we extract the real major
        val legacyMatch = LEGACY_MAJOR_REGEX.find(input)
        if (legacyMatch != null) return legacyMatch.groupValues[1].toIntOrNull()

        // Modern naming: java-21-openjdk, jdk-21.0.4, openjdk-17
        val match = MAJOR_REGEX.find(input) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun parseMajorVersion(path: Path?): Int? = parseMajorVersion(path?.fileName?.toString())
}
