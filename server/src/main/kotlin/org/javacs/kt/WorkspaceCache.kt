package org.javacs.kt

import com.dynatrace.hash4j.hashing.Hashing

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction

import java.net.URI

private const val FINGERPRINT_LENGTH = 16
private const val HEX_RADIX = 16

internal object WorkspaceFingerprintTable : IntIdTable() {
    val fingerprintHash = varchar("fingerprinthash", length = FINGERPRINT_LENGTH)
}

internal class WorkspaceFingerprintEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<WorkspaceFingerprintEntity>(WorkspaceFingerprintTable)

    var fingerprintHash by WorkspaceFingerprintTable.fingerprintHash
}

/**
 * A workspace-level cache that persists a single project fingerprint to SQLite.
 * Used to skip initial compilation on startup when nothing has changed.
 *
 * The fingerprint is a hex-encoded xxHash3-64 over the sorted (uri, content-hash) pairs
 * of all source files plus the build file version. Sorting by URI string ensures the
 * fingerprint is deterministic regardless of the order files are passed in.
 *
 * On startup, the caller computes a fingerprint from the current file hashes and compares
 * it against the stored one. If they match, the workspace hasn't changed and the expensive
 * initial compilation can be skipped. The persisted [org.javacs.kt.index.SymbolIndex] data
 * (which lives in the same SQLite database) is reused as-is in that case.
 */
class WorkspaceCache(private val db: Database) {
    init {
        transaction(db) {
            SchemaUtils.create(WorkspaceFingerprintTable)
        }
    }

    /**
     * Compute xxHash3-64 of a string's UTF-8 bytes.
     */
    fun hashContent(content: String): Long =
        Hashing.xxh3_64().hashBytesToLong(content.toByteArray(Charsets.UTF_8))

    /**
     * Compute a project fingerprint from file content hashes and build file version.
     *
     * @param fileHashes collection of (URI, content hash) pairs for all source files in the workspace
     * @param buildFileVersion the content hash of the build files,
     *   used to detect build configuration changes
     * @return a hex-encoded string representing the workspace fingerprint
     */
    fun computeFingerprint(
        fileHashes: Collection<Pair<URI, Long>>,
        buildFileVersion: Long
    ): String {
        val sorted = fileHashes.sortedBy { it.first.toString() }
        val sb = StringBuilder()
        for ((uri, hash) in sorted) {
            sb.append(uri)
            sb.append(':')
            sb.append(hash)
            sb.append('\n')
        }
        sb.append("buildVersion:")
        sb.append(buildFileVersion)
        // Zero-pad to a full 16 hex chars so two distinct hashes with a leading
        // zero byte can't accidentally compare equal as strings.
        return hashContent(sb.toString()).toULong().toString(HEX_RADIX).padStart(FINGERPRINT_LENGTH, '0')
    }

    /**
     * Check if the workspace cache is still valid by comparing the current fingerprint
     * against the stored one.
     *
     * This method is read-only - it does not modify the database. If the cache is
     * invalid, the caller should run a full lint and then call [saveFingerprint] to
     * update the stored state.
     *
     * @return true if the stored fingerprint matches the computed one, false otherwise
     *   (including when the cache has never been populated)
     */
    fun isCacheValid(
        fileHashes: Collection<Pair<URI, Long>>,
        buildFileVersion: Long
    ): Boolean {
        val fingerprint = computeFingerprint(fileHashes, buildFileVersion)
        return transaction(db) {
            WorkspaceFingerprintEntity.all().firstOrNull()?.fingerprintHash == fingerprint
        }
    }

    /**
     * Save the workspace fingerprint after a successful full lint. This marks the cache
     * as valid for the next startup.
     *
     * The fingerprint is stored as a single row in [WorkspaceFingerprintTable], replacing
     * any previous entry.
     */
    fun saveFingerprint(
        fileHashes: Collection<Pair<URI, Long>>,
        buildFileVersion: Long
    ) {
        val fingerprint = computeFingerprint(fileHashes, buildFileVersion)
        transaction(db) {
            WorkspaceFingerprintTable.deleteAll()
            WorkspaceFingerprintEntity.new { fingerprintHash = fingerprint }
        }
    }
}
