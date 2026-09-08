package org.javacs.kt

import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.FormattingOptions
import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo

class FormatTest : SingleFileTestFixture("formatting", "NonFormatted.kt") {
    @Test fun `format kotlin code`() {
        val edits = languageServer.textDocumentService.formatting(DocumentFormattingParams(
            TextDocumentIdentifier(uri(file).toString()),
            FormattingOptions(
                4, // tabSize
                true // insertSpaces
            )
        )).get()!!
        assertThat(edits.size, equalTo(1))
        assertThat(edits[0].newText.replace("\r\n", "\n"), equalTo("""class Door(val width: Int = 3, val height: Int = 4)

class House {
    val door = Door()

    val window = "Window"
}
""".replace("\r\n", "\n")))
    }
}

class FormatToLineTest : SingleFileTestFixture("formatting", "Spaces.kt") {
    @Test fun `format to single line`() {
        val formatted = languageServer.textDocumentService.formatting(DocumentFormattingParams(
            TextDocumentIdentifier(uri(file).toString()),
            FormattingOptions()
        )).get()!![0].newText
        assertThat(formatted.replace("\r\n", "\n"), equalTo("class Test(val a: String, val b: String)\n"))
    }
}

class OnTypeFormattingTest : SingleFileTestFixture("formatting", "NonFormatted.kt") {
    @Test fun `on type format kotlin code`() {
        // Test formatting at line 10 (0-indexed): "    val     door=Door (  )"
        // Position (10, 15) is after "Door " - the part that needs formatting
        val params = DocumentOnTypeFormattingParams().apply {
            textDocument = TextDocumentIdentifier(uri(file).toString())
            position = Position(10, 15)
            ch = "}"
            options = FormattingOptions(4, true)
        }

        val edits = languageServer.textDocumentService.onTypeFormatting(params).get()

        // Should return edits if formatting was applied
        assertThat(edits.size, equalTo(1))
    }
}
