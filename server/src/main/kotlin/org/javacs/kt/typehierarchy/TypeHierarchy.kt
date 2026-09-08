package org.javacs.kt.typehierarchy

import org.eclipse.lsp4j.*
import org.javacs.kt.CompiledFile
import org.javacs.kt.LOG
import org.javacs.kt.SourcePath
import org.javacs.kt.implementation.directSuperClassifiers
import org.javacs.kt.position.location
import org.javacs.kt.position.offset
import org.javacs.kt.position.range
import org.javacs.kt.position.toURIString
import org.javacs.kt.util.ArchiveDecompileContext
import org.javacs.kt.util.decompileArchiveLocation
import org.javacs.kt.util.emptyResult
import org.javacs.kt.util.findParent
import org.javacs.kt.util.nullResult
import org.javacs.kt.util.parseURI
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import com.google.gson.JsonPrimitive
import java.nio.file.Path
import java.nio.file.Paths

private val typeHierarchyPattern = Regex("(?:class|interface|object|enum class|fun interface)\\s+(\\w+)")

fun prepareTypeHierarchy(file: Path, cursor: Int, sp: SourcePath, ctx: TypeHierarchyContext): List<TypeHierarchyItem>? {
    val recover = sp.currentVersion(file.toUri())
    val descriptor = resolveDescriptorAt(recover, cursor) ?: return null

    return toTypeHierarchyItem(descriptor, sp, ctx)?.let { listOf(it) }
}

fun supertypes(item: TypeHierarchyItem, sp: SourcePath, ctx: TypeHierarchyContext): List<TypeHierarchyItem> {
    val descriptor = resolveItem(item, sp) ?: return emptyResult("Could not resolve item to class descriptor")
    val fromDescriptor = descriptor.directSuperClassifiers()
        .mapNotNull { toTypeHierarchyItem(it, sp, ctx) }

    // Supplement with indexed supertypes (covers external/JAR ancestors when the
    // live descriptor isn't resolvable from the module).
    val fromIndex = sp.index.supertypesOf(descriptor.fqNameSafe)
        .mapNotNull { sp.classDescriptorByFqName(it) }
        .mapNotNull { toTypeHierarchyItem(it, sp, ctx) }

    return mergeByFqName(fromDescriptor, fromIndex)
}

fun subtypes(item: TypeHierarchyItem, sp: SourcePath, ctx: TypeHierarchyContext): List<TypeHierarchyItem> {
    val target = resolveItem(item, sp) ?: return emptyResult("Could not resolve item to class descriptor")

    // Sealed types have their full, precise subtype set known to the compiler
    // without any source scan -- use it directly when available.
    if (target.modality == Modality.SEALED) {
        return target.sealedSubclasses
            .mapNotNull { toTypeHierarchyItem(it, sp, ctx) }
            .sortedWith(compareBy({ it.uri }, { it.range.start.line }))
    }

    val targetFqName = target.fqNameSafe
    val targetPackage = targetFqName.parent()

    val relevantUris = sp.dependencyTracker.filesInPackageOrImporting(targetPackage)
    // External/JAR types are not in any source package, and specific class imports
    // (e.g. `import kotlin.collections.AbstractList`) don't match the package-level
    // query. Fall back to all source files when the dependency tracker finds nothing.
    val urisToScan = relevantUris.ifEmpty { sp.fileContentHashes().keys }
    val context = sp.compileFiles(urisToScan)

    val fromSource = context.getSliceContents(BindingContext.CLASS)
        .values
        .filter { classDescriptor ->
            classDescriptor != target
            && classDescriptor.directSuperClassifiers().any { it.fqNameSafe == targetFqName }
        }
        .mapNotNull { toTypeHierarchyItem(it, sp, ctx) }

    // Supplement with indexed subtypes (e.g. an external/JAR class implementing a
    // source interface). Source PSI results win for range/URI on FQN collision.
    val fromIndex = sp.index.subtypesOf(targetFqName)
        .mapNotNull { sp.classDescriptorByFqName(it) }
        .mapNotNull { toTypeHierarchyItem(it, sp, ctx) }

    return mergeByFqName(fromSource, fromIndex)
        .sortedWith(compareBy({ it.uri }, { it.range.start.line }))
}

/**
 * Merges two hierarchy item lists, de-duplicating by FQN (from [TypeHierarchyItem.data]).
 * [primary] entries win over [secondary] on collision.
 */
private fun mergeByFqName(
    primary: List<TypeHierarchyItem>,
    secondary: List<TypeHierarchyItem>
): List<TypeHierarchyItem> {
    val seen = primary.mapNotNull { it.data as? String }.toMutableSet()
    val result = primary.toMutableList()
    for (item in secondary) {
        val fqName = item.data as? String
        if (fqName == null || seen.add(fqName)) {
            result.add(item)
        }
    }
    return result
}

@Suppress("ReturnCount") // many early-exit paths are natural for resolvers
private fun resolveDescriptorAt(file: CompiledFile, cursor: Int): ClassDescriptor? {
    // Declaration site: cursor on or within a class/object/interface/typealias.
    // Checked first to avoid calling referenceAtPoint (which triggers
    // parseAtPoint with asReference=true, creating a virtual file that can
    // cause re-entrant compilation / StackOverflow in the full test suite).
    val psi = file.elementAtPoint(cursor)
    if (psi != null) {
        val fromDecl = classDescriptorFromPsi(psi, file)
        if (fromDecl != null) return fromDecl
    }

    // Reference site: cursor on a type usage (e.g. supertype `: Expr` in a class
    // header, or a typealias used at a call site). Only reached when the PSI path
    // doesn't find a class/typealias declaration, so parseAtPoint won't crash
    // (the crash only happens for class declaration names near file start).
    val fromReference = file.referenceAtPoint(cursor)
    if (fromReference != null) {
        val descriptor = fromReference.second
        if (descriptor is ClassDescriptor) return descriptor
        if (descriptor is TypeAliasDescriptor) return descriptor.classDescriptor
    }

    return null
}

private fun resolveItem(item: TypeHierarchyItem, sp: SourcePath): ClassDescriptor? {
    val uri = parseURI(item.uri)

    // Source (file://) items that are on the source path resolve by reparsing
    // the declaration at the cursor. Decompiled temp files (also file:// URIs
    // but not on the source path) fall through to FQN resolution below.
    if (uri.scheme == "file") {
        val sourceResult = resolveSourceItem(item, sp)
        if (sourceResult != null) return sourceResult
    }

    // External items (kls://, decompiled temp files, jar:) resolve by FQN stored
    // in `data`. This lets clients expand the hierarchy further from a JAR-backed
    // node (e.g. supertypes of AbstractList) without reparsing a decompiled file.
    // Note: after LSP roundtrip, `data` may be a Gson `JsonPrimitive`, not `String`.
    val fqName = extractDataString(item.data)
    if (fqName != null) {
        return sp.classDescriptorByFqName(FqName(fqName))
            ?: nullResult("Could not resolve external type hierarchy item by FQN: $fqName")
    }

    return nullResult("Cannot resolve type hierarchy item without FQN data: $uri")
}

/** Extracts a string from [data] which may be a `String` or a Gson `JsonPrimitive` after LSP roundtrip. */
private fun extractDataString(data: Any?): String? = when (data) {
    is String -> data
    is JsonPrimitive -> data.asString
    else -> null
}

@Suppress("ReturnCount")
private fun resolveSourceItem(item: TypeHierarchyItem, sp: SourcePath): ClassDescriptor? {
    val uri = parseURI(item.uri)
    // resolveSourceItem is called for every file:// item, including decompiled
    // temp files that were already cleaned up. currentVersion/content throw for
    // files no longer tracked by SourcePath; we swallow the exception here so
    // that resolveItem can fall through to the FQN-based external resolution path
    // (which reconstructs the file from its .class entry).
    val file = try {
        sp.currentVersion(uri)
    } catch (e: Exception) {
        LOG.warn("Failed to get current version of {}: {}. Falling through to FQN resolution.", uri, e.message)
        return null
    }

    val content = try {
        sp.content(uri)
    } catch (e: Exception) {
        LOG.warn("Failed to get content of {}: {}. Falling through to FQN resolution.", uri, e.message)
        return null
    }

    val cursor = offset(content, item.selectionRange.start)

    val psi = file.elementAtPoint(cursor)
        ?: return nullResult("Could not find element at ${item.uri}:${item.selectionRange.start.line}")

    val fromPsi = classDescriptorFromPsi(psi, file)
    if (fromPsi != null) return fromPsi

    return nullResult("Cursor not on a class or type alias at ${item.uri}:${item.selectionRange.start.line}")
}

@Suppress("ReturnCount") // multi-case resolver with natural early exits
private fun toTypeHierarchyItem(descriptor: ClassDescriptor, sp: SourcePath, ctx: TypeHierarchyContext): TypeHierarchyItem? {
    val name = descriptor.name.asString()
    val kind = kindForClassDescriptor(descriptor)
    val detail = typeDetail(descriptor)
    val data = descriptor.fqNameSafe.asString()
    val zeroRange = Range(Position(0, 0), Position(0, 0))

    // Case 1: descriptor has resolvable source PSI (source file or decompiled Kotlin from JAR)
    val psi = descriptor.findPsi() as? KtNamedDeclaration
    if (psi != null) {
        val fileUri = psi.containingFile.toURIString()

        // Source files (file:// URI, not inside an archive): use PSI ranges directly.
        if (parseURI(fileUri).scheme == "file" && !fileUri.contains(".jar!") && !fileUri.contains(".zip!")) {
            val content = sp.content(parseURI(fileUri))
            val nameIdentifier = psi.nameIdentifier ?: return null
            return TypeHierarchyItem(
                name, kind, fileUri,
                range(content, psi.textRange),
                range(content, nameIdentifier.textRange)
            ).apply { this.detail = detail; this.data = data }
        }

        // Archive URIs (jar:, jrt:, file://...jar!): route through decompiler to
        // get a navigable kls:// URI and find the declaration range in decompiled content.
        val decompiled = decompileLocation(Location(fileUri, zeroRange), descriptor, ctx)
        return TypeHierarchyItem(name, kind, decompiled.uri, decompiled.range, decompiled.range).apply {
            this.detail = detail; this.data = data
        }
    }

    // Case 2: no Kotlin PSI -- try location(descriptor) (handles Java classes and
    // some deserialized descriptors whose source is a PsiSourceFile).
    val loc = location(descriptor)
    if (loc != null) {
        val decompiled = decompileLocation(loc, descriptor, ctx)
        return TypeHierarchyItem(name, kind, decompiled.uri, decompiled.range, decompiled.range).apply {
            this.detail = detail; this.data = data
        }
    }

    // Case 3: no PSI and no source file -- construct a file://...jar! URI from the
    // FQN and the classpath, then decompile. This handles purely deserialized
    // classes (e.g. stdlib classes from JAR metadata with NO_SOURCE_FILE).
    val fqName = descriptor.fqNameSafe
    if (!fqName.isRoot) {
        val classFilePath = fqName.parent().asString().replace('.', '/') + '/' +
            fqName.shortName().asString() + ".class"
        val jarInfo = ctx.cp.findJarContainingClass(classFilePath)
        if (jarInfo != null) {
            val jarFileUri = Paths.get(jarInfo.first).toUri().toString() + "!/" + classFilePath
            val decompiled = decompileLocation(Location(jarFileUri, zeroRange), descriptor, ctx)
            return TypeHierarchyItem(name, kind, decompiled.uri, decompiled.range, decompiled.range).apply {
                this.detail = detail; this.data = data
            }
        }
    }

    // Case 4: no resolvable source (e.g. synthetic kotlin.Any, not on classpath)
    return null
}

/**
 * Resolves a [ClassDescriptor] from a PSI element by walking up the tree to find
 * a class/object/interface or type alias declaration and looking up its descriptor.
 * Used both at the cursor (prepareTypeHierarchy) and when restoring from a
 * TypeHierarchyItem (resolveSourceItem), hence extracted to avoid duplication.
 */
private fun classDescriptorFromPsi(psi: KtElement, file: CompiledFile): ClassDescriptor? {
    val classDecl = psi.findParent<KtClassOrObject>()
    if (classDecl != null) {
        return file.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, classDecl] as? ClassDescriptor
    }
    val typeAliasDecl = psi.findParent<KtTypeAlias>()
    if (typeAliasDecl != null) {
        val aliasDesc = file.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, typeAliasDecl] as? TypeAliasDescriptor
        return aliasDesc?.classDescriptor
    }
    return null
}

/** Routes a [Location] through the decompiler to convert jar:/jrt: URIs to navigable kls:// URIs. */
private fun decompileLocation(loc: Location, descriptor: ClassDescriptor, ctx: TypeHierarchyContext): Location =
    decompileArchiveLocation(
        ArchiveDecompileContext(
            loc, descriptor,
            ctx.classContentProvider, ctx.tempDir,
            ctx.config.useKlsScheme, typeHierarchyPattern,
            ctx.cp.javaHome
        )
    )

private fun typeDetail(descriptor: ClassDescriptor): String {
    val typeParams = descriptor.declaredTypeParameters
    if (typeParams.isEmpty()) return descriptor.fqNameSafe.asString()

    val paramNames = typeParams.joinToString(", ") { it.name.asString() }
    return "${descriptor.fqNameSafe.asString()}<$paramNames>"
}

private fun kindForClassDescriptor(descriptor: ClassDescriptor): SymbolKind = when (descriptor.kind) {
    ClassKind.INTERFACE -> SymbolKind.Interface
    ClassKind.ENUM_CLASS -> SymbolKind.Enum
    ClassKind.ENUM_ENTRY -> SymbolKind.EnumMember
    ClassKind.OBJECT -> SymbolKind.Object
    ClassKind.ANNOTATION_CLASS -> SymbolKind.Class
    ClassKind.CLASS -> SymbolKind.Class
}
