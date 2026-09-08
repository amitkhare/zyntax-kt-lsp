package org.javacs.kt

import org.eclipse.lsp4j.TypeDefinitionParams
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

class TypeDefinitionTest : SingleFileTestFixture("typedefinition", "GoFrom.kt") {

    private fun typeDefinitionParams(relativePath: String, line: Int, column: Int): TypeDefinitionParams {
        return textDocumentPosition(relativePath, line, column).run {
            TypeDefinitionParams(textDocument, position)
        }
    }

    @Test
    fun `type definition of a typed value goes to the class declaration`() {
        // Line 8 (1-indexed): val typed: GoTo = GoTo()
        // Cursor on "typed" (column 9) -> type is GoTo in the other file
        val locations = languageServer.textDocumentService.typeDefinition(typeDefinitionParams(file, 8, 9)).get().left
        val uris = locations.map { it.uri }

        assertThat(locations, hasSize(1))
        assertThat(uris, hasItem(containsString("GoTo.kt")))
    }

    @Test
    fun `type definition of a type name goes to the class declaration`() {
        // Line 8 (1-indexed): val typed: GoTo = GoTo()
        // Cursor on "GoTo" type name (column 16)
        val locations = languageServer.textDocumentService.typeDefinition(typeDefinitionParams(file, 8, 16)).get().left
        val uris = locations.map { it.uri }

        assertThat(locations, hasSize(1))
        assertThat(uris, hasItem(containsString("GoTo.kt")))
    }

    @Test
    fun `type definition of an inferred value goes to the class declaration`() {
        // Line 9 (1-indexed): val other = GoTo()
        // Cursor on "other" (column 9) -> inferred type is GoTo
        val locations = languageServer.textDocumentService.typeDefinition(typeDefinitionParams(file, 9, 9)).get().left
        val uris = locations.map { it.uri }

        assertThat(locations, hasSize(1))
        assertThat(uris, hasItem(containsString("GoTo.kt")))
    }

    @Test
    fun `type definition of a same file value goes to the class declaration`() {
        // Line 10 (1-indexed): val sameFile: SameFile = SameFile()
        // Cursor on "sameFile" (column 9) -> type is SameFile in the same file
        val locations = languageServer.textDocumentService.typeDefinition(typeDefinitionParams(file, 10, 9)).get().left
        val uris = locations.map { it.uri }

        assertThat(locations, hasSize(1))
        assertThat(uris, hasItem(containsString("GoFrom.kt")))
    }

    @Test
    fun `type definition of a local variable goes to the class declaration`() {
        // Line 13 (1-indexed): val local = GoTo()
        // Cursor on "local" (column 13) -> inferred type is GoTo
        val locations = languageServer.textDocumentService.typeDefinition(typeDefinitionParams(file, 13, 13)).get().left
        val uris = locations.map { it.uri }

        assertThat(locations, hasSize(1))
        assertThat(uris, hasItem(containsString("GoTo.kt")))
    }
}
