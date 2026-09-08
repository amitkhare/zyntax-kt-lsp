package org.javacs.kt

import org.javacs.kt.classpath.ClassPathEntry
import org.javacs.kt.classpath.ClassPathResolver
import org.javacs.kt.classpath.defaultClassPathResolver
import org.javacs.kt.compiler.Compiler
import org.javacs.kt.database.DatabaseService
import org.javacs.kt.index.JarIndex
import org.javacs.kt.util.AsyncExecutor

import java.io.Closeable
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Manages the class path (compiled JARs, etc.), the Java source path
 * and the compiler. Note that Kotlin sources are stored in SourcePath.
 */
class CompilerClassPath(
    private val config: CompilerConfiguration,
    private val scriptsConfig: ScriptsConfiguration,
    private val codegenConfig: CodegenConfiguration,
    private val databaseService: DatabaseService
) : Closeable {
    val workspaceRoots = mutableSetOf<Path>()

    private val javaSourcePath = mutableSetOf<Path>()
    private val buildScriptClassPath = mutableSetOf<Path>()
    val classPath = mutableSetOf<ClassPathEntry>()
    val outputDirectory: File = Files.createTempDirectory("klsBuildOutput").toFile()
    val javaHome: String? = System.getProperty("java.home", null)

    /**
     * Absolute path to a JDK `lib/src.zip` that should be used for `java.*` / `javax.*` source
     * lookups. When set, this overrides the auto-detected location. Synced from
     * [ExternalSourcesConfiguration.jdkSourceOverride] by [KotlinWorkspaceService].
     */
    var jdkSourceOverride: String? = null

    /** The current classpath resolver, created during refresh(). */
    private var classPathResolver: ClassPathResolver? = null

    /** The current build file version from the resolver, for cache invalidation. */
    val currentBuildFileVersion: Long
        get() = classPathResolver?.currentBuildFileVersion ?: 1L

    var compiler = Compiler(
        javaSourcePath,
        classPath.map { it.compiledJar }.toSet(),
        buildScriptClassPath,
        scriptsConfig,
        codegenConfig,
        outputDirectory
    )
        private set

    private val async = AsyncExecutor

    /** Cache for JAR index lookups: classPath -> (jarPath, sourceJarPath) */
    private val jarIndexCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Pair<String, String?>>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String?>?>): Boolean {
                return size > 1000
            }
        }
    )

    /** Persistent storage for JAR index */
    val jarIndex = JarIndex(databaseService)

    init {
        compiler.updateConfiguration(config)
    }

    /** Updates and possibly reinstantiates the compiler using new paths. */
    private fun refresh(
        updateClassPath: Boolean = true,
        updateBuildScriptClassPath: Boolean = true,
        updateJavaSourcePath: Boolean = true
    ): Boolean {
        // TODO: Fetch class path and build script class path concurrently (and asynchronously)
        val resolver = defaultClassPathResolver(workspaceRoots, databaseService.db)
        classPathResolver = resolver
        var refreshCompiler = updateJavaSourcePath

        if (updateClassPath) {
            // Fetch both main and test classpaths
            val newClassPath = resolver.classpathOrEmpty
            val newTestClassPath = resolver.testClasspathOrEmpty
            val combinedClassPath = newClassPath + newTestClassPath

            if (combinedClassPath != classPath) {
                synchronized(classPath) {
                    syncPaths(classPath, combinedClassPath, "class path") { it.compiledJar }
                }
                refreshCompiler = true
            }

            // Resolve sources (async, no result needed)
            async.compute {
                val newClassPathWithSources = resolver.classpathWithSources
                val newTestClassPathWithSources = resolver.testClasspathOrEmpty
                val combinedWithSources = newClassPathWithSources + newTestClassPathWithSources
                synchronized(classPath) {
                    syncPaths(classPath, combinedWithSources, "class path with sources") { it.compiledJar }
                }
            }
        }

        if (updateBuildScriptClassPath) {
            LOG.info("Update build script path")
            val newBuildScriptClassPath = resolver.buildScriptClasspathOrEmpty
            if (newBuildScriptClassPath != buildScriptClassPath) {
                synchronized(buildScriptClassPath) {
                    syncPaths(buildScriptClassPath, newBuildScriptClassPath, "build script class path") { it }
                }
                refreshCompiler = true
            }
        }

        if (refreshCompiler) {
            LOG.info("Reinstantiating compiler")
            compiler.close()
            val classPathSnapshot = synchronized(classPath) { classPath.map { it.compiledJar }.toSet() }
            val buildScriptSnapshot = synchronized(buildScriptClassPath) { buildScriptClassPath.toSet() }
            compiler = Compiler(
                javaSourcePath,
                classPathSnapshot,
                buildScriptSnapshot,
                scriptsConfig,
                codegenConfig,
                outputDirectory
            )
            updateCompilerConfiguration()
        }

        return refreshCompiler
    }

    /** Synchronizes the given two path sets and logs the differences. */
    private fun <T> syncPaths(dest: MutableSet<T>, new: Set<T>, name: String, toPath: (T) -> Path) {
        val added = new - dest
        val removed = dest - new

        logAdded(added.map(toPath), name)
        logRemoved(removed.map(toPath), name)

        dest.removeAll(removed)
        dest.addAll(added)
    }

    fun updateCompilerConfiguration() {
        compiler.updateConfiguration(config)
    }

    /**
     * Finds the JAR and optional source JAR that contains the given class.
     * @param classPath e.g., "java/util/List.class"
     * @return Pair of (jarPath, sourceJarPath) or null if not found
     */
    fun findJarContainingClass(classPath: String): Pair<String, String?>? {
        // Check the cache (including sentinel values)
        val cached = jarIndexCache[classPath]
        if (cached != null) {
            // Return null if it's the NOT_FOUND sentinel
            if (cached === NOT_FOUND) return null
            // If IN_PROGRESS, another thread is searching - wait briefly and return
            if (cached === IN_PROGRESS) return null
            return cached
        }

        // Check the database for an existing path
        jarIndex.findJar(classPath)?.let {
            jarIndexCache[classPath] = it
            return it
        }

        // Mark as "in progress" to prevent duplicate searches
        jarIndexCache[classPath] = IN_PROGRESS

        // Search JARs in parallel using virtual threads (I/O bound)
        val entries = this.classPath.toList()
        val result = try {
            async.ioRace(entries) { entry ->
                if (jarContainsClass(entry.compiledJar, classPath)) {
                    val compiledJarPath = entry.compiledJar.toString()
                    val sourceJarPath = entry.sourceJar?.toString()
                        ?: findSourcesJarInDirectory(compiledJarPath)
                    Pair(compiledJarPath, sourceJarPath)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to search for class {}: {}", classPath, e.message)
            LOG.printStackTrace(e)
            null
        }

        // Update cache with result (use NOT_FOUND sentinel for null results)
        if (result != null) {
            jarIndexCache[classPath] = result
            jarIndex.save(classPath, result.first, result.second)
        } else {
            jarIndexCache[classPath] = NOT_FOUND
        }

        return result
    }

    /** Sentinel value indicating a search is in progress */
    private val IN_PROGRESS = Pair<String, String?>(Sentinel.IN_PROGRESS.name, null)

    /** Sentinel value indicating the class was not found in any JAR */
    private val NOT_FOUND = Pair<String, String?>(Sentinel.NOT_FOUND.name, null)

    private enum class Sentinel { IN_PROGRESS, NOT_FOUND }

    /**
     * Attempts to find a sources JAR in the same directory as the compiled JAR.
     * Looks for: foo-sources.jar when given foo.jar
     */
    private fun findSourcesJarInDirectory(jarPath: String): String? {
        val jarFile = File(jarPath)
        val dir = jarFile.parentFile ?: return null
        val baseName = jarFile.nameWithoutExtension

        // Try common sources jar naming patterns in same directory
        val sourcesNames = listOf(
            "$baseName-sources.jar",
            "$baseName-sources.zip",
            "${baseName}-sources.jar"
        )

        for (name in sourcesNames) {
            val sourcesFile = File(dir, name)
            if (sourcesFile.exists()) {
                return sourcesFile.absolutePath
            }
        }

        // If not found, try searching parent directory (e.g., for Gradle cache with hash directories)
        val parentDir = dir.parentFile ?: return null
        return searchForSourcesJar(parentDir, baseName)
    }

    /**
     * Searches for a sources artifact (JAR or ZIP) in the given directory's subdirectories.
     *
     * Looks for files matching `{baseName}-sources.jar` or `{baseName}-sources.zip`.
     * Uses parallel search with early termination for performance.
     *
     * @param baseDir directory containing subdirectories to search
     * @param baseName file name prefix (e.g., "my-library-1.0")
     * @return absolute path to the first sources artifact found, or null
     */
    private fun searchForSourcesJar(baseDir: File, baseName: String): String? {
        val sourcesPatterns = listOf(
            "$baseName-sources.jar",
            "$baseName-sources.zip"
        )

        // Search in subdirectories (for Gradle cache hash directories)
        // Use parallel search with early termination via virtual threads
        val subdirs = baseDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        if (subdirs.isEmpty()) return null

        // Search subdirectories in parallel, stopping at first match
        return async.ioMapFirstOrNull(subdirs.toList()) { subdir ->
            sourcesPatterns.firstNotNullOfOrNull { pattern ->
                val file = File(subdir, pattern)
                if (file.exists()) file else null
            }
        }?.absolutePath
    }

    /**
     * Checks if a JAR contains the given class.
     */
    private fun jarContainsClass(jarPath: Path, classFilePath: String): Boolean {
        return try {
            ZipFile(jarPath.toFile()).use { zip ->
                zip.getEntry(classFilePath) != null
            }
        } catch (e: Exception) {
            LOG.debug("Error while checking jar path {} ({}): {}", classFilePath, jarPath.toFile(), e.message)
            e.printStackTrace(LOG.outStream)
            false
        }
    }

    fun addWorkspaceRoot(root: Path): Boolean {
        LOG.info("Searching for dependencies and Java sources in workspace root {}", root)

        workspaceRoots.add(root)
        javaSourcePath.addAll(findJavaSourceFiles(root))

        return refresh()
    }

    fun removeWorkspaceRoot(root: Path): Boolean {
        LOG.info("Removing dependencies and Java source path from workspace root {}", root)

        workspaceRoots.remove(root)
        javaSourcePath.removeAll(findJavaSourceFiles(root))

        return refresh()
    }

    fun createdOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.add(file)
        }
        return changedOnDisk(file)
    }

    fun deletedOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.remove(file)
        }
        return changedOnDisk(file)
    }

    fun changedOnDisk(file: Path): Boolean {
        val buildScript = isBuildScript(file)
        val javaSource = isJavaSource(file)
        return if (buildScript || javaSource) {
            refresh(updateClassPath = buildScript, updateBuildScriptClassPath = false, updateJavaSourcePath = javaSource)
        } else {
            false
        }
    }

    private fun isJavaSource(file: Path): Boolean = file.fileName.toString().endsWith(".java")

    private fun isBuildScript(file: Path): Boolean = file.fileName.toString().let { it == "pom.xml" || it == "build.gradle" || it == "build.gradle.kts" }

    private fun findJavaSourceFiles(root: Path): Set<Path> {
        val sourceMatcher = FileSystems.getDefault().getPathMatcher("glob:*.java")
        return SourceExclusions(listOf(root), scriptsConfig)
            .walkIncluded()
            .filter { sourceMatcher.matches(it.fileName) }
            .toSet()
    }

    override fun close() {
        compiler.close()
        outputDirectory.delete()
        jarIndexCache.clear()
    }
}

private fun logAdded(sources: Collection<Path>, name: String) {
    when {
        sources.isEmpty() -> return
        sources.size > 5 -> LOG.info("Adding {} files to {}", sources.size, name)
        else -> LOG.info("Adding {} to {}", sources, name)
    }
}

private fun logRemoved(sources: Collection<Path>, name: String) {
    when {
        sources.isEmpty() -> return
        sources.size > 5 -> LOG.info("Removing {} files from {}", sources.size, name)
        else -> LOG.info("Removing {} from {}", sources, name)
    }
}
