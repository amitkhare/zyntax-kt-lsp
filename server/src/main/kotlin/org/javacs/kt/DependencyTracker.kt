package org.javacs.kt

import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class DependencyTracker {
    private val state = AtomicReference(TrackerState.EMPTY)

    data class TrackerState(
        val fileImports: Map<URI, Set<FqName>>,
        val importedBy: Map<FqName, Set<URI>>,
        val filesInPackage: Map<FqName, Set<URI>>
    ) {
        companion object {
            val EMPTY = TrackerState(emptyMap(), emptyMap(), emptyMap())

            fun build(files: Collection<KtFile>, uris: Collection<URI>): TrackerState {
                LOG.info("Building dependency index with {} files", files.size)

                val fileImports = mutableMapOf<URI, Set<FqName>>()
                val importedBy = mutableMapOf<FqName, MutableSet<URI>>()
                val filesInPackage = mutableMapOf<FqName, MutableSet<URI>>()

                for ((file, uri) in files.zip(uris)) {
                    val packageFqName = file.packageFqName ?: FqName.ROOT
                    filesInPackage.getOrPut(packageFqName) { mutableSetOf() }.add(uri)

                    val imports = file.importDirectives
                        .mapNotNull { it.importedFqName }
                        .toSet()

                    fileImports[uri] = imports

                    for (fqName in imports) {
                        importedBy.getOrPut(fqName) { mutableSetOf() }.add(uri)
                    }
                }

                LOG.info("Dependency index built: {} packages, {} import relationships",
                    filesInPackage.size, importedBy.size)

                return TrackerState(
                    fileImports = fileImports,
                    importedBy = importedBy,
                    filesInPackage = filesInPackage
                )
            }
        }
    }

    /**
     * Returns all files that import the given FqName.
     * Used internally by filesInPackageOrImporting().
     */
    fun filesImporting(fqName: FqName): Set<URI> {
        val result = state.get().importedBy[fqName] ?: emptySet()
        LOG.trace("filesImporting({}) -> {} results", fqName, result.size)
        return result
    }

    /**
     * Returns all files in the given package.
     * Used internally by filesInPackageOrImporting().
     */
    fun filesInPackage(packageFqName: FqName): Set<URI> {
        val result = state.get().filesInPackage[packageFqName] ?: emptySet()
        LOG.trace("filesInPackage({}) -> {} results", packageFqName, result.size)
        return result
    }

    fun filesInPackageOrImporting(packageFqName: FqName): Set<URI> {
        val currentState = state.get()
        if (currentState == TrackerState.EMPTY) {
            LOG.info("Dependency index is empty, returning empty results for {}", packageFqName)
            return emptySet()
        }
        val inPackage = currentState.filesInPackage[packageFqName] ?: emptySet()
        val importing = currentState.importedBy[packageFqName] ?: emptySet()
        val result = inPackage + importing
        LOG.info("filesInPackageOrImporting({}) -> {} results ({} in package, {} importing)",
            packageFqName, result.size, inPackage.size, importing.size)
        return result
    }

    /**
     * Returns all packages imported by the given file.
     * Currently unused but kept for potential future LSP features (e.g., organize imports).
     */
    fun getImportedPackages(uri: URI): Set<FqName> =
        state.get().fileImports[uri] ?: emptySet()

    fun rebuildIndex(files: Collection<KtFile>, uris: Collection<URI>) {
        LOG.info("Rebuilding dependency index with {} files", files.size)
        val newState = TrackerState.build(files, uris)
        state.set(newState)
    }

    /**
     * Clears the dependency index.
     * Currently unused but kept for potential workspace reset functionality.
     */
    fun clear() {
        LOG.info("Clearing dependency index")
        state.set(TrackerState.EMPTY)
    }

    fun isEmpty(): Boolean = state.get() == TrackerState.EMPTY
}
