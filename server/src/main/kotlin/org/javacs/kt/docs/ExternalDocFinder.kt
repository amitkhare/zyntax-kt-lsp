package org.javacs.kt.docs

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.kdoc.lexer.KDocLexer
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens

private val htmlConverter = HtmlToMarkdownConverter()

/**
 * Extracts documentation from a source file (Kotlin or Java) by finding the KDoc/Javadoc
 * comment preceding the declaration. Uses KDocLexer for Kotlin sources and HTML->Markdown
 * conversion for Java sources.
 *
 * @param sourceContent The full source file content.
 * @param declarationName The name of the declaration to find the doc comment for.
 * @param sourceFileName (optional) File name used to determine language.
 * @param descriptor (optional) Descriptor used to narrow the search to the correct
 *   declaration type (class, method, field). When provided, only the matching
 *   search strategy is used; when null, all strategies are tried in order.
 * @return The extracted documentation text, or null if no doc comment is found.
 */
fun extractKDocFromSource(
    sourceContent: String,
    declarationName: String,
    sourceFileName: String? = null,
    descriptor: DeclarationDescriptor? = null
): String? {
    val kdocStart = findKDocPosition(sourceContent, declarationName, sourceFileName, descriptor) ?: return null
    val kdocEnd = findKDocEnd(sourceContent, kdocStart) ?: return null

    val kdocText = sourceContent.substring(kdocStart, kdocEnd)
    val isJava = sourceFileName?.endsWith(".java") == true

    return try {
        if (isJava) {
            parseJavaDocWithHtmlConverter(kdocText)
        } else {
            parseKDocWithLexer(kdocText) ?: cleanKDoc(kdocText)
        }
    } catch (_: Exception) {
        cleanKDoc(kdocText)
    }
}

/** Extracts Javadoc using HTML->Markdown conversion (for HTML tags in Javadoc). */
private fun parseJavaDocWithHtmlConverter(kdocText: String): String {
    return htmlConverter.convert(kdocText)
}

/** Extracts KDoc using the Kotlin compiler's KDocLexer for reliable tokenization. */
private fun parseKDocWithLexer(kdocText: String): String? {
    val lexer = KDocLexer()
    lexer.start(kdocText)

    val content = StringBuilder()
    var lastEnd = 0

    while (true) {
        val tokenType = lexer.tokenType ?: break
        val tokenText = lexer.tokenText
        val tokenStart = lexer.tokenStart
        val tokenEnd = lexer.tokenEnd

        if (tokenStart > lastEnd) {
            val gap = kdocText.substring(lastEnd, tokenStart)
            content.append(gap)
        }

        when (tokenType) {
            KDocTokens.TEXT -> content.append(tokenText)
            KDocTokens.TAG_NAME -> content.append(tokenText)
        }

        lastEnd = tokenEnd
        lexer.advance()
    }

    return content.toString().trim().ifEmpty { null }
}

/**
 * Finds the start position of a KDoc/Javadoc comment preceding the declaration.
 *
 * Delegates to Java or Kotlin-specific search strategies based on the source file type.
 * When a [descriptor] is provided for Java sources, the search is narrowed to the
 * matching declaration type (class, method, field).
 *
 * @param sourceContent The full source file content to search within.
 * @param declarationName The name of the declaration to find the doc comment for.
 * @param sourceFileName Optional file name used to determine language (Java vs Kotlin).
 * @param descriptor Optional descriptor used to narrow the search to the correct declaration type in Java sources.
 * @return The character index of the start of the doc comment, or null if not found.
 */
internal fun findKDocPosition(
    sourceContent: String,
    declarationName: String,
    sourceFileName: String? = null,
    descriptor: DeclarationDescriptor? = null
): Int? {
    val ranges = commentRanges(sourceContent)
    val isJava = sourceFileName?.endsWith(".java") == true
    return if (isJava) {
        findJavaDocPosition(sourceContent, declarationName, ranges, descriptor)
    } else {
        findKotlinDocPosition(sourceContent, declarationName, ranges)
    }
}

/**
 * Searches for a Javadoc comment in Java source, dispatching to the correct
 * search strategy based on the descriptor type (class, method, or field).
 *
 * When the descriptor is null, all three strategies are tried in order as a
 * defensive fallback.
 *
 * @param sourceContent The full Java source content.
 * @param declarationName The name of the Java declaration to locate.
 * @param ranges Non-code ranges (comments and string literals) used to skip false matches.
 * @param descriptor Optional descriptor used to select the search strategy by type.
 * @return The character index of the Javadoc start, or null if not found.
 */
private fun findJavaDocPosition(
    sourceContent: String,
    declarationName: String,
    ranges: List<IntRange>,
    descriptor: DeclarationDescriptor? = null
): Int? {
    return when (descriptor) {
        is ClassDescriptor -> findJavaClassDocPosition(sourceContent, declarationName, ranges)
        is ConstructorDescriptor, is FunctionDescriptor -> findJavaMethodDocPosition(sourceContent, declarationName, ranges, descriptor)
        is PropertyDescriptor -> findJavaFieldDocPosition(sourceContent, declarationName, ranges)
        else -> // No descriptor: fallback chain for callers without type info
            findJavaClassDocPosition(sourceContent, declarationName, ranges)
            ?: findJavaMethodDocPosition(sourceContent, declarationName, ranges)
            ?: findJavaFieldDocPosition(sourceContent, declarationName, ranges)
    }
}

/**
 * Searches for a Javadoc comment before a class, interface, or enum declaration.
 *
 * Validates that each candidate occurrence is preceded by a word boundary and followed by a valid class
 * delimiter (`{`, `<`, whitespace, `extends`, or `implements`).
 *
 * @param sourceContent The full Java source content.
 * @param declarationName The class/interface/enum name to locate.
 * @param ranges Non-code ranges used to skip matches inside comments and strings.
 * @return The character index of the Javadoc start, or null if no valid match is found.
 */
private fun findJavaClassDocPosition(sourceContent: String, declarationName: String, ranges: List<IntRange>): Int? {
    val classKeywords = listOf("class ", "interface ", "enum ")

    for (keyword in classKeywords) {
        var classPos = sourceContent.indexOf(keyword + declarationName)

        while (classPos >= 0) {
            val namePos = classPos + keyword.length

            if (!isInRanges(classPos, ranges) &&
                isValidJavaClassOccurrence(sourceContent, classPos, namePos, declarationName)
            ) {
                return findKDocBeforePosition(sourceContent, namePos, ranges)
            }

            classPos = sourceContent.indexOf(keyword + declarationName, classPos + 1)
        }
    }

    return null
}

/**
 * Returns true if the class declaration occurrence at [classPos] has valid surrounding context.
 *
 * A valid occurrence is one where:
 * - The keyword is at the start of content or preceded by whitespace/newline.
 * - The declaration name is followed by `{`, `<`, whitespace, or a character that starts
 *   `extends` (`e`) or `implements` (`i`).
 *
 * @param sourceContent The full source content.
 * @param classPos The position of the keyword (e.g. "class ") in the source.
 * @param namePos The position of the declaration name itself.
 * @param declarationName The name being validated.
 */
private fun isValidJavaClassOccurrence(
    sourceContent: String,
    classPos: Int,
    namePos: Int,
    declarationName: String
): Boolean {
    val isValidPredecessor = classPos == 0 ||
        sourceContent[classPos - 1].let { it.isWhitespace() || it == '\n' }

    val afterPos = namePos + declarationName.length
    val isValidSuccessor = afterPos >= sourceContent.length ||
        sourceContent[afterPos].let { char ->
            char == '{' || char == '<' || char.isWhitespace() ||
                char == 'e' || char == 'i'  // starts "extends" or "implements"
        }

    return isValidPredecessor && isValidSuccessor
}

/**
 * Searches for a Javadoc comment before a method or constructor declaration.
 *
 * Iterates over every occurrence of `declarationName(`, skipping those inside non-code
 * [ranges] (comments, strings) and those that are not declarations (call sites like
 * `handler.openConnection(this)` or Javadoc cross-references like `#openConnection(URL)`).
 *
 * When [descriptor] is a [FunctionDescriptor], the parameter list is parsed and only the
 * overload whose parameter count matches is accepted, so `openConnection()` and
 * `openConnection(Proxy)` each get their own documentation.
 *
 * @param sourceContent The full Java source content.
 * @param declarationName The method or constructor name.
 * @param ranges Non-code ranges used to skip matches inside comments and strings.
 * @param descriptor Optional descriptor used to match the exact overload.
 * @return The character index of the Javadoc start, or null if not found.
 */
private fun findJavaMethodDocPosition(
    sourceContent: String,
    declarationName: String,
    ranges: List<IntRange>,
    descriptor: DeclarationDescriptor? = null
): Int? = findJavaMethodDocPosition(
    sourceContent,
    declarationName,
    ranges,
    (descriptor as? FunctionDescriptor)?.valueParameters?.size
)

/**
 * Testable overload that matches a method declaration by its parameter count instead of a
 * compiler descriptor.
 */
internal fun findJavaMethodDocPosition(sourceContent: String, declarationName: String, expectedParamCount: Int?): Int? =
    findJavaMethodDocPosition(sourceContent, declarationName, commentRanges(sourceContent), expectedParamCount)

private fun findJavaMethodDocPosition(
    sourceContent: String,
    declarationName: String,
    ranges: List<IntRange>,
    expectedParamCount: Int?
): Int? {
    val methodPattern = "$declarationName("
    var methodPos = sourceContent.indexOf(methodPattern)

    while (methodPos >= 0) {
        if (isValidJavaMethodOccurrence(sourceContent, methodPos, declarationName, ranges, expectedParamCount)) {
            return findKDocBeforePosition(sourceContent, methodPos, ranges)
        }
        methodPos = sourceContent.indexOf(methodPattern, methodPos + 1)
    }

    return null
}

/**
 * Searches for a Javadoc comment before a field declaration.
 *
 * Scans all occurrences of [declarationName] and checks whether each is likely a field
 * declaration - preceded by whitespace or a dot, and followed by `=`, `;`, `:`, or whitespace.
 *
 * @param sourceContent The full Java source content.
 * @param declarationName The field name to locate.
 * @param ranges Non-code ranges used to skip matches inside comments and strings.
 * @return The character index of the Javadoc start, or null if no valid field match is found.
 */
private fun findJavaFieldDocPosition(sourceContent: String, declarationName: String, ranges: List<IntRange>): Int? {
    var fieldPos = sourceContent.indexOf(declarationName)

    while (fieldPos >= 0) {
        if (!isInRanges(fieldPos, ranges) &&
            isValidJavaFieldOccurrence(sourceContent, fieldPos, declarationName)
        ) {
            return findKDocBeforePosition(sourceContent, fieldPos, ranges)
        }

        fieldPos = sourceContent.indexOf(declarationName, fieldPos + 1)
    }

    return null
}

/**
 * Returns true if the occurrence of [declarationName] at [fieldPos] looks like a field declaration.
 *
 * Checks that the name is:
 * - Preceded by whitespace or `.` (not in the middle of another identifier).
 * - Followed by `=`, `;`, `:`, or whitespace (classic field declaration delimiters).
 *
 * @param sourceContent The full source content.
 * @param fieldPos The position of the candidate field name.
 * @param declarationName The field name being validated.
 */
private fun isValidJavaFieldOccurrence(
    sourceContent: String,
    fieldPos: Int,
    declarationName: String
): Boolean {
    val isPrecededByWhitespace = fieldPos == 0 ||
        sourceContent[fieldPos - 1].let { it.isWhitespace() || it == '.' }

    val afterPos = fieldPos + declarationName.length
    val isFollowedByFieldDelimiter = afterPos < sourceContent.length &&
        sourceContent[afterPos].let { char ->
            char == '=' || char == ';' || char == ':' || char.isWhitespace()
        }

    return isPrecededByWhitespace && isFollowedByFieldDelimiter
}

/**
 * Searches for a KDoc comment position in Kotlin source using declaration keyword patterns.
 *
 * Tries each of `fun`, `class`, `interface`, `object`, `val`, and `var` prefixed to [declarationName]
 * and returns the position of the first matching KDoc comment found. Matches inside comments or
 * string literals are skipped using [ranges].
 *
 * @param sourceContent The full Kotlin source content.
 * @param declarationName The name of the Kotlin declaration to locate.
 * @param ranges Non-code ranges used to skip matches inside comments and strings.
 * @return The character index of the KDoc start, or null if not found.
 */
private fun findKotlinDocPosition(sourceContent: String, declarationName: String, ranges: List<IntRange>): Int? {
    val declarations = listOf("fun ", "class ", "interface ", "object ", "val ", "var ")

    for (pattern in declarations.map { "$it$declarationName" }) {
        findKotlinDocForPattern(sourceContent, pattern, ranges)?.let { return it }
    }

    return null
}

/** Returns the KDoc start for the first occurrence of [pattern] not inside a comment or string. */
private fun findKotlinDocForPattern(sourceContent: String, pattern: String, ranges: List<IntRange>): Int? {
    var declarationPos = sourceContent.indexOf(pattern)

    while (declarationPos >= 0) {
        if (!isInRanges(declarationPos, ranges)) {
            findKDocBeforePosition(sourceContent, declarationPos, ranges)?.let { return it }
        }
        declarationPos = sourceContent.indexOf(pattern, declarationPos + 1)
    }

    return null
}

/** Removes KDoc/Javadoc markers (/**, */, leading *) and formats as plain text. */
internal fun cleanKDoc(kdocText: String): String {
    val lines = kdocText.lines()
    if (lines.isEmpty()) return ""

    val docLines = mutableListOf<String>()
    var inCodeBlock = false

    for (line in lines) {
        val trimmed = line.trim()

        if (trimmed.startsWith("/**")) {
            val content = trimmed.removePrefix("/**").trim()
            if (content.isNotEmpty()) {
                docLines.add(content)
            }
            continue
        }

        if (trimmed.endsWith("*/")) {
            val content = trimmed.removeSuffix("*/").trim()
            if (content.isNotEmpty()) {
                if (content.startsWith("*")) {
                    docLines.add(content.removePrefix("*").trim())
                } else {
                    docLines.add(content)
                }
            }
            continue
        }

        if (trimmed.isEmpty()) {
            if (docLines.isNotEmpty()) {
                docLines.add("")
            }
            continue
        }

        val processedLine = if (trimmed.startsWith("*")) {
            trimmed.removePrefix("*").trim()
        } else {
            trimmed
        }

        if (processedLine.startsWith("```")) {
            inCodeBlock = !inCodeBlock
        }

        if (processedLine.isNotEmpty() || docLines.isNotEmpty()) {
            docLines.add(processedLine)
        }
    }

    var result = docLines.joinToString("\n").trim()

    // Format Javadoc tags: add newlines before them for better readability
    // This converts: "description @param x @return y"
    // to: "description\n@param x\n@return y"
    val javadocTags = listOf("@param", "@return", "@see", "@throws", "@since", "@deprecated")
    for (tag in javadocTags) {
        result = result.replace(" $tag", "\n$tag")
    }

    return result
}

/** Checks if a URI points to a file inside a JAR or ZIP archive. */
fun isArchiveUri(uri: String): Boolean {
    return uri.contains(".jar!") || uri.contains(".zip!")
}
