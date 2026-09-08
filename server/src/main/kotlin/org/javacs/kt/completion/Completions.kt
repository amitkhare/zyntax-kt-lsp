package org.javacs.kt.completion

// TODO: Refactor - file has too many functions (50), split into smaller modules by concern
// See detekt TooManyFunctions threshold exception

import com.google.common.cache.CacheBuilder
import org.eclipse.lsp4j.*
import org.javacs.kt.CompiledFile
import org.javacs.kt.LOG
import org.javacs.kt.CompletionConfiguration
import org.javacs.kt.ScriptsConfiguration
import org.javacs.kt.index.Symbol
import org.javacs.kt.index.SymbolIndex
import org.javacs.kt.util.containsCharactersInOrder
import org.javacs.kt.util.findParent
import org.javacs.kt.util.noResult
import org.javacs.kt.util.toPath
import org.javacs.kt.util.onEachIndexed
import org.javacs.kt.imports.getImportTextEditEntry
import org.javacs.kt.util.stringDistance
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.load.java.lazy.descriptors.isJavaField
import org.jetbrains.kotlin.load.java.sources.JavaSourceElement
import org.jetbrains.kotlin.load.java.structure.JavaMethod
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter.Companion
import org.jetbrains.kotlin.resolve.scopes.HierarchicalScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.resolve.scopes.utils.parentsWithSelf
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.typeUtil.replaceArgumentsWithStarProjections
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.utils.addToStdlib.applyIf

import java.util.concurrent.TimeUnit

/** The maximum number of completion items */
private const val MAX_COMPLETION_ITEMS = 75

/** The minimum length after which completion lists are sorted */
private const val MIN_SORT_LENGTH = 3

/** Length of "get" or "set" prefix in Java accessor method names. */
private const val ACCESSOR_PREFIX_LENGTH = 3

/** Length of "is" prefix in Java boolean getter names. */
private const val IS_PREFIX_LENGTH = 2

/** Return type info for a Java getter/setter pair that maps to the same Kotlin property. */
private data class AccessorInfo(
    val getterReturnType: KotlinType?,
    val setterReturnType: KotlinType?,
    /** `true` if setter returns void/Unit */
    val isSetterStandard: Boolean
)

/** A synthetic completion item representing a unified getter+setter property. */
private data class SyntheticAccessorItem(
    val propertyName: String,
    val getterReturnType: KotlinType?,
    val fromLabel: String  // e.g., "from getName()/setName()"
)

/** Wraps either a real DeclarationDescriptor or a synthetic accessor item. */
private sealed class DeduplicationResult {
    abstract fun toCompletionItem(surroundingElement: KtElement, file: CompiledFile, config: CompletionConfiguration): CompletionItem
}

private class RealDescriptor(val descriptor: DeclarationDescriptor) : DeduplicationResult() {
    override fun toCompletionItem(surroundingElement: KtElement, file: CompiledFile, config: CompletionConfiguration): CompletionItem =
        completionItem(descriptor, surroundingElement, file, config, emptyMap())
}

private class SyntheticResult(val item: SyntheticAccessorItem) : DeduplicationResult() {
    override fun toCompletionItem(surroundingElement: KtElement, file: CompiledFile, config: CompletionConfiguration): CompletionItem =
        syntheticCompletionItem(item)
}

/** Returns true if the type is void or Unit (standard Java/Kotlin setter return). */
private fun isVoidOrUnit(type: KotlinType?): Boolean {
    if (type == null) return false
    val fqName = type.constructor.declarationDescriptor?.fqNameSafe?.asString()
    return fqName == "kotlin.Unit" || fqName == "java.lang.Void" || fqName == "void"
}

/** Deduplicates visibility-hiding log messages so each (caller, target) pair is only logged once. */
private val loggedHidden = CacheBuilder.newBuilder()
    .expireAfterWrite(1, TimeUnit.MINUTES)
    .build<Pair<Name, Name>, Unit>()

private val JDK_PACKAGE_PREFIXES = setOf(
    "java.", "javax.", "sun.", "com.sun.", "jdk.", "jdk.internal."
)

/** Finds completions at the specified position. */
fun completions(file: CompiledFile, cursor: Int, index: SymbolIndex, config: CompletionConfiguration, scriptsConfig: ScriptsConfiguration): CompletionList {
    val partial = findPartialIdentifier(file, cursor)
    LOG.debug("Looking for completions that match '{}'", partial)

    val (elementItems, element) = elementCompletionItems(file, cursor, config, scriptsConfig, partial)
    val elementItemList = elementItems.toList()
    val elementItemLabels = elementItemList.mapNotNull { it.label }.toSet()

    val items = (
        elementItemList.asSequence()
        + indexCompletionItems(file, cursor, element, index, partial, config).filter { it.label !in elementItemLabels }
        + (if (elementItemList.isEmpty()) keywordCompletionItems(partial) else emptySequence())
    )
    val itemList = items
        .take(MAX_COMPLETION_ITEMS)
        .toList()
        .onEachIndexed { i, item -> item.sortText = i.toString().padStart(2, '0') }
    val isIncomplete = itemList.size >= MAX_COMPLETION_ITEMS || elementItemList.isEmpty()

    return CompletionList(isIncomplete, itemList)
}

private fun getQueryNameFromExpression(receiver: KtExpression?, cursor: Int, file: CompiledFile): FqName? {
    val receiverType = receiver?.let { expr -> file.scopeAtPoint(cursor)?.let { file.typeOfExpression(expr, it) } }
    return receiverType?.constructor?.declarationDescriptor?.fqNameSafe
}

/** Finds completions in the global symbol index, for potentially unimported symbols. */
@Suppress("LongParameterList")
private fun indexCompletionItems(file: CompiledFile, cursor: Int, element: KtElement?, index: SymbolIndex, partial: String, config: CompletionConfiguration): Sequence<CompletionItem> {
    val parsedFile = file.parse
    val imports = parsedFile.importDirectives
    // TODO: Deal with alias imports
    val wildcardPackages = imports
        .mapNotNull { it.importPath }
        .filter { it.isAllUnder }
        .map { it.fqName }
        .toSet()
    val importedNames = imports
        .mapNotNull { it.importedFqName?.shortName() }
        .toSet()

    val queryName = when (element) {
        is KtQualifiedExpression -> getQueryNameFromExpression(element.receiverExpression, element.receiverExpression.startOffset, file)
        is KtSimpleNameExpression -> {
            val receiver = element.getReceiverExpression()
            when {
                receiver != null -> getQueryNameFromExpression(receiver, receiver.startOffset, file)
                else -> null
            }
        }
        is KtUserType -> file.referenceAtPoint(element.qualifier?.startOffset ?: cursor)?.second?.fqNameSafe
        is KtTypeElement -> file.referenceAtPoint(element.startOffsetInParent)?.second?.fqNameOrNull()
        else -> null
    }

    return index
        .query(partial, queryName, limit = MAX_COMPLETION_ITEMS)
        .asSequence()
        .filter { it.kind != Symbol.Kind.MODULE }
        .filter { it.fqName.shortName() !in importedNames && it.fqName.parent() !in wildcardPackages }
        .filter { isVisibleSymbol(it) }
        .filter { it.fqName.isRoot || !config.isTypeFiltered(it.fqName) }
        .map { createIndexCompletionItem(it, parsedFile) }
}

private fun isVisibleSymbol(symbol: Symbol): Boolean =
    // TODO: Visibility checker should be less literal
    symbol.visibility == Symbol.Visibility.PUBLIC
    || symbol.visibility == Symbol.Visibility.PROTECTED
    || symbol.visibility == Symbol.Visibility.INTERNAL

private fun createIndexCompletionItem(symbol: Symbol, parsedFile: KtFile): CompletionItem =
    CompletionItem().apply {
        label = symbol.fqName.shortName().toString()
        kind = when (symbol.kind) {
            Symbol.Kind.CLASS -> CompletionItemKind.Class
            Symbol.Kind.INTERFACE -> CompletionItemKind.Interface
            Symbol.Kind.FUNCTION -> CompletionItemKind.Function
            Symbol.Kind.VARIABLE -> CompletionItemKind.Variable
            Symbol.Kind.MODULE -> CompletionItemKind.Module
            Symbol.Kind.ENUM -> CompletionItemKind.Enum
            Symbol.Kind.ENUM_MEMBER -> CompletionItemKind.EnumMember
            Symbol.Kind.CONSTRUCTOR -> CompletionItemKind.Constructor
            Symbol.Kind.FIELD -> CompletionItemKind.Field
            Symbol.Kind.UNKNOWN -> CompletionItemKind.Text
        }
        detail = "(import from ${symbol.fqName.parent()})"
        additionalTextEdits = listOf(getImportTextEditEntry(parsedFile, symbol.fqName))
    }

/** Finds keyword completions starting with the given partial identifier. */
private fun keywordCompletionItems(partial: String): Sequence<CompletionItem> =
    (KtTokens.SOFT_KEYWORDS.types + KtTokens.KEYWORDS.types).asSequence()
        .mapNotNull { (it as? KtKeywordToken)?.value }
        .filter { it.startsWith(partial) }
        .map { CompletionItem().apply {
            label = it
            kind = CompletionItemKind.Keyword
        } }

data class ElementCompletionItems(val items: Sequence<CompletionItem>, val element: KtElement? = null)

/**
 * Finds completions based on the element around the user's cursor.
 *
 * Deduplication criteria for Java getter/setter methods:
 * 1. Collect Java getter methods and their (name, returnType) pairs
 *
 * 2. Suppress PropertyDescriptors that match a Java getter (same name AND same type)
 *    or have initialSignatureDescriptor (generated from Java getters)
 *
 * 3. Suppress JavaMethodDescriptors (getters/setters) when a property
 *    with the same name exists in ownedPropertyNames
 *
 * 4. Prefer Kotlin properties and Java fields over Java accessor methods
 */
private fun elementCompletionItems(
    file: CompiledFile,
    cursor: Int,
    config: CompletionConfiguration,
    scriptsConfig: ScriptsConfiguration,
    partial: String
): ElementCompletionItems {
    val (surroundingElement, isGlobal) = completableElement(file, cursor) ?: return ElementCompletionItems(emptySequence())
    val skipJdkConstructors = file.isScript && !scriptsConfig.enableJdkSymbols
    val completions = elementCompletions(file, cursor, surroundingElement, isGlobal, skipJdkConstructors)
        .applyIf(isGlobal) { filter { declarationIsInfix(it) } }
        .applyIf(surroundingElement.endOffset == cursor) {
            filter { containsCharactersInOrder(name(it), partial, caseSensitive = false) }
        }

    val sorted = completions.takeIf { partial.length >= MIN_SORT_LENGTH }?.sortedBy {
        stringDistance(name(it), partial)
    } ?: completions.sortedBy { if (name(it).startsWith(partial)) 0 else 1 }

    val visible = sorted
        .filter(isVisible(file, cursor))
        .filter { descriptor ->
            val fqName = descriptor.fqNameSafe
            fqName.isRoot || !config.isTypeFiltered(fqName)
        }
        .toList()

    val javaAccessorInfo = collectJavaAccessorInfo(visible)
    val propertyBackedNames = collectPropertyBackedNames(visible, javaAccessorInfo)
    val deduplicated = deduplicateDescriptors(visible, javaAccessorInfo, propertyBackedNames)

    return ElementCompletionItems(deduplicated.asSequence().map {
        it.toCompletionItem(surroundingElement, file, config)
    }, surroundingElement)
}

private fun collectJavaAccessorInfo(visible: List<DeclarationDescriptor>): Map<String, AccessorInfo> =
    visible
        .filterIsInstance<JavaMethodDescriptor>()
        .filter { isNotStaticJavaMethod(it) && (isGetter(it) || isSetter(it)) }
        // Build map of property name -> AccessorInfo (merging getter/setter info for same property)
        .fold(mutableMapOf()) { acc, method ->
            val propertyName = extractPropertyName(method)
            val returnType = method.returnType
            val existing = acc[propertyName]
            if (isGetter(method)) {
                acc[propertyName] = AccessorInfo(
                    getterReturnType = returnType,
                    setterReturnType = existing?.setterReturnType,
                    isSetterStandard = existing?.isSetterStandard ?: false
                )
            } else {
                acc[propertyName] = AccessorInfo(
                    getterReturnType = existing?.getterReturnType,
                    setterReturnType = returnType,
                    isSetterStandard = isVoidOrUnit(returnType)
                )
            }
            acc
        }

private fun collectPropertyBackedNames(visible: List<DeclarationDescriptor>, javaAccessorInfo: Map<String, AccessorInfo>): Set<String> =
    visible
        .filterIsInstance<PropertyDescriptor>()
        .filter { pd ->
            val propertyName = pd.name.identifier
            val propertyType = pd.type
            val accessorInfo = javaAccessorInfo[propertyName]
            val matchesAccessor = accessorInfo != null && (
                accessorInfo.getterReturnType?.constructor == propertyType.constructor ||
                (accessorInfo.isSetterStandard && accessorInfo.setterReturnType?.constructor == propertyType.constructor)
            )
            val isOwned = !pd.isJavaField || !matchesAccessor
            LOG.trace("[DEDUP] PropertyDescriptor: name={}, type={}, isJavaField={}, matchesAccessor={}, isOwned={}", propertyName, propertyType, pd.isJavaField, matchesAccessor, isOwned)
            isOwned
        }
        .map { it.name.identifier }
        .toSet()

private fun deduplicateDescriptors(
    visible: List<DeclarationDescriptor>,
    javaAccessorInfo: Map<String, AccessorInfo>,
    propertyBackedNames: Set<String>
): List<DeduplicationResult> {
    val syntheticEntries = mutableMapOf<String, SyntheticAccessorItem>()
    val suppressedGetters = mutableSetOf<String>()
    val suppressedSetters = mutableSetOf<String>()

    val results = visible.mapNotNull { descriptor ->
        when (descriptor) {
            is PropertyDescriptor -> handlePropertyDescriptor(descriptor, javaAccessorInfo)
            is JavaMethodDescriptor -> resolveJavaAccessor(
                descriptor, javaAccessorInfo, propertyBackedNames, suppressedGetters, suppressedSetters
            )
            else -> RealDescriptor(descriptor)
        }
    }

    createSyntheticEntries(suppressedGetters, suppressedSetters, javaAccessorInfo, syntheticEntries)
    return results + syntheticEntries.values.map { SyntheticResult(it) }
}

private fun handlePropertyDescriptor(
    descriptor: PropertyDescriptor,
    javaAccessorInfo: Map<String, AccessorInfo>
): DeduplicationResult? {
    val propertyName = descriptor.name.identifier
    val propertyType = descriptor.type
    val hasInitialSig = descriptor.getter?.initialSignatureDescriptor != null
    val accessorInfo = javaAccessorInfo[propertyName]
    val matchesAccessor = accessorInfo != null && (
        accessorInfo.getterReturnType?.constructor == propertyType.constructor ||
        (accessorInfo.isSetterStandard && accessorInfo.setterReturnType?.constructor == propertyType.constructor)
    )

    return when {
        descriptor.isJavaField && matchesAccessor -> {
            LOG.trace("[DEDUP] PropertyDescriptor suppressed (Java field with matching getter): name={}", propertyName)
            null
        }
        !descriptor.isJavaField && (matchesAccessor || hasInitialSig) -> {
            LOG.trace("[DEDUP] PropertyDescriptor suppressed (Kotlin property from Java getter): name={}", propertyName)
            null
        }
        else -> {
            LOG.trace("[DEDUP] PropertyDescriptor kept (native Kotlin property): name={}", propertyName)
            RealDescriptor(descriptor)
        }
    }
}

/** Decides whether to suppress a Java getter/setter method in favor of a synthetic property entry. */
private fun resolveJavaAccessor(
    descriptor: JavaMethodDescriptor,
    javaAccessorInfo: Map<String, AccessorInfo>,
    propertyBackedNames: Set<String>,
    suppressedGetters: MutableSet<String>,
    suppressedSetters: MutableSet<String>
): DeduplicationResult? {
    val isGetterMethod = isGetter(descriptor)
    val isSetterMethod = isSetter(descriptor)

    val isNotStatic = isNotStaticJavaMethod(descriptor)

    val accessorInfo = javaAccessorInfo[extractPropertyName(descriptor)]

    val isStandardSetter = isSetterMethod && accessorInfo?.isSetterStandard == true
    val isNonStandardSetter = isSetterMethod && !isStandardSetter

    val propertyName = extractPropertyName(descriptor)
    val shouldSuppress = propertyName in propertyBackedNames || accessorInfo != null

    return when {
        isNotStatic && isGetterMethod && shouldSuppress -> {
            suppressedGetters.add(propertyName)
            LOG.trace("[DEDUP] JavaMethodDescriptor suppressed (getter, property backed): name={}", descriptor.name)
            null
        }
        isNotStatic && isStandardSetter && shouldSuppress -> {
            suppressedSetters.add(propertyName)
            LOG.trace("[DEDUP] JavaMethodDescriptor suppressed (standard setter, property backed): name={}", descriptor.name)
            null
        }
        isNotStatic && isNonStandardSetter -> {
            LOG.trace("[DEDUP] JavaMethodDescriptor kept (non-standard setter): name={}", descriptor.name)
            RealDescriptor(descriptor)
        }
        else -> {
            LOG.trace("[DEDUP] JavaMethodDescriptor kept (other): name={}", descriptor.name)
            RealDescriptor(descriptor)
        }
    }
}

private fun createSyntheticEntries(
    suppressedGetters: Set<String>,
    suppressedSetters: Set<String>,
    javaAccessorInfo: Map<String, AccessorInfo>,
    syntheticEntries: MutableMap<String, SyntheticAccessorItem>
) {
    for (propertyName in suppressedGetters.intersect(suppressedSetters)) {
        val accessor = javaAccessorInfo[propertyName] ?: continue
        val (getterName, setterName) = accessorMethodNames(propertyName)
        syntheticEntries[propertyName] = SyntheticAccessorItem(
            propertyName = propertyName,
            getterReturnType = accessor.getterReturnType,
            fromLabel = "from $getterName()/$setterName()"
        )
    }

    for (propertyName in suppressedGetters - suppressedSetters) {
        val accessor = javaAccessorInfo[propertyName] ?: continue
        val (getterName, _) = accessorMethodNames(propertyName)
        syntheticEntries[propertyName] = SyntheticAccessorItem(
            propertyName = propertyName,
            getterReturnType = accessor.getterReturnType,
            fromLabel = "from $getterName()"
        )
    }
}

private fun completionItem(d: DeclarationDescriptor, surroundingElement: KtElement, file: CompiledFile, config: CompletionConfiguration, javaAccessorInfo: Map<String, AccessorInfo> = emptyMap()): CompletionItem {
    val renderWithSnippets = config.snippets.enabled
        && surroundingElement !is KtCallableReferenceExpression
        && surroundingElement !is KtImportDirective

    val result = d.accept(RenderCompletionItem(renderWithSnippets), null)

    val originalLabel = result.label
    result.label = methodSignature.find(result.detail)?.groupValues?.get(1) ?: result.label

    // Java getters/setters are accessed as Kotlin properties (no parentheses)
    if (isNotStaticJavaMethod(d) && (isGetter(d) || isSetter(d))) {
        val name = extractPropertyName(d)
        val accessorInfo = javaAccessorInfo[name]
        val hasGetter = accessorInfo?.getterReturnType != null
        val hasSetter = accessorInfo?.setterReturnType != null
        val fromSuffix = if (hasGetter && hasSetter) "/set$name" else ""

        result.apply {
            detail += " (from $originalLabel$fromSuffix)"
            label = name
            insertText = name
            filterText = name
            kind = CompletionItemKind.Property
            insertTextFormat = InsertTextFormat.PlainText
        }
    }

    if (KotlinBuiltIns.isDeprecated(d)) {
        result.tags = listOf(CompletionItemTag.Deprecated)
    }

    val matchCall = callPattern.matchEntire(result.insertText)
    val hasSnippet = result.insertText.contains("$")
    if (!hasSnippet && file.lineAfter(surroundingElement.endOffset).startsWith("(") && matchCall != null) {
        result.insertText = matchCall.groups[1]!!.value
    }

    return result
}

private fun syntheticCompletionItem(item: SyntheticAccessorItem): CompletionItem {
    val returnType = item.getterReturnType
    val typeLabel = returnType?.constructor?.declarationDescriptor?.fqNameSafe?.asString()?.substringAfterLast('.') ?: "Unit"

    return CompletionItem().apply {
        label = item.propertyName
        detail = "$typeLabel! ${item.fromLabel}"
        insertText = item.propertyName
        filterText = item.propertyName
        kind = CompletionItemKind.Property
        insertTextFormat = InsertTextFormat.PlainText
    }
}

private fun isNotStaticJavaMethod(
    descriptor: DeclarationDescriptor
): Boolean {
    val javaMethodDescriptor = descriptor as? JavaMethodDescriptor ?: return true
    val source = javaMethodDescriptor.source as? JavaSourceElement ?: return true
    val javaElement = source.javaElement
    if (javaElement !is JavaMethod) return true
    val result = !javaElement.isStatic
    LOG.trace("[DEDUP] isNotStaticJavaMethod: method={}, isStatic={}, result={}", descriptor.name, javaElement.isStatic, result)
    return result
}

private fun extractPropertyName(d: DeclarationDescriptor): String {
    val identifier = d.name.identifier
    return when {
        (identifier.startsWith("get") || identifier.startsWith("set")) && identifier.length > ACCESSOR_PREFIX_LENGTH ->
            propertyNameFromAccessor(identifier.substring(ACCESSOR_PREFIX_LENGTH))
        identifier.startsWith("is") && identifier.length > IS_PREFIX_LENGTH -> {
            val remainder = identifier.substring(IS_PREFIX_LENGTH)
            if (remainder.firstOrNull()?.isUpperCase() == true) identifier
            else remainder.replaceFirstChar { it.lowercaseChar() }
        }
        else -> identifier
    }
}

/** Returns the getter and setter method names for a given property name (e.g., "name" -> "getName", "setName"). */
private fun accessorMethodNames(propertyName: String): Pair<String, String> {
    val capitalized = propertyName.replaceFirstChar { it.uppercaseChar() }
    return "get$capitalized" to "set$capitalized"
}

/**
 * Converts the remainder of a Java getter name to a Kotlin property name.
 *
 * Kotlin synthesizes properties from Java getters following specific rules:
 * - "getURL()" -> property "url"
 * - "getHTTPResponse()" -> property "httpResponse"
 * - "getSLF4JLogger()" -> property "slF4JLogger" (acronym preserved)
 * - "getHTML5Parser()" -> property "htmL5Parser" (digit terminates acronym)
 *
 * This function implements Kotlin's `decapitalizeSmartForCompiler` algorithm,
 * which handles acronyms, digits, and lowercase-first-character edge cases.
 *
 * @param remainder The getter name without the "get" prefix (e.g., "URL", "HTTPResponse")
 * @return The corresponding Kotlin property name
 */
internal fun propertyNameFromAccessor(remainder: String): String {
    if (remainder.isEmpty()) return remainder
    // decapitalizeSmartForCompiler returns unchanged when first char is not uppercase (e.g., "aName" stays "aName")
    if (!remainder[0].isUpperCase()) return remainder

    // Single char OR second char is lowercase: lowercase only the first char
    if (remainder.length == 1 || !remainder[1].isUpperCase()) {
        return remainder.replaceFirstChar { it.lowercaseChar() }
    }

    // Multiple uppercase chars at start (possible acronym): find first non-uppercase char
    val secondWordStart = remainder.indexOfFirst { !it.isUpperCase() }

    return if (secondWordStart <= 0) {
        // All uppercase (e.g., "URL"): lowercase everything
        remainder.lowercase()
    } else {
        // Preserve acronym as-is, lowercase from second word onward
        // e.g., "SLF4JLogger" -> "slF4JLogger" (keeps "F4JLogger" uppercase)
        remainder.substring(0, secondWordStart - 1).lowercase() + remainder.substring(secondWordStart - 1)
    }
}

private fun isGetter(d: DeclarationDescriptor): Boolean =
        d is CallableDescriptor &&
        !d.name.isSpecial &&
        d.name.identifier.matches(getterPattern) &&
        d.valueParameters.isEmpty()

private fun isSetter(d: DeclarationDescriptor): Boolean =
        d is CallableDescriptor &&
        !d.name.isSpecial &&
        d.name.identifier.matches(setterPattern) &&
        d.valueParameters.size == 1

private fun isGlobalCall(el: KtElement) = el is KtBlockExpression || el is KtClassBody || el.parent is KtBinaryExpression

private fun asGlobalCompletable(file: CompiledFile, cursor: Int, el: KtElement): KtElement? {
    val psi =  file.parse.findElementAt(cursor) ?: return null
    val element = when (val e = psi.getPrevSiblingIgnoringWhitespace() ?: psi.parent) {
        is KtProperty -> e.children.lastOrNull()
        is KtBinaryExpression -> el
        else -> e
    }
    return element as? KtReferenceExpression
        ?: element as? KtQualifiedExpression
        ?: element as? KtConstantExpression
}

/** Returns the nearest enclosing element that can serve as a class context for completion, or null if none exists. */
private fun KtElement.asKtClass(): KtElement? {
    return this.findParent<KtImportDirective>() // import x.y.?
        // package x.y.?
        ?: this.findParent<KtPackageDirective>()
        // :?
        ?: this as? KtUserType
        ?: this.parent as? KtTypeElement
        // .?
        ?: this as? KtQualifiedExpression
        ?: this.parent as? KtQualifiedExpression
        // something::?
        ?: this as? KtCallableReferenceExpression
        ?: this.parent as? KtCallableReferenceExpression
        // something.foo() with cursor in the method
        ?: this.parent?.parent as? KtQualifiedExpression
        // ?
        ?: this as? KtNameReferenceExpression
        // x ? y (infix)
        ?: this.parent as? KtBinaryExpression
        // x()
        ?: this as? KtCallExpression
        // x (constant)
        ?: this as? KtConstantExpression
}

private fun completableElement(file: CompiledFile, cursor: Int): Pair<KtElement, Boolean>? {
    val parsed = file.parseAtPoint(cursor - 1) ?: return null
    val asGlobal = isGlobalCall(parsed)
    val el = (
            if (asGlobal) asGlobalCompletable(file, cursor, parsed) else null
     ) ?: parsed

    return el.asKtClass()?.let {
        Pair(it, asGlobal)
    }
}

@Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod")
private fun elementCompletions(
    file: CompiledFile,
    cursor: Int,
    surroundingElement: KtElement,
    infixCall: Boolean,
    skipJdkConstructors: Boolean
): Sequence<DeclarationDescriptor> {
    return when (surroundingElement) {
        // import x.y.?
        is KtImportDirective -> {
            LOG.info("Completing import '{}'", surroundingElement.text)
            val module = file.module
            val match = importPattern.matchEntire(surroundingElement.text) ?: return doesntLookLikeImport(surroundingElement)
            val parentPackage = resolveParentPackage(module, match)
            parentPackage.memberScope.getContributedDescriptors().asSequence()
        }
        // package x.y.?
        is KtPackageDirective -> {
            LOG.info("Completing package '{}'", surroundingElement.text)
            val module = file.module
            val match = packagePattern.matchEntire(surroundingElement.text)
                ?: return doesntLookLikePackage(surroundingElement)
            val parentPackage = resolveParentPackage(module, match)
            parentPackage.memberScope.getDescriptorsFiltered(DescriptorKindFilter.PACKAGES).asSequence()
        }
        // :?
        is KtTypeElement -> {
            // : Outer.?
            if (surroundingElement is KtUserType && surroundingElement.qualifier != null) {
                val referenceTarget = file.referenceAtPoint(surroundingElement.qualifier!!.startOffset)?.second
                if (referenceTarget is ClassDescriptor) {
                    LOG.info("Completing members of {}", referenceTarget.fqNameSafe)
                    referenceTarget.getDescriptors()
                } else {
                    LOG.warn("No type reference in '{}'", surroundingElement.text)
                    emptySequence()
                }
            } else {
                // : ?
                LOG.info("Completing type identifier '{}'", surroundingElement.text)
                val scope = file.scopeAtPoint(cursor) ?: return emptySequence()
                scopeChainTypes(scope)
            }
        }
        // .?
        is KtQualifiedExpression -> {
            LOG.info("Completing member expression '{}'", surroundingElement.text)
            val exp = if (infixCall) surroundingElement else surroundingElement.receiverExpression
            completeMembers(file, cursor, exp, surroundingElement is KtSafeQualifiedExpression)
        }
        is KtCallableReferenceExpression -> {
            // something::?
            if (surroundingElement.receiverExpression != null) {
                LOG.info("Completing method reference '{}'", surroundingElement.text)
                completeMembers(file, cursor, surroundingElement.receiverExpression!!)
            }
            // ::?
            else {
                LOG.info("Completing function reference '{}'", surroundingElement.text)
                val scope = file.scopeAtPoint(surroundingElement.startOffset) ?: return noResult("No scope at ${file.describePosition(cursor)}", emptySequence())
                identifiers(scope, skipJdkConstructors)
            }
        }
        // ?
        is KtNameReferenceExpression -> {
            LOG.info("Completing identifier '{}'", surroundingElement.text)
            if (infixCall) {
                completeMembers(file, surroundingElement.startOffset, surroundingElement)
            } else {
                val scope = file.scopeAtPoint(surroundingElement.startOffset) ?: return noResult("No scope at ${file.describePosition(cursor)}", emptySequence())
                identifiers(scope, skipJdkConstructors)
            }
        }
        // x ? y (infix)
        is KtBinaryExpression -> {
            if (surroundingElement.operationToken == KtTokens.IDENTIFIER) {
                completeMembers(file, cursor, surroundingElement.left!!)
            } else emptySequence()
        }
        is KtCallExpression, is KtConstantExpression -> {
            completeMembers(file, cursor, surroundingElement as KtExpression)
        }
        else -> {
            LOG.info("{} {} didn't look like a type, a member, or an identifier", surroundingElement::class.simpleName, surroundingElement.text)
            emptySequence()
        }
    }
}

private fun completeMembers(file: CompiledFile, cursor: Int, receiverExpr: KtExpression, unwrapNullable: Boolean = false): Sequence<DeclarationDescriptor> {
    // thingWithType.?
    var descriptors = emptySequence<DeclarationDescriptor>()
    file.scopeAtPoint(cursor)?.let { lexicalScope ->
        file.typeOfExpression(receiverExpr, lexicalScope)?.let { expressionType ->
            val receiverType = if (unwrapNullable) try {
                TypeUtils.makeNotNullable(expressionType)
            } catch (e: Exception) {
                LOG.printStackTrace(e)
                expressionType
            } else expressionType

            LOG.debug("Completing members of instance '{}'", receiverType)
            val members = receiverType.memberScope.getContributedDescriptors().asSequence()
            val extensions = extensionFunctions(lexicalScope).filter { isExtensionFor(receiverType, it) }
            descriptors = members + extensions

            if (!isCompanionOfEnum(receiverType) && !isCompanionOfSealed(receiverType)) {
                return descriptors
            }
        }
    }

    // JavaClass.?
    val referenceTarget = file.referenceAtPoint(receiverExpr.endOffset - 1)?.second
    if (referenceTarget is ClassDescriptor) {
        LOG.debug("Completing members of '{}'", referenceTarget.fqNameSafe)
        return descriptors + referenceTarget.getDescriptors()
    }

    LOG.debug("Can't find member scope for {}", receiverExpr.text)
    return emptySequence()
}

private fun resolveParentPackage(module: ModuleDescriptor, match: MatchResult): PackageViewDescriptor {
    val parentDot = match.groupValues[1].ifBlank { "." }
    val parent = parentDot.substring(0, parentDot.length - 1)
    LOG.debug("Looking for members of package '{}'", parent)
    return module.getPackage(FqName.fromSegments(parent.split('.')))
}

private fun ClassDescriptor.getDescriptors(): Sequence<DeclarationDescriptor> {
    val statics = staticScope.getContributedDescriptors().asSequence()
    val classes = unsubstitutedInnerClassesScope.getContributedDescriptors().asSequence()
    val types = unsubstitutedMemberScope.getContributedDescriptors().asSequence()
    val companionDescriptors = if (hasCompanionObject && companionObjectDescriptor != null) companionObjectDescriptor!!.getDescriptors() else emptySequence()

    return (statics + classes + types + companionDescriptors).toSet().asSequence()

}

private fun declarationIsInfix(declaration: DeclarationDescriptor): Boolean {
    val functionDescriptor = declaration as? FunctionDescriptor ?: return false
    return functionDescriptor.isInfix
}

private fun isCompanionOfEnum(kotlinType: KotlinType): Boolean {
    val classDescriptor = TypeUtils.getClassDescriptor(kotlinType)
    val isCompanion = DescriptorUtils.isCompanionObject(classDescriptor)
    if (!isCompanion) {
        return false
    }
    return DescriptorUtils.isEnumClass(classDescriptor?.containingDeclaration)
}

private fun isCompanionOfSealed(kotlinType: KotlinType): Boolean {
    val classDescriptor = TypeUtils.getClassDescriptor(kotlinType)
    val isCompanion = DescriptorUtils.isCompanionObject(classDescriptor)
    if (!isCompanion) {
        return false
    }

    return DescriptorUtils.isSealedClass(classDescriptor?.containingDeclaration)
}

private fun findPartialIdentifier(file: CompiledFile, cursor: Int): String {
    val line = file.lineBefore(cursor)

    return if (line.matches(dotPattern)) ""
    else if (line.matches(dotWordPattern)) line.substringAfterLast(".")
    else wordPattern.findAll(line).lastOrNull()?.value ?: ""
}

fun memberOverloads(type: KotlinType, identifier: String): Sequence<CallableDescriptor> {
    val nameFilter = equalsIdentifier(identifier)

    return type.memberScope
            .getContributedDescriptors(Companion.CALLABLES).asSequence()
            .filterIsInstance<CallableDescriptor>()
            .filter(nameFilter)
}

private fun scopeChainTypes(scope: LexicalScope): Sequence<DeclarationDescriptor> =
        scope.parentsWithSelf.flatMap(::scopeTypes)

private val TYPES_FILTER = DescriptorKindFilter(DescriptorKindFilter.NON_SINGLETON_CLASSIFIERS_MASK or DescriptorKindFilter.TYPE_ALIASES_MASK)

private fun scopeTypes(scope: HierarchicalScope): Sequence<DeclarationDescriptor> =
        scope.getContributedDescriptors(TYPES_FILTER).asSequence()

fun identifierOverloads(scope: LexicalScope, identifier: String): Sequence<CallableDescriptor> {
    val nameFilter = equalsIdentifier(identifier)

    return identifiers(scope)
            .filterIsInstance<CallableDescriptor>()
            .filter(nameFilter)
}

private fun extensionFunctions(scope: LexicalScope): Sequence<CallableDescriptor> =
    scope.parentsWithSelf.flatMap(::scopeExtensionFunctions)

private fun scopeExtensionFunctions(scope: HierarchicalScope): Sequence<CallableDescriptor> =
    scope.getContributedDescriptors(DescriptorKindFilter.CALLABLES).asSequence()
            .filterIsInstance<CallableDescriptor>()
            .filter { it.isExtension }

private fun identifiers(scope: LexicalScope, skipJdkConstructors: Boolean = false): Sequence<DeclarationDescriptor> =
    scope.parentsWithSelf
            .flatMap(::scopeIdentifiers)
            .flatMap { explodeConstructors(it, skipJdkConstructors) }

private fun scopeIdentifiers(scope: HierarchicalScope): Sequence<DeclarationDescriptor> {
    val locals = scope.getContributedDescriptors().asSequence()
    val members = implicitMembers(scope)

    return locals + members
}

private fun isJdkClass(classDescriptor: ClassDescriptor): Boolean {
    val fqName = classDescriptor.fqNameSafe.asString()
    return JDK_PACKAGE_PREFIXES.any { fqName.startsWith(it) }
}

private fun explodeConstructors(declaration: DeclarationDescriptor, skipJdkConstructors: Boolean = false): Sequence<DeclarationDescriptor> {
    // Guard: skip only JDK constructor expansion for scripts (to avoid "module dependencies not set" errors)
    // This preserves user class constructors while skipping JDK ones
    if (skipJdkConstructors && declaration is ClassDescriptor && isJdkClass(declaration)) {
        return sequenceOf(declaration)
    }

    return when (declaration) {
        is ClassDescriptor ->
            declaration.constructors.asSequence() + declaration
        else ->
            sequenceOf(declaration)
    }
}

private fun implicitMembers(scope: HierarchicalScope): Sequence<DeclarationDescriptor> {
    if (scope !is LexicalScope) return emptySequence()
    val implicit = scope.implicitReceiver ?: return emptySequence()

    return implicit.type.memberScope.getContributedDescriptors().asSequence()
}

private fun equalsIdentifier(identifier: String): (DeclarationDescriptor) -> Boolean =
    { name(it) == identifier }

private fun name(d: DeclarationDescriptor): String {
    return if (d is ConstructorDescriptor)
        d.constructedClass.name.identifier
    else
        d.name.identifier
}

private fun isVisible(file: CompiledFile, cursor: Int): (DeclarationDescriptor) -> Boolean {
    val el = file.elementAtPoint(cursor) ?: return { true }
    val from = el.parentsWithSelf.firstNotNullOfOrNull {
        file.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, it]
    } ?: return { true }

    fun check(target: DeclarationDescriptor): Boolean {
        val visible = isDeclarationVisible(target, from)
        if (!visible) logHidden(target, from)
        return visible
    }

    return ::check
}

// We can't use the implementations in Visibilities because they don't work with our type of incremental compilation
// Instead, we implement our own "liberal" visibility checker that defaults to visible when in doubt
private fun isDeclarationVisible(target: DeclarationDescriptor, from: DeclarationDescriptor): Boolean =
    target.parentsWithSelf
            .filterIsInstance<DeclarationDescriptorWithVisibility>()
            .none { isNotVisible(it, from) }

private fun isNotVisible(target: DeclarationDescriptorWithVisibility, from: DeclarationDescriptor): Boolean {
    return when (target.visibility.delegate) {
        Visibilities.Private, Visibilities.PrivateToThis -> {
            if (DescriptorUtils.isTopLevelDeclaration(target))
                !sameFile(target, from)
            else
                !sameParent(target, from)
        }
        Visibilities.Protected -> {
            !subclassParent(target, from)
        }
        else -> false
    }
}

private fun sameFile(target: DeclarationDescriptor, from: DeclarationDescriptor): Boolean {
    val targetFile = DescriptorUtils.getContainingSourceFile(target)
    val fromFile = DescriptorUtils.getContainingSourceFile(from)

    if (targetFile == SourceFile.NO_SOURCE_FILE || fromFile == SourceFile.NO_SOURCE_FILE) return true
    else return targetFile.name == fromFile.name
}

private fun sameParent(target: DeclarationDescriptor, from: DeclarationDescriptor): Boolean {
    val targetParent = target.parentsWithSelf.firstNotNullOfOrNull(::isParentClass) ?: return true
    val fromParents = from.parentsWithSelf.mapNotNull(::isParentClass).toList()

    return fromParents.any { it.fqNameSafe == targetParent.fqNameSafe }
}

private fun subclassParent(target: DeclarationDescriptor, from: DeclarationDescriptor): Boolean {
    val targetParent = target.parentsWithSelf.firstNotNullOfOrNull(::isParentClass) ?: return true
    val fromParents = from.parentsWithSelf.mapNotNull(::isParentClass).toList()

    if (fromParents.isEmpty()) return true
    else return fromParents.any { DescriptorUtils.isSubclass(it, targetParent) }
}

private fun isParentClass(declaration: DeclarationDescriptor): ClassDescriptor? =
    if (declaration is ClassDescriptor && !DescriptorUtils.isCompanionObject(declaration))
        declaration
    else null

private fun isExtensionFor(type: KotlinType, extensionFunction: CallableDescriptor): Boolean {
    val receiverType = extensionFunction.extensionReceiverParameter?.type?.replaceArgumentsWithStarProjections() ?: return false
    return KotlinTypeChecker.DEFAULT.isSubtypeOf(type, receiverType)
        || (TypeUtils.getTypeParameterDescriptorOrNull(receiverType)?.isGenericExtensionFor(type) ?: false)
}

private fun TypeParameterDescriptor.isGenericExtensionFor(type: KotlinType): Boolean =
    upperBounds.all { KotlinTypeChecker.DEFAULT.isSubtypeOf(type, it) }

/** Logs that a declaration was hidden due to visibility, deduplicating to avoid spamming the same pair. */
private fun logHidden(target: DeclarationDescriptor, from: DeclarationDescriptor) {
    val key = Pair(from.name, target.name)

    loggedHidden.get(key) { doLogHidden(target, from) }
}

private fun doLogHidden(target: DeclarationDescriptor, from: DeclarationDescriptor) {
    LOG.debug("Hiding {} because it's not visible from {}", describeDeclaration(target), describeDeclaration(from))
}

private fun describeDeclaration(declaration: DeclarationDescriptor): String {
    val file = declaration.findPsi()?.containingFile?.toPath()?.fileName?.toString() ?: "<unknown-file>"
    val container = declaration.containingDeclaration?.name?.toString() ?: "<top-level>"

    return "($file $container.${declaration.name})"
}

private fun doesntLookLikeImport(importDirective: KtImportDirective): Sequence<DeclarationDescriptor> {
    LOG.debug("{} doesn't look like import a.b...", importDirective.text)

    return emptySequence()
}

private fun doesntLookLikePackage(packageDirective: KtPackageDirective): Sequence<DeclarationDescriptor> {
    LOG.debug("{} doesn't look like package a.b...", packageDirective.text)

    return emptySequence()
}
