package org.javacs.kt.definition

import org.eclipse.lsp4j.Location
import org.javacs.kt.CompiledFile
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.ExternalSourcesConfiguration
import org.javacs.kt.LOG
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.declarationLocation
import org.javacs.kt.util.decompileIfArchive

private val definitionPattern = Regex("(?:class|interface|object|fun)\\s+(\\w+)")

fun goToDefinition(
    file: CompiledFile,
    cursor: Int,
    classContentProvider: ClassContentProvider,
    tempDir: TemporaryDirectory,
    config: ExternalSourcesConfiguration,
    cp: CompilerClassPath
): Location? {
    val (_, target) = file.referenceExpressionAtPoint(cursor) ?: return null

    LOG.info("Found declaration descriptor {}", target)
    return decompileIfArchive(
        target.declarationLocation(),
        target,
        classContentProvider,
        tempDir,
        config.useKlsScheme,
        cp.javaHome,
        definitionPattern
    )
}
