package org.javacs.kt.index

import org.javacs.kt.LOG

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ExternalSymbol(
    val fqName: String,
    val shortName: String,
    val kind: Symbol.Kind,
    val visibility: Symbol.Visibility,
    val jarPath: String
)

object JarSymbolScanner {
    private const val MAX_SYMBOLS_PER_PACKAGE = 500

    fun scanJarForSymbols(jarPath: Path, packagePrefix: String? = null): List<ExternalSymbol> {
        val jarFile = jarPath.toFile()
        if (!jarFile.exists()) return emptyList()

        return try {
            ZipFile(jarFile).use { zip ->
                zip.entries().toList()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .filter { matchesPackagePrefix(it, packagePrefix) }
                    .take(MAX_SYMBOLS_PER_PACKAGE)
                    .map { toExternalSymbol(it, jarPath) }
                    .toList()
            }
        } catch (e: Exception) {
            LOG.warn("Error scanning JAR {}: {}", jarPath, e.message)
            LOG.printStackTrace(e)
            emptyList()
        }
    }

    private fun matchesPackagePrefix(entry: ZipEntry, packagePrefix: String?): Boolean {
        if (packagePrefix == null) return true
        return entry.name.startsWith(packagePrefix.replace('.', '/'))
    }

    private fun toExternalSymbol(entry: ZipEntry, jarPath: Path): ExternalSymbol {
        val className = entry.name.substringBeforeLast(".class").replace('/', '.')
        val lastDot = className.lastIndexOf('.')
        val shortName = if (lastDot > 0) className.substring(lastDot + 1) else className

        return ExternalSymbol(
            fqName = className,
            shortName = shortName,
            kind = determineKind(className),
            visibility = Symbol.Visibility.INTERNAL,
            jarPath = jarPath.toString()
        )
    }

    private fun determineKind(fqName: String): Symbol.Kind {
        val shortName = fqName.substringAfterLast('.')
        return when {
            fqName.contains(".package-info") -> Symbol.Kind.UNKNOWN
            shortName.first().isUpperCase() -> Symbol.Kind.CLASS
            else -> Symbol.Kind.FUNCTION
        }
    }
}
