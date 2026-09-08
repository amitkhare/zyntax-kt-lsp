package org.javacs.kt.signaturehelp

import org.eclipse.lsp4j.*
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.CompiledFile
import org.javacs.kt.completion.DECL_RENDERER
import org.javacs.kt.completion.identifierOverloads
import org.javacs.kt.completion.memberOverloads
import org.javacs.kt.docs.findDoc
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.findParent
import org.javacs.kt.util.nullResult
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.javacs.kt.LOG

private val async = AsyncExecutor

fun fetchSignatureHelpAt(
    file: CompiledFile,
    cursor: Int,
    classContentProvider: ClassContentProvider? = null,
    cp: CompilerClassPath? = null
): SignatureHelp? {
    val (signatures, activeSignature, activeParameter) = computeSignatureHelpAt(file, cursor, classContentProvider, cp)
        ?: return nullResult("No call around ${file.describePosition(cursor)}")
    return SignatureHelp(signatures, activeSignature, activeParameter)
}

/**
 * Returns the doc string of the first found CallableDescriptor
 *
 * Avoids fetching the SignatureHelp triplet due to an OutOfBoundsException that can occur due to the offset difference math.
 * When hovering, the cursor param is set to the doc offset where the mouse is hovering over, rather than where the actual cursor is,
 * hence this is seen to cause issues when slicing the param list string
 */
fun getDocString(
    file: CompiledFile,
    cursor: Int,
    classContentProvider: ClassContentProvider? = null,
    cp: CompilerClassPath? = null
): String {
    val signatures = getSignatures(file, cursor, classContentProvider, cp)
    if (signatures.isNullOrEmpty() || signatures[0].documentation == null)
        return ""
    return if (signatures[0].documentation.isLeft) signatures[0].documentation.left else ""
}

/**
 * Computes signature help information at the given cursor position.
 *
 * Finds all callable candidates (functions/methods) at the cursor location,
 * generates signature information for each, and determines the active signature
 * and parameter based on the current call context.
 *
 * @param file The compiled file to analyze
 * @param cursor The cursor offset position
 * @param classContentProvider Optional provider for external class content (for documentation)
 * @param cp Optional compiler classpath for external source lookup
 * @return Triple of (all signatures, active signature index, active parameter index),
 *         or null if no call expression found at cursor
 */
@Suppress("ReturnCount")
private fun computeSignatureHelpAt(
    file: CompiledFile,
    cursor: Int,
    classContentProvider: ClassContentProvider? = null,
    cp: CompilerClassPath? = null
): Triple<List<SignatureInformation>, Int?, Int?>? {
    val call = file.parseAtPoint(cursor)?.findParent<KtCallExpression>() ?: return null
    val candidates = candidates(call, file)
    if (candidates.isEmpty()) {
        return null
    }
    val activeSignature = activeSignature(call, candidates)
    val activeParameter = activeParameter(call, cursor)
    val signatures = async.ioMap(candidates) { toSignature(it, classContentProvider, cp) }

    return Triple(signatures, activeSignature, activeParameter)
}

private fun getSignatures(
    file: CompiledFile,
    cursor: Int,
    classContentProvider: ClassContentProvider? = null,
    cp: CompilerClassPath? = null
): List<SignatureInformation>? {
    val call = file.parseAtPoint(cursor)?.findParent<KtCallExpression>() ?: return null
    val candidates = candidates(call, file)
    return async.ioMap(candidates) { toSignature(it, classContentProvider, cp) }
}

private fun toSignature(
    desc: CallableDescriptor,
    classContentProvider: ClassContentProvider? = null,
    cp: CompilerClassPath? = null
): SignatureInformation {
    val label = DECL_RENDERER.render(desc)
    val params = desc.valueParameters.map { toParameter(it, classContentProvider, cp) }
    val docstring = docstring(desc, classContentProvider, cp)

    return SignatureInformation(label, docstring, params)
}

private fun toParameter(
    param: ValueParameterDescriptor,
    classContentProvider: ClassContentProvider? = null,
    cp: CompilerClassPath? = null
): ParameterInformation {
    val label = DECL_RENDERER.renderValueParameters(listOf(param), false)
    check(label.length >= 2) { "Expected parenthesized parameter list but got: $label" }
    val removeParens = label.substring(1, label.length - 1)
    val docstring = docstring(param, classContentProvider, cp)

    return ParameterInformation(removeParens, docstring)
}

private fun docstring(
    declaration: DeclarationDescriptorWithSource,
    classContentProvider: ClassContentProvider? = null,
    cp: CompilerClassPath? = null
): String {
    val doc = findDoc(declaration, classContentProvider, cp) ?: return ""

    return doc.trim()
}

private fun candidates(call: KtCallExpression, file: CompiledFile): List<CallableDescriptor> {
    val target = checkNotNull(call.calleeExpression) { "KtCallExpression without calleeExpression" }
    // For foo.bar(), target.text is "bar" (the method name), not "foo.bar"
    val identifier = target.text
    val dotParent = target.findParent<KtDotQualifiedExpression>()
    if (dotParent != null) {
        val type = file.typeAtPoint(dotParent.receiverExpression.startOffset) ?: return emptyList()

        return memberOverloads(type, identifier).toList()
    }
    val idParent = target.findParent<KtNameReferenceExpression>()
    if (idParent != null) {
        val scope = file.scopeAtPoint(idParent.startOffset) ?: return emptyList()

        return identifierOverloads(scope, identifier).toList()
    }
    return emptyList()
}

private fun activeSignature(call: KtCallExpression, candidates: List<CallableDescriptor>): Int? {
    val activeIndex = candidates.indexOfFirst { isCompatibleWith(call, it) }
    if (activeIndex < 0) {
        LOG.warn("No activeSignature found, omitting from SignatureHelp response.")
        return null
    }
    return activeIndex
}

private fun isCompatibleWith(call: KtCallExpression, candidate: CallableDescriptor): Boolean {
    val argumentList = call.valueArgumentList ?: return true
    val nArguments = argumentList.text.count { it == ',' } + 1
    if (nArguments > candidate.valueParameters.size)
        return false

    for (arg in call.valueArguments) {
        if (arg.isNamed()) {
            if (candidate.valueParameters.none { arg.name == it.name.identifier })
                return false
        }
    }

    return true
}

@Suppress("ReturnCount")
private fun activeParameter(call: KtCallExpression, cursor: Int): Int? {
    val args = call.valueArgumentList ?: return null
    val text = args.text
    if (text.length == 2)
        return 0
    val min = args.textRange.startOffset.coerceAtMost(cursor)
    val max = args.textRange.startOffset.coerceAtLeast(cursor)
    val beforeCursor = text.subSequence(0, max-min)
    return beforeCursor.count { it == ','}
}
