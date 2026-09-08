package org.javacs.kt.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Owns SQLite storage: persistent for a workspace, isolated in-memory otherwise. */
class DatabaseService : Closeable {
    companion object {
        const val DB_FILENAME = "kls_database.db"
    }

    var db: Database? = null
        private set
    private var memoryConnection: Connection? = null

    fun setup(storagePath: Path?) {
        close()
        require(storagePath == null || Files.isDirectory(storagePath)) {
            "Database storage path is not a directory: $storagePath"
        }
        val url = if (storagePath == null) {
            "jdbc:sqlite:file:kls_${UUID.randomUUID()}?mode=memory&cache=shared"
        } else {
            "jdbc:sqlite:${storagePath.toAbsolutePath().normalize().resolve(DB_FILENAME)}"
        }
        db = Database.connect(url, "org.sqlite.JDBC")
        try {
            // Keep no-workspace storage alive between Exposed transactions.
            if (storagePath == null) memoryConnection = DriverManager.getConnection(url)
            transaction(db) { exec("PRAGMA schema_version") }
        } catch (error: Exception) {
            try {
                close()
            } catch (cleanup: Exception) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    override fun close() {
        try {
            db?.let(TransactionManager::closeAndUnregister)
            db = null
        } finally {
            memoryConnection?.close()
            memoryConnection = null
        }
    }
}
