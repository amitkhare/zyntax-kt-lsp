package org.javacs.kt

import org.eclipse.lsp4j.DeclarationParams
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.containsString
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

class DeclarationTest : SingleFileTestFixture("declaration", "DeclarationExample.kt") {

    private fun declarationParams(relativePath: String, line: Int, column: Int): DeclarationParams {
        return textDocumentPosition(relativePath, line, column).run {
            DeclarationParams(textDocument, position)
        }
    }

    @Test
    fun `go to declaration of type alias`() {
        // Line 13: fun useTypeAlias(): StringList {
        // "StringList" starts at column 25 (1-indexed)
        val declarations = languageServer.textDocumentService.declaration(
            declarationParams(file, 13, 25) // On StringList usage (1-indexed)
        ).get().left
        assertThat(declarations, hasSize(1))
        assertThat(declarations.map { it.uri }, hasItem(containsString("DeclarationExample.kt")))
    }

    @Test
    fun `go to declaration of interface`() {
        // Line 8: class DeclarationExample : MyInterface {
        // "MyInterface" starts at column 33 (1-indexed)
        val declarations = languageServer.textDocumentService.declaration(
            declarationParams(file, 8, 33) // On MyInterface usage (1-indexed)
        ).get().left
        assertThat(declarations, hasSize(1))
        assertThat(declarations.map { it.uri }, hasItem(containsString("DeclarationExample.kt")))
    }
}
