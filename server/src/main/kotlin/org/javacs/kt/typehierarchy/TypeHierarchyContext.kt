package org.javacs.kt.typehierarchy

import org.javacs.kt.CompilerClassPath
import org.javacs.kt.ExternalSourcesConfiguration
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.util.TemporaryDirectory

/** Bundles the decompiler services needed to produce navigable URIs for external/JAR types. */
data class TypeHierarchyContext(
    val classContentProvider: ClassContentProvider,
    val tempDir: TemporaryDirectory,
    val config: ExternalSourcesConfiguration,
    val cp: CompilerClassPath
)
