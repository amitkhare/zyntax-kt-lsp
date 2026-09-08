package org.javacs.kt.util

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.javacs.kt.externalsources.ClassContentProvider
import org.javacs.kt.externalsources.KlsURI
import org.javacs.kt.externalsources.toKlsURI
import org.javacs.kt.position.isZero
import org.javacs.kt.position.position
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

private const val MAX_CACHED_TEMP_FILES = 100
private const val CACHE_LOAD_FACTOR = 0.75f
private const val CACHE_INITIAL_CAPACITY_MULTIPLIER = 1.25f

private val cachedTempFiles = Collections.synchronizedMap(object : LinkedHashMap<KlsURI, Path>(
    (MAX_CACHED_TEMP_FILES * CACHE_INITIAL_CAPACITY_MULTIPLIER).toInt(),
    CACHE_LOAD_FACTOR,
    true
) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<KlsURI, Path>): Boolean {
        if (size > MAX_CACHED_TEMP_FILES) {
            eldest.value.toFile().delete()
            return true
        }
        return false
    }
})

private const val JAR_SCHEME = ".jar!"
private const val ZIP_SCHEME = ".zip!"

fun isInsideArchive(uri: String, javaHome: String?): Boolean {
    if (uri.contains(JAR_SCHEME) || uri.contains(ZIP_SCHEME)) {
        return true
    }
    return javaHome?.let {
        Paths.get(parseURI(uri)).toString().startsWith(File(it).path)
    } ?: false
}

data class ArchiveDecompileContext(
    val destination: Location,
    val target: DeclarationDescriptor,
    val classContentProvider: ClassContentProvider,
    val tempDir: TemporaryDirectory,
    val useKlsScheme: Boolean,
    val declarationPattern: Regex,
    val javaHome: String?
)

fun decompileArchiveLocation(context: ArchiveDecompileContext): Location {
    val rawClassURI = context.destination.uri

    if (!isInsideArchive(rawClassURI, context.javaHome)) {
        return context.destination
    }

    val klsURI = parseURI(rawClassURI).toKlsURI() ?: return context.destination
    val (klsSourceURI, content) = context.classContentProvider.contentOf(klsURI)

    updateDestinationUri(context.destination, klsSourceURI, content, context)
    updateDestinationRangeIfNeeded(context.destination, context.target, content, context.declarationPattern)

    return context.destination
}

private fun updateDestinationUri(
    destination: Location,
    klsSourceURI: KlsURI,
    content: String,
    context: ArchiveDecompileContext
) {
    if (context.useKlsScheme) {
        destination.uri = klsSourceURI.toString()
    } else {
        destination.uri = getOrCreateTempFile(klsSourceURI, content, context.tempDir).toUri().toString()
    }
}

private fun getOrCreateTempFile(klsSourceURI: KlsURI, content: String, tempDir: TemporaryDirectory): Path {
    cachedTempFiles[klsSourceURI]?.let { return it }

    val name = klsSourceURI.fileName.partitionAroundLast(".").first
    val extension = klsSourceURI.fileExtension?.let { ".$it" } ?: ""
    val tmpFile = tempDir.createTempFile(name, extension)

    tmpFile.toFile().writeText(content)
    cachedTempFiles[klsSourceURI] = tmpFile

    return tmpFile
}

private fun updateDestinationRangeIfNeeded(
    destination: Location,
    target: DeclarationDescriptor,
    content: String,
    declarationPattern: Regex
) {
    if (destination.range.isZero) {
        val name = extractTargetName(target)
        val range = findDeclarationRange(content, declarationPattern, name)
        range?.let { destination.range = Range(position(content, it.first), position(content, it.last)) }
    }
}

private fun extractTargetName(target: DeclarationDescriptor): String = when (target) {
    is ConstructorDescriptor -> target.constructedClass.name.toString()
    else -> target.name.toString()
}

private fun findDeclarationRange(content: String, pattern: Regex, name: String): IntRange? {
    return pattern.findAll(content)
        .map { checkNotNull(it.groups[1]) { "Regex ${pattern.pattern} has no group 1 in match: ${it.value}" } }
        .find { it.value == name }
        ?.range
}
