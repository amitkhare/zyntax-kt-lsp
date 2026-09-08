package org.javacs.kt

import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent
import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.javacs.kt.util.findCommandOnPath
import org.junit.Assume.assumeTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import java.nio.file.Files

class AdditionalWorkspaceTest : LanguageServerTestFixture("mainWorkspace") {
    val file = "MainWorkspaceFile.kt"

    fun addWorkspaceRoot() {
        val folder = WorkspaceFolder()
        folder.uri = absoluteWorkspaceRoot("additionalWorkspace").toUri().toString()

        val addWorkspace = DidChangeWorkspaceFoldersParams()
        addWorkspace.event = WorkspaceFoldersChangeEvent()
        addWorkspace.event.added = listOf(folder)

        languageServer.workspaceService.didChangeWorkspaceFolders(addWorkspace)
    }

    private fun hasGradle(): Boolean {
        val workspaceRoot = absoluteWorkspaceRoot("additionalWorkspace")
        val wrapper = workspaceRoot.resolve("gradlew")
        return (Files.isExecutable(wrapper)) || findCommandOnPath("gradle") != null
    }

    @Test fun `junit should be on classpath`() {
        assumeTrue("Gradle not available", hasGradle())
        addWorkspaceRoot()
        open(file)

        // Wait for classpath resolution to complete (including test dependencies)
        val hasJUnit = waitForClasspathWithTimeout(30_000)
        assertThat("JUnit should be on the classpath", hasJUnit, equalTo(true))
    }

    private fun waitForClasspathWithTimeout(maxWaitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val classPath = languageServer.classPath.classPath
            if (classPath.any { it.compiledJar.toString().contains("junit") }) {
                return true
            }
            Thread.sleep(200)
        }
        return false
    }

    @Ignore // TODO
    @Test fun `recompile all when classpath changes`() {
        open(file)

        val hover = languageServer.textDocumentService.hover(hoverParams(file, 5, 14)).get()
        assertThat("No hover before JUnit is added to classpath", hover, nullValue())

        addWorkspaceRoot()
        val hoverAgain = languageServer.textDocumentService.hover(hoverParams(file, 5, 14)).get() ?: return fail("No hover")
        assertThat(hoverAgain.contents.right.value, containsString("fun assertTrue"))
    }
}
