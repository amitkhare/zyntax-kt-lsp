package org.javacs.kt.project

import org.gradle.tooling.model.kotlin.dsl.EditorPosition
import org.gradle.tooling.model.kotlin.dsl.EditorReport
import org.gradle.tooling.model.kotlin.dsl.EditorReportSeverity
import org.gradle.tooling.model.kotlin.dsl.KotlinDslScriptModel
import org.gradle.tooling.model.kotlin.dsl.KotlinDslScriptsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class GradleKotlinScriptsTest {
    private val root = File("gradle-model-test").absoluteFile

    @Test fun `preserves independent evaluated script models and reports`() {
        val position = object : EditorPosition {
            override fun getLine() = 7
            override fun getColumn() = 3
        }
        val reports = listOf(report("Invalid accessor", position), report("Build configuration failed", null))
        val build = script(listOf(root.resolve("accessors.jar"), root.resolve("gradle.jar")), reports)
        val settings = script(listOf(root.resolve("settings.jar")))
        val result = projectGradleKotlinScripts(model(linkedMapOf(
            root.resolve("app/../build.gradle.kts") to build,
            root.resolve("settings.gradle.kts") to settings
        )))

        val buildModel = result.getValue(root.resolve("build.gradle.kts").toPath())
        val settingsModel = result.getValue(root.resolve("settings.gradle.kts").toPath())
        assertEquals(build.classPath.map(File::toPath), buildModel.classPath)
        assertEquals(settings.classPath.map(File::toPath), settingsModel.classPath)
        assertEquals(listOf(root.resolve("sources.jar").toPath()), buildModel.sourcePath)
        assertEquals(listOf("org.gradle.kotlin.dsl.*", "generated.accessors.*"), buildModel.implicitImports)
        assertEquals(GradleScriptPosition(7, 3), buildModel.editorReports[0].position)
        assertEquals(EditorReportSeverity.ERROR, buildModel.editorReports[0].severity)
        assertEquals("Invalid accessor", buildModel.editorReports[0].message)
        assertNull(buildModel.editorReports[1].position)
        assertEquals(listOf("Project evaluation failed"), buildModel.exceptions)
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects duplicate normalized script identities`() {
        projectGradleKotlinScripts(model(linkedMapOf(
            root.resolve("build.gradle.kts") to script(emptyList()),
            root.resolve("app/../build.gradle.kts") to script(emptyList())
        )))
    }

    private fun model(scripts: Map<File, KotlinDslScriptModel>) = object : KotlinDslScriptsModel {
        override fun getScriptModels() = scripts
    }

    private fun script(paths: List<File>, reports: List<EditorReport> = emptyList()) = object : KotlinDslScriptModel {
        override fun getClassPath() = paths
        override fun getSourcePath() = listOf(root.resolve("sources.jar"))
        override fun getImplicitImports() = listOf("org.gradle.kotlin.dsl.*", "generated.accessors.*")
        override fun getEditorReports() = reports
        override fun getExceptions() = listOf("Project evaluation failed")
    }

    private fun report(message: String, position: EditorPosition?) = object : EditorReport {
        override fun getSeverity() = EditorReportSeverity.ERROR
        override fun getMessage() = message
        override fun getPosition() = position
    }
}
