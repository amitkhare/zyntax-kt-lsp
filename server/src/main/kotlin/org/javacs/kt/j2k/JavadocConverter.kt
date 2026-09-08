package org.javacs.kt.j2k

import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.javadoc.PsiDocTag

private val CODE_TAG = Regex("\\{@code\\s+([^}]+)}")
private val LINK_TAG = Regex("\\{@link\\s+([^}]+)}")
private val LITERAL_TAG = Regex("\\{@literal\\s+([^}]+)}")
private val VALUE_TAG = Regex("\\{@value\\s+([^}]+)}")
private val HREF_ATTR = Regex("href=\"([^\"]+)\"")
private val WHITESPACE_SPLIT = Regex("\\s+")

fun convertJavadocToKDoc(javadoc: PsiDocComment): String {
    val description = convertDescription(javadoc)
    val tags = javadoc.tags
    val convertedTags = tags.mapNotNull { convertTag(it) }

    val parts = mutableListOf<String>()
    if (description.isNotEmpty()) {
        parts.add(description)
    }
    if (convertedTags.isNotEmpty()) {
        parts.add(convertedTags.joinToString("\n"))
    }

    return if (parts.isNotEmpty()) {
        "/**\n${parts.joinToString("\n\n")}\n */"
    } else {
        ""
    }
}

private fun convertDescription(javadoc: PsiDocComment): String {
    val descriptionElements = javadoc.descriptionElements
    val description = descriptionElements
        .mapNotNull { element ->
            when {
                element.text.isNullOrEmpty() -> null
                else -> convertInlineTags(element.text)
            }
        }
        .joinToString("")
        .trim()

    return if (description.isNotEmpty()) {
        " * " + description.lines().joinToString("\n * ")
    } else {
        ""
    }
}

fun convertTag(tag: PsiDocTag): String? {
    val tagName = tag.name.lowercase()
    val value = tag.valueElement?.text ?: ""
    val dataElements = tag.dataElements
        .mapNotNull { element -> element.text }
        .joinToString(" ")
        .trim()

    // For param and throws tags, the first element in dataElements is the name
    // so we need to extract just the description
    val description = when (tagName) {
        "param", "throws", "exception" -> {
            // Remove the first word (parameter/exception name) from dataElements
            val parts = dataElements.trim().split(WHITESPACE_SPLIT, 2)
            if (parts.size > 1) parts[1].trim() else ""
        }
        else -> dataElements
    }

    return when (tagName) {
        "param" -> convertParamTag(value, description)
        "return" -> convertReturnTag(dataElements)
        "throws", "exception" -> convertThrowsTag(value, description)
        "see" -> convertSeeTag(dataElements)
        "author" -> convertAuthorTag(dataElements)
        "version" -> convertVersionTag(dataElements)
        "since" -> convertSinceTag(dataElements)
        "deprecated" -> convertDeprecatedTag(dataElements)
        else -> convertGenericTag(tagName, dataElements)
    }
}

private fun convertParamTag(paramName: String, description: String): String {
    val desc = convertInlineTags(description.trim()).lines()
    return if (desc.size == 1) {
        " * @param ${paramName.trim()} ${desc[0].trim()}"
    } else {
        val first = desc.first().trim()
        val rest = desc.drop(1).joinToString("\n") { " *     ${it.trim()}" }
        " * @param ${paramName.trim()} $first\n$rest"
    }
}

private fun convertReturnTag(description: String): String {
    val desc = convertInlineTags(description.trim()).lines()
    return if (desc.size == 1) {
        " * @return ${desc[0].trim()}"
    } else {
        val first = desc.first().trim()
        val rest = desc.drop(1).joinToString("\n") { " *     ${it.trim()}" }
        " * @return $first\n$rest"
    }
}

private fun convertThrowsTag(exceptionName: String, description: String): String {
    val desc = convertInlineTags(description.trim()).lines()
    return if (desc.size == 1) {
        " * @throws ${exceptionName.trim()} ${desc[0].trim()}"
    } else {
        val first = desc.first().trim()
        val rest = desc.drop(1).joinToString("\n") { " *     ${it.trim()}" }
        " * @throws ${exceptionName.trim()} $first\n$rest"
    }
}

private fun convertSeeTag(reference: String): String {
    val ref = reference.trim()
    return if (ref.startsWith("\"") && ref.endsWith("\"")) {
        " * @see $ref"
    } else if (ref.startsWith("<a")) {
        val href = extractHref(ref) ?: ref
        " * @see $href"
    } else {
        " * @see [$ref]"
    }
}

private fun convertAuthorTag(author: String): String {
    return " * @author ${author.trim()}"
}

private fun convertVersionTag(version: String): String {
    return " * @version ${version.trim()}"
}

private fun convertSinceTag(since: String): String {
    return " * @since ${since.trim()}"
}

private fun convertDeprecatedTag(description: String): String {
    val desc = if (description.isNotBlank()) convertInlineTags(description.trim()) else ""
    return " * @deprecated ${desc.trim()}"
}

private fun convertGenericTag(tagName: String, content: String): String {
    val contentTrimmed = content.trim()
    return if (contentTrimmed.isNotEmpty()) {
        " * @$tagName $contentTrimmed"
    } else {
        " * @$tagName"
    }
}

fun convertInlineTags(text: String): String {
    var result = text

    result = CODE_TAG.replace(result) { "`${it.groupValues[1].trim()}`" }
    result = LINK_TAG.replace(result) { matchResult ->
        val target = matchResult.groupValues[1].trim()
        if (target.contains("#")) {
            val parts = target.split("#", limit = 2)
            "[${parts[1].trim()}](${parts[0].trim()}#${parts[1].trim()})"
        } else {
            "[$target]($target)"
        }
    }
    result = LITERAL_TAG.replace(result) { "`${it.groupValues[1].trim()}`" }
    result = VALUE_TAG.replace(result) { it.groupValues[1].trim() }

    return result
}

private fun extractHref(htmlTag: String): String? {
    return HREF_ATTR.find(htmlTag)?.groupValues?.get(1)
}
