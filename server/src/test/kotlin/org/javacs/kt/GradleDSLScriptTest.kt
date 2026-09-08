package org.javacs.kt

import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*

/**
 * Tests for Gradle Kotlin DSL script support.
 */
class GradleDSLScriptTest : SingleFileTestFixture("kotlinDSLWorkspace", "build.gradle.kts", Configuration().apply {
    scripts.enabled = true
    scripts.buildScriptsEnabled = true
}) {
    @Test fun `edit repositories`() {
        // Complete inside mavenCentral() call (1-indexed line 7, col 10)
        // Note: position() helper converts 1-indexed to 0-indexed for LSP
        val completions = languageServer.textDocumentService.completion(completionParams(file, 7, 10)).get().right!!
        val labels = completions.items.map { it.label }

        // At this position inside the repositories block body, we should see maven repo configuration options
        assertThat("Should have maven repo options in completions", labels, hasItem("maven"))
    }

    @Test fun `hover plugin`() {
        // Hover over "kotlin" in plugins block (line 2, col 5)
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 1, 5)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("PluginDependenciesSpec"))
    }
}
