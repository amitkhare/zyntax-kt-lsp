package org.javacs.kt.codelens

import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.*
import org.javacs.kt.CompiledFile
import org.javacs.kt.SourcePath
import org.javacs.kt.implementation.findImplementations
import org.javacs.kt.implementation.findSubclasses
import org.javacs.kt.position.location
import org.javacs.kt.position.offset
import org.javacs.kt.references.findReferences
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

// Finds code lenses for a compiled Kotlin file, showing references, implementations, and subclasses.
fun findCodeLenses(file: CompiledFile, sp: SourcePath): List<CodeLens> {
    val codeLenses = mutableListOf<CodeLens>()
    val parsedFile = file.parse
    val filePath = parsedFile.containingFile.toPath()
    val uri = filePath.toUri().toString()
    val content = file.content

    // Recursively find all classes/interfaces/objects/enums at any nesting level
    val allClasses = PsiTreeUtil.findChildrenOfType(parsedFile, KtClassOrObject::class.java)
        .filter { shouldHaveCodeLens(it) }

    allClasses.forEach { ktClass ->
        val classDesc = file.compile.get(BindingContext.CLASS, ktClass)
        if (classDesc != null) {
            val loc = location(ktClass) ?: return@forEach
            val startOffset = offset(content, loc.range.start.line, loc.range.start.character)

            when (classDesc.kind) {
                // For interfaces, show implementations (concrete classes that implement the interface)
                ClassKind.INTERFACE -> {
                    val implementations = findImplementations(filePath, startOffset, sp)
                    if (implementations.isNotEmpty()) {
                        codeLenses.add(CodeLens(
                            loc.range,
                            Command(
                                "Show ${implementations.size} Implementation${if (implementations.size > 1) "s" else ""}",
                                "kotlin.showImplementations",
                                listOf(uri, loc.range.start.line, loc.range.start.character)
                            ),
                            null
                        ))
                    }
                }
                // For classes, show subclasses (non-interface, non-abstract classes that extend the class)
                ClassKind.CLASS -> {
                    val subclasses = findSubclasses(filePath, startOffset, sp)
                    if (subclasses.isNotEmpty()) {
                        codeLenses.add(CodeLens(
                            loc.range,
                            Command(
                                "Show ${subclasses.size} Subclass${if (subclasses.size > 1) "es" else ""}",
                                "kotlin.showSubclasses",
                                listOf(uri, loc.range.start.line, loc.range.start.character)
                            ),
                            null
                        ))
                    }
                }
                // For enums, objects, enum entries, and annotation classes, show reference counts
                ClassKind.ENUM_CLASS, ClassKind.OBJECT,
                ClassKind.ENUM_ENTRY, ClassKind.ANNOTATION_CLASS -> {
                    addReferenceCodeLens(ktClass, filePath, uri, content, sp, codeLenses)
                }
            }
        }
    }

    // Recursively find all named functions and properties at any nesting level
    val allFunctions = PsiTreeUtil.findChildrenOfType(parsedFile, KtNamedFunction::class.java)
        .filter { shouldHaveCodeLens(it) }
    val allProperties = PsiTreeUtil.findChildrenOfType(parsedFile, KtProperty::class.java)
        .filter { shouldHaveCodeLens(it) }

    // Add lenses for functions and properties (show references, excluding the declaration itself)
    (allFunctions + allProperties).forEach { declaration ->
        addReferenceCodeLens(declaration, filePath, uri, content, sp, codeLenses)
    }

    return codeLenses
}

/**
 * Returns `true` if [declaration] should receive a code lens.
 *
 * A declaration qualifies when it is a referenceable member:
 *
 * - Its parent chain reaches a [KtFile] or [KtClassBody] without passing
 *   through a [KtBlockExpression] -- i.e. it sits at the top level or is a
 *   member of a class/object/interface, not a local inside a function body,
 *   lambda, or init block.
 *
 * - It has a non-null `nameIdentifier`, which excludes anonymous objects,
 *   function literals, and other synthetic elements.
 */
private fun shouldHaveCodeLens(declaration: KtNamedDeclaration): Boolean {
    if (declaration.nameIdentifier == null) return false

    var parent = declaration.parent
    while (parent != null) {
        when (parent) {
            is KtFile, is KtClassBody -> return true
            is KtBlockExpression -> return false
        }
        parent = parent.parent
    }
    return true
}

/**
 * Adds a code lens for references to a declaration if there are more than just the declaration itself.
 *
 * @param declaration The function or property to find references to
 * @param filePath The path to the file containing the declaration
 * @param uri The URI of the file
 * @param content The content of the file
 * @param sp The source path for finding references across the project
 * @param codeLenses The list to add the code lens to
 */
private fun addReferenceCodeLens(
    declaration: KtNamedDeclaration,
    filePath: Path,
    uri: String,
    content: String,
    sp: SourcePath,
    codeLenses: MutableList<CodeLens>
) {
    val loc = location(declaration) ?: return
    val startOffset = offset(content, loc.range.start.line, loc.range.start.character)
    val references = findReferences(filePath, startOffset, sp, forceFresh = false)

    // Only show if there's more than just the declaration
    if (references.size > 1) {
        codeLenses.add(CodeLens(
            loc.range,
            Command(
                "Show ${references.size} References",
                "kotlin.showReferences",
                listOf(uri, loc.range.start.line, loc.range.start.character)
            ),
            null
        ))
    }
}
