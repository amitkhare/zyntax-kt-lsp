package org.javacs.kt.docs

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.javacs.kt.externalsources.JdkSrcZipResult
import org.junit.Test

class FindDocAnnotationTest {

    @Test
    fun `annotateIfNeeded prepends version mismatch notice for newer src`() {
        val result = JdkSrcZipResult(
            path = "/tmp/jdk-26/lib/src.zip",
            majorVersion = 26,
            runningMajorVersion = 25,
            isExactMatch = false,
            direction = JdkSrcZipResult.Direction.NEWER,
        )
        val annotated = annotateIfNeeded("Returns the current value of the running Java Virtual Machine's high-resolution time source.", result)
        assertThat(annotated, containsString("> _Documentation from Java 26, runtime is Java 25._"))
        assertThat(annotated, containsString("Returns the current value"))
    }

    @Test
    fun `annotateIfNeeded prepends version mismatch notice for older src`() {
        val result = JdkSrcZipResult(
            path = "/tmp/jdk-21/lib/src.zip",
            majorVersion = 21,
            runningMajorVersion = 25,
            isExactMatch = false,
            direction = JdkSrcZipResult.Direction.OLDER,
        )
        val annotated = annotateIfNeeded("Doc body", result)
        assertThat(annotated, containsString("> _Documentation from Java 21, runtime is Java 25._"))
    }

    @Test
    fun `annotateIfNeeded is a no-op for exact match`() {
        val result = JdkSrcZipResult(
            path = "/tmp/jdk-25/lib/src.zip",
            majorVersion = 25,
            runningMajorVersion = 25,
            isExactMatch = true,
            direction = JdkSrcZipResult.Direction.EXACT,
        )
        val doc = "Plain doc body"
        assertThat(annotateIfNeeded(doc, result), equalTo(doc))
    }

    @Test
    fun `annotateIfNeeded is a no-op when jdkSrc is null (third-party JAR)`() {
        val doc = "Third-party doc body"
        assertThat(annotateIfNeeded(doc, null), equalTo(doc))
        assertThat(annotateIfNeeded(doc, null), not(containsString("Documentation from Java")))
    }
}
