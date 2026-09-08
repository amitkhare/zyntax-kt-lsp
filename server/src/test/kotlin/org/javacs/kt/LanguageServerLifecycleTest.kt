package org.javacs.kt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LanguageServerLifecycleTest {
    @Test fun shutdownWaitsForExit() {
        KotlinLanguageServer().use { server ->
            server.shutdown().join()
            assertFalse(server.exitStatus.isDone)
            server.exit()
            assertEquals(0, server.exitStatus.join())
        }
    }

    @Test fun exitWithoutShutdownFails() {
        KotlinLanguageServer().use { server ->
            server.exit()
            assertEquals(1, server.exitStatus.join())
        }
    }
}
