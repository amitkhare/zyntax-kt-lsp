package org.javacs.kt

import org.jetbrains.exposed.sql.Database
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceCacheTest {
    private lateinit var db: Database
    private lateinit var cache: WorkspaceCache

    companion object {
        private var counter = 0
    }

    @Before
    fun setUp() {
        counter++
        db = Database.connect("jdbc:h2:mem:workspacecache_$counter;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        cache = WorkspaceCache(db)
    }

    @Test
    fun `hashContent produces consistent results`() {
        val content = "fun main() { println(\"hello\") }"
        val hash1 = cache.hashContent(content)
        val hash2 = cache.hashContent(content)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `hashContent produces different results for different content`() {
        val hash1 = cache.hashContent("foo")
        val hash2 = cache.hashContent("bar")
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `hashContent handles empty string`() {
        val hash = cache.hashContent("")
        assertEquals(cache.hashContent(""), hash)
    }

    @Test
    fun `computeFingerprint is deterministic`() {
        val files = listOf(
            URI("file:///a.kt") to 12345L,
            URI("file:///b.kt") to 67890L
        )
        val fp1 = cache.computeFingerprint(files, 100L)
        val fp2 = cache.computeFingerprint(files, 100L)
        assertEquals(fp1, fp2)
    }

    @Test
    fun `computeFingerprint changes when build file version changes`() {
        val files = listOf(URI("file:///a.kt") to 12345L)
        val fp1 = cache.computeFingerprint(files, 100L)
        val fp2 = cache.computeFingerprint(files, 200L)
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `computeFingerprint changes when file hash changes`() {
        val files1 = listOf(URI("file:///a.kt") to 12345L)
        val files2 = listOf(URI("file:///a.kt") to 99999L)
        val fp1 = cache.computeFingerprint(files1, 100L)
        val fp2 = cache.computeFingerprint(files2, 100L)
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `computeFingerprint changes when file is added`() {
        val files1 = listOf(URI("file:///a.kt") to 12345L)
        val files2 = listOf(
            URI("file:///a.kt") to 12345L,
            URI("file:///b.kt") to 67890L
        )
        val fp1 = cache.computeFingerprint(files1, 100L)
        val fp2 = cache.computeFingerprint(files2, 100L)
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun `isCacheValid returns false when no fingerprint saved`() {
        val files = listOf(URI("file:///a.kt") to 12345L)
        assertFalse(cache.isCacheValid(files, 100L))
    }

    @Test
    fun `saveFingerprint and isCacheValid round-trip`() {
        val files = listOf(
            URI("file:///a.kt") to 12345L,
            URI("file:///b.kt") to 67890L
        )
        cache.saveFingerprint(files, 100L)
        assertTrue(cache.isCacheValid(files, 100L))
    }

    @Test
    fun `isCacheValid returns false when build version changes`() {
        val files = listOf(URI("file:///a.kt") to 12345L)
        cache.saveFingerprint(files, 100L)
        assertFalse(cache.isCacheValid(files, 200L))
    }

    @Test
    fun `isCacheValid returns false when file hash changes`() {
        val files1 = listOf(URI("file:///a.kt") to 12345L)
        cache.saveFingerprint(files1, 100L)
        val files2 = listOf(URI("file:///a.kt") to 99999L)
        assertFalse(cache.isCacheValid(files2, 100L))
    }

    @Test
    fun `isCacheValid returns false when file count changes`() {
        val files1 = listOf(URI("file:///a.kt") to 12345L)
        cache.saveFingerprint(files1, 100L)
        val files2 = listOf(
            URI("file:///a.kt") to 12345L,
            URI("file:///b.kt") to 67890L
        )
        assertFalse(cache.isCacheValid(files2, 100L))
    }

    @Test
    fun `fingerprint is zero-padded to 16 hex chars`() {
        // The hex encoding must always be exactly 16 chars (64 bits) so that
        // string comparison cannot be fooled by a leading zero byte. The actual
        // value is irrelevant; we just need to make sure padStart worked.
        repeat(100) { i ->
            val files = listOf(URI("file:///a.kt") to i.toLong())
            val fp = cache.computeFingerprint(files, 100L)
            assertEquals("fingerprint should be 16 hex chars", 16, fp.length)
            assertTrue("fingerprint should be lowercase hex: $fp", fp.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun `KlsFolder getOrCreatePath always returns the kls subdirectory`() {
        // Regression test: the LSP always stores its database in
        // <workspaceRoot>/.kls/kls_database.db. This invariant must hold
        // regardless of init_options.storagePath (which is deprecated and
        // ignored).
        val tempDir: Path = Files.createTempDirectory("kls-storage-test-")
        try {
            val resolved = KlsFolder.getOrCreatePath(tempDir)
            assertEquals(
                "Storage path must always be <workspaceRoot>/.kls/",
                tempDir.resolve(".kls"),
                resolved
            )
            assertTrue("Kls folder should be created on disk", Files.isDirectory(resolved))
        } finally {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `KlsFolder migrateLegacyDatabase deletes the legacy file when kls folder already has a database`() {
        // Simulates the scenario where a user previously ran the buggy
        // version (which stored the DB at <root>/kls_database.db) and has
        // since upgraded.

        // The migration should clean up the legacy file without touching the
        // canonical .kls/ database.
        val tempDir: Path = Files.createTempDirectory("kls-migration-test-")
        try {
            val klsDir = Files.createDirectories(tempDir.resolve(".kls"))
            val klsDb = klsDir.resolve("kls_database.db")
            Files.write(klsDb, "canonical".toByteArray())

            val legacyDb = tempDir.resolve("kls_database.db")
            Files.write(legacyDb, "legacy".toByteArray())

            KlsFolder.migrateLegacyDatabase(tempDir)

            assertEquals(
                "Canonical .kls/kls_database.db must be untouched",
                "canonical",
                Files.readString(klsDb)
            )
            assertFalse(
                "Legacy kls_database.db at workspace root must be removed",
                Files.exists(legacyDb)
            )
        } finally {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
}
