package org.javacs.kt.references

// TODO: Refactor - file has too many functions (34), split into smaller modules by reference type
// See detekt TooManyFunctions threshold exception

import org.eclipse.lsp4j.*
import org.javacs.kt.LOG
import org.javacs.kt.SourcePath
import org.javacs.kt.position.location
import org.javacs.kt.util.emptyResult
import org.javacs.kt.util.findParent
import org.javacs.kt.util.preOrderTraversal
import org.javacs.kt.util.toPath
import org.javacs.kt.CompiledFile
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.lexer.KtSingleValueToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.util.OperatorNameConventions
import java.nio.file.Path

/**
 * Finds locations for all references to the declaration at the given cursor position.
 *
 * Resolves the declaration under [cursor] in [file], then returns all reference locations
 * found in the current source snapshot [sp].
 *
 * @param file file containing the cursor position
 * @param cursor character offset within [file]
 * @param sp source snapshot used for resolution and reference lookup
 * @return sorted list of reference locations, or an empty list if no declaration is found
 */
fun findReferences(file: Path, cursor: Int, sp: SourcePath, forceFresh: Boolean = true): List<Location> {
    return doFindReferences(file, cursor, sp, forceFresh)
            .map { location(it) }
            .filterNotNull()
            .toList()
            .sortedWith(compareBy({ it.uri }, { it.range.start.line }))
}

/**
 * Finds locations for all references to [declaration].
 *
 * Uses the current [SourcePath] to resolve references and returns the corresponding
 * locations.
 *
 * @param declaration declaration whose references should be found
 * @param sp source snapshot used for reference lookup
 * @return sorted list of reference locations
 */
fun findReferences(declaration: KtNamedDeclaration, sp: SourcePath): List<Location> {
    return doFindReferences(declaration, sp)
        .map { location(it) }
        .filterNotNull()
        .toList()
        .sortedWith(compareBy({ it.uri }, { it.range.start.line }))
}

private fun doFindReferences(file: Path, cursor: Int, sp: SourcePath, forceFresh: Boolean = true): Collection<KtElement> {
    val recover = sp.currentVersion(file.toUri())
    val element = recover.elementAtPoint(cursor)?.findParent<KtNamedDeclaration>()
        ?: return emptyResult("No declaration at ${recover.describePosition(cursor)}")
    return doFindReferences(element, sp, forceFresh)
}

private fun doFindReferences(element: KtNamedDeclaration, sp: SourcePath, forceFresh: Boolean = true): Collection<KtElement> {
    val recover = sp.currentVersion(element.containingFile.toPath().toUri())
    val declaration = recover.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, element]
        ?: return emptyResult("Declaration ${element.fqName} has no descriptor")

    val (maybesPaths, recompile) = compileCandidateFiles(element, declaration, sp, forceFresh)
    LOG.debug("Scanning {} files for references to {}", maybesPaths.size, element.fqName)
    val refTargets = recompile.getSliceContents(BindingContext.REFERENCE_TARGET)
    LOG.debug { "REFERENCE_TARGET has ${refTargets.size} entries for ${element.fqName} (candidates=${maybesPaths.size})" }

    return when {
        isComponent(declaration) -> findAllComponentReferences(element, recompile) + findAllNameReferences(element, recompile)
        isIterator(declaration) -> findAllIteratorReferences(element, recompile) + findAllNameReferences(element, recompile)
        isPropertyDelegate(declaration) -> findAllDelegateReferences(element, recompile) + findAllNameReferences(element, recompile)
        else -> findAllNameReferences(element, recompile)
    }
}

/**
 * Compiles all files that may contain references to [descriptor], ensuring the
 * declaration's own file is included even when the dependency tracker hasn't
 * indexed it yet.
 *
 * @return the compiled [BindingContext] and the resolved candidate file [Path]s
 */
internal fun compileCandidateFiles(declaration: KtNamedDeclaration, descriptor: DeclarationDescriptor, sp: SourcePath, forceFresh: Boolean = true): Pair<List<Path>, BindingContext> {
    val declarationUri = declaration.containingFile.toPath().toUri()
    val maybes = possibleReferences(descriptor, sp).toMutableList()
    if (maybes.none { it.toPath().toUri() == declarationUri }) {
        sp.tryParsedFile(declarationUri)?.let { maybes.add(it) }
    }
    val maybesPaths = maybes.map { it.toPath() }
    val recompile = sp.compileFiles(maybesPaths.map(Path::toUri), forceFresh = forceFresh)
    return Pair(maybesPaths, recompile)
}

/**
 * Finds references to the named declaration in the given file. The declaration may or may not reside in another file.
 *
 * @returns ranges of references in the file. Empty list if none are found
 */
fun findReferencesToDeclarationInFile(declaration: KtNamedDeclaration, file: CompiledFile): List<Range> {
    val descriptor = file.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration] ?: return emptyResult("Declaration ${declaration.fqName} has no descriptor")
    val bindingContext = file.compile
    val targetFilePath = file.parse.containingFile.toPath()

    val references = when {
        isComponent(descriptor) -> findComponentReferences(declaration, bindingContext, targetFilePath) + findNameReferences(declaration, bindingContext, targetFilePath)
        isIterator(descriptor) -> findIteratorReferences(declaration, bindingContext, targetFilePath) + findNameReferences(declaration, bindingContext, targetFilePath)
        isPropertyDelegate(descriptor) -> findDelegateReferences(declaration, bindingContext, targetFilePath) + findNameReferences(declaration, bindingContext, targetFilePath)
        else -> findNameReferences(declaration, bindingContext, targetFilePath)
    }

    return references.map {
        location(it)?.range
    }.filterNotNull()
     .sortedWith(compareBy { it.start.line })
}

/**
 * Finds references to a declaration within a single file.
 * Used by document highlight, which only shows references in the currently open file.
 *
 * @param element The declaration to find references to
 * @param recompile The binding context containing resolved references
 * @param targetFilePath The path of the file to search within
 */
private fun findNameReferences(element: KtNamedDeclaration, recompile: BindingContext, targetFilePath: Path): List<KtElement> {
    val references = recompile.getSliceContents(BindingContext.REFERENCE_TARGET)

    return references.filter { (refExpr, descriptor) ->
        matchesReference(descriptor, element) && refExpr.containingFile.toPath() == targetFilePath
    }.map { it.key }
}

/**
 * Finds references to a declaration across all files in the compilation.
 * Used by find references, which shows references across the entire project.
 *
 * @param element The declaration to find references to
 * @param recompile The binding context containing resolved references
 */
private fun findAllNameReferences(element: KtNamedDeclaration, recompile: BindingContext): List<KtElement> {
    val references = recompile.getSliceContents(BindingContext.REFERENCE_TARGET)

    return references.filter { (_, descriptor) ->
        matchesReference(descriptor, element)
    }.map { it.key }
}

/**
 * Finds delegate property references within a single file.
 * Used by document highlight, which only shows references in the currently open file.
 */
private fun findDelegateReferences(element: KtNamedDeclaration, recompile: BindingContext, targetFilePath: Path): List<KtElement> {
    val references = recompile.getSliceContents(BindingContext.DELEGATED_PROPERTY_RESOLVED_CALL)

    return references
            .filter { matchesReference(it.value.candidateDescriptor, element) && it.value.call.callElement?.containingFile?.toPath() == targetFilePath }
            .map { it.value.call.callElement }
}

/**
 * Finds delegate property references across all files in the compilation.
 * Used by find references, which shows references across the entire project.
 */
private fun findAllDelegateReferences(element: KtNamedDeclaration, recompile: BindingContext): List<KtElement> {
    val references = recompile.getSliceContents(BindingContext.DELEGATED_PROPERTY_RESOLVED_CALL)

    return references
            .filter { matchesReference(it.value.candidateDescriptor, element) }
            .map { it.value.call.callElement }
}

/**
 * Finds iterator references within a single file.
 * Used by document highlight, which only shows references in the currently open file.
 */
private fun findIteratorReferences(element: KtNamedDeclaration, recompile: BindingContext, targetFilePath: Path): List<KtElement> {
    val references = recompile.getSliceContents(BindingContext.LOOP_RANGE_ITERATOR_RESOLVED_CALL)

    return references
            .filter { matchesReference(it.value.candidateDescriptor, element) && it.value.call.callElement?.containingFile?.toPath() == targetFilePath }
            .map { it.value.call.callElement }
}

/**
 * Finds iterator references across all files in the compilation.
 * Used by find references, which shows references across the entire project.
 */
private fun findAllIteratorReferences(element: KtNamedDeclaration, recompile: BindingContext): List<KtElement> {
    val references = recompile.getSliceContents(BindingContext.LOOP_RANGE_ITERATOR_RESOLVED_CALL)

    return references
            .filter { matchesReference(it.value.candidateDescriptor, element) }
            .map { it.value.call.callElement }
}

/**
 * Finds component references within a single file.
 * Used by document highlight, which only shows references in the currently open file.
 */
private fun findComponentReferences(element: KtNamedDeclaration, recompile: BindingContext, targetFilePath: Path): List<KtElement> {
    val references = recompile.getSliceContents(BindingContext.COMPONENT_RESOLVED_CALL)

    return references
            .filter { matchesReference(it.value.candidateDescriptor, element) && it.value.call.callElement?.containingFile?.toPath() == targetFilePath }
            .map { it.value.call.callElement }
}

/**
 * Finds component references across all files in the compilation.
 * Used by find references, which shows references across the entire project.
 */
private fun findAllComponentReferences(element: KtNamedDeclaration, recompile: BindingContext): List<KtElement> {
    val references = recompile.getSliceContents(BindingContext.COMPONENT_RESOLVED_CALL)

    return references
            .filter { matchesReference(it.value.candidateDescriptor, element) }
            .map { it.value.call.callElement }
}

// TODO use imports to limit search
internal fun possibleReferences(declaration: DeclarationDescriptor, sp: SourcePath): Set<KtFile> {
    // Use import-based filtering for better performance
    val fqName = declaration.fqNameSafe

    // For class members (properties, functions inside a class), the containing declaration
    // is the class, not a package fragment. We need to get the class's package.
    val packageFqName = when (val container = declaration.containingDeclaration) {
        is PackageFragmentDescriptor -> container.fqName
        is ClassDescriptor -> container.fqNameSafe.parent()
        else -> fqName.parent() // fallback for other cases
    }

    // Get files in the same package or importing from it
    val importBasedCandidates = sp.dependencyTracker.filesInPackageOrImporting(packageFqName)
        .mapNotNull { uri -> sp.tryParsedFile(uri) }
        .toSet()

    // Use import-based results if available, otherwise fall back to name-based heuristics
    val baseCandidates = importBasedCandidates.ifEmpty {
        emptySet()
    }

    // Add special cases for operators and other patterns
    val additionalCandidates = mutableSetOf<KtFile>()
    if (declaration is ClassConstructorDescriptor) {
        additionalCandidates.addAll(possibleNameReferences(declaration.constructedClass.name, sp))
    }
    if (isComponent(declaration)) {
        additionalCandidates.addAll(possibleComponentReferences(sp))
        additionalCandidates.addAll(possibleNameReferences(declaration.name, sp))
    }
    if (isPropertyDelegate(declaration)) {
        additionalCandidates.addAll(hasPropertyDelegates(sp))
        additionalCandidates.addAll(possibleNameReferences(declaration.name, sp))
    }
    if (isGetSet(declaration)) {
        additionalCandidates.addAll(possibleGetSets(sp))
        additionalCandidates.addAll(possibleNameReferences(declaration.name, sp))
    }
    if (isIterator(declaration)) {
        additionalCandidates.addAll(hasForLoops(sp))
        additionalCandidates.addAll(possibleNameReferences(declaration.name, sp))
    }
    if (declaration is FunctionDescriptor && declaration.isOperator && declaration.name == OperatorNameConventions.INVOKE) {
        additionalCandidates.addAll(possibleInvokeReferences(declaration, sp))
        additionalCandidates.addAll(possibleNameReferences(declaration.name, sp))
    }
    if (declaration is FunctionDescriptor) {
        val operators = operatorNames(declaration.name)
        additionalCandidates.addAll(possibleTokenReferences(operators, sp))
        additionalCandidates.addAll(possibleNameReferences(declaration.name, sp))
    }

    // For non-function declarations (properties, classes, objects, enum entries),
    // add name-based candidates so files with usage references are included
    // in the compilation. FunctionDescriptor cases are already handled above.
    if (declaration !is FunctionDescriptor) {
        additionalCandidates.addAll(possibleNameReferences(declaration.name, sp))
    }

    return if (baseCandidates.isEmpty()) {
        // No import info available yet, use old heuristic
        additionalCandidates
    } else {
        // Combine import-based results with special cases
        baseCandidates + additionalCandidates
    }
}

internal fun isPropertyDelegate(declaration: DeclarationDescriptor) =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        (declaration.name == OperatorNameConventions.GET_VALUE || declaration.name == OperatorNameConventions.SET_VALUE)

internal fun hasPropertyDelegates(sp: SourcePath): Set<KtFile> =
        sp.all().filter(::hasPropertyDelegate).toSet()

fun hasPropertyDelegate(source: KtFile): Boolean =
        source.preOrderTraversal().filterIsInstance<KtPropertyDelegate>().any()

internal fun isIterator(declaration: DeclarationDescriptor) =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        declaration.name == OperatorNameConventions.ITERATOR

internal fun hasForLoops(sp: SourcePath): Set<KtFile> =
        sp.all().filter(::hasForLoop).toSet()

internal fun hasForLoop(source: KtFile): Boolean =
        source.preOrderTraversal().filterIsInstance<KtForExpression>().any()

internal fun isGetSet(declaration: DeclarationDescriptor) =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        (declaration.name == OperatorNameConventions.GET || declaration.name == OperatorNameConventions.SET)

internal fun possibleGetSets(sp: SourcePath): Set<KtFile> =
        sp.all().filter(::possibleGetSet).toSet()

internal fun possibleGetSet(source: KtFile) =
        source.preOrderTraversal().filterIsInstance<KtArrayAccessExpression>().any()

internal fun possibleInvokeReferences(declaration: FunctionDescriptor, sp: SourcePath) =
        sp.all().filter { possibleInvokeReference(declaration, it) }.toSet()

// TODO this is not very selective
internal fun possibleInvokeReference(@Suppress("UNUSED_PARAMETER") declaration: FunctionDescriptor, source: KtFile): Boolean =
        source.preOrderTraversal().filterIsInstance<KtCallExpression>().any()

internal fun isComponent(declaration: DeclarationDescriptor): Boolean =
        declaration is FunctionDescriptor &&
        declaration.isOperator &&
        OperatorNameConventions.COMPONENT_REGEX.matches(declaration.name.identifier)

internal fun possibleComponentReferences(sp: SourcePath): Set<KtFile> =
        sp.all().filter { possibleComponentReference(it) }.toSet()

internal fun possibleComponentReference(source: KtFile): Boolean =
        source.preOrderTraversal()
                .filterIsInstance<KtDestructuringDeclarationEntry>()
                .any()

internal fun possibleTokenReferences(find: List<KtSingleValueToken>, sp: SourcePath): Set<KtFile> =
        sp.all().filter { possibleTokenReference(find, it) }.toSet()

internal fun possibleTokenReference(find: List<KtSingleValueToken>, source: KtFile): Boolean =
        source.preOrderTraversal()
                .filterIsInstance<KtOperationReferenceExpression>()
                .any { it.operationSignTokenType in find }

internal fun possibleNameReferences(declaration: Name, sp: SourcePath): Set<KtFile> =
        sp.all().filter { possibleNameReference(declaration, it) }.toSet()

internal fun possibleNameReference(declaration: Name, source: KtFile): Boolean =
        source.preOrderTraversal()
                .filterIsInstance<KtSimpleNameExpression>()
                .any { it.getReferencedNameAsName() == declaration }

internal fun matchesReference(found: DeclarationDescriptor, search: KtNamedDeclaration): Boolean {
    val matched = if (found is ConstructorDescriptor && found.isPrimary)
        search is KtClass && found.constructedClass.fqNameSafe == search.fqName
    else
        found.findPsi() == search

    return matched
}

internal fun operatorNames(name: Name): List<KtSingleValueToken> =
        when (name) {
            OperatorNameConventions.EQUALS -> listOf(KtTokens.EQEQ)
            OperatorNameConventions.COMPARE_TO -> listOf(KtTokens.GT, KtTokens.LT, KtTokens.LTEQ, KtTokens.GTEQ)
            else -> {
                val token = OperatorConventions.UNARY_OPERATION_NAMES.inverse()[name] ?:
                            OperatorConventions.BINARY_OPERATION_NAMES.inverse()[name] ?:
                            OperatorConventions.ASSIGNMENT_OPERATIONS.inverse()[name] ?:
                            OperatorConventions.BOOLEAN_OPERATIONS.inverse()[name]
                listOfNotNull(token)
            }
        }
