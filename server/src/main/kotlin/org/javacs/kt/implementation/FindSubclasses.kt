package org.javacs.kt.implementation

import org.eclipse.lsp4j.Location
import org.javacs.kt.SourcePath
import org.javacs.kt.position.location
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import java.nio.file.Path

// Finds subclasses (non-abstract, non-interface classes that extend) a given class.
fun findSubclasses(file: Path, cursor: Int, sp: SourcePath): List<Location> {
    val compiled = sp.currentVersion(file.toUri())
    val target = compiled.referenceExpressionAtPoint(cursor)?.second
        ?: compiled.elementAtPoint(cursor)
            ?.let { compiled.compile[BindingContext.CLASS, it] }
        ?: return emptyList()

    if (target !is ClassDescriptor) return emptyList()

    return findClassSubclasses(target, sp)
}

// Scans all source files to find classes that extend the target class.
private fun findClassSubclasses(target: ClassDescriptor, sp: SourcePath): List<Location> {
    val targetFqName = target.fqNameSafe
    val targetPackage = targetFqName.parent()

    // Use dependency tracker to find files in the same package or importing it,
    // instead of recompiling all files in the project
    val relevantUris = sp.dependencyTracker.filesInPackageOrImporting(targetPackage)
    val context = sp.compileFiles(relevantUris)

    return context.getSliceContents(BindingContext.CLASS)
        .values
        .filter { classDescriptor ->
            classDescriptor != target
            && classDescriptor.kind != ClassKind.INTERFACE
            && classDescriptor.getAllSuperClassifiers().any { it.fqNameSafe == targetFqName }
        }
        .mapNotNull { locationOfClassIdentifier(it) }
        .sortedWith(compareBy({ it.uri }, { it.range.start.line }))
}

// Gets the location of a class's name identifier for navigation.
private fun locationOfClassIdentifier(descriptor: ClassDescriptor): Location? {
    val psi = descriptor.findPsi()
    if (psi is KtNamedDeclaration) {
        return psi.nameIdentifier?.let { location(it) } ?: location(psi)
    }
    return location(descriptor)
}
