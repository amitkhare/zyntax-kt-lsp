package org.javacs.kt.util

import org.eclipse.lsp4j.Location
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.position.location
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Location of the declaration of [this], refined to its `nameIdentifier` when the PSI element is a named
 * declaration (e.g. the identifier of a class rather than the whole class node).
 *
 * Used by go-to-definition, go-to-declaration and go-to-type-definition.
 */
fun DeclarationDescriptor.declarationLocation(): Location? =
    when (val psi = findPsi()) {
        is KtNamedDeclaration -> psi.nameIdentifier?.let(::location) ?: location(psi)
        else -> location(this)
    }

/**
 * Routes [destination] through the archive decompiler when the target lives inside
 * a JAR/JDK archive, yielding a navigable `kls://` URI or decompiled temp file.
 * Returns `null` if [destination] is null (no resolvable location).
 */
@Suppress("LongParameterList")
fun decompileIfArchive(
    destination: Location?,
    target: DeclarationDescriptor,
    classContentProvider: ClassContentProvider,
    tempDir: TemporaryDirectory,
    useKlsScheme: Boolean,
    javaHome: String?,
    declarationPattern: Regex
): Location? {
    if (destination == null) return null

    return decompileArchiveLocation(ArchiveDecompileContext(
        destination = destination,
        target = target,
        classContentProvider = classContentProvider,
        tempDir = tempDir,
        useKlsScheme = useKlsScheme,
        declarationPattern = declarationPattern,
        javaHome = javaHome
    ))
}
