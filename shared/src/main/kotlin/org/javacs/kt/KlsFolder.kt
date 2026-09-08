package org.javacs.kt

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * Manages the `.kls/` folder location and related operations for ktlsp.
 *
 * The `.kls/` folder contains:
 * - `kls_database.db` - Symbol/index database
 * - `logs/` - Log files
 * - `exclusions.txt` - Project-specific exclusion patterns
 *
 * The folder location can be overridden via the `KLS_HOME` environment variable.
 */
object KlsFolder {
    private const val KLS_FOLDER_NAME = ".kls"
    private const val LOGS_FOLDER_NAME = "logs"
    private const val EXCLUSIONS_FILE_NAME = "exclusions.txt"
    private const val DATABASE_FILE_NAME = "kls_database.db"

    private const val KLS_HOME_ENV = "KLS_HOME"
    private const val KLS_LOG_FILE_ENV = "KLS_LOG_FILE"

    /**
     * Returns the path to the `.kls/` folder.
     *
     * Uses `KLS_HOME` environment variable if set, otherwise returns
     * `<workspaceRoot>/.kls/`.
     *
     * @param workspaceRoot The root path of the workspace
     * @return The path to the `.kls/` folder
     */
    fun getPath(workspaceRoot: Path): Path {
        val klsHomeEnv = System.getenv(KLS_HOME_ENV)
        if (klsHomeEnv != null && klsHomeEnv.isNotBlank()) {
            return Paths.get(klsHomeEnv)
        }
        return workspaceRoot.resolve(KLS_FOLDER_NAME)
    }

    /**
     * Returns the path to the `.kls/` folder, creating it if it doesn't exist.
     *
     * @param workspaceRoot The root path of the workspace
     * @return The path to the `.kls/` folder (guaranteed to exist)
     */
    fun getOrCreatePath(workspaceRoot: Path): Path {
        val klsPath = getPath(workspaceRoot)
        if (!Files.exists(klsPath)) {
            Files.createDirectories(klsPath)
        }
        return klsPath
    }

    /**
     * Returns the path to the `.kls/logs/` folder.
     *
     * @param workspaceRoot The root path of the workspace
     * @return The path to the logs folder
     */
    fun getLogsPath(workspaceRoot: Path): Path {
        val klsPath = getPath(workspaceRoot)
        return klsPath.resolve(LOGS_FOLDER_NAME)
    }

    /**
     * Returns the path to the `.kls/exclusions.txt` file.
     *
     * @param workspaceRoot The root path of the workspace
     * @return The path to the exclusions file
     */
    fun getExclusionsFile(workspaceRoot: Path): Path {
        return getPath(workspaceRoot).resolve(EXCLUSIONS_FILE_NAME)
    }

    /**
     * Returns the path to the `.kls/kls_database.db` file.
     *
     * @param workspaceRoot The root path of the workspace
     * @return The path to the database file
     */
    fun getDatabaseFile(workspaceRoot: Path): Path {
        return getPath(workspaceRoot).resolve(DATABASE_FILE_NAME)
    }

    /**
     * Checks if file logging should be enabled.
     *
     * Enabled by default; set `KLS_LOG_FILE=false` to disable.
     *
     * @return true if file logging should be enabled
     */
    fun shouldLogToFile(): Boolean {
        val raw = System.getProperty(KLS_LOG_FILE_ENV)
            ?: System.getenv(KLS_LOG_FILE_ENV)
            ?.trim()
            ?: return true
        return raw.lowercase(Locale.ROOT) != "false"
    }

    /**
     * Reads project-specific exclusion patterns from `.kls/exclusions.txt`.
     *
     * Each line in the file can contain a glob pattern (e.g., `.deps`, `generated`).
     * Lines starting with `#` are treated as comments and ignored.
     *
     * @param workspaceRoot The root path of the workspace
     * @return List of exclusion patterns, or empty list if file doesn't exist
     */
    fun readExclusions(workspaceRoot: Path): List<String> {
        val exclusionsFile = getExclusionsFile(workspaceRoot)
        return if (Files.exists(exclusionsFile) && Files.isRegularFile(exclusionsFile)) {
            Files.readAllLines(exclusionsFile)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } else {
            emptyList()
        }
    }

    /**
     * Searches for a legacy database file in common locations.
     *
     * Checks the workspace root, `.idea/` folder, and user home directory.
     *
     * @param workspaceRoot The root path of the workspace
     * @return Path to the legacy database, or null if not found
     */
    fun findLegacyDatabase(workspaceRoot: Path): Path? {
        val commonLocations = listOf(
            workspaceRoot,
            workspaceRoot.resolve(".idea"),
            Paths.get(System.getProperty("user.home")).resolve(".kotlin-language-server"),
            Paths.get(System.getProperty("user.home")).resolve(".local/share/kotlin-language-server"),
        )

        for (location in commonLocations) {
            val dbPath = location.resolve(DATABASE_FILE_NAME)
            if (Files.exists(dbPath)) {
                return dbPath
            }
        }
        return null
    }

    /**
     * Migrates a legacy database file to the new `.kls/` location.
     *
     * If a legacy database exists in a common location, it will be moved
     * to `.kls/kls_database.db`. If the new location already has a database,
     * the legacy one is deleted.
     *
     * @param workspaceRoot The root path of the workspace
     * @return true if migration was performed, false if no legacy database was found
     */
    fun migrateLegacyDatabase(workspaceRoot: Path): Boolean {
        val legacyDb = findLegacyDatabase(workspaceRoot) ?: return false

        val newDb = getDatabaseFile(workspaceRoot)
        if (Files.exists(newDb)) {
            Files.deleteIfExists(legacyDb)
            return true
        }

        return try {
            getOrCreatePath(workspaceRoot)
            Files.move(legacyDb, newDb)
            LOG.info("Migrated database from {} to {}", legacyDb, newDb)
            true
        } catch (e: Exception) {
            LOG.warn("Failed to migrate database from {} to {}: {}", legacyDb, newDb, e.message)
            false
        }
    }
}
