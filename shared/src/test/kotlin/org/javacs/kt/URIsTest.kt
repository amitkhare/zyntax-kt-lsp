package org.javacs.kt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.javacs.kt.util.parseURI
import org.junit.Test
import java.net.URI

class URIsTest {
    @Test
    fun `parseURI should work with different paths`() {
        assertEquals(
            URI.create("/home/ws%201"),
            parseURI("/home/ws 1")
        )

        assertEquals(
            URI.create("/home/ws-1"),
            parseURI("/home/ws-1")
        )

        assertEquals(
            URI.create("file:/home/ws%201"),
            parseURI("file:///home/ws%201")
        )

        // VS Code encoding (percent-encoded colons and slashes)
        // %3A -> :, %2F -> /
        assertEquals(
            URI.create("file:///home/ws%201"),
            parseURI("file%3A%2F%2F%2Fhome%2Fws%201")
        )
    }

    @Test
    fun `parseURI should handle percent encoding without double-decoding`() {
        val input = "file:///path%25with%25percent"
        val expected = URI("file:///path%25with%25percent")

        assertEquals(expected, parseURI(input))
    }

    @Test
    fun `parseURI should handle various encodings`() {
        // Spaces already encoded
        assertEquals(
            URI.create("file:///home/user/path%20name/file.kt"),
            parseURI("file:///home/user/path%20name/file.kt")
        )

        // Special characters
        assertEquals(
            URI.create("file:///path/with%23hash"),
            parseURI("file:///path/with%23hash")
        )

        // Multiple encoded sequences
        assertEquals(
            URI.create("file:///path%20with%20multiple%20spaces"),
            parseURI("file:///path%20with%20multiple%20spaces")
        )

        // Encoding that VS Code might send (colons)
        // %3A -> : produces file://test (double slash is correct for file: scheme)
        assertEquals(
            URI.create("file://test/file.kt"),
            parseURI("file%3A//test/file.kt")
        )
    }

    @Test
    fun `parseURI should handle edge cases`() {
        // Empty string should not crash
        parseURI("")

        // Pure hex sequences that aren't percent encoding
        assertEquals(
            URI.create("file:///path/ABCDEF123456"),
            parseURI("file:///path/ABCDEF123456")
        )

        // Mix of encoded and raw
        assertEquals(
            URI.create("file:///path%20normal/mixed.kt"),
            parseURI("file:///path%20normal/mixed.kt")
        )
    }

    @Test
    fun `parseURI preserves plus signs as literal characters`() {
        // '+' must stay a '+', never form-decoded to a space (#326: '+' in a jar name).
        // URI.equals is encoding-sensitive, so assert on the decoded path instead.
        for (input in listOf("file:///local/foo+bar.jar", "file:///local/foo%2Bbar.jar")) {
            val result = parseURI(input)
            assertEquals("/local/foo+bar.jar", result.path)
            assertFalse(result.toString().contains("foo bar.jar"))
        }
        // Raw space is still encoded to %20, not left raw or double-decoded.
        assertEquals("/local/foo bar.jar", parseURI("file:///local/foo bar.jar").path)
    }

    @Test
    fun `parseURI preserves brackets and unicode`() {
        // VSCode emits %5B/%5D (brackets) and UTF-8 Unicode escapes, and URI.create decodes them (#223).
        assertEquals(
            URI.create("file:///local/a%5Bb%5D.jar"),
            parseURI("file:///local/a%5Bb%5D.jar")
        )
        assertEquals(
            URI.create("file:///local/caf%C3%A9.txt"),
            parseURI("file:///local/caf%C3%A9.txt")
        )
    }

    @Test
    fun `parseURI encodes raw brackets from classpath jars`() {
        // Classpath jar named with '[' ']' (#223): the compiler returns a location URI
        // with brackets *raw*, which java.net.URI rejects. parseURI encodes them
        // while preserving the rest (and never touching '+').
        val jarOnly = parseURI("file:///local/lib/a[b]1.0+beta.jar")
        assertEquals("/local/lib/a[b]1.0+beta.jar", jarOnly.path)
        assertEquals("file:///local/lib/a%5Bb%5D1.0+beta.jar", jarOnly.toString())

        // Same with the '!/' archive-inner-path form used by kls: URIs.
        val inArchive = parseURI("file:///local/lib/a[b]1.0+beta.jar!/com/example/Foo.class")
        assertEquals("/local/lib/a[b]1.0+beta.jar!/com/example/Foo.class", inArchive.path)
        assertEquals("file:///local/lib/a%5Bb%5D1.0+beta.jar!/com/example/Foo.class", inArchive.toString())
    }
}
