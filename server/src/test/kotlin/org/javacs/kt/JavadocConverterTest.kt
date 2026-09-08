package org.javacs.kt

import org.javacs.kt.j2k.convertInlineTags
import org.junit.Test
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*

class JavadocConverterTest {
    @Test
    fun `convert code inline tag`() {
        val input = "Use {@code int x = 5} for inline code examples."
        val expected = "Use `int x = 5` for inline code examples."
        assertThat(convertInlineTags(input), equalTo(expected))
    }

    @Test
    fun `convert link inline tag`() {
        val input = "Reference {@link String} class."
        val expected = "Reference [String](String) class."
        assertThat(convertInlineTags(input), equalTo(expected))
    }

    @Test
    fun `convert link inline tag with method reference`() {
        val input = "See {@link String#toUpperCase()} method."
        val expected = "See [toUpperCase()](String#toUpperCase()) method."
        assertThat(convertInlineTags(input), equalTo(expected))
    }

    @Test
    fun `convert literal inline tag`() {
        val input = "Use {@literal <html>} for literal text."
        val expected = "Use `<html>` for literal text."
        assertThat(convertInlineTags(input), equalTo(expected))
    }

    @Test
    fun `convert value inline tag`() {
        val input = "The value is {@value MAX_VALUE}."
        val expected = "The value is MAX_VALUE."
        assertThat(convertInlineTags(input), equalTo(expected))
    }

    @Test
    fun `convert multiple inline tags`() {
        val input = "Use {@code int x} and {@link String} together."
        val expected = "Use `int x` and [String](String) together."
        assertThat(convertInlineTags(input), equalTo(expected))
    }

    @Test
    fun `preserve plain text without tags`() {
        val input = "This is plain text without any tags."
        assertThat(convertInlineTags(input), equalTo(input))
    }

    @Test
    fun `handle empty string`() {
        assertThat(convertInlineTags(""), equalTo(""))
    }

    @Test
    fun `handle inline tags with extra spaces`() {
        val input = "Use {@code   int x = 5  } with spaces."
        val expected = "Use `int x = 5` with spaces."
        assertThat(convertInlineTags(input), equalTo(expected))
    }
}
