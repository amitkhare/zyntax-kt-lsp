package org.javacs.kt.externalsources

import org.javacs.kt.CompilerClassPath
import org.javacs.kt.LOG

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class JdkSourceArchiveProvider(
    private val cp: CompilerClassPath
) : SourceArchiveProvider {

    /**
     * Checks if the given path is inside the JDK. If it is, we return the corresponding source zip.
     * The source zip is resolved via [JdkSrcZipLocator] which honors the user's
     * `jdkSourceOverride` and falls back to scanning common JDK install locations.
     */
    override fun fetchSourceArchive(compiledArchive: Path): Path? {
        // Override wins
        cp.jdkSourceOverride?.let { override ->
            val p = Paths.get(override)
            if (Files.exists(p)) {
                LOG.debug("Using configured JDK source archive at: {}", p)
                return p
            }
        }

        return cp.javaHome?.let { javaHomePath ->
            val homeDir = File(javaHomePath).toPath()
            if (!compiledArchive.toString().startsWith(homeDir.toString())) {
                return@let null
            }

            val srcZipPath = JdkSrcZipLocator.resolve(cp.jdkSourceOverride)?.path
            if (srcZipPath != null) {
                val path = Paths.get(srcZipPath)
                LOG.debug("Found JDK source archive at: {}", path)
                path
            } else {
                LOG.warn("JDK source archive not found for runtime {}", homeDir)
                null
            }
        }
    }
}
