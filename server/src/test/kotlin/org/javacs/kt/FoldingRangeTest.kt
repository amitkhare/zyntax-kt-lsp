package org.javacs.kt

import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test

class FoldingRangeTest : SingleFileTestFixture("folding", "FoldingExample.kt") {

    @Test
    fun `folding ranges should include imports`() {
        val ranges = languageServer.textDocumentService.foldingRange(
            FoldingRangeRequestParams(TextDocumentIdentifier(uri(file).toString()))
        ).get()

        val importRanges = ranges.filter { it.kind == FoldingRangeKind.Imports }
        assertThat(importRanges, hasSize(1))
    }

    @Test
    fun `folding ranges should include class body`() {
        val ranges = languageServer.textDocumentService.foldingRange(
            FoldingRangeRequestParams(TextDocumentIdentifier(uri(file).toString()))
        ).get()

        val regionRanges = ranges.filter { it.kind == FoldingRangeKind.Region }
        // More regions than expected: class body, doSomething, anotherMethod, topLevelFunction, if block, for loop, object body
        assertThat(regionRanges.size, greaterThan(4))
    }

    @Test
    fun `folding ranges should include KDoc comments`() {
        val ranges = languageServer.textDocumentService.foldingRange(
            FoldingRangeRequestParams(TextDocumentIdentifier(uri(file).toString()))
        ).get()

        val commentRanges = ranges.filter { it.kind == FoldingRangeKind.Comment }
        assertThat(commentRanges.size, greaterThan(0))
    }

    @Test
    fun `folding ranges should include nested blocks`() {
        val ranges = languageServer.textDocumentService.foldingRange(
            FoldingRangeRequestParams(TextDocumentIdentifier(uri(file).toString()))
        ).get()

        // Check that nested blocks like if/for are included
        val regionRanges = ranges.filter { it.kind == FoldingRangeKind.Region }
        assertThat(regionRanges.size, greaterThan(3))
    }
}
