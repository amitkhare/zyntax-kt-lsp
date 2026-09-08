package org.javacs.kt.declaration

import org.eclipse.lsp4j.*
import org.javacs.kt.CompiledFile
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.LOG
import org.javacs.kt.ExternalSourcesConfiguration
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.declarationLocation
import org.javacs.kt.util.decompileIfArchive
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor

private val declarationPattern = Regex("(?:class|interface|object|fun|typealias)\\s+(\\w+)")

data class GotoDeclarationContext(
    val classContentProvider: ClassContentProvider,
    val tempDir: TemporaryDirectory,
    val config: ExternalSourcesConfiguration,
    val cp: CompilerClassPath
)

fun goToDeclaration(
    file: CompiledFile,
    cursor: Int,
    context: GotoDeclarationContext
): Location? {
    val (_, target) = file.referenceExpressionAtPoint(cursor) ?: return null

    LOG.info("Found declaration descriptor {}", target)

    // Navigate to the declaration of the symbol:
    // - Type aliases: the type alias declaration, not the expanded type
    // - Classes: the class declaration (not the constructor)
    // - Constructors: the constructed class declaration
    // - Properties: the property declaration, not the getter/setter
    val destination = when (target) {
        is ConstructorDescriptor -> target.constructedClass.declarationLocation()
        else -> target.declarationLocation()
    }

    return decompileIfArchive(
        destination,
        target,
        context.classContentProvider,
        context.tempDir,
        context.config.useKlsScheme,
        context.cp.javaHome,
        declarationPattern
    )
}
