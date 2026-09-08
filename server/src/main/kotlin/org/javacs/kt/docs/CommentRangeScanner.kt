package org.javacs.kt.docs

/**
 * Low-level source-scanning helpers shared by the doc-extraction searches.
 *
 * Declaration names also appear inside non-code text (comments, string literals, Javadoc cross-references),
 * where a simpler `indexOf` would match them instead of the real declaration.
 *
 * These helpers compute the set of "non-code" ranges so the searches can skip them.
 */

private const val BLOCK_COMMENT_END = "*/"
private const val TEXT_BLOCK_DELIM = "\"\"\""

/**
 * Index ranges of all comments (line/block/doc) and string literals (regular, char, text-block) in [sourceContent],
 * so declaration searches can ignore names inside non-code text.
 */
internal fun commentRanges(sourceContent: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var i = 0
    while (i < sourceContent.length) {
        i = consumeToken(sourceContent, i, ranges)
    }
    return ranges
}

/** Consumes one token (comment or literal) starting at [i], recording its range and advancing. */
private fun consumeToken(sourceContent: String, i: Int, ranges: MutableList<IntRange>): Int {
    val n = sourceContent.length
    val isCommentStart = sourceContent[i] == '/' && i + 1 < n
    return when {
        isCommentStart && sourceContent[i + 1] == '/' -> consumeLineComment(sourceContent, i, ranges)
        isCommentStart && sourceContent[i + 1] == '*' -> consumeBlockComment(sourceContent, i, ranges)
        isTextBlockAt(sourceContent, i) -> consumeTextBlock(sourceContent, i, ranges)
        sourceContent[i] == '"' -> consumeQuoted(sourceContent, i, '"', ranges)
        sourceContent[i] == '\'' -> consumeQuoted(sourceContent, i, '\'', ranges)
        else -> i + 1
    }
}

private fun isTextBlockAt(sourceContent: String, i: Int): Boolean =
    i + 2 < sourceContent.length &&
        sourceContent[i] == '"' && sourceContent[i + 1] == '"' && sourceContent[i + 2] == '"'

private fun consumeLineComment(sourceContent: String, start: Int, ranges: MutableList<IntRange>): Int {
    var end = start
    while (end < sourceContent.length && sourceContent[end] != '\n') end++
    ranges.add(start until end)
    return end
}

private fun consumeBlockComment(sourceContent: String, start: Int, ranges: MutableList<IntRange>): Int {
    val closeIdx = sourceContent.indexOf(BLOCK_COMMENT_END, start + BLOCK_COMMENT_END.length)
    val end = if (closeIdx < 0) sourceContent.length else closeIdx + BLOCK_COMMENT_END.length
    ranges.add(start until end)
    return end
}

private fun consumeTextBlock(sourceContent: String, start: Int, ranges: MutableList<IntRange>): Int {
    val closeIdx = sourceContent.indexOf(TEXT_BLOCK_DELIM, start + TEXT_BLOCK_DELIM.length)
    val end = if (closeIdx < 0) sourceContent.length else closeIdx + TEXT_BLOCK_DELIM.length
    ranges.add(start until end)
    return end
}

private fun consumeQuoted(sourceContent: String, start: Int, quote: Char, ranges: MutableList<IntRange>): Int {
    var end = start + 1
    while (end < sourceContent.length) {
        when {
            sourceContent[end] == '\\' && end + 1 < sourceContent.length -> end += 2
            sourceContent[end] == quote -> {
                ranges.add(start until end + 1)
                return end + 1
            }
            else -> end++
        }
    }
    ranges.add(start until end)
    return end
}

/** Returns true iif [pos] falls inside one of the non-code [ranges]. */
internal fun isInRanges(pos: Int, ranges: List<IntRange>): Boolean = ranges.any { pos in it }

/**
 * Finds the start of the nearest KDoc/Javadoc comment before [declarationPos].
 *
 * Skips non-doc comments (line/plain-block), so a `/** */` nested inside a method body is never mistaken
 * for the declaration's own documentation.
 */
internal fun findKDocBeforePosition(sourceContent: String, declarationPos: Int): Int? =
    findKDocBeforePosition(sourceContent, declarationPos, commentRanges(sourceContent))

/**
 * Finds the start of the nearest KDoc/Javadoc comment before [declarationPos], considering only the non-code [ranges].
 */
internal fun findKDocBeforePosition(
    sourceContent: String,
    declarationPos: Int,
    ranges: List<IntRange>
): Int? {
    return ranges
        .filter { it.last < declarationPos && sourceContent.startsWith("/**", it.first) }
        .maxByOrNull { it.first }
        ?.first
}

/** Index just past the comment's closing marker (the `*` slash) at [start], or null if unterminated. */
internal fun findKDocEnd(sourceContent: String, start: Int): Int? {
    val end = sourceContent.indexOf("*/", start)
    return if (end >= 0) end + 2 else null
}

/**
 * True iif `declarationName(` at [namePos] is a real method/constructor declaration.
 *
 * Rejects occurrences inside [ranges] (comments, strings), those preceded by an identifier
 * char/`.`/`#`/`$`/`(` (call sites, `#name(` cross-references, longer identifiers), those whose
 * param count mismatches [expectedParamCount], and those not followed by `{`/`;`/`throws`
 * (i.e. expression call sites like `openConnection().foo()`).
 */
internal fun isValidJavaMethodOccurrence(
    sourceContent: String,
    namePos: Int,
    declarationName: String,
    ranges: List<IntRange>,
    expectedParamCount: Int?
): Boolean {
    if (isInRanges(namePos, ranges)) return false

    // Reject call sites (`.name(`), Javadoc cross-references (`#name(`), and names that are a
    // suffix of a longer identifier.
    if (namePos > 0 && isNameContinuationChar(sourceContent[namePos - 1])) return false

    return matchesDeclarationShape(sourceContent, namePos, declarationName, expectedParamCount)
}

/**
 * Returns true iif [c] preceding a candidate name means the occurrence is a call site,
 * a Javadoc cross-reference (`#name(`), or a suffix of a longer identifier.
 */
private fun isNameContinuationChar(c: Char): Boolean =
    c.isLetterOrDigit() || c == '_' || c == '.' || c == '#' || c == '$' || c == '('

/**
 * Checks that the occurrence at [namePos] has a matching parameter list and is followed by
 * a declaration continuation, rejecting expression call sites.
 */
private fun matchesDeclarationShape(
    sourceContent: String,
    namePos: Int,
    declarationName: String,
    expectedParamCount: Int?
): Boolean {
    val openParenPos = namePos + declarationName.length
    val closeParenPos = findMatchingCloseParen(sourceContent, openParenPos) ?: return false

    // Match the exact overload when the descriptor tells us how many parameters it has.
    if (expectedParamCount != null) {
        val actualParamCount = countParameters(sourceContent, namePos, declarationName) ?: return false
        if (actualParamCount != expectedParamCount) return false
    }

    // Reject expression call sites: after the closing parenthesis must come `{`, `;`, or
    // `throws` (declaration continuations), never `.` (chained calls) or similar.
    return isDeclarationContinuation(sourceContent, closeParenPos + 1)
}

/**
 * Number of top-level parameters in the list opening at `namePos + declarationName.length`, or `null`
 * if the list can't be parsed.
 *
 * Scans to the matching `)` counting top-level commas, tracking paren/generic depth so nested
 * commas (`Map<String, Integer>`) aren't counted. Returns 0 for `()`.
 */
internal fun countParameters(sourceContent: String, namePos: Int, declarationName: String): Int? {
    val openParenPos = namePos + declarationName.length
    val closeParenPos = findMatchingCloseParen(sourceContent, openParenPos) ?: return null

    var count = 0
    var parenDepth = 0
    var genericDepth = 0
    var i = openParenPos + 1

    while (i < closeParenPos) {
        when (sourceContent[i]) {
            '(' -> parenDepth++
            '<' -> if (parenDepth == 0) genericDepth++
            '>' -> if (genericDepth > 0) genericDepth--
            ',' -> if (parenDepth == 0 && genericDepth == 0) count++
            '"' -> i = skipString(sourceContent, i)
        }
        i++
    }

    // Comma count is parameter count minus one; an empty list has zero parameters.
    val inner = sourceContent.substring(openParenPos + 1, closeParenPos).trim()
    return if (inner.isEmpty()) 0 else count + 1
}

/** Index of the `)` matching the `(` at [openParenPos], or null if unbalanced. */
internal fun findMatchingCloseParen(sourceContent: String, openParenPos: Int): Int? {
    var i = openParenPos
    var depth = 0

    while (i < sourceContent.length) {
        when (sourceContent[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
            '"' -> i = skipString(sourceContent, i)
        }
        i++
    }

    return null
}

/** Index just past the string literal that opens at [start] (its opening `"`). */
private fun skipString(sourceContent: String, start: Int): Int {
    var i = start + 1
    while (i < sourceContent.length) {
        when {
            sourceContent[i] == '\\' && i + 1 < sourceContent.length -> i += 2
            sourceContent[i] == '"' -> return i + 1
            else -> i++
        }
    }
    return i
}

/**
 * True if [idx] continues a method declaration rather than an expression: `{` (body), `;` (abstract/interface),
 * or a `throws` clause.
 */
internal fun isDeclarationContinuation(sourceContent: String, idx: Int): Boolean {
    var i = idx
    while (i < sourceContent.length && sourceContent[i].isWhitespace()) i++
    if (i >= sourceContent.length) return false
    return when {
        sourceContent[i] == '{' || sourceContent[i] == ';' -> true
        sourceContent.startsWith("throws", i) -> true
        else -> false
    }
}
