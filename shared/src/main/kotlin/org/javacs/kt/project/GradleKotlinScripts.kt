package org.javacs.kt.project

import org.gradle.tooling.ModelBuilder
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.kotlin.dsl.EditorReportSeverity
import org.gradle.tooling.model.kotlin.dsl.KotlinDslModelsParameters
import org.gradle.tooling.model.kotlin.dsl.KotlinDslScriptsModel
import java.io.File
import java.nio.file.Path

data class GradleKotlinScript(
    val classPath: List<Path>,
    val sourcePath: List<Path>,
    val implicitImports: List<String>,
    val editorReports: List<GradleScriptReport>,
    val exceptions: List<String>
)

data class GradleScriptReport(
    val severity: EditorReportSeverity,
    val message: String,
    val position: GradleScriptPosition?
)

data class GradleScriptPosition(val line: Int, val column: Int)

/** The caller owns the root-build connection and configures JVM, cancellation and output. */
fun readGradleKotlinScripts(
    connection: ProjectConnection,
    configureRequest: ModelBuilder<KotlinDslScriptsModel>.() -> Unit
): Map<Path, GradleKotlinScript> {
    val request = connection.model(KotlinDslScriptsModel::class.java)
    request.configureRequest()
    request.forTasks(KotlinDslModelsParameters.PREPARATION_TASK_NAME)
    request.addArguments(KotlinDslModelsParameters.STRICT_CLASSPATH_MODE_SYSTEM_PROPERTY_DECLARATION)
    return projectGradleKotlinScripts(request.get())
}

internal fun projectGradleKotlinScripts(model: KotlinDslScriptsModel): Map<Path, GradleKotlinScript> = buildMap {
    for ((file, script) in model.scriptModels) {
        val path = modelPath(file)
        check(path !in this) { "Gradle returned duplicate script model: $path" }
        put(path, GradleKotlinScript(
            classPath = script.classPath.map(::modelPath),
            sourcePath = script.sourcePath.map(::modelPath),
            implicitImports = script.implicitImports.toList(),
            editorReports = script.editorReports.map { report ->
                GradleScriptReport(report.severity, report.message, report.position?.let {
                    GradleScriptPosition(it.line, it.column)
                })
            },
            exceptions = script.exceptions.toList()
        ))
    }
}

private fun modelPath(file: File): Path {
    val path = file.toPath()
    require(path.isAbsolute) { "Gradle model path must be absolute: $path" }
    return path.normalize()
}
