package org.javacs.kt.imports

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.javacs.kt.position.location

fun getImportTextEditEntry(parsedFile: KtFile, fqName: FqName): TextEdit {
    val imports = parsedFile.importDirectives
    val importedNames = imports
        .mapNotNull { it.importedFqName?.shortName() }
        .toSet()

    val pos = findImportInsertionPosition(parsedFile, fqName)
    val prefix = if (importedNames.isEmpty()) "\n\n" else "\n"
    return TextEdit(Range(pos, pos), "${prefix}import ${backtickBuiltins(fqName)}")
}

/** Finds a good insertion position for a new import of the given fully-qualified name.
 * Uses lexicographic (alphabetical) ordering based on the import's FQ name.
 */
private fun findImportInsertionPosition(parsedFile: KtFile, fqName: FqName): Position =
    (findLexicographicPosition(parsedFile.importDirectives, fqName) as? KtElement ?: parsedFile.packageDirective as? KtElement)
        ?.let(::location)
        ?.range
        ?.end
        ?: Position(0, 0)

/**
 * Finds the import directive that should precede the new import in lexicographic order.
 * Returns null if the new import should be inserted at the beginning.
 */
private fun findLexicographicPosition(imports: List<KtImportDirective>, newFqName: FqName): KtImportDirective? {
    if (imports.isEmpty()) return null

    val newImportStr = newFqName.asString()

    // Sort imports by their FQ name to find the correct position
    val sortedImports = imports.sortedBy { it.importedFqName?.asString() ?: "" }

    // Find the import that should come before the new one
    // (the last import that is lexicographically less than the new one)
    return sortedImports
        .filter { (it.importedFqName?.asString() ?: "") < newImportStr }
        .maxByOrNull { it.importedFqName?.asString() ?: "" }
}

private fun backtickBuiltins(fqName: FqName): String {
    val builtInKeywords = KtTokens.KEYWORDS.types
        .mapNotNull { (it as? KtKeywordToken)?.value }
    var result = fqName.asString()
    for (builtin in builtInKeywords) {
        if (result.contains(builtin)) {
            // need to go through each part to handle words
            // that are part of other words (e.g, as and class)
            result = result.split('.').joinToString(".") { part ->
                if (builtin == part) "`$builtin`" else part
            }
        }
    }

    return result
}
