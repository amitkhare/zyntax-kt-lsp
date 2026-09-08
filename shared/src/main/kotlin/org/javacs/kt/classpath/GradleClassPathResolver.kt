package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.KotlinLSException
import org.javacs.kt.util.execAndReadStdoutAndStderr
import org.javacs.kt.util.findCommandOnPath
import org.javacs.kt.util.isOSWindows
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.eclipse.EclipseProject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal class GradleClassPathResolver(private val path: Path, private val includeKotlinDSL: Boolean): ClassPathResolver {
    private val async = AsyncExecutor
    override val resolverType: String = "Gradle"
    private val projectDir: Path get() = path.parent

    override val classpath: Set<ClassPathEntry> get() {
        LOG.trace("GradleClassPathResolver: Getting classpath for {}", projectDir)
        val result = readDependenciesViaToolingAPI(projectDir)
        LOG.trace("GradleClassPathResolver: Tooling API returned {} dependencies for '{}'", result.size, projectDir.fileName)
        result.forEach { dep ->
            LOG.trace("GradleClassPathResolver: Raw dependency: {}", dep)
        }
        return result
            .apply {
                if (isNotEmpty()) {
                    LOG.trace("GradleClassPathResolver: Successfully resolved {} dependencies using Gradle Tooling API for '{}'", size, projectDir.fileName)
                } else {
                    LOG.trace("GradleClassPathResolver: No dependencies resolved via Tooling API for '{}'", projectDir.fileName)
                }
            }
            .let { jarPaths ->
                async.ioMap(jarPaths) { jarPath ->
                    val sourceJar = findSourcesJarForGradleDependency(jarPath)
                    if (sourceJar != null) {
                        LOG.debug("Found source JAR for {}: {}", jarPath, sourceJar)
                    }
                    ClassPathEntry(jarPath, sourceJar)
                }.toSet()
            }
    }

    override val testClasspath: Set<ClassPathEntry> get() {
        LOG.trace("GradleClassPathResolver: Getting test classpath for {}", projectDir)
        val result = readTestDependenciesViaGradleCLI(projectDir)
        LOG.trace("GradleClassPathResolver: CLI returned {} test dependencies for '{}'", result.size, projectDir.fileName)
        return result
            .let { jarPaths ->
                async.ioMap(jarPaths) { jarPath ->
                    val sourceJar = findSourcesJarForGradleDependency(jarPath)
                    ClassPathEntry(jarPath, sourceJar)
                }.toSet()
            }
    }

    override val buildScriptClasspath: Set<Path> get() {
        return if (includeKotlinDSL) {
            readDependenciesViaGradleCLIForBuildScripts(projectDir)
        } else {
            emptySet()
        }
    }

    override val currentBuildFileVersion: Long get() = BuildFileHashing.hash(path)

    companion object {
        fun maybeCreate(file: Path): GradleClassPathResolver? =
            file.takeIf { file.endsWith("build.gradle") || file.endsWith("build.gradle.kts") }
                ?.let { GradleClassPathResolver(it, includeKotlinDSL = file.toString().endsWith(".kts")) }
    }
}

private fun readDependenciesViaToolingAPI(projectDirectory: Path): Set<Path> {
    LOG.debug("TOOLING API: Starting resolution for {}", projectDirectory)

    val classpathEntries = mutableSetOf<Path>()

    try {
        LOG.debug("TOOLING API: Creating connector for {}", projectDirectory)
        val connector = GradleConnector.newConnector()
            .forProjectDirectory(projectDirectory.toFile())

        LOG.debug("TOOLING API: Connecting to {}", projectDirectory)
        val connection = connector.connect()

        try {
            LOG.debug("TOOLING API: Fetching EclipseProject model for {}", projectDirectory)
            val model = connection.getModel(EclipseProject::class.java)

            LOG.debug("TOOLING API: Got EclipseProject, collecting classpath for {}", projectDirectory)
            collectClasspathEntries(model, classpathEntries)

            LOG.debug("TOOLING API: Found {} classpath entries for {}", classpathEntries.size, projectDirectory)

            classpathEntries.forEach { entry ->
                LOG.trace("TOOLING API: Entry: {}", entry)
            }
        } catch (e: Exception) {
            LOG.error("TOOLING API: Error fetching model: {}", e.message, e)
            throw e
        } finally {
            connection.close()
            LOG.debug("TOOLING API: Connection closed for {}", projectDirectory)
        }
    } catch (e: Exception) {
        LOG.error("TOOLING API: EXCEPTION during resolution for {}: {}", projectDirectory, e.message, e)
        LOG.warn("TOOLING API: Falling back to CLI for {}", projectDirectory)
        return readDependenciesViaGradleCLI(projectDirectory)
    }

    return classpathEntries
        .filter { it.toString().lowercase().endsWith(".jar") || Files.isDirectory(it) }
        .toSet()
}

private fun readTestDependenciesViaGradleCLI(projectDirectory: Path): Set<Path> {
    LOG.debug("CLI: Resolving test dependencies for {}", projectDirectory.fileName)

    val scripts = listOf("testClassPathFinder.gradle")
    val tasks = listOf("kotlinLSPTestDeps")

    return readDependenciesViaGradleCLI(projectDirectory, scripts, tasks)
        .apply { if (isNotEmpty()) LOG.debug("CLI: Resolved {} test dependencies for '{}'", size, projectDirectory.fileName) }
}

private fun collectClasspathEntries(project: EclipseProject, entries: MutableSet<Path>) {
    LOG.debug("TOOLING API: Processing project: {}", project.name)
    LOG.debug("TOOLING API: Classpath size for {}: {}", project.name, project.classpath.size)

    project.classpath.forEach { dep ->
        try {
            LOG.trace("TOOLING API: Processing dependency: {}", dep)
            val file = dep.file
            LOG.trace("TOOLING API: Dependency {} has file: {}", dep, file)
            if (file != null && file.exists()) {
                LOG.trace("TOOLING API: Adding {} to classpath", file)
                entries.add(file.toPath())
            } else {
                LOG.debug("TOOLING API: File is null or doesn't exist for {}", dep)
            }
        } catch (e: Exception) {
            LOG.error("TOOLING API: Error getting file for {}: {}", dep, e.message, e)
        }
    }

    LOG.debug("TOOLING API: Processing {} child projects for {}", project.children.size, project.name)
    project.children.forEach { child ->
        collectClasspathEntries(child, entries)
    }
}

private fun readDependenciesViaGradleCLIForBuildScripts(projectDirectory: Path): Set<Path> {
    LOG.debug("CLI: Resolving build script dependencies for {}", projectDirectory.fileName)

    val scripts = listOf("kotlinDSLClassPathFinder.gradle")
    val tasks = listOf("kotlinLSPKotlinDSLDeps")

    return readDependenciesViaGradleCLI(projectDirectory, scripts, tasks)
        .apply { if (isNotEmpty()) LOG.debug("CLI: Resolved {} build script dependencies for '{}'", size, projectDirectory.fileName) }
}

private fun readDependenciesViaGradleCLI(projectDirectory: Path): Set<Path> {
    LOG.debug("CLI: Resolving dependencies for {} (fallback)", projectDirectory.fileName)

    val scripts = listOf("projectClassPathFinder.gradle")
    val tasks = listOf("kotlinLSPProjectDeps")

    return readDependenciesViaGradleCLI(projectDirectory, scripts, tasks)
        .apply { if (isNotEmpty()) LOG.debug("CLI: Resolved {} dependencies for '{}'", size, projectDirectory.fileName) }
}

private fun readDependenciesViaGradleCLI(projectDirectory: Path, gradleScripts: List<String>, gradleTasks: List<String>): Set<Path> {
    LOG.debug("CLI: Running Gradle with tasks {}", gradleTasks)

    val tmpScripts = gradleScripts.map { gradleScriptToTempFile(it, deleteOnExit = false).toPath().toAbsolutePath() }
    val gradle = getGradleCommand(projectDirectory)

    LOG.debug("CLI: Using Gradle: {}", gradle)

    val command = listOf(gradle.toString()) + tmpScripts.flatMap { listOf("-I", it.toString()) } + gradleTasks + listOf("--console=plain")
    LOG.debug("CLI: Full command: {}", command)

    val dependencies = findGradleCLIDependencies(command, projectDirectory)
        ?.also { LOG.debug("CLI: Found {} dependencies", it.size) }
        .orEmpty()
        .filter { it.toString().lowercase().endsWith(".jar") || Files.isDirectory(it) }
        .toSet()

    LOG.debug("CLI: Filtered to {} dependencies", dependencies.size)

    dependencies.forEach { dep ->
        LOG.trace("CLI: Dependency: {}", dep)
    }

    tmpScripts.forEach(Files::delete)
    return dependencies
}

private fun gradleScriptToTempFile(scriptName: String, deleteOnExit: Boolean = false): File {
    val config = File.createTempFile("classpath", ".gradle")
    if (deleteOnExit) {
        config.deleteOnExit()
    }

    LOG.debug("CLI: Creating temporary gradle file: {}", config.absolutePath)

    config.bufferedWriter().use { configWriter ->
        GradleClassPathResolver::class.java.getResourceAsStream("/$scriptName").bufferedReader().use { configReader ->
            configReader.copyTo(configWriter)
        }
    }

    return config
}

private fun getGradleCommand(workspace: Path): Path {
    val wrapperName = if (isOSWindows()) "gradlew.bat" else "gradlew"
    val wrapper = workspace.resolve(wrapperName).toAbsolutePath()
    return if (Files.isExecutable(wrapper)) {
        wrapper
    } else {
        workspace.parent?.let(::getGradleCommand)
            ?: findCommandOnPath("gradle")
            ?: throw KotlinLSException("Could not find 'gradle' on PATH")
    }
}

private fun findGradleCLIDependencies(command: List<String>, projectDirectory: Path): Set<Path>? {
    val (result, errors) = execAndReadStdoutAndStderr(command, projectDirectory)
    if ("FAILURE: Build failed" in errors) {
        LOG.error("CLI: Gradle task FAILED: {}", errors)
    } else {
        for (error in errors.lines()) {
            if ("ERROR: " in error) {
                LOG.error("CLI: Gradle ERROR: {}", error)
            }
        }
    }
    return parseGradleCLIDependencies(result)
}

private val artifactPattern by lazy { "kotlin-lsp-gradle (.+)(?:\r?\n)".toRegex() }

private fun parseGradleCLIDependencies(output: String): Set<Path> {
    LOG.trace("CLI: Parsing output (length: {})", output.length)
    val artifacts = artifactPattern.findAll(output)
        .mapNotNull { Paths.get(it.groups[1]?.value) }
    return artifacts.toSet()
}

/**
 * Searches for a source JAR corresponding to a compiled JAR in Gradle cache.
 * Handles the Gradle cache directory structure where source JARs are in sibling hash directories.
 */
private fun findSourcesJarForGradleDependency(compiledJar: Path): Path? {
    val jarFile = compiledJar.toFile()
    if (!jarFile.exists()) {
        return null
    }

    val dir = jarFile.parentFile ?: return null
    val baseName = jarFile.nameWithoutExtension

    // Pattern: foo.jar -> foo-sources.jar
    val sourceJarName = "$baseName-sources.jar"

    // 1. Check same directory (Maven-style layout)
    val sameDirSource = File(dir, sourceJarName)
    if (sameDirSource.exists()) {
        LOG.trace("Found source JAR in same directory: {}", sameDirSource)
        return sameDirSource.toPath()
    }

    // 2. Check parent directory's subdirectories (Gradle cache layout)
    // Example: compiledJar is in ~/.gradle/caches/.../2.2.20/5380b19.../kotlin-stdlib.jar
    // Source JAR is in ~/.gradle/caches/.../2.2.20/cd60139.../kotlin-stdlib-sources.jar
    val parentDir = dir.parentFile ?: return null

    // List subdirectories of parent (hash directories)
    val subdirs = parentDir.listFiles { f -> f.isDirectory } ?: emptyArray()

    for (subdir in subdirs) {
        if (subdir == dir) continue  // Skip the directory we're already in

        val candidate = File(subdir, sourceJarName)
        if (candidate.exists()) {
            LOG.trace("Found source JAR in sibling directory: {}", candidate)
            return candidate.toPath()
        }
    }

    // Also try -sources.zip variant
    val sourceZipName = "$baseName-sources.zip"
    for (subdir in subdirs) {
        if (subdir == dir) continue

        val candidate = File(subdir, sourceZipName)
        if (candidate.exists()) {
            LOG.trace("Found source ZIP in sibling directory: {}", candidate)
            return candidate.toPath()
        }
    }

    LOG.trace("No source JAR found for {} in {}", compiledJar, parentDir)
    return null
}
