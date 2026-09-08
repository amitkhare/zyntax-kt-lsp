package org.javacs.kt.database

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.sql.SQLException

class DatabaseServiceTest {
    @Test fun `null storage uses isolated in-memory SQLite`() {
        DatabaseService().use { service ->
            service.setup(null)
            val version = transaction(service.db!!) {
                exec("SELECT sqlite_version()") { result -> result.next(); result.getString(1) }
            }
            assertFalse(version.isNullOrBlank())
            transaction(service.db!!) { exec("CREATE TABLE marker (value INTEGER)") }
            DatabaseService().use { other ->
                other.setup(null)
                val count = transaction(other.db!!) {
                    exec("SELECT COUNT(*) FROM sqlite_master WHERE name = 'marker'") { result ->
                        result.next(); result.getInt(1)
                    }
                }
                assertEquals(0, count)
            }
        }
    }

    @Test fun `workspace storage persists across setup and close`() {
        val directory = Files.createTempDirectory("kls-db-test-")
        try {
            DatabaseService().use { service ->
                service.setup(directory)
                transaction(service.db!!) {
                    exec("CREATE TABLE marker (value INTEGER)")
                    exec("INSERT INTO marker VALUES (42)")
                }
                service.setup(directory)
                val value = transaction(service.db!!) {
                    exec("SELECT value FROM marker") { result -> result.next(); result.getInt(1) }
                }
                assertEquals(42, value)
            }
            assertTrue(Files.isRegularFile(directory.resolve(DatabaseService.DB_FILENAME)))
        } finally {
            Files.deleteIfExists(directory.resolve(DatabaseService.DB_FILENAME))
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `invalid storage fails without opening another database`() {
        val file = Files.createTempFile("kls-db-test-", ".tmp")
        try {
            DatabaseService().use { service ->
                assertThrows(IllegalArgumentException::class.java) { service.setup(file) }
                assertNull(service.db)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun `corrupt database fails without replacing its contents`() {
        val directory = Files.createTempDirectory("kls-db-test-")
        val file = directory.resolve(DatabaseService.DB_FILENAME)
        try {
            Files.writeString(file, "not a SQLite database")
            DatabaseService().use { service ->
                assertThrows(SQLException::class.java) { service.setup(directory) }
                assertNull(service.db)
            }
            assertEquals("not a SQLite database", Files.readString(file))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun `close releases no-workspace storage`() {
        DatabaseService().use { service ->
            service.setup(null)
            transaction(service.db!!) { exec("CREATE TABLE marker (value INTEGER)") }
            service.close()
            assertNull(service.db)
            service.setup(null)
            val count = transaction(service.db!!) {
                exec("SELECT COUNT(*) FROM sqlite_master WHERE name = 'marker'") { result ->
                    result.next(); result.getInt(1)
                }
            }
            assertEquals(0, count)
        }
    }

    @Test fun `multiple close calls are safe`() {
        val service = DatabaseService()
        service.setup(null)
        service.close()
        service.close()
        assertNull(service.db)
    }
}
