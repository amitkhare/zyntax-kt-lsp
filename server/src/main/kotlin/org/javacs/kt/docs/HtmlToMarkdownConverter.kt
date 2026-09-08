package org.javacs.kt.docs

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal val blockTagRegex = Regex("""^@(\w+)(?:\s+(.*))?$""")
internal data class TagEntry(val name: String?, val description: String)

/**
 * Converts raw Javadoc comment text into Markdown.
 *
 * Handles comment markers, inline and block tags, HTML markup, code blocks, and
 * member reference cleanup while preserving readable structure.
 */

class HtmlToMarkdownConverter {

    companion object {
        private val linkRegex = Regex("""\{@link\s+(.*?)\}""")
        private val linkplainRegex = Regex("""\{@linkplain\s+(.*?)\}""")
        private val literalRegex = Regex("""\{@literal\s+(.*?)\}""")
        private val inheritDocRegex = Regex("""\{@inheritDoc\}""")
        private val valueRegex = Regex("""\{@value\s+(.*?)\}""")
        private val memberRefRegex = Regex("""\[([^\]]+)#([^\]]+)\]""")
        private val parenRegex = Regex("\\(.*\\)")
    }

    private enum class CodeBlockType {
        SINGLE_LINE,
        MULTI_LINE
    }
    private data class CodeBlock(val content: String, val type: CodeBlockType)

    /** Main entry point: converts raw Javadoc text to Markdown. */
    fun convert(rawText: String): String {
        val stripped = stripMarkers(rawText)

        if (stripped.isBlank()) {
            return ""
        }

        val (noCode, blocks) = extractCodeBlocks(stripped)

        val tagged = processInlineTags(noCode)

        // Parse block tags before Jsoup (preserves line separation of @param, @return, etc.)
        val (noTags, tagSections) = parseBlockTags(tagged)

        val html = convertHtml(noTags)

        val assembled = assembleSections(html, tagSections)

        val withCode = reinsertCodeBlocks(assembled, blocks)

        return postProcess(withCode).trim()
    }

    /** Strips `/**`, `*/`, and leading `*` markers while preserving code indentation. */
    private fun stripMarkers(text: String): String {
        val lines = text.lines()
        if (lines.isEmpty()) return ""

        val result = mutableListOf<String>()

        for ((i, line) in lines.withIndex()) {
            if (!processMarkerLine(i, line.trimStart(), result)) break
        }

        return result.joinToString("\n")
    }

    /** Processes a single comment line, returning false when the closing marker is reached. */
    private fun processMarkerLine(i: Int, line: String, result: MutableList<String>): Boolean {
        // Handle the opening line specially because it may start with "/**" and may also close on
        // the same line
        if (i == 0 && line.startsWith("/**")) {
            val afterOpen = line.removePrefix("/**")
            if (afterOpen.contains("*/")) {
                val content = afterOpen.substringBefore("*/").trim()
                if (content.isNotEmpty()) result.add(content)
                return false
            }
            if (afterOpen.isNotEmpty()) result.add(afterOpen.trimStart())
            return true
        }

        // The final comment line may end with "*/"; strip the closing marker
        if (line.endsWith("*/")) {
            val beforeClose = line.removeSuffix("*/").removePrefix("*")
            val content = if (beforeClose.startsWith(" ")) beforeClose.substring(1) else beforeClose
            if (content.isNotEmpty()) result.add(content)
            return false
        }

        // Middle lines in block comments often start with "*" for formatting.
        if (line == "*" || line.startsWith("*")) {
            val afterStar = line.removePrefix("*")
            val content = if (afterStar.startsWith(" ")) afterStar.substring(1) else afterStar
            result.add(content)
            return true
        }

        // Preserve empty lines inside the comment body
        if (line.isEmpty()) result.add("")
        return true
    }

    /**
     * Extracts code blocks (`<pre>{@code...}</pre>`, `{@code...}`, `<pre>...</pre>`) and replaces
     * them with inert placeholders.
     *
     * Uses brace-depth scanning for `{@code}` blocks so that nested `{`/`}` from method bodies are
     * correctly tracked without relying on fragile regex.
     */
    private fun extractCodeBlocks(text: String): Pair<String, List<CodeBlock>> {
        val blocks = mutableListOf<CodeBlock>()
        val result = StringBuilder()
        var i = 0

        while (i < text.length) {
            i = tryExtractCodeBlock(text, i, blocks, result)
        }

        return Pair(result.toString(), blocks)
    }

    /** Scans at position `i` for a code block, returning the new position after extraction. */
    private fun tryExtractCodeBlock(
        text: String,
        i: Int,
        blocks: MutableList<CodeBlock>,
        result: StringBuilder
    ): Int {
        val remaining = text.substring(i)

        fun emit(content: String) {
            val cleaned = stripTagPrefix(content)
            val isMultiLine = cleaned.contains('\n')
            val placeholder = "___KTLSP_CODE_${blocks.size}___"
            blocks.add(CodeBlock(cleaned.trimEnd(), if (isMultiLine) CodeBlockType.MULTI_LINE else CodeBlockType.SINGLE_LINE))
            result.append(placeholder)
        }

        // <pre>{@code ... }</pre>
        if (remaining.startsWith("<pre>{@code")) {
            val scanResult = scanBraceDepth(remaining, "<pre>{@code".length, expectPreClose = true)
            if (scanResult != null) {
                val (endOffset, content) = scanResult
                emit(content)
                return i + endOffset
            }
        }

        // {@code ... } (bare, not inside <pre>)
        if (remaining.startsWith("{@code")) {
            val scanResult = scanBraceDepth(remaining, "{@code".length, expectPreClose = false)
            if (scanResult != null) {
                val (endOffset, content) = scanResult
                emit(content)
                return i + endOffset
            }
        }

        // <pre>...</pre> (without {@code})
        if (remaining.startsWith("<pre>")) {
            val closeIdx = remaining.indexOf("</pre>")
            if (closeIdx != -1) {
                val content = remaining.substring("<pre>".length, closeIdx)
                emit(content)
                return i + closeIdx + "</pre>".length
            }
        }

        result.append(text[i])
        return i + 1
    }

    /** Strips exactly one leading whitespace character (space or newline) after a tag prefix. */
    private fun stripTagPrefix(content: String): String =
        if (content.startsWith(" ")) content.substring(1)
        else if (content.startsWith("\r\n")) content.substring(2)
        else if (content.startsWith("\n")) content.substring(1)
        else content

    /**
     * Scans from [startPos] to find the matching closing `}` for `{@code...}`, tracking brace depth
     * to handle nested `{`/`}` correctly.
     *
     * @param expectPreClose if true, the closing `}` must be followed by `</pre>`
     * @return (endOffset, content) where endOffset is relative to `text` start and content is the
     *         text between `{@code` and the closing `}`
     */
    private fun scanBraceDepth(
        text: CharSequence,
        startPos: Int,
        expectPreClose: Boolean
    ): Pair<Int, String>? {
        // depth starts at 1 for the `{` in `{@code`
        var depth = 1

        for (i in startPos until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--

                    // only the matching `}` of `{@code` stops the scan
                    if (depth != 0) continue

                    val content = text.substring(startPos, i)

                    if (!expectPreClose) {
                        return Pair(i + 1, content)
                    }

                    // } must be followed by </pre> for <pre> blocks
                    val afterBrace = i + 1
                    if (text.length < afterBrace + "</pre>".length ||
                        !text.startsWith("</pre>", afterBrace)
                    ) {
                        return null
                    }

                    return Pair(afterBrace + "</pre>".length, content)
                }
            }
        }

        return null
    }

    /** Converts Javadoc inline tags to Markdown equivalents. */
    private fun processInlineTags(text: String): String {
        return text
            .replace(linkRegex) { match -> renderLinkTag(match.groupValues[1]) }
            .replace(linkplainRegex) { match -> renderLinkTag(match.groupValues[1]) }
            // @literal escapes HTML but is not code, so no backticks around it
            .replace(literalRegex) { match ->
                match.groupValues[1].trim()
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
            }
            .replace(inheritDocRegex) { "" }
            .replace(valueRegex) { match ->
                "`${match.groupValues[1].trim()}`"
            }
    }

    /** Renders a `{@link}` or `{@linkplain}` tag body as bracket notation. */
    private fun renderLinkTag(content: String): String {
        val (ref, label) = extractLinkParts(content)
        return if (label != null) "[$label]"
        else "[${ref.replace('#', '.').replace(parenRegex, "").substringAfterLast('.')}]"
    }

    /**
     * Splits a `{@link}` tag body into (reference, optional label).
     *
     * Tracks parenthesis depth so method signatures like `String#format(String, Object...)` are
     * parsed as a single reference, not split on the space inside the parameter list.
     */
    private fun extractLinkParts(content: String): Pair<String, String?> {
        val trimmed = content.trim()
        var depth = 0
        var spaceIdx = -1
        for (i in trimmed.indices) {
            when (trimmed[i]) {
                '(' -> depth++
                ')' -> depth--
                ' ' -> if (depth == 0) { spaceIdx = i; break }
            }
        }
        return if (spaceIdx > 0) {
            Pair(trimmed.substring(0, spaceIdx), trimmed.substring(spaceIdx + 1).trim().ifEmpty { null })
        } else {
            Pair(trimmed, null)
        }
    }

    /** Converts remaining HTML to Markdown using Jsoup, with fixed node iteration. */
    private fun convertHtml(text: String): String {
        if (text.isBlank()) return ""
        val doc = Jsoup.parse(text)
        return convertContainer(doc.body())
    }

    /** Recursively converts all child nodes of an element to Markdown. */
    private fun convertContainer(element: Element): String =
        element.childNodes().joinToString("") { convertNode(it) }

    /** Dispatches a DOM node to text extraction or element conversion. */
    private fun convertNode(node: Node): String = when (node) {
        is TextNode -> node.text()
        is Element -> convertElement(node)
        else -> ""
    }

    /** Converts a single HTML element to Markdown based on its tag name. */
    private fun convertElement(element: Element): String {
        return when (element.tagName().lowercase()) {
            "body", "html" -> convertContainer(element)
            // trim Jsoup's inter-tag whitespace from the raw Javadoc
            "p" -> {
                val content = convertContainer(element).trim()
                if (content.isBlank()) "" else "\n\n$content"
            }
            "br" -> "\n"
            "b", "strong" -> "**${element.text()}**"
            "i", "em" -> "*${element.text()}*"
            "code" -> "`${element.text()}`"
            "pre" -> {
                val code = element.text()
                "\n```\n$code\n```\n"
            }
            "a" -> {
                val href = element.attr("href")
                val text = element.text()

                if (href.isNotEmpty()) "[$text]($href)" else text
            }
            "ul", "ol" -> "\n" + element.children().joinToString("") { convertNode(it) }
            "li" -> {
                val parent = element.parent()
                val prefix = if (parent?.tagName()?.lowercase() == "ol") "1. " else "- "

                "$prefix${convertContainer(element)}\n"
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = element.tagName().last().digitToIntOrNull() ?: 1

                "\n${"#".repeat(level)} ${element.text()}\n"
            }
            "hr" -> "\n---\n"
            "table" -> convertTable(element)
            "blockquote" -> {
                val content = convertContainer(element)

                "\n> ${content.replace("\n", "\n> ")}\n"
            }
            "span" -> convertContainer(element)
            "div" -> convertContainer(element)
            // unknown HTML tags: flatten to plain text rather than crashing
            else -> element.text()
        }
    }

    /** Converts an HTML `<table>` element to a Markdown table. */
    private fun convertTable(table: Element): String {
        val rows = table.select("tr")
        if (rows.isEmpty()) return table.text()

        val markdown = StringBuilder()

        for ((index, row) in rows.withIndex()) {
            val cells = row.select("th, td")
            val cellTexts = cells.joinToString(" | ") { it.text() }

            if (index == 0) {
                markdown.append("$cellTexts\n")
                val separators = cells.indices.joinToString(" | ") { "---" }
                markdown.append("$separators\n")
            } else {
                markdown.append("$cellTexts\n")
            }
        }

        return "\n$markdown\n"
    }

    /** Parses and groups block-level Javadoc tags (`@param`, `@return`, etc.). */
    private fun parseBlockTags(text: String): Pair<String, Map<String, List<TagEntry>>> {
        val parser = TagParser()
        for (line in text.lines()) parser.processLine(line)
        parser.flushTag()
        return Pair(parser.bodyLines.joinToString("\n").trim(), parser.tags)
    }

    /** Reinserts code block placeholders as proper Markdown. */
    private fun reinsertCodeBlocks(text: String, blocks: List<CodeBlock>): String {
        return blocks.foldIndexed(text) { i, acc, block ->
            // \u0000 cannot appear in real text, safe as placeholder
            val placeholder = "___KTLSP_CODE_${i}___"
            when (block.type) {
                CodeBlockType.MULTI_LINE -> acc.replace(placeholder, "\n```java\n${block.content}\n```\n")
                CodeBlockType.SINGLE_LINE -> acc.replace(placeholder, "`${block.content}`")
            }
        }
    }

    /** Assembles body text and grouped tag sections into final output. */
    private fun assembleSections(body: String, tags: Map<String, List<TagEntry>>): String {
        if (tags.isEmpty()) return body

        val sections = mutableListOf<String>()

        val tagConfig = listOf(
            "param" to "Parameters",
            "return" to "Returns",
            "throws" to "Throws",
            "see" to "See also",
            "since" to "Since",
            "author" to "Author",
            "deprecated" to "Deprecated"
        )

        for ((tagName, heading) in tagConfig) {
            val entries = tags[tagName] ?: continue
            when (tagName) {
                "param", "throws" -> {
                    val lines = entries.joinToString("\n") { entry ->
                        "  - `${entry.name}`: ${entry.description}"
                    }
                    sections.add("**${heading}:**\n$lines")
                }
                // shorten FQN references (strip package prefix, drop method params)
                "see" -> {
                    val refs = entries.joinToString(", ") { entry ->
                        val cleaned = entry.description.replace('#', '.').replace(parenRegex, "")
                        val parts = cleaned.split('.')
                        val shortRef = parts.takeLast(2).joinToString(".")
                        "[$shortRef]"
                    }
                    sections.add("**${heading}:** $refs")
                }
                // text-only tags without a name component
                "return", "since", "author", "deprecated" -> {
                    val joiner = if (tagName == "author") ", " else " "
                    val header = if (tagName == "deprecated") "Deprecated" else heading
                    val desc = entries.joinToString(joiner) { it.description }
                    sections.add("**${header}:** $desc")
                }
            }
        }

        val tagSection = sections.joinToString("\n\n")
        return if (body.isBlank()) tagSection else "$body\n\n$tagSection"
    }

    /** Converts `Class#method` references to `Class.method` in bracket notation. */
    private fun postProcess(text: String): String {
        return text.replace(memberRefRegex) { match ->
            "[${match.groupValues[1]}.${match.groupValues[2]}]"
        }
    }
}
