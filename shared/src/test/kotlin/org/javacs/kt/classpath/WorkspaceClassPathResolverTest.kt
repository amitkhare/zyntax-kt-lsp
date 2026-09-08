package org.javacs.kt.classpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Paths
import java.util.jar.JarFile

class WorkspaceClassPathResolverTest {
    @Test fun `standalone files use the packaged standard library`() {
        val expected = Paths.get(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val actual = defaultClassPathResolver(emptyList()).classpath.single()
        assertEquals(ClassPathEntry(expected), actual)
        JarFile(actual.compiledJar.toFile()).use { assertNotNull(it.getJarEntry("kotlin/Unit.class")) }
    }

    @Test fun `declared dependencies are neither replaced nor supplemented`() {
        val dependencies = linkedSetOf(
            ClassPathEntry(Paths.get("project/kotlin-stdlib-1.9.25.jar"), Paths.get("project/stdlib-sources.jar")),
            ClassPathEntry(Paths.get("project/kotlin-stdlib-2.1.0.jar"))
        )
        val project = object : ClassPathResolver {
            override val resolverType = "project"
            override val classpath get() = dependencies
        }
        val selected = workspaceClassPathResolver(listOf(project))
        assertSame(project, selected)
        assertEquals(dependencies, selected.classpath)
        dependencies.clear()
        assertEquals(emptySet<ClassPathEntry>(), selected.classpath)

        val failed = object : ClassPathResolver {
            override val resolverType = "failed project"
            override val classpath: Set<ClassPathEntry> get() = error("Project import failed")
        }
        assertThrows(IllegalStateException::class.java) { workspaceClassPathResolver(listOf(failed)).classpath }
    }
}
