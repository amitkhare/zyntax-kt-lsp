package org.javacs.kt.externalsources

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.javacs.kt.LOG
import org.javacs.kt.util.KotlinLSException
import org.javacs.kt.util.replaceExtensionWith
import org.javacs.kt.util.withCustomStdout
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler

class FernFlowerDecompiler : Decompiler {
    private val outputDir: Path by lazy {
        Files.createTempDirectory("fernflowerOut").also {
            Runtime.getRuntime().addShutdownHook(Thread { it.toFile().deleteRecursively() })
        }
    }

    private val decompilerOptions =
        arrayOf(
            "-iec=1", // Include entire classpath for better context
            "-jpr=1", // Include parameter names in method signatures
        )

    private companion object {
        const val MAX_LOGGED_OUTPUT_CHARS = 500
    }

    override fun decompileClass(compiledClass: Path): Path {
        return decompile(compiledClass, ".java")
    }

    override fun decompileJar(compiledJar: Path): Path {
        return decompile(compiledJar, ".jar")
    }

    private fun decompile(input: Path, extension: String): Path {
        LOG.info("Decompiling ${input.fileName} using FernFlower...")

        val args = decompilerOptions + arrayOf(input.toString(), outputDir.toString())

        // Capture FernFlower's stdout in a buffer, NOT via LOG.outStream.
        // Routing through LOG.outStream causes infinite recursion when the logging
        // backend is connected (log -> outBackend -> println -> LOG.outStream -> log).
        val captured = ByteArrayOutputStream()
        withCustomStdout(PrintStream(captured)) { ConsoleDecompiler.main(args) }
        LOG.debug { "FernFlower output: ${captured.toString().take(MAX_LOGGED_OUTPUT_CHARS)}" }

        val outName = input.fileName.replaceExtensionWith(extension)
        val outPath = outputDir.resolve(outName)
        if (!Files.exists(outPath)) {
            throw KotlinLSException(
                "Could not decompile ${input.fileName}: FernFlower did not generate sources at $outName"
            )
        }
        return outPath
    }
}
