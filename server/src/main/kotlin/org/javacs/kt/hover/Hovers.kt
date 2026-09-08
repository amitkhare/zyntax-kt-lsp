package org.javacs.kt.hover

import org.eclipse.lsp4j.*
import com.intellij.psi.PsiDocCommentBase
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.CompiledFile
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.completion.DECL_RENDERER
import org.javacs.kt.docs.findDoc
import org.javacs.kt.position.position
import org.javacs.kt.signaturehelp.getDocString
import org.javacs.kt.util.findParent
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.isPlain
import org.jetbrains.kotlin.psi.psiUtil.plainContent
import org.jetbrains.kotlin.renderer.ClassifierNamePolicy
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.renderer.DescriptorRendererModifier
import org.jetbrains.kotlin.renderer.ParameterNameRenderingPolicy
import org.jetbrains.kotlin.renderer.RenderingFormat
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.utils.IDEAPluginsCompatibilityAPI

/** Renderer that includes visibility, modality, and other modifiers. */
private val DECL_MODIFIERS_RENDERER = DescriptorRenderer.withOptions {
    withDefinedIn = false
    modifiers = DescriptorRendererModifier.ALL_EXCEPT_ANNOTATIONS
    classifierNamePolicy = ClassifierNamePolicy.SHORT
    parameterNameRenderingPolicy = ParameterNameRenderingPolicy.ONLY_NON_SYNTHESIZED
    typeNormalizer = { it }
}

/**
 * Entry point for LSP textDocument/hover. Dispatches to four hover providers in priority order,
 * returning the first non-null result:
 *
 * 1. [importDirectiveHoverAt] — cursor on an import statement
 * 2. Reference hover — cursor on a symbol reference (e.g. a variable use or call site)
 * 3. [declarationHoverAt] — cursor on a named declaration site (e.g. `val`/`var`/`fun` keyword)
 * 4. [typeHoverAt] — fallback: renders the expression type for any other expression
 */
fun hoverAt(
    file: CompiledFile,
    cursor: Int,
    classContentProvider: ClassContentProvider? = null,
    cp: CompilerClassPath? = null
): Hover? {
    val importHover = importDirectiveHoverAt(file, cursor, classContentProvider, cp)
    if (importHover != null) return importHover

    // Reference hover: resolves the symbol use under the cursor (KtReferenceExpression) and renders
    // the declaration signature. This is the primary hover path for most identifiers, call sites,
    // and property accesses.
    val refAt = file.referenceAtPoint(cursor)
    if (refAt != null) {
        val (ref, target) = refAt
        var javaDoc = getDocString(file, cursor, classContentProvider, cp)

        // getDocString only produces documentation for KtCallExpression sites (it walks up to a
        // KtCallExpression parent).
        //
        // For type references like `val x: CompletableFuture` or field accesses, getDocString
        // returns empty -- try findDoc directly on the descriptor to fetch doc from external JAR
        if (javaDoc.isEmpty() && classContentProvider != null && cp != null && target is DeclarationDescriptorWithSource) {
            val externalDoc = findDoc(target, classContentProvider, cp)
            if (externalDoc != null) {
                javaDoc = externalDoc
            }
        }

        val location = ref.textRange
        val hoverText = DECL_RENDERER.render(target)
        val hover = MarkupContent("markdown", listOf("```kotlin\n$hoverText\n```", javaDoc).filter { it.isNotEmpty() }.joinToString("\n---\n"))
        val range = Range(
            position(file.content, location.startOffset),
            position(file.content, location.endOffset)
        )
        return Hover(hover, range)
    }

    return declarationHoverAt(file, cursor, classContentProvider, cp) ?: typeHoverAt(file, cursor)
}

/**
 * Renders hover for a named declaration site (cursor on `val`/`var`/`fun`/`class`
 * keyword or the declaration name identifier), showing the full signature with
 * visibility and modality modifiers via DECL_MODIFIERS_RENDERER.
 * Returns null if either PSI resolution or descriptor lookup fails, so the caller
 * can fall through to typeHoverAt.
 */
private fun declarationHoverAt(
    file: CompiledFile,
    cursor: Int,
    classContentProvider: ClassContentProvider? = null,
    cp: CompilerClassPath? = null
): Hover? {
    val element = file.elementAtPoint(cursor) ?: return null
    val declaration = element as? KtNamedDeclaration ?: return null
    val descriptor = file.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration] ?: return null

    val hoverText = DECL_MODIFIERS_RENDERER.render(descriptor)
    val javaDoc = getDocString(file, cursor, classContentProvider, cp)
    val hover = MarkupContent("markdown", listOf("```kotlin\n$hoverText\n```", javaDoc).filter { it.isNotEmpty() }.joinToString("\n---\n"))
    val nameIdentifier = declaration.nameIdentifier ?: declaration
    val range = Range(
        position(file.content, nameIdentifier.textRange.startOffset),
        position(file.content, nameIdentifier.textRange.endOffset)
    )
    return Hover(hover, range)
}

/**
 * Fallback hover: renders the type of any KtExpression at the cursor using
 * expression-level compilation. Used when neither reference nor declaration
 * hover matched (e.g. string literals, arithmetic expressions, `this`).
 * Also appends string length info for plain string literals.
 */
private fun typeHoverAt(file: CompiledFile, cursor: Int): Hover? {
    val expression = file.parseAtPoint(cursor)?.findParent<KtExpression>() ?: return null

    if (!isValidExpressionForHover(expression)) {
        return null
    }

    val javaDoc: String = expression.children.mapNotNull { (it as? PsiDocCommentBase)?.text }.map(::renderJavaDoc).firstOrNull() ?: ""
    val scope = file.scopeAtPoint(cursor) ?: return null
    val context = file.bindingContextOf(expression, scope) ?: return null
    val hoverText = renderTypeOf(expression, context) ?: return null
    val stringInfo = stringLiteralInfo(expression)
    val displayText = if (stringInfo != null) "$hoverText ($stringInfo)" else hoverText
    val parts = listOfNotNull("```kotlin\n$displayText\n```", javaDoc.ifEmpty { null })
    val hover = MarkupContent("markdown", parts.joinToString("\n---\n"))
    return Hover(hover)
}

private fun importDirectiveHoverAt(
    file: CompiledFile,
    cursor: Int,
    classContentProvider: ClassContentProvider? = null,
    cp: CompilerClassPath? = null
): Hover? {
    val element = file.elementAtPoint(cursor) ?: return null
    val importDirective = element.findParent<KtImportDirective>() ?: return null

    val importedFqName = importDirective.importedFqName ?: return null
    val module = file.module

    val parentPackageName = importedFqName.parent()
    val parentPackage = module.getPackage(parentPackageName)

    val descriptor =
        parentPackage.memberScope.getContributedDescriptors().firstOrNull { it.name == importedFqName.shortName() } ?: return null

    val hoverText = DECL_RENDERER.render(descriptor)
    val javaDoc = getDocString(file, cursor, classContentProvider, cp)
    val hover = MarkupContent("markdown", listOf("```kotlin\n$hoverText\n```", javaDoc)
        .filter { it.isNotEmpty() }
        .joinToString("\n---\n"))

    val range = Range(
        position(file.content, importDirective.textRange.startOffset),
        position(file.content, importDirective.textRange.endOffset)
    )

    return Hover(hover, range)
}

private fun stringLiteralInfo(expression: KtExpression): String? {
    if (expression !is KtStringTemplateExpression) return null
    if (!expression.isPlain()) return null

    val length = expression.plainContent.length
    val suffix = if (length == 1) "" else "s"
    return "${length} character$suffix"
}

private fun isValidExpressionForHover(expression: KtExpression): Boolean {
    if (expression.text.isBlank()) {
        return false
    }

    if (expression is KtBlockExpression || expression is KtDeclaration) {
        return false
    }

    if (expression.containingFile?.text?.getOrNull(expression.textRange.startOffset)?.isWhitespace() != false) {
        if (expression.textRange.startOffset == expression.textRange.endOffset) {
            return false
        }
    }

    return true
}

// Source: https://github.com/JetBrains/kotlin/blob/master/idea/src/org/jetbrains/kotlin/idea/codeInsight/KotlinExpressionTypeProvider.kt
private val TYPE_RENDERER: DescriptorRenderer by lazy { DescriptorRenderer.COMPACT.withOptions {
    textFormat = RenderingFormat.PLAIN
    classifierNamePolicy = object: ClassifierNamePolicy {
        override fun renderClassifier(classifier: ClassifierDescriptor, renderer: DescriptorRenderer): String {
            if (DescriptorUtils.isAnonymousObject(classifier)) {
                return "<anonymous object>"
            }
            return ClassifierNamePolicy.SHORT.renderClassifier(classifier, renderer)
        }
    }
}}

private fun renderJavaDoc(text: String): String {
    val lines = text.lines()
    if (lines.isEmpty()) return ""

    val docLines = mutableListOf<String>()
    var inCodeBlock = false

    for (i in lines.indices) {
        val line = lines[i].trim()

        if (i == 0 && line.startsWith("/**")) {
            val docContent = line.substring(3).trim()
            if (docContent.isNotEmpty()) {
                docLines.add(docContent)
            }
            continue
        }

        if (i == lines.size - 1 && line.endsWith("*/")) {
            val docContent = line.substring(0, line.length - 2).trim()
            if (docContent.startsWith("*")) {
                val content = docContent.substring(1).trim()
                if (content.isNotEmpty()) {
                    docLines.add(content)
                }
            } else if (docContent.isNotEmpty()) {
                docLines.add(docContent)
            }
            continue
        }

        val processedLine = if (line.startsWith("*")) {
            line.substring(1).trim()
        } else {
            line.trim()
        }

        if (processedLine.startsWith("```")) {
            inCodeBlock = !inCodeBlock
        }

        if (processedLine.isNotEmpty() || docLines.isNotEmpty()) {
            docLines.add(processedLine)
        }
    }

    return docLines.joinToString("\n").trim()
}

@OptIn(IDEAPluginsCompatibilityAPI::class)
private fun renderTypeOf(element: KtExpression, bindingContext: BindingContext): String? {
    if (element is KtCallableDeclaration) {
        val descriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, element]
        if (descriptor != null) {
            when (descriptor) {
                is CallableDescriptor -> return descriptor.returnType?.let(TYPE_RENDERER::renderType)
            }
        }
    }

    val expressionType = bindingContext[BindingContext.EXPRESSION_TYPE_INFO, element]?.type ?: element.getType(bindingContext)
    val result = expressionType?.let { TYPE_RENDERER.renderType(it) } ?: return null

    val smartCast = bindingContext[BindingContext.SMARTCAST, element]
    if (smartCast != null && element is KtReferenceExpression) {
        val declaredType = (bindingContext[BindingContext.REFERENCE_TARGET, element] as? CallableDescriptor)?.returnType
        if (declaredType != null) {
            return result + " (smart cast from " + TYPE_RENDERER.renderType(declaredType) + ")"
        }
    }
    return result
}
