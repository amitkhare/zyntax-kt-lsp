package org.javacs.kt.classpath

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

import java.nio.file.Files
import java.nio.file.Path

class BuildFileHashingTest {
    private lateinit var tempDir: Path

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("build-file-hashing-test")
    }

    @After fun tearDown() {
        // Best-effort cleanup
        tempDir.toFile().deleteRecursively()
    }

    @Test fun `hash is stable for identical content`() {
        val a = tempDir.resolve("a.gradle.kts")
        val b = tempDir.resolve("b.gradle.kts")
        Files.writeString(a, "plugins { kotlin(\"jvm\") }")
        Files.writeString(b, "plugins { kotlin(\"jvm\") }")

        assertEquals(BuildFileHashing.hash(a), BuildFileHashing.hash(b))
    }

    @Test fun `hash changes when content changes`() {
        val a = tempDir.resolve("a.gradle.kts")
        Files.writeString(a, "plugins { kotlin(\"jvm\") }")
        val first = BuildFileHashing.hash(a)

        Files.writeString(a, "plugins { kotlin(\"jvm\") }\n// add a comment")
        val second = BuildFileHashing.hash(a)

        assertNotEquals(first, second)
    }

    @Test fun `missing file returns NO_BUILD_FILE`() {
        val missing = tempDir.resolve("does-not-exist.gradle.kts")
        assertEquals(BuildFileHashing.NO_BUILD_FILE, BuildFileHashing.hash(missing))
    }

    @Test fun `directory returns NO_BUILD_FILE`() {
        val dir = tempDir.resolve("a-dir")
        Files.createDirectories(dir)
        assertEquals(BuildFileHashing.NO_BUILD_FILE, BuildFileHashing.hash(dir))
    }

    @Test fun `empty file is not NO_BUILD_FILE`() {
        // An empty build file is a *valid* state (e.g. a placeholder before the
        // user adds any config), so its hash must not be the NO_BUILD_FILE
        // sentinel, otherwise it would be invisible at union layers.
        val empty = tempDir.resolve("empty.gradle.kts")
        Files.writeString(empty, "")
        val hash = BuildFileHashing.hash(empty)
        assertNotEquals(BuildFileHashing.NO_BUILD_FILE, hash)
    }
}
