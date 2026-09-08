package org.javacs.kt.codeaction.quickfix

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.javacs.kt.CompiledFile
import org.javacs.kt.index.SymbolIndex
import org.javacs.kt.util.isSubrangeOf
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.diagnostics.Diagnostic as KotlinDiagnostic

interface QuickFix {
    // Computes the quickfix. Return empty list if the quickfix is not valid or no alternatives exist.
    fun compute(file: CompiledFile, index: SymbolIndex, range: Range, diagnostics: List<Diagnostic>, filteredTypes: List<String> = emptyList()): List<Either<Command, CodeAction>>
}

/**
 * Extracts the string diagnostic code, or null if it is absent or numeric.
 *
 * LSP allows `Diagnostic.code` to be a string or an integer and it may be unset
 * by the client; numeric codes can never match compiler diagnostic factory
 * names, so both cases yield null.
 */
fun Diagnostic.codeString(): String? = code?.takeIf { it.isLeft }?.left

fun diagnosticMatch(diagnostic: Diagnostic, range: Range, diagnosticTypes: Set<String>): Boolean =
    range.isSubrangeOf(diagnostic.range) && diagnosticTypes.contains(diagnostic.codeString())

fun diagnosticMatch(diagnostic: KotlinDiagnostic, startCursor: Int, endCursor: Int, diagnosticTypes: Set<String>): Boolean =
    diagnostic.textRanges.any { it.startOffset <= startCursor && it.endOffset >= endCursor } && diagnosticTypes.contains(diagnostic.factory.name)

fun findDiagnosticMatch(diagnostics: List<Diagnostic>, range: Range, diagnosticTypes: Set<String>) =
    diagnostics.find { diagnosticMatch(it, range, diagnosticTypes) }

fun anyDiagnosticMatch(diagnostics: Diagnostics, startCursor: Int, endCursor: Int, diagnosticTypes: Set<String>) =
    diagnostics.any { diagnosticMatch(it, startCursor, endCursor, diagnosticTypes) }
