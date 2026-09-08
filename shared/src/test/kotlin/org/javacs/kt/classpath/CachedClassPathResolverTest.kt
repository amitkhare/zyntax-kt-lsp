package org.javacs.kt.classpath

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.UUID

class CachedClassPathResolverTest {
    @Test fun `ordinary classpath refresh does not validate unresolved or stale script entries`() {
        val url = "jdbc:sqlite:file:classpath_${UUID.randomUUID()}?mode=memory&cache=shared"
        val db = Database.connect(url, "org.sqlite.JDBC")
        val resolver = object : ClassPathResolver {
            override val resolverType = "test"
            var version = 1L
            var ordinaryReads = 0
            var scriptReads = 0
            override val currentBuildFileVersion get() = version
            override val classpath: Set<ClassPathEntry> get() {
                ordinaryReads++
                return setOf(ClassPathEntry(Paths.get("main-$version.jar")))
            }
            override val buildScriptClasspath: Set<Path> get() {
                scriptReads++
                return if (version == 3L) emptySet() else setOf(Paths.get("script-$version.jar"))
            }
        }

        try {
            DriverManager.getConnection(url).use {
                transaction(db) {
                    exec("CREATE TABLE classpathmetadatacache (id INTEGER PRIMARY KEY, includessources BOOLEAN NOT NULL, buildfileversion BIGINT)")
                    exec("INSERT INTO classpathmetadatacache VALUES (2, TRUE, 1)")
                }
                for (version in 1L..3L) {
                    resolver.version = version
                    val cache = CachedClassPathResolver(resolver, db)
                    val expectedMain = setOf(ClassPathEntry(Paths.get("main-$version.jar")))
                    val expectedScript = if (version == 3L) emptySet() else setOf(Paths.get("script-$version.jar"))
                    assertEquals(expectedMain, cache.classpath)
                    assertEquals(expectedMain, cache.classpath)
                    assertEquals(expectedScript, cache.buildScriptClasspath)

                    val reopened = CachedClassPathResolver(resolver, db)
                    assertEquals(expectedMain, reopened.classpath)
                    assertEquals(expectedScript, reopened.buildScriptClasspath)
                    assertEquals(version.toInt(), resolver.ordinaryReads)
                    assertEquals(version.toInt(), resolver.scriptReads)
                }
            }
        } finally {
            TransactionManager.closeAndUnregister(db)
        }
    }
}
