package org.javacs.kt

import com.google.gson.JsonParser
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.InitializeParams
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class SourcePathTest {
    @Test fun `compiler settings refresh preserves unsaved text and invalidates old analysis`() {
        val config = Configuration(indexing = IndexingConfiguration(false), cache = CacheConfiguration(workspaceCacheEnabled = false))
        KotlinLanguageServer(config).use { server ->
            server.initialize(InitializeParams().apply { capabilities = ClientCapabilities() }).join()
            server.textDocumentService.debounceLint.waitForPendingTask()
            val sourcePath = server.sourcePath
            val uri = Paths.get("Unsaved.kt").toAbsolutePath().toUri()
            sourcePath.put(uri, "class Saved", null)
            sourcePath.compileFiles(listOf(uri))
            val before = sourcePath.latestCompiledVersion(uri)
            val text = "class Unsaved"
            sourcePath.put(uri, text, null)
            val hashes = sourcePath.fileContentHashes()
            val generated = Files.createDirectories(server.classPath.outputDirectory.toPath().resolve("nested")).resolve("stale.class")
            Files.createFile(generated)

            server.workspaceService.didChangeConfiguration(DidChangeConfigurationParams(
                JsonParser.parseString("""{"kotlin":{"compiler":{"jvm":{"target":"17"}}}}""")
            ))

            val after = sourcePath.latestCompiledVersion(uri)
            assertEquals(text, sourcePath.content(uri))
            assertEquals(text, after.parse.text)
            assertEquals(hashes, sourcePath.fileContentHashes())
            assertNotSame(before.parse, after.parse)
            assertNotSame(before.compile, after.compile)
            assertNotSame(before.module, after.module)
            assertFalse(Files.exists(generated.parent))
            assertTrue(Files.isDirectory(server.classPath.outputDirectory.toPath()))
        }
    }
}
