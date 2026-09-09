package org.javacs.kt.analysis

import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

/** One owner serializes requests and publishes complete project generations after reimport. */
internal class FirWorkspace(specs: List<SourceModuleSpec>) : AutoCloseable {
    private var definitions = freeze(specs)
    private val openText = mutableMapOf<Path, String>()
    private val versions = mutableMapOf<Path, Long>()
    private var project = FirProject(definitions, versions)
    private var retiredParses = 0
    private var closed = false
    var generation = 0L
        private set
    val parseCount: Int @Synchronized get() = retiredParses + project.parseCount

    @Synchronized fun <T> read(path: Path, action: (KtFile) -> T): T {
        check(!closed)
        return project.read(canonical(path), action)
    }

    /** Editor text remains authoritative through disk/model refreshes and variant switches. */
    @Synchronized fun update(path: Path, text: String) {
        check(!closed)
        val key = canonical(path)
        if (definitions.any { key in it.files }) project.update(key, text)
        else require(key in openText) { "Source has no selected owner or open document: $key" }
        openText[key] = text
        versions[key] = versions.getOrDefault(key, 0) + 1
    }

    /** The caller supplies fresh disk text; the workspace never writes source files. */
    @Synchronized fun closeDocument(path: Path, diskText: String) {
        check(!closed)
        val key = canonical(path)
        if (definitions.any { key in it.files }) {
            project.update(key, diskText)
            definitions = definitions.map { spec ->
                if (key in spec.files) spec.copy(files = spec.files + (key to diskText)) else spec
            }
            versions[key] = versions.getOrDefault(key, 0) + 1
        }
        openText.remove(key)
    }

    @Synchronized fun addSource(moduleName: String, path: Path, diskText: String) {
        check(!closed)
        val key = canonical(path)
        require(definitions.any { it.name == moduleName }) { "Unknown source module: $moduleName" }
        project.add(moduleName, key, openText[key] ?: diskText, versions.getOrDefault(key, 0))
        definitions = definitions.map { spec ->
            if (spec.name == moduleName) spec.copy(files = spec.files + (key to diskText)) else spec
        }
    }

    @Synchronized fun removeSource(path: Path) {
        check(!closed)
        val key = canonical(path)
        project.remove(key)
        definitions = definitions.map { it.copy(files = it.files - key) }
    }

    /** Binary roots and module settings are project services: replace them as one generation. */
    @Synchronized fun reimport(specs: List<SourceModuleSpec>) {
        check(!closed)
        val nextDefinitions = freeze(specs)
        val next = FirProject(nextDefinitions.map { spec ->
            spec.copy(files = spec.files.mapValues { (path, diskText) -> openText[path] ?: diskText })
        }, versions)
        val previous = project
        retiredParses += previous.parseCount
        project = next
        definitions = nextDefinitions
        generation++
        previous.close()
    }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            project.close()
            openText.clear()
            versions.clear()
        }
    }

    private fun freeze(specs: List<SourceModuleSpec>) = specs.map { spec ->
        val files = spec.files.entries.associate { canonical(it.key) to it.value }
        require(files.size == spec.files.size) { "Duplicate normalized source path in ${spec.name}" }
        spec.copy(files = files, dependencies = spec.dependencies.map { it.normalized() }, friends = spec.friends.map { it.normalized() })
    }
}

internal fun canonical(path: Path): Path {
    require(path.isAbsolute) { "Analysis path must be absolute: $path" }
    return path.normalize()
}
