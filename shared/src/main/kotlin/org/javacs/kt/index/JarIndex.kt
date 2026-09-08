package org.javacs.kt.index

import org.javacs.kt.LOG
import org.javacs.kt.database.DatabaseService
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction

private object JarIndexTable : IntIdTable() {
    val classPath = varchar("classPath", 511).index()
    val jarPath = varchar("jarPath", 511)
    val sourceJarPath = varchar("sourceJarPath", 511).nullable()
}

class JarIndexEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<JarIndexEntity>(JarIndexTable)

    var classPath by JarIndexTable.classPath
    var jarPath by JarIndexTable.jarPath
    var sourceJarPath by JarIndexTable.sourceJarPath
}

/**
 * Provides persistent storage for JAR -> source JAR mappings.
 * Used to quickly find which JAR contains a given class and its corresponding source JAR.
 */
class JarIndex(private val databaseService: DatabaseService) {

    fun setup() {
        transaction(checkNotNull(databaseService.db) { "Database is not initialized" }) {
            SchemaUtils.create(JarIndexTable)
        }
    }

    /**
     * Finds the JAR and optional source JAR for a given class path.
     * @param classPath e.g., "java/util/List.class"
     * @return Pair of (jarPath, sourceJarPath) or null if not found
     */
    fun findJar(classPath: String): Pair<String, String?>? {
        return databaseService.db?.let { database ->
            try {
                transaction(database) {
                    JarIndexEntity.find { JarIndexTable.classPath eq classPath }
                        .firstOrNull()
                        ?.let { Pair(it.jarPath, it.sourceJarPath) }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to query JarIndex for {}: {}", classPath, e.message)
                null
            }
        }
    }

    /**
     * Saves a JAR mapping to the database.
     * @param classPath e.g., "java/util/List.class"
     * @param jarPath e.g., "/path/to/lib.jar"
     * @param sourceJarPath optional source JAR path, or null if no source available
     */
    fun save(classPath: String, jarPath: String, sourceJarPath: String?) {
        databaseService.db?.let { database ->
            try {
                transaction(database) {
                    val existing = JarIndexEntity.find {
                        (JarIndexTable.classPath eq classPath) and (JarIndexTable.jarPath eq jarPath)
                    }.firstOrNull()

                    if (existing == null) {
                        JarIndexEntity.new {
                            this.classPath = classPath
                            this.jarPath = jarPath
                            this.sourceJarPath = sourceJarPath
                        }
                    } else {
                        if (sourceJarPath != null && existing.sourceJarPath == null) {
                            existing.sourceJarPath = sourceJarPath
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to save JarIndex entry for {}: {}", classPath, e.message)
            }
        }
    }

    /**
     * Clears all entries from the index.
     */
    fun clear() {
        databaseService.db?.let { database ->
            try {
                transaction(database) {
                    JarIndexTable.deleteAll()
                }
                LOG.info("JarIndex cleared")
            } catch (e: Exception) {
                LOG.warn("Failed to clear JarIndex: {}", e.message)
            }
        }
    }

    /**
     * Returns the number of entries in the index.
     */
    fun count(): Int {
        return databaseService.db?.let { database ->
            try {
                transaction(database) {
                    JarIndexEntity.count().toInt()
                }
            } catch (_: Exception) {
                0
            }
        } ?: 0
    }
}
