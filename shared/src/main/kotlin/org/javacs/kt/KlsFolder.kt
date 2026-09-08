package org.javacs.kt

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Manages the `.kls/` folder location and related operations for ktlsp.
 *
 * The `.kls/` folder contains:
 * - `kls_database.db` - Symbol/index database
 * - `logs/` - Log files
 * - `exclusions.txt` - Project-specific exclusion patterns
 */
object KlsFolder {
    private const val KLS_FOLDER_NAME = ".kls"
    private const val LOGS_FOLDER_NAME = "logs"
    private const val EXCLUSIONS_FILE_NAME = "exclusions.txt"
    private const val DATABASE_FILE_NAME = "kls_database.db"

    private const val KLS_LOG_FILE_ENV = "KLS_LOG_FILE"

    /**
     * Returns the path to the `.kls/` folder.
     *
     * Always returns `<workspaceRoot>/.kls/`.
     *
     * @param workspaceRoot The root path of the workspace
     * @return The path to the `.kls/` folder
     */
    fun getPath(workspaceRoot: Path): Path = workspaceRoot.resolve(KLS_FOLDER_NAME)

    /**
     * Returns the path to the `.kls/` folder, creating it if it doesn't exist.
     *
     * @param workspaceRoot The root path of the workspace
     * @return The path to the `.kls/` folder (guaranteed to exist)
     */
    fun getOrCreatePath(workspaceRoot: Path): Path = Files.createDirectories(getPath(workspaceRoot))

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

}
