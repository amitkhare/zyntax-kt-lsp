package org.javacs.kt.database

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DatabaseServiceTest {

    @Test
    fun `setup with null storagePath uses in-memory H2`() {
        val service = DatabaseService()
        service.setup(null)
        assertNotNull("Database should be initialized", service.db)
        val result = transaction(service.db!!) {
            exec("SELECT 1") { rs -> rs.next(); rs.getInt(1) }
        }
        assertEquals("H2 in-memory database should work", 1, result)
        service.close()
    }

    @Test
    fun `setup with valid directory uses SQLite`() {
        val tempDir = Files.createTempDirectory("kls-db-test-")
        try {
            val service = DatabaseService()
            service.setup(tempDir)
            assertNotNull("Database should be initialized", service.db)
            val dbFile = tempDir.resolve("kls_database.db")
            assertTrue("Database file should exist", Files.exists(dbFile))
            val result = transaction(service.db!!) {
                exec("SELECT 1") { rs -> rs.next(); rs.getInt(1) }
            }
            assertEquals("SQLite database should work", 1, result)
            service.close()
        } finally {
            Files.deleteIfExists(tempDir.resolve("kls_database.db"))
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `setup with non-directory storagePath falls back to H2`() {
        val tempFile = Files.createTempFile("kls-db-test-", ".tmp")
        try {
            val service = DatabaseService()
            service.setup(tempFile)
            assertNotNull("Database should be initialized", service.db)
            val result = transaction(service.db!!) {
                exec("SELECT 1") { rs -> rs.next(); rs.getInt(1) }
            }
            assertEquals("Fallback H2 database should work", 1, result)
            service.close()
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `version mismatch recreates database`() {
        val tempDir = Files.createTempDirectory("kls-db-test-")
        try {
            val service = DatabaseService()
            service.setup(tempDir)
            val firstDb = service.db
            assertNotNull(firstDb)

            transaction(firstDb!!) {
                exec("UPDATE databasemetadata SET version = 1")
            }

            service.setup(tempDir)
            val secondDb = service.db
            assertNotNull(secondDb)

            val dbVersion = transaction(secondDb!!) {
                DatabaseMetadataEntity.all().firstOrNull()?.version ?: 0
            }
            assertEquals("Should have current version", DatabaseService.DB_VERSION, dbVersion)
            service.close()
        } finally {
            Files.deleteIfExists(tempDir.resolve("kls_database.db"))
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `close releases database connection`() {
        val tempDir = Files.createTempDirectory("kls-db-test-")
        try {
            val service = DatabaseService()
            service.setup(tempDir)
            assertNotNull(service.db)
            service.close()
            assertNull("Database should be null after close", service.db)
        } finally {
            Files.deleteIfExists(tempDir.resolve("kls_database.db"))
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `multiple close calls are safe`() {
        val service = DatabaseService()
        service.setup(null)
        service.close()
        service.close() // Should not throw
        assertNull(service.db)
    }
}
