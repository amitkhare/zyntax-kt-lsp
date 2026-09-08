package org.javacs.kt.project

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import org.gradle.tooling.CancellationToken
import org.gradle.tooling.GradleConnector
import org.javacs.kt.util.TemporaryDirectory
import java.io.OutputStream
import java.nio.file.Files
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

data class GradleProjectImport(
    val project: GradleProjectModel,
    val scripts: Map<Path, GradleKotlinScript>
)

internal fun parseGradleProjectModel(json: String, root: Path): GradleProjectModel {
    val gson = GsonBuilder().registerTypeHierarchyAdapter(Path::class.java, JsonDeserializer<Path> { value, _, _ ->
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Gradle model path must be a string" }
        Path.of(value.asString).also { require(it.isAbsolute) { "Gradle model path must be absolute: $it" } }.normalize()
    }).create()
    val model = requireNotNull(gson.fromJson(json, GradleProjectModel::class.java)) { "Missing Gradle project model" }
    require(model.buildRoot == root) { "Gradle returned a model for a different build: ${model.buildRoot}" }
    require(model.gradleVersion.isNotBlank()) { "Missing Gradle version" }
    val ids = model.compilations.map { it.id }
    require(ids.all(String::isNotBlank) && ids.size == ids.toSet().size) { "Invalid Gradle compilation identities" }
    for (compilation in model.compilations) {
        require(compilation.associatedCompilations.all { it in ids }) {
            "Unknown associated compilation in ${compilation.id}"
        }
    }
    return model
}

/** Runs only model preparation through Gradle's Tooling API; no wrapper/shell fallback. */
fun importGradleProject(
    root: Path,
    gradleJavaHome: Path,
    output: OutputStream,
    cancellation: CancellationToken
): GradleProjectImport {
    val buildRoot = root.toRealPath()
    require(Files.isDirectory(gradleJavaHome)) { "Gradle Java home must be an installed JDK directory" }
    return TemporaryDirectory("ktlspGradleImport").use { temporary ->
        val script = temporary.createTempFile("project-model", ".gradle")
        val result = temporary.createTempFile("project-model", ".json")
        checkNotNull(GradleProjectImport::class.java.getResourceAsStream("/gradleProjectModel.gradle")) {
            "Packaged Gradle project importer is missing"
        }.use { Files.copy(it, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        GradleConnector.newConnector().forProjectDirectory(buildRoot.toFile()).connect().use { connection ->
            connection.newBuild()
                .forTasks("ktlspProjectModel")
                .withArguments("--init-script", script.toString(), "-PktlspModelOutput=$result", "--console=plain", "--no-configuration-cache")
                .setJavaHome(gradleJavaHome.toFile())
                .setStandardOutput(output).setStandardError(output)
                .withCancellationToken(cancellation).run()
            val project = parseGradleProjectModel(Files.readString(result), buildRoot)
            val scripts = readGradleKotlinScripts(connection) {
                setJavaHome(gradleJavaHome.toFile())
                setStandardOutput(output).setStandardError(output)
                withCancellationToken(cancellation)
            }
            GradleProjectImport(project, scripts)
        }
    }
}
