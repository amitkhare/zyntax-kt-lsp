package org.javacs.kt.docs

/** State machine that groups `@param`, `@return`, `@throws`, `@see`, etc. into sections. */
internal class TagParser {
    val tags = mutableMapOf<String, MutableList<TagEntry>>()
    val bodyLines = mutableListOf<String>()
    private var currentTag: String? = null
    private var currentName: String? = null
    private var currentDesc = mutableListOf<String>()
    private var inTags = false

    /** Flushes the current accumulated tag entry into the tags map. */
    fun flushTag() {
        val tag = currentTag ?: return
        val desc = currentDesc.joinToString(" ").trim()
        tags.getOrPut(tag) { mutableListOf() }.add(TagEntry(currentName, desc))
        currentTag = null
        currentName = null
        currentDesc = mutableListOf()
    }

    /** Processes one line of text, detecting and populating tag entries. */
    fun processLine(line: String) {
        val trimmed = line.trim()
        val tagMatch = blockTagRegex.find(trimmed)

        if (tagMatch != null) {
            inTags = true
            flushTag()
            currentTag = tagMatch.groupValues[1]
            val rest = tagMatch.groupValues[2].orEmpty()
            // @param and @throws have a name part; others just have a description
            if (currentTag == "param" || currentTag == "throws") {
                val parts = rest.split(" ", limit = 2)
                currentName = parts[0]
                if (parts.size > 1) currentDesc.add(parts[1])
            } else {
                currentName = null
                if (rest.isNotEmpty()) currentDesc.add(rest)
            }
            // Blank line separates tags: flush and exit tag mode
        } else if (inTags && trimmed.isEmpty()) {
            flushTag()
            inTags = false
            // Continuation of the current tag's multi-line description
        } else if (inTags && trimmed.isNotEmpty()) {
            currentDesc.add(trimmed)
            // Lines before any @tag belong to the body
        } else if (!inTags) {
            bodyLines.add(line)
        }
    }
}
