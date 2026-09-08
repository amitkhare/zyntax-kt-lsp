package org.javacs.kt

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.hamcrest.Matchers.*
import org.hamcrest.Matchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

class RenameReferenceTest : SingleFileTestFixture("rename", "SomeClass.kt") {

    @Test
    fun `rename in the reference`() {
        val edits = languageServer.textDocumentService.rename(renameParams(file, 4, 26, "NewClassName")).get()!!
        val changes = edits.documentChanges

        assertThat(changes.size, equalTo(3))
        assertThat(changes[0].left.textDocument.uri, startsWith("file://"))
        assertThat(changes[0].left.textDocument.uri, containsString("SomeOtherClass.kt"))

        assertThat(changes[0].left.edits[0].left.newText, equalTo("NewClassName"))
        assertThat(changes[0].left.edits[0].left.range.start, equalTo(Position(2, 6)))
        assertThat(changes[0].left.edits[0].left.range.end, equalTo(Position(2, 20)))
    }
}

class RenameDefinitionTest : SingleFileTestFixture("rename", "SomeOtherClass.kt") {

    @Test
    fun `rename in the definition`() {
        val edits = languageServer.textDocumentService.rename(renameParams(file, 2, 15, "NewClassName")).get()!!
        val changes = edits.documentChanges

        println(changes)

        assertThat(changes.size, equalTo(3))
        assertThat(changes[0].left.textDocument.uri, startsWith("file://"))
        assertThat(changes[0].left.textDocument.uri, containsString("SomeOtherClass.kt"))

        assertThat(changes[0].left.edits[0].left.newText, equalTo("NewClassName"))
        assertThat(changes[0].left.edits[0].left.range.start, equalTo(Position(2, 6)))
        assertThat(changes[0].left.edits[0].left.range.end, equalTo(Position(2, 20)))
    }
}

class RenameDeclarationSiteTest : SingleFileTestFixture("rename", "DeclSite.kt") {

    @Test
    fun `should rename variable from usage site`() {
        val usageFile = workspaceRoot.resolve("UsageSite.kt").toString()
        val edits = languageServer.textDocumentService.rename(renameParams(usageFile, 4, 13, "newvarname")).get()!!
        val changes = edits.documentChanges

        assertThat(changes.size, equalTo(2))

        val firstChange = changes[0].left
        assertThat(firstChange.textDocument.uri, startsWith("file://"))
        assertThat(firstChange.textDocument.uri, containsString("DeclSite.kt"))
        assertThat(firstChange.edits[0].left.newText, equalTo("newvarname"))
        assertThat(firstChange.edits[0].left.range, equalTo(range(3, 5, 3, 10)))

        val secondChange = changes[1].left
        assertThat(secondChange.textDocument.uri, startsWith("file://"))
        assertThat(secondChange.textDocument.uri, containsString("UsageSite.kt"))
        assertThat(secondChange.edits[0].left.newText, equalTo("newvarname"))
        assertThat(secondChange.edits[0].left.range, equalTo(range(4, 13, 4, 18)))
    }

    @Test
    fun `should rename variable from declaration site`() {
        val edits = languageServer.textDocumentService.rename(renameParams(file, 3, 6, "newvarname")).get()!!
        val changes = edits.documentChanges

        assertThat(changes.size, equalTo(2))

        val firstChange = changes[0].left
        assertThat(firstChange.textDocument.uri, startsWith("file://"))
        assertThat(firstChange.textDocument.uri, containsString("DeclSite.kt"))
        assertThat(firstChange.edits[0].left.newText, equalTo("newvarname"))
        assertThat(firstChange.edits[0].left.range, equalTo(range(3, 5, 3, 10)))

        val secondChange = changes[1].left
        assertThat(secondChange.textDocument.uri, startsWith("file://"))
        assertThat(secondChange.textDocument.uri, containsString("UsageSite.kt"))
        assertThat(secondChange.edits[0].left.newText, equalTo("newvarname"))
        assertThat(secondChange.edits[0].left.range, equalTo(range(4, 13, 4, 18)))
    }
}

class RenameImplicitItTest : SingleFileTestFixture("rename", "ImplicitIt.kt") {

    @Test
    fun `should rename implicit it in forEach`() {
        // Line 5: list.forEach { println(it) }
        // 'it' starts at around column 28 (0-indexed)
        val edits = languageServer.textDocumentService.rename(renameParams(file, 5, 28, "item")).get()!!
        val changes = edits.documentChanges

        println("Changes: $changes")

        // All edits are combined into a single TextDocumentEdit
        assertThat(changes.size, equalTo(1))

        val documentEdit = changes[0].left
        assertThat(documentEdit.textDocument.uri, containsString("ImplicitIt.kt"))

        // Should have 2 edits: one for parameter, one for renaming 'it'
        assertThat(documentEdit.edits.size, equalTo(2))

        // First edit adds parameter: { item -> println(it) }
        val paramEdit = documentEdit.edits[0].left
        assertThat(paramEdit.newText, equalTo(" item ->"))

        // Second edit renames 'it' to 'item'
        val refEdit = documentEdit.edits[1].left
        assertThat(refEdit.newText, equalTo("item"))
    }

    @Test
    fun `should not rename for keyword`() {
        // Try to rename 'it' to a keyword - should return null
        // 'it' is at column 28
        val edits = languageServer.textDocumentService.rename(renameParams(file, 5, 28, "class")).get()

        println("Keyword rename result: $edits")
        assertThat(edits, equalTo(null))
    }

    @Test
    fun `should rename implicit it in nested lambda`() {
        // Line 10: nested.map { inner -> inner.map { it + 1 } }
        // The inner 'it' is at column 38 (0-indexed)
        val edits = languageServer.textDocumentService.rename(renameParams(file, 10, 38, "value")).get()
        println("Nested lambda edits: $edits")
        assertThat(edits, equalTo(null))
    }

    @Test
    fun `should rename implicit it in double nested lambda`() {
        // Line 14: doubleNested.flatMap { outer -> outer.map { inner -> inner + it } }
        // The 'it' at the end (inner + it) is at column 65
        val edits = languageServer.textDocumentService.rename(renameParams(file, 14, 65, "x")).get()
        println("Double nested edits: $edits")
        assertThat(edits, equalTo(null))
    }
}

class RenameExplicitParamTest : SingleFileTestFixture("rename", "ExplicitParam.kt") {

    @Test
    fun `should rename explicit lambda parameter`() {
        // Line 7: list.forEach { item -> println(item) }
        // 'item' is at column 20 (0-indexed), inside the lambda braces
        val edits = languageServer.textDocumentService.rename(renameParams(file, 7, 20, "element")).get()!!
        val changes = edits.documentChanges

        println("Explicit param rename: $changes")

        assertThat(changes.size, equalTo(1))

        val documentEdit = changes[0].left
        assertThat(documentEdit.textDocument.uri, containsString("ExplicitParam.kt"))

        // Should have 2 edits: one for parameter declaration, one for usage
        assertThat(documentEdit.edits.size, equalTo(2))
    }

    @Test
    fun `should rename explicit parameter with multiple usages`() {
        // Line 10: list.filter { triState -> triState > 0 }
        // "list.filter { triState" = 26 chars, so "triState" starts at position 19 (1-indexed)
        val edits = languageServer.textDocumentService.rename(renameParams(file, 10, 19, "value")).get()!!
        val changes = edits.documentChanges

        assertThat(changes.size, equalTo(1))

        val documentEdit = changes[0].left
        assertThat(documentEdit.edits.size, equalTo(2))
    }
}

class PrepareRenameTest : SingleFileTestFixture("rename", "PrepareRenameExample.kt") {

    @Test
    fun `prepare rename should succeed on variable declaration`() {
        // Line 2: val name: String = "test"
        // "name" starts at column 8 (0-indexed)
        val result = languageServer.textDocumentService.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri(file).toString()), Position(1, 8))
        ).get()

        assertThat(result, notNullValue())
        if (result != null && result.isSecond) {
            assertThat(result.second.placeholder, equalTo("name"))
        }
    }

    @Test
    fun `prepare rename should succeed on function name`() {
        // Line 4: fun doSomething() {
        // "doSomething" starts at column 10 (0-indexed)
        val result = languageServer.textDocumentService.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri(file).toString()), Position(3, 10))
        ).get()

        assertThat(result, notNullValue())
        if (result != null && result.isSecond) {
            assertThat(result.second.placeholder, equalTo("doSomething"))
        }
    }

    @Test
    fun `prepare rename should succeed on local variable`() {
        // Line 5: val localVar = 42
        // "localVar" starts at column 13 (0-indexed)
        val result = languageServer.textDocumentService.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri(file).toString()), Position(4, 13))
        ).get()

        assertThat(result, notNullValue())
        if (result != null && result.isSecond) {
            assertThat(result.second.placeholder, equalTo("localVar"))
        }
    }

    @Test
    fun `prepare rename should succeed on function parameter`() {
        // Line 9: fun renameMe(target: String) {
        // "target" starts at column 22 (0-indexed)
        val result = languageServer.textDocumentService.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri(file).toString()), Position(8, 22))
        ).get()

        assertThat(result, notNullValue())
        if (result != null && result.isSecond) {
            assertThat(result.second.placeholder, equalTo("target"))
        }
    }

    @Test
    fun `prepare rename should return null on keyword`() {
        // Line 2: val name: String = "test"
        // Cursor on "val" keyword (column 4)
        val result = languageServer.textDocumentService.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri(file).toString()), Position(1, 4))
        ).get()

        // Cursor on "val" keyword should return null (not a valid rename target)
        assertThat(result, equalTo(null))
    }

    @Test
    fun `prepare rename should return null in whitespace`() {
        // Line 2: val name: String = "test"
        // Cursor after the declaration in whitespace (column 25)
        val result = languageServer.textDocumentService.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(uri(file).toString()), Position(1, 25))
        ).get()

        // Whitespace should return null (not a valid rename target)
        assertThat(result, equalTo(null))
    }
}
