package org.javacs.kt.callhierarchy

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.*
import org.javacs.kt.LOG
import org.javacs.kt.SourcePath
import org.javacs.kt.position.offset
import org.javacs.kt.position.range
import org.javacs.kt.position.toURIString
import org.javacs.kt.references.compileCandidateFiles
import org.javacs.kt.references.matchesReference
import org.javacs.kt.util.emptyResult
import org.javacs.kt.util.findParent
import org.javacs.kt.util.nullResult
import org.javacs.kt.util.parseURI
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import java.nio.file.Path

fun prepareCallHierarchy(file: Path, cursor: Int, sp: SourcePath): List<CallHierarchyItem>? {
    val recover = sp.currentVersion(file.toUri())
    val descriptor = resolveDescriptorAt(cursor, recover) ?: return null

    if (descriptor !is FunctionDescriptor && descriptor !is ConstructorDescriptor && descriptor !is PropertyDescriptor) {
        return nullResult("Declaration ${descriptor.fqNameSafe} is not a callable")
    }

    val psi = descriptor.findPsi() as? KtNamedDeclaration ?: return null
    // Constructors don't have a nameIdentifier (their name is <init>), so use
    // the constructor keyword or the PSI element itself as the selection range.
    val nameIdentifier = psi.nameIdentifier ?: psi
    val fileUri = psi.containingFile.toURIString()
    val content = try {
        sp.content(parseURI(fileUri))
    } catch (_: Exception) {
        return null
    }

    val kind = kindForDescriptor(descriptor)
    // Constructors are named <init> by the compiler; display the class name instead.
    val displayName = when (descriptor) {
        is ConstructorDescriptor -> descriptor.constructedClass.name.asString()
        else -> descriptor.name.asString()
    }

    return listOf(
        CallHierarchyItem(
            displayName,
            kind,
            fileUri,
            range(content, psi.textRange),
            range(content, nameIdentifier.textRange)
        )
    )
}

fun incomingCalls(item: CallHierarchyItem, sp: SourcePath): List<CallHierarchyIncomingCall> {
    val (declaration, descriptor) = resolveItem(item, sp) ?: return emptyResult("Could not resolve item to declaration")
    val (maybesPaths, recompile) = compileCandidateFiles(declaration, descriptor, sp)
    LOG.debug("Scanning {} files for incoming calls to {}", maybesPaths.size, declaration.fqName)
    val refTargets = recompile.getSliceContents(BindingContext.REFERENCE_TARGET)

    val incomingByCaller = mutableMapOf<KtNamedDeclaration, MutableList<Range>>()

    val matchingRefs = refTargets.asSequence()
        .filter { matchesReference(it.value, declaration) && (descriptor is PropertyDescriptor || isCall(it.key)) }
        .toList()

    for ((refExpr, _) in matchingRefs) {
        val caller = findContainingDeclaration(refExpr)
        if (caller != null) {
            incomingByCaller
                .getOrPut(caller) { mutableListOf() }
                .add(range(refExpr.containingFile.text, refExpr.textRange))
        }
    }

    // Also check special binding context slices for component, delegate, and iterator calls
    for ((_, resolvedCall) in recompile.getSliceContents(BindingContext.COMPONENT_RESOLVED_CALL)) {
        addIncomingFromResolvedCall(resolvedCall, declaration, incomingByCaller)
    }
    for ((_, resolvedCall) in recompile.getSliceContents(BindingContext.DELEGATED_PROPERTY_RESOLVED_CALL)) {
        addIncomingFromResolvedCall(resolvedCall, declaration, incomingByCaller)
    }
    for ((_, resolvedCall) in recompile.getSliceContents(BindingContext.LOOP_RANGE_ITERATOR_RESOLVED_CALL)) {
        addIncomingFromResolvedCall(resolvedCall, declaration, incomingByCaller)
    }
    // Constructor delegation (this(...) / super(...)) is recorded in its own slice.
    for ((_, resolvedCall) in recompile.getSliceContents(BindingContext.CONSTRUCTOR_RESOLVED_DELEGATION_CALL)) {
        addConstructorDelegationIncoming(resolvedCall, declaration, incomingByCaller)
    }

    return incomingByCaller.mapNotNull { (caller, ranges) ->
        val callerDescriptor = recompile[BindingContext.DECLARATION_TO_DESCRIPTOR, caller]
            ?: return@mapNotNull null
        CallHierarchyIncomingCall(toCallHierarchyItem(caller, callerDescriptor, sp), ranges)
    }
}

private fun addIncomingFromResolvedCall(
    resolvedCall: ResolvedCall<*>,
    declaration: KtNamedDeclaration,
    incomingByCaller: MutableMap<KtNamedDeclaration, MutableList<Range>>
) {
    if (matchesReference(resolvedCall.candidateDescriptor, declaration)) {
        val callElement = resolvedCall.call.callElement
        val caller = findContainingDeclaration(callElement)
        if (caller != null) {
            incomingByCaller
                .getOrPut(caller) { mutableListOf() }
                .add(range(callElement.containingFile.text, callElement.textRange))
        }
    }
}

/**
 * Adds an incoming call for constructor delegation (this(...) / super(...)) when
 * the delegated-to constructor matches [declaration]. The caller is the
 * delegating constructor's containing declaration.
 */
private fun addConstructorDelegationIncoming(
    resolvedCall: ResolvedCall<ConstructorDescriptor>,
    declaration: KtNamedDeclaration,
    incomingByCaller: MutableMap<KtNamedDeclaration, MutableList<Range>>
) {
    val targetDesc = resolvedCall.candidateDescriptor
    val matches = when (declaration) {
        is KtClass -> targetDesc.constructedClass.fqNameSafe == declaration.fqName
        is KtSecondaryConstructor -> targetDesc.findPsi() == declaration
        else -> false
    }
    if (!matches) return

    val callElement = resolvedCall.call.callElement
    val caller = findContainingDeclaration(callElement)
    if (caller != null) {
        incomingByCaller
            .getOrPut(caller) { mutableListOf() }
            .add(range(callElement.containingFile.text, callElement.textRange))
    }
}

private fun collectOutgoingFromSlice(
    slice: Map<*, ResolvedCall<*>>,
    bodyRanges: List<TextRange>,
    declarationPath: Path
): Map<DeclarationDescriptor, MutableList<Range>> {
    val result = mutableMapOf<DeclarationDescriptor, MutableList<Range>>()
    for ((_, resolvedCall) in slice) {
        val callElement = resolvedCall.call.callElement
        val targetDesc = resolvedCall.candidateDescriptor
        val isCallable = targetDesc is FunctionDescriptor || targetDesc is ConstructorDescriptor
        val sameFile = callElement.containingFile.toPath() == declarationPath
        val inBody = bodyRanges.any { range -> callElement.textRange in range }
        if (isCallable && sameFile && inBody) {
            result
                .getOrPut(targetDesc) { mutableListOf() }
                .add(range(callElement.containingFile.text, callElement.textRange))
        }
    }
    return result
}

/**
 * Collects outgoing constructor delegation calls (this(...) / super(...)) for the
 * given PSI declaration. The delegation call site lives in the super-type list, not
 * the constructor body, so it is scoped to [declarationPath] directly rather than
 * checked against body ranges.
 */
private fun collectConstructorDelegationOutgoing(
    slice: Map<ConstructorDescriptor, ResolvedCall<ConstructorDescriptor>>,
    declarationPsi: KtNamedDeclaration,
    declarationPath: Path
): Map<DeclarationDescriptor, MutableList<Range>> {
    val result = mutableMapOf<DeclarationDescriptor, MutableList<Range>>()
    for ((delegatingCtor, resolvedCall) in slice) {
        if (delegationBelongsTo(delegatingCtor, declarationPsi) &&
            resolvedCall.call.callElement.containingFile.toPath() == declarationPath
        ) {
            val callElement = resolvedCall.call.callElement
            val targetDesc = resolvedCall.candidateDescriptor
            result
                .getOrPut(targetDesc) { mutableListOf() }
                .add(range(callElement.containingFile.text, callElement.textRange))
        }
    }
    return result
}

/**
 * True when [delegatingCtor] is a constructor that belongs to [declarationPsi].
 * Compares by PSI rather than descriptor identity because the delegation slice
 * comes from a recompiled context with fresh descriptor instances.
 */
private fun delegationBelongsTo(delegatingCtor: ConstructorDescriptor, declarationPsi: KtNamedDeclaration): Boolean =
    when (declarationPsi) {
        is KtClassOrObject -> delegatingCtor.findPsi()?.findParent<KtClassOrObject>() == declarationPsi
        is KtSecondaryConstructor -> delegatingCtor.findPsi() == declarationPsi
        else -> false
    }

fun outgoingCalls(item: CallHierarchyItem, sp: SourcePath): List<CallHierarchyOutgoingCall> {
    val (declaration, descriptor) = resolveItem(item, sp) ?: return emptyResult("Could not resolve item to declaration")

    val bodyRanges = bodyRangesForDeclaration(declaration)
        ?: return emptyResult("Declaration has no searchable body")

    val (maybesPaths, recompile) = compileCandidateFiles(declaration, descriptor, sp)
    LOG.debug("Scanning {} files for outgoing calls from {}", maybesPaths.size, declaration.fqName)
    val refTargets = recompile.getSliceContents(BindingContext.REFERENCE_TARGET)

    val outgoingByCallee = mutableMapOf<DeclarationDescriptor, MutableList<Range>>()

    val declarationPath = declaration.containingFile.toPath()
    val outgoingRefs = refTargets.asSequence()
        .filter { it.key.containingFile.toPath() == declarationPath }
        .filter { ref -> bodyRanges.any { range -> ref.key.textRange in range } } // only refs inside this declaration's body
        .filter { (it.value is FunctionDescriptor || it.value is ConstructorDescriptor) && isCall(it.key) }
        .toList()

    for ((refExpr, targetDesc) in outgoingRefs) {
        outgoingByCallee
            .getOrPut(targetDesc) { mutableListOf() }
            .add(range(refExpr.containingFile.text, refExpr.textRange))
    }

    // Also check special binding context slices for component, delegate, and iterator calls
    collectOutgoingFromSlice(
        recompile.getSliceContents(BindingContext.COMPONENT_RESOLVED_CALL),
        bodyRanges, declarationPath
    ).forEach { (desc, ranges) ->
        outgoingByCallee.getOrPut(desc) { mutableListOf() }.addAll(ranges)
    }
    collectOutgoingFromSlice(
        recompile.getSliceContents(BindingContext.DELEGATED_PROPERTY_RESOLVED_CALL),
        bodyRanges, declarationPath
    ).forEach { (desc, ranges) ->
        outgoingByCallee.getOrPut(desc) { mutableListOf() }.addAll(ranges)
    }
    collectOutgoingFromSlice(
        recompile.getSliceContents(BindingContext.LOOP_RANGE_ITERATOR_RESOLVED_CALL),
        bodyRanges, declarationPath
    ).forEach { (desc, ranges) ->
        outgoingByCallee.getOrPut(desc) { mutableListOf() }.addAll(ranges)
    }
    // Constructor delegation (this(...) / super(...)) lives in the super-type list,
    // outside the constructor body, so it is collected separately scoped to this declaration.
    collectConstructorDelegationOutgoing(
        recompile.getSliceContents(BindingContext.CONSTRUCTOR_RESOLVED_DELEGATION_CALL),
        declaration, declarationPath
    ).forEach { (desc, ranges) ->
        outgoingByCallee.getOrPut(desc) { mutableListOf() }.addAll(ranges)
    }

    return outgoingByCallee.mapNotNull { (calleeDesc, ranges) ->
        val calleePsi = calleeDesc.findPsi() as? KtNamedDeclaration ?: return@mapNotNull null
        val calleeItem = toCallHierarchyItem(calleePsi, calleeDesc, sp)
        CallHierarchyOutgoingCall(calleeItem, ranges)
    }
}

private fun bodyRangesForDeclaration(declaration: KtNamedDeclaration): List<TextRange>? {
    return when (declaration) {
        is KtNamedFunction -> declaration.bodyExpression?.let { listOf(it.textRange) }
        is KtProperty -> bodyRangesForProperty(declaration)
        is KtClass -> bodyRangesForClass(declaration)
        is KtSecondaryConstructor -> bodyRangesForSecondaryConstructor(declaration)
        else -> null
    }
}

private fun bodyRangesForSecondaryConstructor(declaration: KtSecondaryConstructor): List<TextRange>? {
    val ranges = mutableListOf<TextRange>()
    declaration.bodyExpression?.let { ranges.add(it.textRange) }
    // Include the constructor delegation call (super()/this()) so outgoing
    // calls from the delegation are found even when the body is empty.
    declaration.getDelegationCall()?.let { ranges.add(it.textRange) }
    return ranges.ifEmpty { null }
}

private fun bodyRangesForProperty(declaration: KtProperty): List<TextRange>? {
    val ranges = mutableListOf<TextRange>()
    declaration.initializer?.let { ranges.add(it.textRange) }
    declaration.delegateExpression?.let { ranges.add(it.textRange) }
    declaration.getter?.bodyExpression?.let { ranges.add(it.textRange) }
    declaration.setter?.bodyExpression?.let { ranges.add(it.textRange) }
    return ranges.ifEmpty { null }
}

private fun bodyRangesForClass(declaration: KtClass): List<TextRange>? {
    val ranges = mutableListOf<TextRange>()
    for (init in declaration.getAnonymousInitializers()) {
        init.body?.let { ranges.add(it.textRange) }
    }
    for (ctor in declaration.getSecondaryConstructors()) {
        ctor.bodyExpression?.let { ranges.add(it.textRange) }
    }
    declaration.primaryConstructor?.let { primaryCtor ->
        for (param in primaryCtor.valueParameters) {
            param.defaultValue?.let { ranges.add(it.textRange) }
        }
    }
    for (entry in declaration.superTypeListEntries) {
        if (entry is KtSuperTypeCallEntry) {
            ranges.add(entry.textRange)
        }
    }
    for (member in declaration.body?.declarations.orEmpty()) {
        if (member is KtProperty) {
            member.initializer?.let { ranges.add(it.textRange) }
            member.delegateExpression?.let { ranges.add(it.textRange) }
        }
    }
    return ranges.ifEmpty { null }
}

private fun resolveDescriptorAt(cursor: Int, file: org.javacs.kt.CompiledFile): DeclarationDescriptor? {
    // Reference site: cursor on a call expression pointing to a declaration.
    // Tried first so cursor on a call site (e.g. `b()` inside `fun a()`)
    // resolves to the callee `b`, not the enclosing `a`.
    val fromReference = file.referenceAtPoint(cursor)
    if (fromReference != null) return fromReference.second

    // Declaration site: cursor on a function/property/constructor name.
    val decl = file.elementAtPoint(cursor)?.findParent<KtNamedDeclaration>() ?: return null
    return file.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, decl]
}

private fun resolveItem(item: CallHierarchyItem, sp: SourcePath): Pair<KtNamedDeclaration, DeclarationDescriptor>? {
    val uri = parseURI(item.uri)
    val file = sp.currentVersion(uri)
    val content = sp.content(uri)
    val cursor = offset(content, item.selectionRange.start)

    val decl = file.elementAtPoint(cursor)?.findParent<KtNamedDeclaration>()
        ?: return nullResult("Could not find declaration at ${item.uri}:${item.selectionRange.start.line}")

    val desc = file.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, decl]
        ?: return nullResult("Declaration has no descriptor")

    return Pair(decl, desc)
}

private fun toCallHierarchyItem(declaration: KtNamedDeclaration, descriptor: DeclarationDescriptor, sp: SourcePath): CallHierarchyItem {
    val psi = (descriptor.findPsi() as? KtNamedDeclaration) ?: declaration
    val nameIdentifier = psi.nameIdentifier ?: psi
    val fileUri = psi.containingFile.toURIString()
    val content = try { sp.content(parseURI(fileUri)) } catch (_: Exception) { "" }
    // Constructors are named <init> by the compiler; display the class name instead.
    val displayName = when (descriptor) {
        is ConstructorDescriptor -> descriptor.constructedClass.name.asString()
        else -> descriptor.name.asString()
    }

    return CallHierarchyItem(
        displayName,
        kindForDescriptor(descriptor),
        fileUri,
        range(content, psi.textRange),
        range(content, nameIdentifier.textRange)
    )
}

private fun kindForDescriptor(descriptor: DeclarationDescriptor): SymbolKind = when (descriptor) {
    is ConstructorDescriptor -> SymbolKind.Constructor
    is FunctionDescriptor if descriptor.isOperator -> SymbolKind.Operator
    is FunctionDescriptor -> SymbolKind.Function
    is PropertyDescriptor -> SymbolKind.Property
    else -> SymbolKind.Function
}

private fun findContainingDeclaration(element: PsiElement): KtNamedDeclaration? {
    return element.findParent<KtNamedFunction>()
        ?: element.findParent<KtProperty>()
        ?: element.findParent<KtNamedDeclaration>()
}

private fun isCall(refExpr: KtElement): Boolean {
    val parent = refExpr.parent
    if (parent is KtCallExpression && parent.calleeExpression == refExpr) return true
    if (refExpr is KtCallExpression) return true
    return false
}
