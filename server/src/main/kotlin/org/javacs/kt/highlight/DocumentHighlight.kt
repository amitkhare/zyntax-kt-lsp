package org.javacs.kt.highlight

import org.eclipse.lsp4j.*
import org.javacs.kt.CompiledFile
import org.javacs.kt.references.findReferencesToDeclarationInFile
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

fun documentHighlightsAt(file: CompiledFile, cursor: Int): List<DocumentHighlight> {
    val (declaration, declarationLocation) = file.findDeclaration(cursor)
        ?: return emptyList()
    val references = findReferencesToDeclarationInFile(declaration, file)

    return if (declaration.isInFile(file.parse)) {
        listOf(DocumentHighlight(declarationLocation.range, DocumentHighlightKind.Text))
    } else {
        emptyList()
    } + references.map { DocumentHighlight(it, DocumentHighlightKind.Text) }
}

private fun KtNamedDeclaration.isInFile(file: KtFile) = this.containingFile == file
