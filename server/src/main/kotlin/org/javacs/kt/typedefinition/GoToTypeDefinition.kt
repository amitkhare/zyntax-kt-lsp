package org.javacs.kt.typedefinition

import org.eclipse.lsp4j.Location
import org.javacs.kt.CompiledFile
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.ExternalSourcesConfiguration
import org.javacs.kt.LOG
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.declarationLocation
import org.javacs.kt.util.decompileIfArchive
import org.javacs.kt.util.findParent
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.TypeUtils

private val typeDefinitionPattern = Regex("(?:class|interface|object|typealias)\\s+(\\w+)")

data class GoToTypeDefinitionContext(
    val classContentProvider: ClassContentProvider,
    val tempDir: TemporaryDirectory,
    val config: ExternalSourcesConfiguration,
    val cp: CompilerClassPath
)

/**
 * Resolves the type definition at [cursor]: the declaration of the type of the
 * symbol under the cursor.
 *
 * Resolution order:
 * 1. The reference at the cursor (type name or symbol use): if it is a classifier,
 *    navigate to it directly; if it is a variable/callable, navigate to its type.
 * 2. The declaration at the cursor (e.g. the property name in `val x = Foo()`):
 *    navigate to the type of the declared symbol.
 * 3. Fallback: the type of the expression at the cursor.
 */
fun goToTypeDefinition(file: CompiledFile, cursor: Int, context: GoToTypeDefinitionContext): Location? {
    val target = file.referenceExpressionAtPoint(cursor)?.second

    // Case 1 + 2: the referenced or declared symbol's classifier
    val cls = typeClassifierOf(target) ?: declaredClassifierAt(file, cursor)
    if (cls != null) {
        LOG.info("Found type definition descriptor {}", cls)
        return decompileIfArchive(
            cls.declarationLocation(),
            cls,
            context.classContentProvider,
            context.tempDir,
            context.config.useKlsScheme,
            context.cp.javaHome,
            typeDefinitionPattern
        )
    }

    // Case 3: the type of the expression at the cursor
    val type = file.typeAtPoint(cursor) ?: return null
    val fromType = TypeUtils.getClassDescriptor(type) ?: return null

    LOG.info("Found type definition descriptor {}", fromType)
    return decompileIfArchive(
        fromType.declarationLocation(),
        fromType,
        context.classContentProvider,
        context.tempDir,
        context.config.useKlsScheme,
        context.cp.javaHome,
        typeDefinitionPattern
    )
}

/** Resolves the classifier of the declaration at [cursor] (e.g. a `val`/`var`/parameter name). */
private fun declaredClassifierAt(file: CompiledFile, cursor: Int): ClassDescriptor? {
    val declaration = file.elementAtPoint(cursor)?.findParent<KtNamedDeclaration>() ?: return null
    val descriptor = file.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration] ?: return null
    return typeClassifierOf(descriptor)
}

/** Returns the classifier of a symbol's type, or the classifier itself when the symbol is a type. */
private fun typeClassifierOf(target: DeclarationDescriptor?): ClassDescriptor? = when (target) {
    is ClassDescriptor -> target
    is TypeAliasDescriptor -> target.classDescriptor
    is VariableDescriptor -> target.type.let(TypeUtils::getClassDescriptor)
    is CallableDescriptor -> target.returnType?.let(TypeUtils::getClassDescriptor)
    else -> null
}
