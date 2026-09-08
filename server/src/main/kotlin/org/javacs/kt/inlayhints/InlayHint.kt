package org.javacs.kt.inlayhints

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.javacs.kt.CompiledFile
import org.javacs.kt.InlayHintsConfiguration
import org.javacs.kt.completion.DECL_RENDERER
import org.javacs.kt.position.range
import org.javacs.kt.util.preOrderTraversal
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.model.ResolvedValueArgument
import org.jetbrains.kotlin.resolve.calls.model.VarargValueArgument
import org.jetbrains.kotlin.resolve.calls.smartcasts.getKotlinTypeForComparison
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.util.isSingleUnderscore
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.error.ErrorType

private fun PsiElement.determineType(ctx: BindingContext): KotlinType? =
    when (this) {
        is KtNamedFunction -> {
            val descriptor = ctx[BindingContext.FUNCTION, this]
            descriptor?.returnType
        }
        is KtCallExpression -> {
            this.getKotlinTypeForComparison(ctx)
        }
        is KtParameter -> {
            if (this.isLambdaParameter and (this.typeReference == null)) {
                val descriptor = ctx[BindingContext.DECLARATION_TO_DESCRIPTOR, this]
                check(descriptor is CallableDescriptor) {
                    "Expected CallableDescriptor for lambda parameter, got ${descriptor?.javaClass?.simpleName}"
                }
                descriptor.returnType
            } else null
        }
        is KtDestructuringDeclarationEntry -> {
            //skip unused variable denoted by underscore
            //https://kotlinlang.org/docs/destructuring-declarations.html#underscore-for-unused-variables
            if (this.isSingleUnderscore) {
                null
            } else {
                val resolvedCall = ctx[BindingContext.COMPONENT_RESOLVED_CALL, this]
                resolvedCall?.resultingDescriptor?.returnType
            }
        }
        is KtProperty -> {
            val type = this.getKotlinTypeForComparison(ctx)
            if (type is ErrorType) null else type
        }
        else -> null
    }

private fun PsiElement.hintBuilder(kind: InlayKind, file: CompiledFile, label: String? = null): InlayHint? {
    val element = when(this) {
        is KtFunction -> checkNotNull(this.valueParameterList) { "KtFunction without valueParameterList" }.originalElement
        is PsiNameIdentifierOwner -> this.nameIdentifier
        else -> this
    } ?: return null

    val range = range(file.parse.text, element.textRange)

    val hint = when(kind) {
        InlayKind.ParameterHint -> InlayHint(range.start, Either.forLeft("$label:"))
        else ->
            this.determineType(file.compile) ?.let {
                InlayHint(range.end, Either.forLeft(DECL_RENDERER.renderType(it)))
            } ?: return null
    }
    hint.kind = kind.base
    hint.paddingRight = true
    hint.paddingLeft = true
    return hint
}

private fun callableArgNameHints(
    acc: MutableList<InlayHint>,
    callExpression: KtCallExpression,
    file: CompiledFile,
    config: InlayHintsConfiguration
) {
    if (!config.parameterHints) return

    //hints are not rendered for argument of type lambda expression i.e. list.map { it }
    if (callExpression.getChildOfType<KtLambdaArgument>() != null) {
        return
    }

    val resolvedCall = callExpression.getResolvedCall(file.compile)
    val entries = resolvedCall?.valueArguments?.entries ?: return

    val hints = entries.mapNotNull { (t, u) ->
        val valueArg = u.arguments.firstOrNull()
        if (valueArg != null && !valueArg.isNamed()) {
            val label = getArgLabel(t.name, u)
            valueArg.asElement().hintBuilder(InlayKind.ParameterHint, file, label)
        } else null
    }
    acc.addAll(hints)
}

private fun getArgLabel(name: Name, arg: ResolvedValueArgument) =
    (name).let {
        when (arg) {
            is VarargValueArgument -> "...$it"
            else -> it.asString()
        }
    }

private fun lambdaValueParamHints(
    acc: MutableList<InlayHint>,
    node: KtLambdaArgument,
    file: CompiledFile,
    config: InlayHintsConfiguration
) {
    if (!config.typeHints) return

    val params = checkNotNull(node.getLambdaExpression()) { "KtLambdaArgument without lambda expression" }.valueParameters

    //hint should not be rendered when parameter is of type DestructuringDeclaration
    //example: Map.forEach { (k,v) -> _ }
    //lambda parameter (k,v) becomes (k :hint, v :hint) :hint <- outer hint isn't needed
    params.singleOrNull()?.let {
        if (it.destructuringDeclaration != null) return
    }

    val hints = params.mapNotNull {
        it.hintBuilder(InlayKind.TypeHint, file)
    }
    acc.addAll(hints)
}

private fun chainedExpressionHints(
    acc: MutableList<InlayHint>,
    node: KtDotQualifiedExpression,
    file: CompiledFile,
    config: InlayHintsConfiguration
) {
    if (!config.chainedHints) return

    // chaining is defined as an expression whose parent is also a dot-qualified expression.
    // The final call in a chain (e.g., `.b()` in `A.foo.bar().b()`) has a non-dot-qualified parent.
    if (node.parent !is KtDotQualifiedExpression) return

    val hints = node.getChildrenOfType<KtCallExpression>().mapNotNull {
        it.hintBuilder(InlayKind.ChainingHint, file)
    }
    acc.addAll(hints)
}

private fun destructuringVarHints(
    acc: MutableList<InlayHint>,
    node: KtDestructuringDeclaration,
    file: CompiledFile,
    config: InlayHintsConfiguration
) {
    if (!config.typeHints) return

    val hints = node.entries.mapNotNull {
        it.hintBuilder(InlayKind.TypeHint, file)
    }
    acc.addAll(hints)
}

private fun declarationHint(
    acc: MutableList<InlayHint>,
    node: KtProperty,
    file: CompiledFile,
    config: InlayHintsConfiguration
) {
    if (!config.typeHints) return

    // Check declaration does not include type i.e. var t1: String
    if (node.typeReference != null) return

    val hint = node.hintBuilder(InlayKind.TypeHint, file) ?: return
    acc.add(hint)
}

private fun functionHint(
    acc: MutableList<InlayHint>,
    node: KtNamedFunction,
    file: CompiledFile,
    config: InlayHintsConfiguration
) {
    if (!config.typeHints) return

    // Only render hints for functions without block body
    // functions WITH block body will always specify return types explicitly
    if (!node.hasDeclaredReturnType() && !node.hasBlockBody()) {
        val hint = node.hintBuilder(InlayKind.TypeHint, file) ?: return
        acc.add(hint)
    }
}

fun provideHints(file: CompiledFile, config: InlayHintsConfiguration): List<InlayHint> {
    val res = mutableListOf<InlayHint>()
    for (node in file.parse.preOrderTraversal().asIterable()) {
        when (node) {
            is KtNamedFunction -> functionHint(res, node, file, config)
            is KtLambdaArgument -> lambdaValueParamHints(res, node, file, config)
            is KtDotQualifiedExpression -> chainedExpressionHints(res, node, file, config)
            is KtCallExpression -> callableArgNameHints(res, node, file, config)
            is KtDestructuringDeclaration -> destructuringVarHints(res, node, file, config)
            is KtProperty -> declarationHint(res, node, file, config)
        }
    }
    return res
}

enum class InlayKind(val base: InlayHintKind) {
    TypeHint(InlayHintKind.Type),
    ParameterHint(InlayHintKind.Parameter),
    ChainingHint(InlayHintKind.Type),
}
