@file:Suppress("TooManyFunctions")

package org.javacs.kt.rename

import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.javacs.kt.CompiledFile
import org.javacs.kt.LOG
import org.javacs.kt.SourcePath
import org.javacs.kt.position.range as rangeOf
import org.javacs.kt.position.location
import org.javacs.kt.references.findReferences
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext

fun renameSymbol(file: CompiledFile, cursor: Int, sp: SourcePath, newName: String): WorkspaceEdit? {
    // Validate the new name first
    if (!isValidIdentifier(newName)) {
        LOG.info("rename: invalid identifier '$newName'")
        return null
    }

    LOG.debug("rename: cursor=$cursor, newName='$newName'")

    // Try normal rename first (for explicit parameters)
    val declarationResult = file.findDeclaration(cursor)
    if (declarationResult != null) {
        val (declaration, location) = declarationResult

        LOG.debug("rename: found declaration ${declaration.javaClass.simpleName}")

        // Check if this is a lambda parameter that needs special handling
        val renameResult = renameLambdaParameter(declaration, location, file, newName)
        if (renameResult != null) {
            LOG.debug("rename: lambda parameter rename succeeded")
            return renameResult
        }

        // Fall back to normal rename
        LOG.debug("rename: falling back to normal rename")
        return renameDeclaration(declaration, location, sp, newName)
    }

    LOG.debug("rename: no declaration found, trying implicit 'it'")

    // Try implicit 'it' rename
    val implicitItResult = renameImplicitIt(file, cursor, newName)
    if (implicitItResult != null) {
        return implicitItResult
    }

    LOG.info("rename: failed (no matches for rename at cursor=$cursor)")
    return null
}

/**
 * Special handling for lambda parameters - finds all references in the lambda body
 * since standard findReferences may not find references inside the lambda.
 */
private fun renameLambdaParameter(
    declaration: KtNamedDeclaration,
    location: Location,
    file: CompiledFile,
    newName: String
): WorkspaceEdit? {
    val param = declaration as? KtParameter ?: return null

    // For explicit lambda parameters: KtLambdaExpression -> KtFunctionLiteral -> KtParameterList -> KtParameter
    val paramParent = param.parent
    val grandParent = paramParent?.parent
    val greatGrandParent = grandParent?.parent

    val lambda = when (grandParent) {
        is KtLambdaExpression -> grandParent
        is KtFunctionLiteral -> greatGrandParent as? KtLambdaExpression
        else -> null
    }
    if (lambda == null) {
        return null
    }

    val allReferences = findAllParameterReferences(file.compile, param, lambda)
    if (allReferences.isEmpty()) {
        LOG.info("rename: no references found for lambda parameter")
        return null
    }

    LOG.debug("rename: found ${allReferences.size} references for lambda parameter")

    val uri = file.parse.toPath().toUri().toString()
    val versionedId = VersionedTextDocumentIdentifier()
    versionedId.uri = uri

    val allTextEdits = mutableListOf<Either<TextEdit, SnippetTextEdit>>()

    // Parameter declaration rename
    val declarationTextEdit = TextEdit().apply {
        newText = newName
        range = location.range
    }
    allTextEdits.add(Either.forLeft(declarationTextEdit))

    // Reference renames
    for (ref in allReferences) {
        val textEdit = TextEdit().apply {
            this.newText = newName
            range = rangeOf(file.content, ref.textRange)
        }
        allTextEdits.add(Either.forLeft(textEdit))
    }

    val documentEdit = TextDocumentEdit()
    documentEdit.textDocument = versionedId
    documentEdit.edits = allTextEdits

    return WorkspaceEdit(listOf(Either.forLeft(documentEdit)))
}

/**
 * Find all references to a parameter in the lambda body.
 * Uses binding context first, then falls back to text-based search if needed.
 */
private fun findAllParameterReferences(
    bindingContext: BindingContext,
    param: KtParameter,
    lambda: KtLambdaExpression
): List<KtNameReferenceExpression> {
    val paramName = param.name ?: return emptyList()

    val paramDescriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, param]

    if (paramDescriptor != null) {
        val references = bindingContext.getSliceContents(BindingContext.REFERENCE_TARGET)
        val lambdaFile = lambda.containingFile.toPath()
        val lambdaBody = lambda.bodyExpression ?: return emptyList()

        val bindingRefs = references.asSequence()
            .filter { (_, target) -> target == paramDescriptor }
            .map { it.key }
            .filterIsInstance<KtNameReferenceExpression>()
            .filter { it.containingFile.toPath() == lambdaFile }
            .filter { ref ->
                ref.textRange.startOffset >= lambdaBody.textRange.startOffset &&
                ref.textRange.endOffset <= lambdaBody.textRange.endOffset
            }
            .toList()

        if (bindingRefs.isNotEmpty()) {
            return bindingRefs
        }
    }

    // Fallback: text-based search
    return findAllReferencesToNameInLambda(paramName, lambda)
}

/**
 * Text-based fallback: find all references to a parameter name within a lambda body.
 */
private fun findAllReferencesToNameInLambda(
    name: String,
    lambda: KtLambdaExpression
): List<KtNameReferenceExpression> {
    val body = lambda.bodyExpression ?: return emptyList()

    val references = mutableListOf<KtNameReferenceExpression>()

    fun search(element: KtElement) {
        when (element) {
            is KtNameReferenceExpression -> {
                if (element.getReferencedName() == name) {
                    references.add(element)
                }
            }
            is KtLambdaExpression -> return // Nested lambdas have their own scope
        }
        element.children.filterIsInstance<KtElement>().forEach { search(it) }
    }

    search(body)
    return references
}

private fun renameDeclaration(
    declaration: KtNamedDeclaration,
    location: Location,
    sp: SourcePath,
    newName: String
): WorkspaceEdit {
    val declarationEdit = Either.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(
        VersionedTextDocumentIdentifier().apply { uri = location.uri },
        listOf(Either.forLeft(TextEdit(location.range, newName)))
    ))

    val referenceEdits = findReferences(declaration, sp).map { refLocation ->
        Either.forLeft<TextDocumentEdit, ResourceOperation>(TextDocumentEdit(
            VersionedTextDocumentIdentifier().apply { uri = refLocation.uri },
            listOf(Either.forLeft(TextEdit(refLocation.range, newName)))
        ))
    }

    return WorkspaceEdit(listOf(declarationEdit) + referenceEdits)
}

private fun renameImplicitIt(file: CompiledFile, cursor: Int, newName: String): WorkspaceEdit? {
    LOG.debug("rename: processing implicit 'it' at cursor=$cursor")

    val context = findImplicitItContext(file, cursor) ?: return null

    LOG.debug("rename: found ${context.itReferences.size} 'it' references")
    return buildRenameWorkspaceEdit(file, context.lambda, newName, context.itReferences)
}

private data class ImplicitItContext(
    val referenceExpr: KtNameReferenceExpression,
    val lambda: KtLambdaExpression,
    val itReferences: List<KtNameReferenceExpression>
)

private fun findImplicitItContext(file: CompiledFile, cursor: Int): ImplicitItContext? {
    val oldOffset = file.oldOffset(cursor)
    val leafElement = file.parse.findElementAt(oldOffset) ?: return null

    // Walk up from leaf to find KtNameReferenceExpression
    var current: PsiElement? = leafElement
    var referenceExpr: KtNameReferenceExpression? = null
    while (current != null) {
        if (current is KtNameReferenceExpression) {
            referenceExpr = current
            break
        }
        current = current.parent
    }

    if (referenceExpr == null || referenceExpr.getReferencedName() != "it") {
        return null
    }

    val lambda = findEnclosingImplicitLambda(referenceExpr, cursor)
    if (lambda == null) {
        LOG.debug("rename: no enclosing implicit lambda found")
        return null
    }

    // Ensure "it" refers to the implicit lambda parameter (not a captured variable)
    if (!isImplicitItParameter(file.compile, referenceExpr)) {
        return null
    }

    val itReferences = lambda.bodyExpression?.let { body ->
        findImplicitItReferencesInBody(file.compile, body)
    } ?: emptyList()

    if (itReferences.isEmpty()) {
        LOG.info("rename: no 'it' references found in lambda body")
        return null
    }

    return ImplicitItContext(referenceExpr, lambda, itReferences)
}

private fun buildRenameWorkspaceEdit(
    file: CompiledFile,
    lambda: KtLambdaExpression,
    newName: String,
    itReferences: List<KtNameReferenceExpression>
): WorkspaceEdit {
    // Single TextDocumentEdit ensures all edit ranges are relative to original document state
    val uri = file.parse.toPath().toUri().toString()
    val versionedId = VersionedTextDocumentIdentifier()
    versionedId.uri = uri

    val allTextEdits = mutableListOf<Either<TextEdit, SnippetTextEdit>>()

    // Add explicit parameter and rename all references in one edit
    val paramTextEdit = createParameterTextEdit(lambda, newName, file.content)
    allTextEdits.add(Either.forLeft(paramTextEdit))

    for (ref in itReferences) {
        val textEdit = TextEdit().apply {
            newText = newName
            range = rangeOf(file.content, ref.textRange)
        }
        allTextEdits.add(Either.forLeft(textEdit))
    }

    val documentEdit = TextDocumentEdit()
    documentEdit.textDocument = versionedId
    documentEdit.edits = allTextEdits

    return WorkspaceEdit(listOf(Either.forLeft(documentEdit)))
}

/**
 * Check if the given "it" reference is actually an implicit lambda parameter
 * using the binding context.
 */
private fun isImplicitItParameter(bindingContext: BindingContext, refExpr: KtNameReferenceExpression): Boolean {
    // Check if the reference target is a ValueParameterDescriptor
    // which indicates it's a lambda parameter
    val target = bindingContext[BindingContext.REFERENCE_TARGET, refExpr]
    return target is ValueParameterDescriptor
}

/**
 * Find all references to implicit "it" in the lambda body.
 * Uses binding context when available.
 */
private fun findImplicitItReferencesInBody(
    bindingContext: BindingContext,
    body: KtExpression
): List<KtNameReferenceExpression> {
    val references = mutableListOf<KtNameReferenceExpression>()

    fun collectReferences(element: KtElement) {
        when (element) {
            is KtNameReferenceExpression -> {
                if (element.getReferencedName() == "it") {
                    // Only count if 'it' refers to a lambda parameter (not a regular variable)
                    val target = bindingContext[BindingContext.REFERENCE_TARGET, element]
                    if (target is ValueParameterDescriptor) {
                        references.add(element)
                    }
                }
            }
            is KtLambdaExpression -> return // Nested lambdas have their own scope
        }
        element.children.forEach { child ->
            if (child is KtElement) {
                collectReferences(child)
            }
        }
    }

    collectReferences(body)
    return references
}

private fun findEnclosingImplicitLambda(element: KtElement, cursor: Int): KtLambdaExpression? {
    var current: PsiElement? = element
    while (current != null) {
        if (current is KtLambdaExpression && isImplicitLambdaWithCursorInBody(current, cursor)) {
            return current
        }
        current = current.parent
    }
    return null
}

private fun isImplicitLambdaWithCursorInBody(lambda: KtLambdaExpression, cursor: Int): Boolean {
    if (lambda.valueParameters.isNotEmpty()) {
        return false
    }
    val body = lambda.bodyExpression ?: return false
    return cursor >= body.textRange.startOffset && cursor <= body.textRange.endOffset
}

/**
 * Create a TextEdit for inserting the parameter (not a full TextDocumentEdit).
 * Used when we need to combine multiple edits into a single TextDocumentEdit.
 */
private fun computeLambdaParameterInsertion(
    lambda: KtLambdaExpression,
    paramName: String,
    content: String
): Pair<String, Position> {
    val lambdaText = lambda.text
    val lambdaStart = lambda.textRange.startOffset

    val openBraceOffset = lambdaText.indexOf('{')
    check(openBraceOffset != -1) { "Lambda expression without '{' at ${lambda.textRange}" }

    val insertOffset = lambdaStart + openBraceOffset + 1
    val hasArrow = lambdaText.contains("->")
    val insertText = if (hasArrow) {
        " $paramName"
    } else {
        " $paramName ->"
    }

    return Pair(insertText, position(content, insertOffset))
}

/**
 * Create a TextEdit for inserting the parameter (not a full TextDocumentEdit).
 * Used when we need to combine multiple edits into a single TextDocumentEdit.
 */
private fun createParameterTextEdit(
    lambda: KtLambdaExpression,
    paramName: String,
    content: String
): TextEdit {
    val (insertText, insertPos) = computeLambdaParameterInsertion(lambda, paramName, content)

    return TextEdit().apply {
        newText = insertText
        range = Range(insertPos, insertPos)
    }
}

private fun position(content: String, offset: Int): Position {
    var line = 0
    var lastNewline = -1
    var currentOffset = 0

    while (currentOffset < offset && currentOffset < content.length) {
        if (content[currentOffset] == '\n') {
            line++
            lastNewline = currentOffset
        }
        currentOffset++
    }

    val char = offset - lastNewline - 1
    return Position(line, char)
}

private fun isValidIdentifier(name: String): Boolean {
    if (name.isEmpty()) return false

    val reservedWords = setOf(
        "true", "false", "null", "this", "super", "is", "in", "as",
        "if", "else", "when", "for", "while", "do", "return", "break",
        "continue", "throw", "try", "catch", "finally", "class", "interface",
        "object", "fun", "val", "var", "typealias", "enum", "sealed", "data",
        "annotation", "companion", "init", "constructor", "by", "where",
        "get", "set", "setValue", "getValue", "delegate", "field", "file",
        "import", "package", "suspend", "inline", "noinline", "crossinline",
        "reified", "operator", "infix", "tailrec", "external", "override",
        "lateinit", "vararg", "typeParams", "out", "const", "actual",
        "expect", "abstract", "open", "final", "private", "protected",
        "public", "internal", "inner", "transient", "volatile"
    )

    if (name in reservedWords) return false

    val firstChar = name.first()
    if (!firstChar.isLetter() && firstChar != '_') return false

    return name.all { it.isLetterOrDigit() || it == '_' }
}

fun prepareRename(file: CompiledFile, cursor: Int): Pair<Range, String>? {
    LOG.debug { "prepareRename: cursor=$cursor" }

    tryReferenceAtPoint(file, cursor)?.let { return it }
    tryFindDeclaration(file, cursor)?.let { return it }
    tryImplicitIt(file, cursor)?.let { return it }

    LOG.info("prepareRename: failed (no valid rename target at cursor=$cursor)")
    return null
}

private fun tryReferenceAtPoint(file: CompiledFile, cursor: Int): Pair<Range, String>? {
    val referenceResult = file.referenceAtPoint(cursor) ?: return null
    val (_, target) = referenceResult
    val psi = target.findPsi()
    val name = (psi as? KtNamedDeclaration)?.name ?: return null
    val nameIdentifier = psi.nameIdentifier ?: return null
    if (!isValidIdentifier(name)) return null

    LOG.debug { "prepareRename: found reference '${psi.javaClass.simpleName}' with name '$name'" }

    return location(nameIdentifier)?.let { Pair(it.range, name) }
}

private fun tryFindDeclaration(file: CompiledFile, cursor: Int): Pair<Range, String>? {
    val declarationResult = file.findDeclaration(cursor) ?: return null
    val (declaration, loc) = declarationResult

    val name = declaration.name ?: return null
    val nameIdentifier = declaration.nameIdentifier ?: return null
    if (!isValidIdentifier(name) || !isCursorOnSameLineAfterIdentifier(file, cursor, nameIdentifier)) return null

    LOG.debug { "prepareRename: found declaration '${declaration.javaClass.simpleName}' with name '$name'" }
    return Pair(loc.range, name)
}

private fun isCursorOnSameLineAfterIdentifier(file: CompiledFile, cursor: Int, nameIdentifier: PsiElement): Boolean {
    val oldCursor = file.oldOffset(cursor)
    val identifierRange = nameIdentifier.textRange
    val content = file.content
    var cursorLine = 0
    for (i in 0 until oldCursor) {
        if (i < content.length && content[i] == '\n') cursorLine++
    }
    var identifierLine = 0
    for (i in 0 until identifierRange.startOffset) {
        if (i < content.length && content[i] == '\n') identifierLine++
    }

    return cursorLine == identifierLine &&
        oldCursor >= identifierRange.startOffset &&
        oldCursor <= identifierRange.endOffset + 1
}

private fun tryImplicitIt(file: CompiledFile, cursor: Int): Pair<Range, String>? {
    val implicitItContext = findImplicitItContext(file, cursor) ?: return null
    LOG.debug { "prepareRename: found implicit 'it' parameter" }

    val lambda = implicitItContext.lambda
    val body = lambda.bodyExpression ?: return null
    val range = rangeOf(file.content, body.textRange)

    return Pair(range, "it")
}
