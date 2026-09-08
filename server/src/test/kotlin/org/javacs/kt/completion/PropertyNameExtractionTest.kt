package org.javacs.kt.completion

import org.junit.Test
import org.junit.Assert.assertEquals

class PropertyNameExtractionTest {

    // Single uppercase char: lowercase it (A -> a, X -> x)
    @Test
    fun `single uppercase char becomes lowercase`() {
        assertEquals("a", propertyNameFromAccessor("A"))
        assertEquals("x", propertyNameFromAccessor("X"))
    }

    // Already lowercase: no change needed (matches Kotlin's early-return guard)
    @Test
    fun `single lowercase char stays same`() {
        assertEquals("name", propertyNameFromAccessor("name"))
    }

    // Normal camelCase: first char uppercase, second lowercase -> lowercase first char only
    @Test
    fun `normal camelCase decapitalizes first char`() {
        assertEquals("name", propertyNameFromAccessor("Name"))
        assertEquals("server", propertyNameFromAccessor("Server"))
        assertEquals("url", propertyNameFromAccessor("Url"))
    }

    // All uppercase (acronym): everything lowercase (URL -> url, HTML -> html)
    @Test
    fun `all uppercase acronym becomes all lowercase`() {
        assertEquals("url", propertyNameFromAccessor("URL"))
        assertEquals("html", propertyNameFromAccessor("HTML"))
        assertEquals("io", propertyNameFromAccessor("IO"))
        assertEquals("abc", propertyNameFromAccessor("ABC"))
    }

    // Acronym + word: lowercase the acronym portion only, keep rest (HTMLFile -> htmlFile)
    @Test
    fun `acronym followed by word preserves rest`() {
        assertEquals("htmlFile", propertyNameFromAccessor("HTMLFile"))
        assertEquals("ioStream", propertyNameFromAccessor("IOStream"))
        assertEquals("urlConnection", propertyNameFromAccessor("URLConnection"))
        assertEquals("abcDef", propertyNameFromAccessor("ABCDef"))
    }

    // Digit terminates acronym: find first non-uppercase (digit), lowercase before it
    // e.g., SLF4JLogger -> slF4JLogger (only "SL" lowercased, not "F4JLogger")
    @Test
    fun `acronym with digit in middle`() {
        assertEquals("slF4JLogger", propertyNameFromAccessor("SLF4JLogger"))
        assertEquals("htmL5Parser", propertyNameFromAccessor("HTML5Parser"))
        assertEquals("iO2Stream", propertyNameFromAccessor("IO2Stream"))
        assertEquals("a1B", propertyNameFromAccessor("A1B"))
    }

    // Single uppercase + digit: lowercase first char only (A1 -> a1, X99 -> x99)
    @Test
    fun `single uppercase followed by digit`() {
        assertEquals("a1", propertyNameFromAccessor("A1"))
        assertEquals("x99", propertyNameFromAccessor("X99"))
    }

    // Multiple uppercase + digit: lowercase up to first non-uppercase (digit) (AB1C -> aB1C)
    @Test
    fun `multiple uppercase followed by digit`() {
        assertEquals("aB1C", propertyNameFromAccessor("AB1C"))
        assertEquals("slF4J", propertyNameFromAccessor("SLF4J"))
    }


    // Real Java getter names: verify common library patterns work correctly
    @Test
    fun `real world cases`() {
        assertEquals("slF4JLogger", propertyNameFromAccessor("SLF4JLogger"))
        assertEquals("httpResponse", propertyNameFromAccessor("HTTPResponse"))
        assertEquals("ioStream", propertyNameFromAccessor("IOStream"))
        assertEquals("url", propertyNameFromAccessor("URL"))
        assertEquals("urlConnection", propertyNameFromAccessor("URLConnection"))
        assertEquals("jsonParser", propertyNameFromAccessor("JSONParser"))
        assertEquals("xmlDocument", propertyNameFromAccessor("XMLDocument"))
        assertEquals("jdbcConnection", propertyNameFromAccessor("JDBCConnection"))
        assertEquals("awsClient", propertyNameFromAccessor("AWSClient"))
    }

    // Digits and mixed patterns: verify edge cases like HTML5, Version2Info, MyURL
    @Test
    fun `edge cases with digits and acronyms`() {
        assertEquals("htmL5", propertyNameFromAccessor("HTML5"))
        assertEquals("version2Info", propertyNameFromAccessor("Version2Info"))
        assertEquals("myURL", propertyNameFromAccessor("MyURL"))
        assertEquals("addressLine1", propertyNameFromAccessor("AddressLine1"))
    }

    // Kotlin's early-return guard: first char lowercase -> return unchanged (aName -> aName)
    @Test
    fun `first char lowercase returns unchanged`() {
        assertEquals("aName", propertyNameFromAccessor("aName"))
        assertEquals("helloWorld", propertyNameFromAccessor("helloWorld"))
        assertEquals("aBC", propertyNameFromAccessor("aBC"))
    }

    @Test
    fun `empty and short strings`() {
        assertEquals("", propertyNameFromAccessor(""))
        assertEquals("a", propertyNameFromAccessor("A"))
        assertEquals("b", propertyNameFromAccessor("B"))
    }
}
