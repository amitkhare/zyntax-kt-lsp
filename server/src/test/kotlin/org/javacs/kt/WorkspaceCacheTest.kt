package org.javacs.kt

import org.javacs.kt.database.DatabaseService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceCacheTest {
    private val databaseService = DatabaseService()
    private lateinit var cache: WorkspaceCache

    @Before fun setUp() {
        databaseService.setup(null)
        cache = WorkspaceCache(checkNotNull(databaseService.db))
    }

    @After fun tearDown() {
        databaseService.close()
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
        // Workspace storage has one canonical location.
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
}
