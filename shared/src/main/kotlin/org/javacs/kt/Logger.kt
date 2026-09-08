package org.javacs.kt

import org.javacs.kt.util.DelegatePrintStream

import java.io.FileWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.util.logging.*

val LOG = Logger()

private class JULRedirector(private val downstream: Logger): Handler() {
    override fun publish(record: LogRecord) {
        when (record.level) {
            Level.SEVERE -> downstream.error(record.message)
            Level.WARNING -> downstream.warn(record.message)
            Level.INFO -> downstream.info(record.message)
            Level.CONFIG -> downstream.debug(record.message)
            Level.FINE -> downstream.trace(record.message)
            else -> downstream.deepTrace(record.message)
        }
        record.thrown?.let(downstream::printStackTrace)
    }

    override fun flush() {}

    override fun close() {}
}

enum class LogLevel(val value: Int) {
    NONE(100),
    ERROR(2),
    WARN(1),
    INFO(0),
    DEBUG(-1),
    TRACE(-2),
    DEEP_TRACE(-3),
    ALL(-100)
}

class LogMessage(
    val level: LogLevel,
    val message: String
) {
    val formatted: String
        get() = "[$level] $message"
}

class Logger {
    private var outBackend: ((LogMessage) -> Unit)? = null
    private var errBackend: ((LogMessage) -> Unit)? = null
    private val outQueue: Queue<LogMessage> = ArrayDeque()
    private val errQueue: Queue<LogMessage> = ArrayDeque()
    private val errStream = DelegatePrintStream { logError(LogMessage(LogLevel.ERROR, it.trimEnd())) }
    val outStream = DelegatePrintStream { log(LogMessage(LogLevel.INFO, it.trimEnd())) }

    private var fileWriter: PrintWriter? = null
    private var logFilePath: Path? = null

    val logTime = false
    var level = getLogLevel() ?: LogLevel.INFO

    private val logToFile: Boolean = getLogToFile()
    private val logToStdio: Boolean = getLogToStdio()

    /**
     * Get the user-provided logging value from the environment variable `KLS_LOG_LEVEL` or system property.
     * System property takes precedence. If neither exists or is invalid, returns null.
     */
    private fun getLogLevel(): LogLevel? {
        val raw = System.getProperty("KLS_LOG_LEVEL")
            ?: System.getenv("KLS_LOG_LEVEL")
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return LogLevel.entries
            .firstOrNull { it.name == raw.uppercase(Locale.ROOT) }
    }

    /**
     * Check if file logging should be enabled via `KLS_LOG_FILE` env var or system property.
     * System property takes precedence. Enabled by default; set to "false" to disable.
     */
    private fun getLogToFile(): Boolean {
        val raw = System.getProperty("KLS_LOG_FILE")
            ?: System.getenv("KLS_LOG_FILE")
            ?.trim()
            ?: return true
        return raw.lowercase(Locale.ROOT) != "false"
    }

    /**
     * Check if stdio logging should be enabled via `KLS_LOG_STDIO` env var or system property.
     * System property takes precedence. Disabled if set to "false" (case-insensitive), otherwise enabled by default.
     */
    private fun getLogToStdio(): Boolean {
        val raw = System.getProperty("KLS_LOG_STDIO")
            ?: System.getenv("KLS_LOG_STDIO")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return true
        return raw.lowercase(Locale.ROOT) != "false"
    }

    /**
     * Get the log directory from `KLS_LOG_DIR` env var/system property or use platform-specific default.
     */
    private fun getLogDirectory(): Path {
        val customDir = System.getProperty("KLS_LOG_DIR")
            ?: System.getenv("KLS_LOG_DIR")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (customDir != null) {
            return Path.of(customDir)
        }

        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val userHome = System.getProperty("user.home")
        val klsFolder = "kotlin-language-server"

        return when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: userHome
                Path.of(appData, klsFolder, "logs")
            }
            os.contains("mac") -> {
                Path.of(userHome, "Library", "Logs", klsFolder)
            }
            else -> {
                Path.of(userHome, ".local", "share", klsFolder, "logs")
            }
        }
    }

    /**
     * Generate log filename with format: {timestamp}_{pid}.log
     */
    private fun generateLogFilename(): String {
        val now = Instant.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
            .withZone(ZoneId.systemDefault())
        val timestamp = formatter.format(now)
        val pid = ProcessHandle.current().pid()
        return "${timestamp}_${pid}.log"
    }

    /**
     * Create a log file and start writing. No-op if `KLS_LOG_FILE` is set to `"false"`.
     *
     * Idempotent: closes any previous file writer before creating a new one.
     *
     * Log directory resolution (first match wins):
     * 1. `KLS_LOG_DIR` system property
     * 2. `KLS_LOG_DIR` environment variable
     * 3. Platform default (`~/.local/share/kotlin-language-server/logs/` on Linux,
     *    `~/Library/Logs/kotlin-language-server/` on macOS,
     *    `%APPDATA%/kotlin-language-server/logs` on Windows)
     *
     * Filename format: `{yyyy-MM-dd}_{HHmmss}_{pid}.log`
     */
    fun initializeFileLogging() {
        if (!logToFile) return

        closeFileLogging()
        try {
            val logDir = getLogDirectory()
            Files.createDirectories(logDir)

            val filename = generateLogFilename()
            logFilePath = logDir.resolve(filename)

            fileWriter = PrintWriter(FileWriter(logFilePath!!.toFile(), true), true)
            fileWriter?.println("[INFO] File logging enabled: $logFilePath")
            fileWriter?.flush()
        } catch (e: Exception) {
            System.err.println("Failed to initialize file logging: ${e.message}")
        }
    }

    /**
     * Close file logging.
     */
    private fun closeFileLogging() {
        try {
            fileWriter?.close()
        } catch (_: Exception) {
            // Ignore
        }
    }

    fun logError(msg: LogMessage) {
        if (errBackend == null) {
            errQueue.offer(msg)
        } else {
            errBackend?.invoke(msg)
        }
        writeToFile(msg)
    }

    fun log(msg: LogMessage) {
        if (outBackend == null) {
            outQueue.offer(msg)
        } else {
            outBackend?.invoke(msg)
        }
        writeToFile(msg)
    }

    private fun writeToFile(msg: LogMessage) {
        if (logToFile) {
            try {
                fileWriter?.println(msg.formatted)
                fileWriter?.flush()
            } catch (_: Exception) {
                // Silently fail - don't crash the server
            }
        }
    }

    private fun logWithPlaceholdersAt(msgLevel: LogLevel, msg: String, placeholders: Array<out Any?>) {
        if (level.value <= msgLevel.value) {
            log(LogMessage(msgLevel, format(insertPlaceholders(msg, placeholders))))
        }
    }

    inline fun logWithLambdaAt(msgLevel: LogLevel, msg: () -> String) {
        if (level.value <= msgLevel.value) {
            log(LogMessage(msgLevel, msg()))
        }
    }

    fun printStackTrace(throwable: Throwable) = throwable.printStackTrace(errStream)

    // Convenience logging methods using the traditional placeholder syntax

    fun error(msg: String, vararg placeholders: Any?) = logWithPlaceholdersAt(LogLevel.ERROR, msg, placeholders)

    fun warn(msg: String, vararg placeholders: Any?) = logWithPlaceholdersAt(LogLevel.WARN, msg, placeholders)

    fun info(msg: String, vararg placeholders: Any?) = logWithPlaceholdersAt(LogLevel.INFO, msg, placeholders)

    fun debug(msg: String, vararg placeholders: Any?) = logWithPlaceholdersAt(LogLevel.DEBUG, msg, placeholders)

    fun trace(msg: String, vararg placeholders: Any?) = logWithPlaceholdersAt(LogLevel.TRACE, msg, placeholders)

    fun deepTrace(msg: String, vararg placeholders: Any?) = logWithPlaceholdersAt(LogLevel.DEEP_TRACE, msg, placeholders)

    // Convenience logging methods using inlined lambdas

    inline fun error(msg: () -> String) = logWithLambdaAt(LogLevel.ERROR, msg)

    inline fun warn(msg: () -> String) = logWithLambdaAt(LogLevel.WARN, msg)

    inline fun info(msg: () -> String) = logWithLambdaAt(LogLevel.INFO, msg)

    inline fun debug(msg: () -> String) = logWithLambdaAt(LogLevel.DEBUG, msg)

    inline fun trace(msg: () -> String) = logWithLambdaAt(LogLevel.TRACE, msg)

    inline fun deepTrace(msg: () -> String) = logWithLambdaAt(LogLevel.DEEP_TRACE, msg)

    fun connectJULFrontend() {
        val rootLogger = java.util.logging.Logger.getLogger("")
        rootLogger.addHandler(JULRedirector(this))
    }

    fun connectOutputBackend(outBackend: (LogMessage) -> Unit) {
        this.outBackend = outBackend
        flushOutQueue()
    }

    fun connectErrorBackend(errBackend: (LogMessage) -> Unit) {
        this.errBackend = errBackend
        flushErrQueue()
    }

    fun connectStdioBackend() {
        if (logToStdio) {
            connectOutputBackend { println(it.formatted) }
            connectErrorBackend { System.err.println(it.formatted) }
        }
    }

    fun getLogFilePath(): Path? = logFilePath

    fun isFileLoggingEnabled(): Boolean = logToFile

    fun isStdioLoggingEnabled(): Boolean = logToStdio

    fun shutdown() {
        closeFileLogging()
    }

    private fun insertPlaceholders(msg: String, placeholders: Array<out Any?>): String {
        val msgLength = msg.length
        val lastIndex = msgLength - 1
        var charIndex = 0
        var placeholderIndex = 0
        val result = StringBuilder()

        while (charIndex < msgLength) {
            val currentChar = msg[charIndex]
            val nextChar = if (charIndex != lastIndex) msg[charIndex + 1] else '?'
            if ((placeholderIndex < placeholders.size) && (currentChar == '{') && (nextChar == '}')) {
                result.append(placeholders[placeholderIndex] ?: "null")
                placeholderIndex += 1
                charIndex += 2
            } else {
                result.append(currentChar)
                charIndex += 1
            }
        }

        return result.toString()
    }

    private fun flushOutQueue() {
        while (outQueue.isNotEmpty()) {
            val msg = outQueue.poll() ?: continue
            outBackend?.invoke(msg)
        }
    }

    private fun flushErrQueue() {
        while (errQueue.isNotEmpty()) {
            val msg = errQueue.poll() ?: continue
            errBackend?.invoke(msg)
        }
    }

    private fun format(msg: String): String {
        val time = if (logTime) "${Instant.now()} " else ""
        val thread = Thread.currentThread().name

        return time + shortenOrPad(thread, 10) + msg.trimEnd()
    }

    private fun shortenOrPad(str: String, length: Int): String =
            if (str.length <= length) {
                str.padEnd(length, ' ')
            } else {
                ".." + str.substring(str.length - length + 2)
            }
}
