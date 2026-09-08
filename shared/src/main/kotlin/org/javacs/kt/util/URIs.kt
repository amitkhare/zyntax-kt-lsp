package org.javacs.kt.util

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Parses a (possibly percent-encoded) URI string received from an LSP client.
 *
 * [URI.create] already decodes the standard RFC-3986 encoding that modern
 * clients emit natively (drive colon, `+`, `[` `]`, spaces, Unicode), so that
 * case needs no manual handling. The fallback covers two inputs [URI.create]
 * rejects: the legacy over-encoded scheme (`file%3A%2F%2F…`) and raw
 * filesystem characters that Java's [java.net.URI] refuses
 * (`[ ] { } ^ \` | " < >` and space). A classpath jar named with `[` `]` is a
 * concrete example (#223/#326).
 *
 * This is not form-decoding. `+` is never turned into a space (#326).
 *
 * @param uri the URI string as received from the client
 * @return the parsed [URI]
 * @throws IllegalArgumentException if the string cannot be normalized into a
 *   valid URI
 */
fun parseURI(uri: String): URI {
    val tryCreate = { s: String -> runCatching { URI.create(s) }.getOrNull() }

    // URI.create already decodes the standard encoding.
    tryCreate(uri)?.let { if (it.scheme != null) return it }

    // Fallback: decode legacy %3A/%2F scheme markers, then encode raw illegal path chars.
    val normalized = encodeIllegalPathChars(
        uri.replace("%3A", ":", ignoreCase = true).replace("%2F", "/", ignoreCase = true)
    )
    tryCreate(normalized)?.let { return it }

    throw IllegalArgumentException("Cannot parse URI: $uri")
}

/** Percent-encodes filesystem-legal characters that [java.net.URI] rejects, preserving existing escapes and '+'. */
private fun encodeIllegalPathChars(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        sb.append(
            when (c) {
                ' ' -> "%20"
                '[' -> "%5B"
                ']' -> "%5D"
                '{' -> "%7B"
                '}' -> "%7D"
                '^' -> "%5E"
                '`' -> "%60"
                '|' -> "%7C"
                '"' -> "%22"
                '<' -> "%3C"
                '>' -> "%3E"
                else -> c.toString()
            }
        )
    }
    return sb.toString()
}

val URI.filePath: Path? get() = runCatching { Paths.get(this) }.getOrNull()

/** Fetches the file extension WITHOUT the dot. */
val URI.fileExtension: String?
    get() {
        val str = toString()
        val dotOffset = str.lastIndexOf(".")
        val queryStart = str.indexOf("?")
        val end = if (queryStart != -1) queryStart else str.length
        return if (dotOffset < 0) null else str.substring(dotOffset + 1, end)
    }

fun describeURIs(uris: Collection<URI>): String =
    if (uris.isEmpty()) "0 files"
    else if (uris.size > 5) "${uris.size} files"
    else uris.joinToString(", ", transform = ::describeURI)

fun describeURI(uri: String): String = describeURI(parseURI(uri))

fun describeURI(uri: URI): String =
    uri.path?.let {
        val (parent, fileName) = it.partitionAroundLast("/")
        ".../" + parent.substringAfterLast("/") + fileName
    } ?: uri.toString()
