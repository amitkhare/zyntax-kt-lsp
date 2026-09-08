package org.javacs.kt

import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat
import org.javacs.kt.externalsources.JdkSrcZipLocator
import org.junit.Ignore
import org.junit.Test

class HoverLiteralsTest : SingleFileTestFixture("hover", "Literals.kt") {
    @Test fun `string reference`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 3, 19)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("val stringLiteral: String"))
    }

    @Test fun `string literal length`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 2, 27)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("3 characters"))
    }
}

class HoverDeclarationsTest : SingleFileTestFixture("hover", "Declarations.kt") {
    @Test fun `private val declaration`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 2, 9)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("private val myVal: String"))
    }

    @Test fun `private var declaration`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 3, 9)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("private var myVar: Int"))
    }

    @Test fun `fun declaration`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 4, 1)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("fun myFunc(): Boolean"))
    }

    @Test fun `private class declaration`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 1, 9)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("private"))
        assertThat(contents.value, containsString("class MyClass"))
    }
}

class HoverFunctionReferenceTest : SingleFileTestFixture("hover", "FunctionReference.kt") {
    @Test fun `function reference`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 2, 45)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("fun isFoo(s: String): Boolean"))
    }
}

class HoverObjectReferenceTest : SingleFileTestFixture("hover", "ObjectReference.kt") {
    @Test fun `object reference`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 2, 7)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("object AnObject"))
    }

    @Test fun `object reference with incomplete method`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 6, 7)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("object AnObject"))
    }

    @Test fun `object reference with method`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 10, 7)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("object AnObject"))
    }

    @Test fun `object method`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 10, 15)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("fun doh(): Unit"))
    }
}

@Ignore
class HoverRecoverTest : SingleFileTestFixture("hover", "Recover.kt") {
    @Test fun `incrementally repair a single-expression function`() {
        replace(file, 2, 9, "\"Foo\"", "intFunction()")

        val hover = languageServer.textDocumentService.hover(hoverParams(file, 2, 11)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("fun intFunction(): Int"))
    }

    @Test fun `incrementally repair a block function`() {
        replace(file, 5, 13, "\"Foo\"", "intFunction()")

        val hover = languageServer.textDocumentService.hover(hoverParams(file, 5, 13)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("fun intFunction(): Int"))
    }
}

class HoverAcrossFilesTest : LanguageServerTestFixture("hover") {
    @Test fun `resolve across files`() {
        val from = "ResolveFromFile.kt"
        val to = "ResolveToFile.kt"
        open(from)
        open(to)

        val hover = languageServer.textDocumentService.hover(hoverParams(from, 3, 26)).get()!!
        val contents = hover.contents.right

        assertThat(contents.value, containsString("fun target(): Unit"))
    }
}

class HoverJdkConstructorTest : SingleFileTestFixture("hover", "JdkConstructor.kt") {
    @Test fun `JDK constructor hover shows type info`() {
        val hover = languageServer.textDocumentService.hover(hoverParams(file, 4, 27)).get()!!
        val contents = hover.contents.right

        // The compiled type signature is always present, regardless of JDK sources
        assertThat(contents.value, containsString("constructor UUID"))

        // The Javadoc body only appears when a JDK src.zip is resolvable for the running JVM
        val jdkSourcesAvailable = JdkSrcZipLocator.resolve(null) != null

        if (jdkSourcesAvailable) {
            assertThat(contents.value, containsString("Constructs a new"))
        } else {
            assertThat(contents.value, not(containsString("Constructs a new")))
        }
    }
}
