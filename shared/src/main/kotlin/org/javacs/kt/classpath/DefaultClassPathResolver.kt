package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.javacs.kt.util.AsyncExecutor
import org.jetbrains.exposed.sql.Database
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.FileSystems

private val async = AsyncExecutor

fun defaultClassPathResolver(workspaceRoots: Collection<Path>, db: Database? = null): ClassPathResolver {
    // Process multiple workspace roots in parallel
    val resolvers = if (workspaceRoots.size > 1) {
        async.ioMap(workspaceRoots.toList()) { root ->
            workspaceResolvers(root).toList()
        }.flatten()
    } else {
        workspaceRoots.flatMap { workspaceResolvers(it) }
    }

    val childResolver = WithStdlibResolver(
        resolvers.joined
            .or(ShellClassPathResolver.global(workspaceRoots.firstOrNull()))
    ).or(BackupClassPathResolver)

    return db?.let { CachedClassPathResolver(childResolver, it) } ?: childResolver
}

/** Searches the workspace for all files that could provide classpath info. */
private fun workspaceResolvers(workspaceRoot: Path): Sequence<ClassPathResolver> {
    val ignored: List<PathMatcher> = ignoredPathPatterns(workspaceRoot, workspaceRoot.resolve(".gitignore"))
    return folderResolvers(workspaceRoot, ignored).asSequence()
}

/** Searches the folder for all build-files. */
private fun folderResolvers(root: Path, ignored: List<PathMatcher>): Collection<ClassPathResolver> =
    root.toFile()
        .walk()
        .onEnter { file -> ignored.none { it.matches(file.toPath()) } }
        .mapNotNull { asClassPathProvider(it.toPath()) }
        .toList()

/** Tries to read glob patterns from a gitignore. */
private fun ignoredPathPatterns(root: Path, gitignore: Path): List<PathMatcher> =
    gitignore.toFile()
        .takeIf { it.exists() }
        ?.readLines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") }
        ?.map { it.removeSuffix("/") }
        ?.let { it + listOf(
            // Patterns that are ignored by default
            ".git"
        ) }
        ?.mapNotNull { try {
            LOG.debug("Adding ignore pattern '{}' from {}", it, gitignore)
            FileSystems.getDefault().getPathMatcher("glob:$root**/$it")
        } catch (e: Exception) {
            LOG.warn("Did not recognize gitignore pattern: '{}' ({})", it, e.message)
            null
        } }
        ?: emptyList()

/** Tries to create a classpath resolver from a file using as many sources as possible */
private fun asClassPathProvider(path: Path): ClassPathResolver? =
    MavenClassPathResolver.maybeCreate(path)
        ?: GradleClassPathResolver.maybeCreate(path)
        ?: ShellClassPathResolver.maybeCreate(path)
