package org.javacs.kt.docs

import org.javacs.kt.CompilerClassPath
import org.javacs.kt.LOG
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.externalsources.JdkSrcZipLocator
import org.javacs.kt.externalsources.JdkSrcZipResult
import org.javacs.kt.externalsources.toKlsURI
import org.javacs.kt.position.location
import org.javacs.kt.util.parseURI
import org.javacs.kt.util.preOrderTraversal
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.kdoc.parser.KDocKnownTag
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import java.io.File
import java.util.zip.ZipFile

/**
 * Finds documentation for a declaration.
 * First tries PSI-based lookup (for project files), then falls back to external source lookup
 * (for JARs with source archives) if classContentProvider and cp are provided.
 */
fun findDoc(
    declaration: DeclarationDescriptorWithSource,
    classContentProvider: ClassContentProvider? = null,
    cp: CompilerClassPath? = null
): String? {
    val source = DescriptorToSourceUtils.descriptorToDeclaration(declaration)?.navigationElement

    val psiDoc: KDocTag? = when (source) {
        is KtParameter -> {
            val declarations = source.parents.filterIsInstance<KtDeclaration>().toList()
            var container = declarations.firstOrNull() ?: return null
            if (container is KtPrimaryConstructor)
                container = declarations.getOrNull(1) ?: return null
            val doc = container.docComment ?: return null
            val descendants = doc.preOrderTraversal()
            val tags = descendants.filterIsInstance<KDocTag>()
            val params = tags.filter { it.knownTag == KDocKnownTag.PARAM }
            val matchName = params.filter { it.getSubjectName() == declaration.name.toString() }

            matchName.firstOrNull()
        }
        is KtPrimaryConstructor -> {
            val container = source.parents.filterIsInstance<KtDeclaration>().firstOrNull() ?: return null
            val doc = container.docComment ?: return null
            doc.findSectionByTag(KDocKnownTag.CONSTRUCTOR) ?: doc.getDefaultSection()
        }
        is KtDeclaration -> {
            val doc = source.docComment ?: return null
            doc.getDefaultSection()
        }
        else -> null
    }

    if (psiDoc != null) {
        return psiDoc.getContent()
    }

    if (classContentProvider != null && cp != null) {
        return findDocFromExternalSource(declaration, classContentProvider, cp)
    }

    return null
}

/**
 * The identifier used to look up [declaration]'s doc in its source file.
 *
 * Most names equal their descriptor name, but constructors don't: descriptors name every
 * constructor `<init>`, which never appears in source. Constructors are declared by class name
 * (`public UUID(...)`, `class Foo(...)`), so they are searched by their containing class name.
 */
private fun sourceSearchName(declaration: DeclarationDescriptorWithSource): String {
    val containingClass = (declaration as? ConstructorDescriptor)?.containingDeclaration as? ClassDescriptor
    return if (containingClass != null) containingClass.name.asString() else declaration.name.asString()
}

/** Extracts documentation from an external source JAR (compiled or source). */
private fun findDocFromExternalSource(
    declaration: DeclarationDescriptorWithSource,
    classContentProvider: ClassContentProvider,
    cp: CompilerClassPath
): String? {
    // First try the location-based approach
    val loc = location(declaration)
    if (loc != null && isArchiveUri(loc.uri)) {
        val klsUri = parseURI(loc.uri).toKlsURI()
        if (klsUri != null) {
            try {
                val (sourceUri, sourceContent) = classContentProvider.contentOf(klsUri)
                return extractKDocFromSource(sourceContent, sourceSearchName(declaration), sourceUri.fileName, declaration)
            } catch (e: Exception) {
                LOG.warn("Could not extract KDoc from $klsUri: {}", e.message)
                LOG.printStackTrace(e)
            }
        }
    }

    // Fallback: use JAR index to find source
    return findDocFromClassFile(declaration, cp)
}

/**
 * Extracts documentation by finding the JAR containing the declaration's class,
 * then looking up the source JAR and extracting KDoc/Javadoc from the source.
 */
private fun findDocFromClassFile(
    declaration: DeclarationDescriptorWithSource,
    cp: CompilerClassPath
): String? {
    // Determine the class to use for source file lookup:
    // - If declaration is a class itself (top-level), use it
    // - If declaration is a member (function/property), use its containing class
    val container: ClassDescriptor = when (declaration) {
        is ClassDescriptor -> declaration
        else -> declaration.containingDeclaration as? ClassDescriptor ?: return null
    }

    // Get the fully qualified name of the class containing this declaration
    // For nested classes, we use the outer class's FQ name since JARs contain the outer class file
    val outerContainer = container.containingDeclaration as? ClassDescriptor
    val effectiveClass = outerContainer ?: container

    val fqName = effectiveClass.fqNameSafe
    val fqNameString = fqName.asString()

    // Build class file path using the outer class
    // - For top-level classes (e.g., java.lang.Runtime): java/lang/Runtime.class
    // - For nested classes, we always use the outer class (e.g., kotlin.random.Random for Random.Default)
    val classFilePath = fqNameString.replace('.', '/') + ".class"

    // Separate JDK classes from regular JARs: JDK docs come from src.zip, not from the classpath index
    val jdkSrc: JdkSrcZipResult? = if (classFilePath.startsWith("java/") || classFilePath.startsWith("javax/")) {
        JdkSrcZipLocator.resolve(cp.jdkSourceOverride) ?: return null
    } else null

    val (_, sourceJarPath) = jdkSrc?.let { Pair(it.path, it.path) }
        ?: cp.findJarContainingClass(classFilePath)
        ?: return null

    // Build the KLS URI pointing to the source JAR
    // For source files, use the outer class name without inner class part
    val sourceFqName = effectiveClass.fqNameSafe
    val className = sourceFqName.shortName().asString()

    // Try to find the source file in the JAR, handling KMP prefixes
    val (sourceContent, sourceFileName) = findSourceInJar(sourceJarPath!!, className)
        ?: return null

    val targetName = sourceSearchName(declaration)

    return extractKDocFromSource(sourceContent, targetName, sourceFileName, declaration)?.let { doc ->
        annotateIfNeeded(doc, jdkSrc)
    }
}

/**
 * Prepends a version-mismatch notice to the hover output when the JDK src.zip we used is
 * from a different major version than the running JVM. The notice is rendered as a
 * Markdown blockquote so clients display it in italics above the actual documentation.
 */
internal fun annotateIfNeeded(doc: String, jdkSrc: JdkSrcZipResult?): String {
    if (jdkSrc == null || jdkSrc.isExactMatch) return doc
    return "> _Documentation from Java ${jdkSrc.majorVersion}, runtime is Java ${jdkSrc.runningMajorVersion}._\n\n$doc"
}

/**
 * Searches for a source file inside a source JAR.
 * Handles KMP libraries with commonMain/, jvmMain/, etc. prefixes.
 * Tries both .kt and .java extensions.
 *
 * @return Pair of (content, fileName) or null if not found
 */
private fun findSourceInJar(
    sourceJarPath: String,
    className: String
): Pair<String, String>? {
    val jarFile = File(sourceJarPath)
    if (!jarFile.exists()) {
        return null
    }

    return try {
        ZipFile(jarFile).use { zip ->
            val candidates = findSourceCandidates(zip, className)
            if (candidates.isEmpty()) return null
            findBestSourceMatch(zip, candidates)
        }
    } catch (e: Exception) {
        LOG.warn("Could not find JDK from $sourceJarPath: {}", e.message)
        LOG.printStackTrace(e)
        null
    }
}

/**
 * Finds all source archive entries matching [className] in [zip], handling KMP source set
 * prefixes (commonMain, jvmMain, etc.) and both .kt and .java extensions.
 */
private fun findSourceCandidates(zip: ZipFile, className: String): List<String> {
    return zip.entries().asSequence()
        .filter { entry ->
            val entryName = entry.name
            (entryName.endsWith("/$className.kt") || entryName.endsWith("/$className.java")) &&
            !entry.isDirectory
        }
        .map { it.name }
        .toList()
}

/**
 * Selects the best source entry from [candidates] using KMP priority order
 * (commonMain > jvmMain > androidMain > ... > fallback).
 */
private fun findBestSourceMatch(zip: ZipFile, candidates: List<String>): Pair<String, String>? {
    val priorityPrefixes = listOf(
        "commonMain/",
        "jvmMain/",
        "androidMain/",
        "jsMain/",
        "nativeMain/",
        ""
    )

    for (prefix in priorityPrefixes) {
        val matchName = candidates.find { it.startsWith(prefix) ||
            (prefix.isEmpty() && !it.contains("Main/")) }
        val entry = if (matchName != null) zip.getEntry(matchName) else null
        if (entry != null) {
            val content = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            return Pair(content, matchName!!)
        }
    }

    val entryName = candidates.first()
    val entry = zip.getEntry(entryName) ?: return null
    val content = zip.getInputStream(entry).bufferedReader().use { it.readText() }
    return Pair(content, entryName)
}
