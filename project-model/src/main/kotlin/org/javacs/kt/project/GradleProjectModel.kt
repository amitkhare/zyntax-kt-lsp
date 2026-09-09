package org.javacs.kt.project

import java.nio.file.Path

/** One evaluated build; IDs are local to buildRoot, never global module names. */
data class GradleProjectModel(
    val buildRoot: Path,
    val gradleVersion: String,
    val gradleJavaHome: Path,
    val compilations: List<GradleCompilation>
)

data class GradleCompilation(
    val id: String,
    val projectPath: String,
    val projectDirectory: Path,
    val target: String,
    val name: String,
    val kotlinRoots: List<Path>,
    val javaRoots: List<Path>,
    val kotlinFiles: List<Path>,
    val javaFiles: List<Path>,
    /** Null means the public build API does not classify generated roots. */
    val generatedRoots: List<Path>?,
    val classpath: List<Path>,
    val outputs: List<Path>,
    val sourceSets: List<GradleSourceSet>,
    val associatedCompilations: List<String>,
    val friendPaths: List<Path>,
    /** This is the Java task's JDK, not an inferred Kotlin-task JDK. */
    val javaCompileHome: Path?,
    val compilerOptions: GradleCompilerOptions,
    val compilerPluginClasspath: List<Path>,
    val compilerPluginOptions: List<GradleCompilerPluginOption>
)

data class GradleSourceSet(val name: String, val roots: List<Path>, val dependsOn: List<String>)

/** Unset providers remain null; the importer must not invent compiler defaults. */
data class GradleCompilerOptions(
    val languageVersion: String?,
    val apiVersion: String?,
    val jvmTarget: String?,
    val jvmDefault: String?,
    val noJdk: Boolean?,
    val javaParameters: Boolean?,
    val allWarningsAsErrors: Boolean?,
    val suppressWarnings: Boolean?,
    val verbose: Boolean?,
    val moduleName: String?,
    val optIn: List<String>?,
    val progressiveMode: Boolean?,
    val freeCompilerArgs: List<String>?
)

data class GradleCompilerPluginOption(val pluginId: String, val key: String, val value: String)
