package org.javacs.kt

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.javacs.kt.classpath.*
import org.javacs.kt.util.findCommandOnPath
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

import java.nio.file.Files
import java.nio.file.Path

class ClassPathTest {
    companion object {
        @JvmStatic @BeforeClass fun setupLogger() {
            LOG.connectStdioBackend()
        }
    }

    @Test fun `find gradle classpath`() {
        val workspaceRoot = testResourcesRoot().resolve("additionalWorkspace")
        val buildFile = workspaceRoot.resolve("build.gradle")

        assertTrue(Files.exists(buildFile))

        assumeTrue("Gradle not available", hasGradle(workspaceRoot))

        val resolvers = defaultClassPathResolver(listOf(workspaceRoot))
        print(resolvers)
        val classPath = resolvers.classpathOrEmpty.map { it.toString() }

        assertThat(classPath, hasItem(containsString("junit")))
    }

    @Test fun `find maven classpath`() {
        val workspaceRoot = testResourcesRoot().resolve("mavenWorkspace")
        val buildFile = workspaceRoot.resolve("pom.xml")

        assertTrue(Files.exists(buildFile))

        assumeTrue("Maven not available", findCommandOnPath("mvn") != null)

        val resolvers = defaultClassPathResolver(listOf(workspaceRoot))
        print(resolvers)
        val classPath = resolvers.classpathOrEmpty.map { it.toString() }

        assertThat(classPath, hasItem(containsString("junit")))
    }

    @Test fun `find kotlin stdlib`() {
        assertThat(findKotlinStdlib(), notNullValue())
    }

    private fun hasGradle(workspaceRoot: Path): Boolean {
        val wrapper = workspaceRoot.resolve("gradlew")
        return (Files.isExecutable(wrapper)) || findCommandOnPath("gradle") != null
    }
}
