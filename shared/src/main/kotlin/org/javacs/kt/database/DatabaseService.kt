package org.javacs.kt.database

import org.javacs.kt.LOG
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException

private object DatabaseMetadata : IntIdTable() {
    var version = integer("version")
}

class DatabaseMetadataEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DatabaseMetadataEntity>(DatabaseMetadata)

    var version by DatabaseMetadata.version
}

class DatabaseService {

    companion object {
        const val DB_VERSION = 5
        const val DB_FILENAME = "kls_database.db"
        private const val MEM_H2_DB = "jdbc:h2:mem:kls;DB_CLOSE_DELAY=-1"
    }

    var db: Database? = null
        private set

    fun setup(storagePath: Path?) {
        // Close any previously-opened database to prevent registration leaks
        // when setup() is called multiple times (e.g. the fallback path in KotlinLanguageServer).
        closeCurrentDatabase()

        db = getDbFromFile(storagePath, deleteExisting = false)

        val currentVersion = try {
            transaction(db) {
                SchemaUtils.create(DatabaseMetadata)

                DatabaseMetadataEntity.all().firstOrNull()?.version ?: 0
            }
        } catch (e: Exception) {
            LOG.error("Failed to read database version, falling back to in-memory database: {}", e.message)
            fallbackToInMemory()
            return
        }

        if (currentVersion != DB_VERSION) {
            LOG.info("Database has version $currentVersion != $DB_VERSION (the required version), therefore it will be rebuilt...")

            closeCurrentDatabase()

            db = getDbFromFile(storagePath, deleteExisting = true)

            try {
                transaction(db) {
                    SchemaUtils.create(DatabaseMetadata)

                    DatabaseMetadata.deleteAll()
                    DatabaseMetadata.insert { it[version] = DB_VERSION }
                }
            } catch (e: Exception) {
                LOG.error("Failed to initialize database after version mismatch, falling back to in-memory database: {}", e.message)
                fallbackToInMemory()
            }
        } else {
            LOG.info("Database has the correct version $currentVersion and will be used as-is")
        }
    }

    private fun fallbackToInMemory() {
        closeCurrentDatabase()
        db = Database.connect(MEM_H2_DB, "org.h2.Driver")
        try {
            transaction(db) {
                SchemaUtils.create(DatabaseMetadata)
                DatabaseMetadata.deleteAll()
                DatabaseMetadata.insert { it[version] = DB_VERSION }
            }
        } catch (e: Exception) {
            LOG.error("Failed to initialize in-memory database: {}", e.message)
        }
    }

    private fun getDbFromFile(storagePath: Path?, deleteExisting: Boolean): Database {
        if (storagePath == null || !Files.isDirectory(storagePath)) {
            if (storagePath != null) {
                LOG.warn("Storage path '{}' is not a directory, falling back to in-memory database", storagePath)
            }
            return Database.connect(MEM_H2_DB, "org.h2.Driver")
        }
        if (deleteExisting) {
            try {
                Files.deleteIfExists(getDbFilePath(storagePath))
            } catch (e: Exception) {
                LOG.warn("Failed to delete existing database file: {}", e.message)
            }
        }
        return try {
            Database.connect("jdbc:sqlite:${getDbFilePath(storagePath)}")
        } catch (e: SQLException) {
            LOG.error("Failed to connect to SQLite database at {}, falling back to in-memory database: {}", getDbFilePath(storagePath), e.message)
            Database.connect(MEM_H2_DB, "org.h2.Driver")
        }
    }

    private fun getDbFilePath(storagePath: Path) = Path.of(storagePath.toString(), DB_FILENAME)

    private fun closeCurrentDatabase() {
        db?.let { database ->
            try {
                TransactionManager.closeAndUnregister(database)
            } catch (e: Exception) {
                LOG.warn("Failed to close database: {}", e.message)
            }
        }
        db = null
    }

    fun close() {
        closeCurrentDatabase()
    }
}
