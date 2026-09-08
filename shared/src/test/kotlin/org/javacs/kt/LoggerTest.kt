package org.javacs.kt

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

import java.nio.file.Files

class LoggerTest {
    @Test
    fun `log level comparison with INFO level`() {
        val logger = Logger()
        logger.level = LogLevel.INFO

        val messagesLogged = mutableListOf<LogMessage>()
        logger.connectOutputBackend { messagesLogged.add(it) }

        logger.debug("debug message")
        logger.info("info message")
        logger.warn("warn message")
        logger.error("error message")

        assertTrue("DEBUG should NOT be logged when level is INFO", messagesLogged.none { it.level == LogLevel.DEBUG })
        assertTrue("INFO should be logged when level is INFO", messagesLogged.any { it.level == LogLevel.INFO })
        assertTrue("WARN should be logged when level is INFO", messagesLogged.any { it.level == LogLevel.WARN })
        assertTrue("ERROR should be logged when level is INFO", messagesLogged.any { it.level == LogLevel.ERROR })
    }

    @Test
    fun `log level comparison with WARN level`() {
        val logger = Logger()
        logger.level = LogLevel.WARN

        val messagesLogged = mutableListOf<LogMessage>()
        logger.connectOutputBackend { messagesLogged.add(it) }

        logger.debug("debug message")
        logger.info("info message")
        logger.warn("warn message")
        logger.error("error message")

        assertTrue("DEBUG should NOT be logged when level is WARN", messagesLogged.none { it.level == LogLevel.DEBUG })
        assertTrue("INFO should NOT be logged when level is WARN", messagesLogged.none { it.level == LogLevel.INFO })
        assertTrue("WARN should be logged when level is WARN", messagesLogged.any { it.level == LogLevel.WARN })
        assertTrue("ERROR should be logged when level is WARN", messagesLogged.any { it.level == LogLevel.ERROR })
    }

    @Test
    fun `log level comparison with DEBUG level`() {
        val logger = Logger()
        logger.level = LogLevel.DEBUG

        val messagesLogged = mutableListOf<LogMessage>()
        logger.connectOutputBackend { messagesLogged.add(it) }

        logger.debug("debug message")
        logger.info("info message")
        logger.warn("warn message")
        logger.error("error message")

        assertTrue("DEBUG should be logged when level is DEBUG", messagesLogged.any { it.level == LogLevel.DEBUG })
        assertTrue("INFO should be logged when level is DEBUG", messagesLogged.any { it.level == LogLevel.INFO })
        assertTrue("WARN should be logged when level is DEBUG", messagesLogged.any { it.level == LogLevel.WARN })
        assertTrue("ERROR should be logged when level is DEBUG", messagesLogged.any { it.level == LogLevel.ERROR })
    }

    @Test
    fun `log level comparison with ERROR level`() {
        val logger = Logger()
        logger.level = LogLevel.ERROR

        val messagesLogged = mutableListOf<LogMessage>()
        logger.connectOutputBackend { messagesLogged.add(it) }

        logger.debug("debug message")
        logger.info("info message")
        logger.warn("warn message")
        logger.error("error message")

        assertTrue("DEBUG should NOT be logged when level is ERROR", messagesLogged.none { it.level == LogLevel.DEBUG })
        assertTrue("INFO should NOT be logged when level is ERROR", messagesLogged.none { it.level == LogLevel.INFO })
        assertTrue("WARN should NOT be logged when level is ERROR", messagesLogged.none { it.level == LogLevel.WARN })
        assertTrue("ERROR should be logged when level is ERROR", messagesLogged.any { it.level == LogLevel.ERROR })
    }

    @Test
    fun `log level comparison with TRACE level`() {
        val logger = Logger()
        logger.level = LogLevel.TRACE

        val messagesLogged = mutableListOf<LogMessage>()
        logger.connectOutputBackend { messagesLogged.add(it) }

        logger.trace("trace message")
        logger.debug("debug message")

        assertTrue("TRACE should be logged when level is TRACE", messagesLogged.any { it.level == LogLevel.TRACE })
        assertTrue("DEBUG should be logged when level is TRACE", messagesLogged.any { it.level == LogLevel.DEBUG })
    }

    @Test
    fun `log level comparison with ALL level`() {
        val logger = Logger()
        logger.level = LogLevel.ALL

        val messagesLogged = mutableListOf<LogMessage>()
        logger.connectOutputBackend { messagesLogged.add(it) }

        logger.deepTrace("deep trace message")
        logger.trace("trace message")

        assertTrue("DEEP_TRACE should be logged when level is ALL", messagesLogged.any { it.level == LogLevel.DEEP_TRACE })
        assertTrue("TRACE should be logged when level is ALL", messagesLogged.any { it.level == LogLevel.TRACE })
    }

    @Test
    fun `log level comparison with NONE level`() {
        val logger = Logger()
        logger.level = LogLevel.NONE

        val messagesLogged = mutableListOf<LogMessage>()
        logger.connectOutputBackend { messagesLogged.add(it) }

        logger.error("error message")
        logger.warn("warn message")
        logger.info("info message")

        assertTrue("ERROR should NOT be logged when level is NONE", messagesLogged.none { it.level == LogLevel.ERROR })
        assertTrue("WARN should NOT be logged when level is NONE", messagesLogged.none { it.level == LogLevel.WARN })
        assertTrue("INFO should NOT be logged when level is NONE", messagesLogged.none { it.level == LogLevel.INFO })
    }

    @Test
    fun `lambda-based logging respects log level`() {
        val logger = Logger()
        logger.level = LogLevel.WARN

        val messagesLogged = mutableListOf<LogMessage>()
        logger.connectOutputBackend { messagesLogged.add(it) }

        logger.info { "info message" }
        logger.warn { "warn message" }

        assertTrue("INFO should NOT be logged when level is WARN", messagesLogged.none { it.level == LogLevel.INFO })
        assertTrue("WARN should be logged when level is WARN", messagesLogged.any { it.level == LogLevel.WARN })
    }

    @Test
    fun `stdio logging disabled when KLS_LOG_STDIO is false`() {
        val originalEnv = System.getenv("KLS_LOG_STDIO")
        try {
            System.setProperty("KLS_LOG_STDIO", "false")

            val logger = Logger()
            assertFalse("Stdio logging should be disabled", logger.isStdioLoggingEnabled())
        } finally {
            if (originalEnv != null) {
                System.setProperty("KLS_LOG_STDIO", originalEnv)
            } else {
                System.clearProperty("KLS_LOG_STDIO")
            }
        }
    }

    @Test
    fun `stdio logging enabled by default`() {
        val originalEnv = System.getenv("KLS_LOG_STDIO")
        try {
            System.clearProperty("KLS_LOG_STDIO")

            val logger = Logger()
            assertTrue("Stdio logging should be enabled by default", logger.isStdioLoggingEnabled())
        } finally {
            if (originalEnv != null) {
                System.setProperty("KLS_LOG_STDIO", originalEnv)
            }
        }
    }

    @Test
    fun `file logging enabled by default`() {
        val originalEnv = System.getenv("KLS_LOG_FILE")
        try {
            System.clearProperty("KLS_LOG_FILE")

            val logger = Logger()
            assertTrue("File logging should be enabled by default", logger.isFileLoggingEnabled())
        } finally {
            if (originalEnv != null) {
                System.setProperty("KLS_LOG_FILE", originalEnv)
            }
        }
    }

    @Test
    fun `file logging enabled when KLS_LOG_FILE is set`() {
        val originalEnv = System.getenv("KLS_LOG_FILE")
        try {
            System.setProperty("KLS_LOG_FILE", "true")

            val logger = Logger()
            assertTrue("File logging should be enabled when KLS_LOG_FILE is set to true", logger.isFileLoggingEnabled())
        } finally {
            if (originalEnv != null) {
                System.setProperty("KLS_LOG_FILE", originalEnv)
            } else {
                System.clearProperty("KLS_LOG_FILE")
            }
        }
    }

    @Test
    fun `file logging enabled when KLS_LOG_FILE is set to arbitrary value`() {
        val originalEnv = System.getenv("KLS_LOG_FILE")
        try {
            System.setProperty("KLS_LOG_FILE", "anything")

            val logger = Logger()
            assertTrue("File logging should be enabled when KLS_LOG_FILE is set to any non-false value", logger.isFileLoggingEnabled())
        } finally {
            if (originalEnv != null) {
                System.setProperty("KLS_LOG_FILE", originalEnv)
            } else {
                System.clearProperty("KLS_LOG_FILE")
            }
        }
    }

    @Test
    fun `file logging disabled when KLS_LOG_FILE is set to false`() {
        val originalEnv = System.getenv("KLS_LOG_FILE")
        try {
            System.setProperty("KLS_LOG_FILE", "FALSE")

            val logger = Logger()
            assertFalse("File logging should be disabled when KLS_LOG_FILE is set to false", logger.isFileLoggingEnabled())
        } finally {
            if (originalEnv != null) {
                System.setProperty("KLS_LOG_FILE", originalEnv)
            } else {
                System.clearProperty("KLS_LOG_FILE")
            }
        }
    }

    @Test
    fun `log file path is set when file logging enabled`() {
        val originalEnv = System.getenv("KLS_LOG_FILE")
        val tempDir = Files.createTempDirectory("kotlin-lsp-test")
        val originalLogDir = System.getenv("KLS_LOG_DIR")

        try {
            System.setProperty("KLS_LOG_FILE", "true")
            System.setProperty("KLS_LOG_DIR", tempDir.toString())

            val logger = Logger()
            logger.initializeFileLogging()

            assertNotNull("Log file path should be set", logger.getLogFilePath())
            assertTrue("Log file path should be in temp dir", logger.getLogFilePath()?.startsWith(tempDir) == true)

            logger.shutdown()

            val logFile = logger.getLogFilePath()
            assertNotNull("Log file should exist", logFile)
            assertTrue("Log file should exist", Files.exists(logFile!!))

            val content = Files.readString(logFile)
            assertTrue("Log file should contain the info message", content.contains("File logging enabled"))
        } finally {
            if (originalEnv != null) {
                System.setProperty("KLS_LOG_FILE", originalEnv)
            } else {
                System.clearProperty("KLS_LOG_FILE")
            }
            if (originalLogDir != null) {
                System.setProperty("KLS_LOG_DIR", originalLogDir)
            } else {
                System.clearProperty("KLS_LOG_DIR")
            }
            Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach { it.toFile().delete() }
        }
    }

    @Test
    fun `log filename format is correct`() {
        val originalEnv = System.getenv("KLS_LOG_FILE")
        val tempDir = Files.createTempDirectory("kotlin-lsp-test")
        val originalLogDir = System.getenv("KLS_LOG_DIR")

        try {
            System.setProperty("KLS_LOG_FILE", "true")
            System.setProperty("KLS_LOG_DIR", tempDir.toString())

            val logger = Logger()
            logger.initializeFileLogging()
            logger.shutdown()

            val logFile = logger.getLogFilePath()
            assertNotNull("Log file path should be set", logFile)

            val filename = logFile!!.fileName.toString()

            // Check format: {timestamp}_{pid}.log
            // timestamp: yyyy-MM-dd_HHmmss
            val timestampPattern = Regex("\\d{4}-\\d{2}-\\d{2}_\\d{6}")
            val pidPattern = Regex("\\d+")
            val logExtension = filename.endsWith(".log")

            assertTrue("Filename should match timestamp format", timestampPattern.containsMatchIn(filename))
            assertTrue("Filename should contain PID", pidPattern.containsMatchIn(filename))
            assertTrue("Filename should end with .log", logExtension)

            // Extract parts and verify
            val parts = filename.removeSuffix(".log").split("_")
            assertTrue("Filename should have 3 parts (date, time, pid)", parts.size == 3)

            // Verify date format yyyy-MM-dd
            assertTrue("Date part should match yyyy-MM-dd", parts[0].matches(Regex("\\d{4}-\\d{2}-\\d{2}")))

            // Verify time format HHmmss
            assertTrue("Time part should match HHmmss", parts[1].matches(Regex("\\d{6}")))

            // Verify pid is positive number
            assertTrue("PID should be positive number", parts[2].toLong() > 0)
        } finally {
            if (originalEnv != null) {
                System.setProperty("KLS_LOG_FILE", originalEnv)
            } else {
                System.clearProperty("KLS_LOG_FILE")
            }
            if (originalLogDir != null) {
                System.setProperty("KLS_LOG_DIR", originalLogDir)
            } else {
                System.clearProperty("KLS_LOG_DIR")
            }
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { it.toFile().delete() }
        }
    }
}
