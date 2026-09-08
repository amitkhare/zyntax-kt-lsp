package org.javacs.kt

import org.javacs.kt.util.filePath
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path

class SourceExclusions(
    private val workspaceRoots: Collection<Path>,
    private val scriptsConfig: ScriptsConfiguration,
    private val userExcludedPatterns: List<String> = emptyList(),
    private val generatedSourceRoots: List<String> = emptyList()
) {
    private val defaultExcludedPatterns = listOf(
        ".git", ".hg", ".svn",                                                      // Version control systems
        ".idea", ".idea_modules", ".vs", ".vscode", ".code-workspace", ".settings", // IDEs
        "bazel-*", "bin", "build", "node_modules", "target",                        // Build systems
    )

    private val scriptsExcludedPatterns = when {
        !scriptsConfig.enabled -> listOf("*.kts")
        !scriptsConfig.buildScriptsEnabled -> listOf("*.gradle.kts")
        else -> emptyList()
    }

    val excludedPatterns: List<String>
        get() = defaultExcludedPatterns + scriptsExcludedPatterns + projectExcludedPatterns + userExcludedPatterns

    private val projectExcludedPatterns: List<String>
        get() = workspaceRoots.flatMap { root ->
            KlsFolder.readExclusions(root)
        }

    private val exclusionMatchers = excludedPatterns
        .map { FileSystems.getDefault().getPathMatcher("glob:$it") }

    /** Finds all non-excluded files recursively. */
    fun walkIncluded(): Sequence<Path> = workspaceRoots.asSequence().flatMap { root ->
        root.toFile()
            .walk()
            .onEnter { isPathIncluded(it.toPath()) }
            .map { it.toPath() }
    }

    /** Tests whether the given URI is not excluded. */
    fun isURIIncluded(uri: URI) = uri.filePath?.let(this::isPathIncluded) ?: false

    /** Tests whether the given path is not excluded.
     *  Paths under generatedSourceRoots are included even if under excluded parents. */
    fun isPathIncluded(file: Path): Boolean = workspaceRoots.any { file.startsWith(it) }
        && (isUnderGeneratedSourceRoot(file) || !isExcludedByPattern(file))

    /** Checks if file is under any of the configured generated source roots. */
    private fun isUnderGeneratedSourceRoot(file: Path): Boolean = workspaceRoots.any { root ->
        generatedSourceRoots.any { sourceRoot ->
            val sourceRootPath = root.resolve(sourceRoot)
            file.startsWith(sourceRootPath)
        }
    }

    /** Checks if file matches any exclusion pattern. */
    private fun isExcludedByPattern(file: Path): Boolean = exclusionMatchers.any { matcher ->
        workspaceRoots
            .mapNotNull { if (file.startsWith(it)) it.relativize(file) else null }
            .flatMap { it }
            .any(matcher::matches)
    }
}
