package org.javacs.kt.folding

import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind
import org.javacs.kt.position.position
import org.javacs.kt.util.preOrderTraversal
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import com.intellij.psi.PsiElement

private const val KDOC_START = "/**"
private const val BLOCK_COMMENT_START = "/*"
private const val COMMENT_END = "*/"
private const val KDOC_START_LENGTH = 3
private const val BLOCK_COMMENT_START_LENGTH = 2

fun foldingRanges(file: KtFile): List<FoldingRange> {
    val result = mutableListOf<FoldingRange>()
    val content = file.text

    processImports(file, content, result)
    processPsiTree(file, content, result)
    processCommentBlocks(content, result)

    return result.distinctBy { "${it.startLine}:${it.endLine}:${it.kind}" }
}

private fun processImports(file: KtFile, content: String, result: MutableList<FoldingRange>) {
    val imports = file.importList?.imports ?: return
    if (imports.size <= 1) return

    val first = imports.first()
    val last = imports.last()
    result.add(FoldingRange().apply {
        startLine = position(content, first.startOffset).line
        endLine = position(content, last.endOffset).line
        kind = FoldingRangeKind.Imports
    })
}

private fun processPsiTree(file: KtFile, content: String, result: MutableList<FoldingRange>) {
    file.preOrderTraversal().forEach { element ->
        when (element) {
            is KtClassOrObject -> addClassFolding(element, content, result)
            is KtFunction -> addFunctionFolding(element, content, result)
            is KtBlockExpression -> addBlockFolding(element, content, result)
        }
    }
}

private fun addClassFolding(element: KtClassOrObject, content: String, result: MutableList<FoldingRange>) {
    element.body?.let { body ->
        addBraceFolding(body, content, result)
    }
}

private fun addFunctionFolding(element: KtFunction, content: String, result: MutableList<FoldingRange>) {
    element.bodyBlockExpression?.let { body ->
        addBraceFolding(body, content, result)
    }
}

/** Creates a folding range from brace positions for class and function bodies. */
private fun addBraceFolding(body: PsiElement, content: String, result: MutableList<FoldingRange>) {
    val lBrace = (body as? KtClassBody)?.lBrace ?: (body as? KtBlockExpression)?.lBrace
    val rBrace = (body as? KtClassBody)?.rBrace ?: (body as? KtBlockExpression)?.rBrace
    result.add(FoldingRange().apply {
        startLine = position(content, lBrace?.startOffset ?: body.startOffset).line
        endLine = position(content, rBrace?.startOffset ?: body.endOffset).line
        kind = FoldingRangeKind.Region
    })
}

private fun addBlockFolding(element: KtBlockExpression, content: String, result: MutableList<FoldingRange>) {
    val parent = element.parent
    if (parent is KtFunction || parent is KtClassBody) return

    result.add(FoldingRange().apply {
        startLine = position(content, element.lBrace?.startOffset ?: element.startOffset).line
        endLine = position(content, element.rBrace?.startOffset ?: element.endOffset).line
        kind = FoldingRangeKind.Region
    })
}

private fun processCommentBlocks(content: String, result: MutableList<FoldingRange>) {
    var offset = 0
    while (offset < content.length) {
        val (start, endChar) = findCommentStartAndEnd(content, offset) ?: break
        addCommentFolding(content, start, endChar, result)
        offset = endChar + 2
    }
}

private fun findCommentStartAndEnd(content: String, offset: Int): Pair<Int, Int>? {
    val remaining = content.substring(offset)
    val kdocStart = remaining.indexOf(KDOC_START)
    val blockCommentStart = remaining.indexOf(BLOCK_COMMENT_START)

    val start = when {
        kdocStart >= 0 && blockCommentStart !in 0..<kdocStart -> offset + kdocStart
        blockCommentStart >= 0 -> offset + blockCommentStart
        else -> return null
    }

    val searchOffset = if (content.indexOf(KDOC_START, offset) == start) KDOC_START_LENGTH else BLOCK_COMMENT_START_LENGTH
    val endChar = content.indexOf(COMMENT_END, start + searchOffset)

    return if (endChar >= 0) Pair(start, endChar) else null
}

private fun addCommentFolding(
    content: String,
    start: Int,
    endChar: Int,
    result: MutableList<FoldingRange>
) {
    val startPos = position(content, start)
    val endPos = position(content, endChar + 2)
    if (endPos.line > startPos.line) {
        result.add(FoldingRange().apply {
            startLine = startPos.line
            endLine = endPos.line
            kind = FoldingRangeKind.Comment
        })
    }
}
