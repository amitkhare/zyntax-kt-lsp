package org.javacs.kt

// TODO: Refactor - file has too many functions (37), split into smaller modules (file handling, index management, etc.)
// See detekt TooManyFunctions threshold exception

import com.intellij.lang.Language

import org.javacs.kt.compiler.CompilationKind
import org.javacs.kt.database.DatabaseService
import org.javacs.kt.index.SymbolIndex
import org.javacs.kt.progress.Progress
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.fileExtension
import org.javacs.kt.util.filePath
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter

import java.io.Closeable
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.LinkedHashMap
import kotlin.concurrent.withLock

/**
 * Holds the result of a compilation: parsed file, binding context, and module descriptor.
 * Used for both single-file and multi-file compilation caches.
 */
private data class CompilationCache(
    val file: KtFile,
    val context: BindingContext,
    val module: ModuleDescriptor
) {
    /**
     * Checks if the cache is stale compared to current content and parsed tree.
     * A cache is stale if either:
     * - the compiled file's text differs from current content, or
     * - the parsed tree's text differs from the compiled file's text.
     *
     * If [currentParsed] is null (file not yet parsed), the cache is conservatively
     * considered stale to prefer recompilation over potentially stale results.
     */
    fun isStale(currentContent: String, currentParsed: KtFile?): Boolean =
        file.text != currentContent || currentParsed?.text != file.text

    fun toTriple(): Triple<ModuleDescriptor, BindingContext, KtFile> = Triple(module, context, file)
}

class SourcePath(
    private val cp: CompilerClassPath,
    private val contentProvider: URIContentProvider,
    private val indexingConfig: IndexingConfiguration,
    databaseService: DatabaseService,
    private val cacheConfig: CacheConfiguration = CacheConfiguration()
): Closeable {
    private val files = Collections.synchronizedMap(object : LinkedHashMap<URI, SourceFile>((cacheConfig.maxSourceFiles * 1.25).toInt(), 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<URI, SourceFile>): Boolean {
            if (size > cacheConfig.maxSourceFiles) {
                eldest.value.clean()
                return true
            }
            return false
        }
    })
    private val parseDataWriteLock = ReentrantLock()

    var indexEnabled: Boolean by indexingConfig::enabled
    val index = SymbolIndex(databaseService)
    private val indexRefreshDisabled = AtomicBoolean(false)
    /**
     * Per-file import index. Lets `compileFiles()` cheaply find which other files
     * depend on a changed one and recompile them too.
     */
    val dependencyTracker = DependencyTracker()

    /**
     * Workspace cache for skipping compilation on startup when nothing has changed.
     *
     * Set by KotlinLanguageServer after database setup.
     */
    var workspaceCache: WorkspaceCache? = null

    /**
     * Hook for snapshotting the `WorkspaceCache` fingerprint from the latest content
     * before each multi-file compile runs. No-op by default.
     *
     * Wired up by the text-document service so the persisted cache matches
     * the post-lint state.
     */
    var beforeCompileCallback: () -> Unit = {}

    /**
     * Factory for LSP `window/workDoneProgress` notifications.
     *
     * Cascaded to `index.progressFactory` so background indexing can report progress
     * to the editor.
     */
    var progressFactory: Progress.Factory = Progress.Factory.None
        set(factory) {
            field = factory
            index.progressFactory = factory
        }

    private inner class SourceFile(
        /**
         * Unique identifier for the file. Used as the key in the source path map.
         */
        val uri: URI,
        /**
         * Current text content of the file.
         *
         * Updated on every edit and used for parsing and cache invalidation.
         */
        var content: String,
        /**
         * Filesystem path if available.
         *
         * Used for creating PSI files with correct virtual file paths.
         */
        val path: Path? = uri.filePath,
        /**
         * Parsed PSI tree. Lazily created and cached to avoid re-parsing unchanged content.
         */
        var parsed: KtFile? = null,
        /**
         * Language of the file (Kotlin or Kotlin script).
         *
         * TODO: Currently unused but stored for future multi-language support.
         */
        val language: Language? = null,
        /**
         * A temporary source file is excluded from `all()` and is not indexed.
         *
         * Used for ephemeral files like completion snippets.
         */
        val isTemporary: Boolean = false,
        /**
         * Last successfully compiled file used for code generation.
         * Needed to delete old .class files before regenerating.
         */
        var lastSavedFile: KtFile? = null,
        /**
         * Single-file compilation cache populated by doCompile().
         * Used for fast LSP features (hover, completion, goto-definition).
         */
        @Volatile var singleFileCache: CompilationCache? = null,
        /**
         * Multi-file compilation cache populated by compileFiles/compileAndUpdate().
         * Used for diagnostics, indexing, and cross-file type info.
         */
        @Volatile var multiFileCache: CompilationCache? = null,
    ) {
        /**
         * xxHash3-64 of [content], recomputed on every [put].
         *
         * [SourcePath.workspaceCache] must be wired before any file is added; the
         * `checkNotNull` in [put] and [SourcePath.put] guarantees this invariant.
         * Set to 0L in [clean] to mark the file as evicted from the LRU cache.
         */
        var contentHash: Long = 0L
        val extension: String = uri.fileExtension ?: "kt"
        val isScript: Boolean = extension == "kts"
        val kind: CompilationKind =
            if (path?.fileName?.toString()?.endsWith(".gradle.kts") ?: false) CompilationKind.BUILD_SCRIPT
            else CompilationKind.DEFAULT

        fun put(newContent: String) {
            content = newContent
            contentHash = checkNotNull(workspaceCache) {
                "workspaceCache must be wired before files can be added (uri=$uri)"
            }.hashContent(newContent)
        }

        fun clean() {
            parsed = null
            singleFileCache = null
            multiFileCache = null
            lastSavedFile = null
            content = ""
            contentHash = 0L
        }

        fun parse() {
            // TODO: Create PsiFile using the stored language instead
            parsed = cp.compiler.createKtFile(content, path ?: Paths.get("sourceFile.virtual.$extension"), kind)
        }

        fun parseIfChanged() {
            if (content != parsed?.text) {
                parse()
            }
        }

        fun compileIfNull() = parseIfChanged().apply { doCompileIfNull() }

        private fun doCompileIfNull() {
            if (singleFileCache == null) {
                doCompileIfChanged()
            }
        }

        fun compileIfChanged() = parseIfChanged().apply { doCompileIfChanged() }

        fun compile() = parse().apply { doCompile() }

        private fun doCompile() {
            LOG.debug("Compiling {}", path?.fileName)

            val oldFile = clone()

            val parsedFile = checkNotNull(parsed) { "parsed is null in doCompile for $uri" }
            val (context, module) = cp.compiler.compileKtFile(parsedFile, allIncludingThis(), kind)
            parseDataWriteLock.withLock {
                singleFileCache = CompilationCache(parsedFile, context, module)
            }

            refreshWorkspaceIndexes(listOfNotNull(oldFile), listOfNotNull(this))
        }

        private fun doCompileIfChanged() {
            if (singleFileCache == null || singleFileCache!!.isStale(content, parsed)) {
                doCompile()
            }
        }

        fun prepareCompiledFile(): CompiledFile =
                parseIfChanged().apply { compileIfNull() }.let { doPrepareCompiledFile() }

        private fun doPrepareCompiledFile(): CompiledFile {
            val cache = checkNotNull(singleFileCache) { "singleFileCache is null after compileIfNull for $uri" }
            return CompiledFile(content, cache.file, cache.context, cache.module, allIncludingThis(), cp, isScript, kind)
        }

        fun multiFileCacheOrNull(): Triple<ModuleDescriptor, BindingContext, KtFile>? =
            multiFileCache?.toTriple()

        fun singleFileCacheOrNull(): Triple<ModuleDescriptor, BindingContext, KtFile>? =
            singleFileCache?.toTriple()

        fun prepareMultiFileCompiledFile(): CompiledFile? {
            val cache = multiFileCache ?: return null
            return CompiledFile(content, cache.file, cache.context, cache.module, allIncludingThis(), cp, isScript, kind)
        }

        private fun allIncludingThis(): Collection<KtFile> = parseIfChanged().let {
            if (isTemporary) (all().asSequence() + sequenceOf(checkNotNull(parsed) { "parsed is null in allIncludingThis for $uri" })).toList()
            else all()
        }

        // Creates a shallow copy
        fun clone(): SourceFile = SourceFile(
            uri, content, path, parsed, language, isTemporary,
            lastSavedFile = lastSavedFile,
            singleFileCache = singleFileCache,
            multiFileCache = multiFileCache
        ).also {
            it.contentHash = contentHash
        }
    }

    private fun sourceFile(uri: URI): SourceFile {
        if (uri !in files) {
            // Fallback solution, usually *all* source files
            // should be added/opened through SourceFiles
            LOG.warn("Requested source file {} is not on source path, this is most likely a bug. Adding it now temporarily...", describeURI(uri))
            put(uri, contentProvider.contentOf(uri), null, temporary = true)
        }
        return files[uri]!!
    }

    fun put(uri: URI, content: String, language: Language?, temporary: Boolean = false) {
        check(!content.contains('\r')) { "Content for $uri contains \\r characters" }

        if (temporary) {
            LOG.info("Adding temporary source file {} to source path", describeURI(uri))
        }

        if (uri in files) {
            sourceFile(uri).put(content)
        } else {
            val sf = SourceFile(uri, content, language = language, isTemporary = temporary)
            sf.contentHash = checkNotNull(workspaceCache) {
                "workspaceCache must be wired before files can be added (uri=$uri)"
            }.hashContent(content)
            files[uri] = sf
        }
    }

    fun deleteIfTemporary(uri: URI): Boolean =
        if (sourceFile(uri).isTemporary) {
            LOG.info("Removing temporary source file {} from source path", describeURI(uri))
            delete(uri)
            true
        } else {
            false
        }

    fun delete(uri: URI) {
        files[uri]?.let {
            refreshWorkspaceIndexes(listOf(it), listOf())
            cp.compiler.removeGeneratedCode(listOfNotNull(it.lastSavedFile))
        }

        files.remove(uri)
    }

    /**
     * Get the latest content of a file
     */
    fun content(uri: URI): String = sourceFile(uri).content

    fun parsedFile(uri: URI): KtFile {
        val file = sourceFile(uri).apply { parseIfChanged() }
        return checkNotNull(file.parsed) { "parsed is null after parseIfChanged" }
    }

    /**
     * Get the parsed file for a URI, or null if not available
     */
    fun tryParsedFile(uri: URI): KtFile? = files[uri]?.parsed

    /**
     * Compile the latest version of a file (single-file if possible).
     */
    fun currentVersion(uri: URI): CompiledFile =
            sourceFile(uri).apply { compileIfChanged() }.prepareCompiledFile()

    /**
     * Force a fresh single-file compilation, regardless of whether content changed.
     * Used by LSP features that always need up-to-date results (e.g. inlay hints).
     *
     * Writes to the single-file cache only -- never poisons the multi-file cache.
     */
    fun compileVersion(uri: URI): CompiledFile =
            sourceFile(uri).apply { compile() }.prepareCompiledFile()

    /**
     * Return whatever is the most recent compiled version of `file`.
     * Prefers the multi-file cache (populated by compileFiles/lint) which has
     * the most complete cross-file type information.
     *
     * Falls back to single-file cache (populated by compileIfChanged/compile)
     * if no multi-file result is available yet or if it's stale.
     */
    fun latestCompiledVersion(uri: URI): CompiledFile {
        val sf = sourceFile(uri)
        val multiFile = sf.prepareMultiFileCompiledFile()
        val multiCache = sf.multiFileCache
        if (multiFile != null && multiCache != null && multiCache.isStale(sf.content, sf.parsed).not()) {
            return multiFile
        }
        return sf.apply { compileIfChanged() }.prepareCompiledFile()
    }

    /**
     * Compile changed files.
     *
     * @param forceFresh If true, always recompile all files to get fresh diagnostics, like for linting
     */
    fun compileFiles(all: Collection<URI>, forceFresh: Boolean = false): BindingContext {
        // Parse all requested files first to get fresh content for dependency lookup
        val sources = all.map { files[it]!! }
        sources.forEach { it.parseIfChanged() }

        // Find changed files - compare current content against the last multi-file compiled version
        // When forceFresh is true, treat all files as changed to ensure fresh diagnostics
        val allChanged = if (forceFresh) {
            sources
        } else {
            sources.filter { source ->
                source.multiFileCache == null || source.multiFileCache!!.isStale(source.content, source.parsed)
            }
        }
        LOG.info("compileFiles: {} files in lint set, {} detected as changed (forceFresh={})", all.size, allChanged.size, forceFresh)
        val (changedBuildScripts, changedSources) = allChanged.partition { it.kind == CompilationKind.BUILD_SCRIPT }

        // Only rebuild tracker and find dependents when files have actually changed
        val dependentSources = if (allChanged.isNotEmpty()) {
            // Rebuild dependency tracker with new parsed content
            val allParsed = all().toList()
            if (allParsed.isNotEmpty()) {
                val uris = allParsed.map { it.containingFile.toPath().toUri() }
                dependencyTracker.rebuildIndex(allParsed, uris)
            }

            // Find files that depend on changed files using the tracker
            val dependentUris = mutableSetOf<URI>()
            for (changed in allChanged) {
                changed.parsed?.packageFqName?.let { pkg ->
                    val deps = dependencyTracker.filesInPackageOrImporting(pkg)
                    LOG.trace("Found {} dependent files for changed package {}", deps.size, pkg)
                    dependentUris.addAll(deps)
                }
            }
            // Exclude the changed files themselves
            dependentUris.removeAll(all.toSet())
            LOG.trace("Total dependent files to recompile: {}", dependentUris.size)

            // Parse dependent files (so they get recompiled)
            dependentUris.mapNotNull { files[it] }.also { it.forEach { sf -> sf.parseIfChanged() } }
        } else {
            emptyList()
        }

        // Combine changed + dependent for actual compilation
        val sourcesWithDependents = (changedSources + dependentSources).distinctBy { it.uri }
        val buildScriptsWithDependents = (changedBuildScripts + dependentSources.filter { it.kind == CompilationKind.BUILD_SCRIPT }).distinctBy { it.uri }

        // Compile changed and dependent files together
        fun compileAndUpdate(toCompile: List<SourceFile>, kind: CompilationKind): BindingContext? {
            if (toCompile.isEmpty()) return null

            LOG.info("compileAndUpdate: compiling {} files: {}", toCompile.size, toCompile.map { it.uri })

            // Get clones of the old files, so we can remove the old declarations from the index
            val oldFiles = toCompile.mapNotNull {
                if (it.multiFileCache == null || it.multiFileCache!!.isStale(it.content, it.parsed)) {
                    it.clone()
                } else {
                    null
                }
            }

            // Parse the files that need compilation (changed + dependents)
            val parse: Map<SourceFile, KtFile> = toCompile.associateWith {
                checkNotNull(it.parsed) { "parsed is null for ${it.uri} during compileAndUpdate" }
            }

            // Get all the files for module analysis
            val allFiles = all()
            beforeCompileCallback.invoke()
            try {
                val (context, module) = cp.compiler.compileKtFiles(parse.values, allFiles, kind)
                LOG.info("compileAndUpdate: compilation complete")

                // Update multi-file cache for compiled files
                for ((f, parsed) in parse) {
                    parseDataWriteLock.withLock {
                        if (f.parsed == parsed) {
                            //only updated if the parsed file didn't change:
                            f.multiFileCache = CompilationCache(parsed, context, module)
                        }
                    }
                }

                // Only index normal files, not build files
                if (kind == CompilationKind.DEFAULT) {
                    refreshWorkspaceIndexes(oldFiles, parse.keys.toList())
                }

                return context
            } catch (e: Exception) {
                LOG.error("compileAndUpdate: compilation failed with exception: {}", e.message)
                LOG.printStackTrace(e)
                return null
            }
        }

        val buildScriptsContext = compileAndUpdate(buildScriptsWithDependents, CompilationKind.BUILD_SCRIPT)
        val sourcesContext = compileAndUpdate(sourcesWithDependents, CompilationKind.DEFAULT)

        // Combine with past compilations
        val same = sources - allChanged.toSet()
        val combined = listOfNotNull(buildScriptsContext, sourcesContext) + same.mapNotNull { it.multiFileCache?.context }

        return CompositeBindingContext.create(combined)
    }

    fun compileAllFiles() {
        indexRefreshDisabled.set(true)
        try {
            // Copy keys to avoid ConcurrentModificationException - the files map is
            // modified by other executors while we're iterating in the debounced lint task
            val fileList = files.keys.toList()
            fileList.forEach { uri -> compileWithErrorLogging(uri) }
        } finally {
            indexRefreshDisabled.set(false)
        }
    }

    private fun compileWithErrorLogging(uri: URI) {
        try {
            compileFiles(listOf(uri))
        } catch (ex: Exception) {
            if (ex.javaClass.simpleName.contains("TopDownAnalyzer")
                || ex.stackTrace.any { it.className.contains("TopDownAnalyzer") }) {
                LOG.warn("TopDownAnalyzer error compiling {}: {}", uri, ex.message)
            }
            LOG.printStackTrace(ex)
        }
    }

    /**
     * Saves a file. This generates code for the file and deletes previously generated code for this file.
     */
    fun save(uri: URI) {
        files[uri]?.let { file ->
            if (!file.isScript) {
                try {
                    cp.compiler.removeGeneratedCode(listOfNotNull(file.lastSavedFile))
                    val (module, context, compiled) = file.multiFileCacheOrNull()
                        ?: file.singleFileCacheOrNull()
                        ?: return@let
                    cp.compiler.generateCode(module, context, listOfNotNull(compiled))
                    file.lastSavedFile = compiled
                } catch (ex: Exception) {
                    LOG.printStackTrace(ex)
                }
            }
        }
    }

    fun saveAllFiles() {
        val fileList = files.keys.toList()
        fileList.forEach { save(it) }
    }

    fun refreshDependencyIndexes(compileFirst: Boolean = true) {
        if (compileFirst) {
            compileAllFiles()
        }
        val sourceFiles = files.values.toList()
        val module = sourceFiles.firstNotNullOfOrNull { it.multiFileCache?.module ?: it.singleFileCache?.module }
        if (module != null && indexEnabled) {
            val declarations = getDeclarationDescriptors(sourceFiles)
            val classPath = cp.classPath.toList()
            AsyncExecutor.ioCompute {
                index.refreshWithClasspath(module, declarations, classPath)
            }
        }
    }

    /**
     * Refreshes the indexes. If already done, refreshes only the declarations in the files that were changed.
     */
    private fun refreshWorkspaceIndexes(oldFiles: List<SourceFile>, newFiles: List<SourceFile>) {
        if (!indexEnabled || indexRefreshDisabled.get()) return

        val oldDeclarations = getDeclarationDescriptors(oldFiles)
        val newDeclarations = getDeclarationDescriptors(newFiles)

        // Index the new declarations in the Kotlin source files that were just compiled, removing the old ones
        AsyncExecutor.ioCompute {
            index.updateIndexes(oldDeclarations, newDeclarations)
        }
    }

    // Gets all the declaration descriptors for the collection of files
    private fun getDeclarationDescriptors(files: Collection<SourceFile>) =
        files.flatMap { file ->
            val compiledFile = file.multiFileCache?.file ?: file.singleFileCache?.file ?: file.parsed
            val module = file.multiFileCache?.module ?: file.singleFileCache?.module
            if (compiledFile != null && module != null) {
                module.getPackage(compiledFile.packageFqName).memberScope.getContributedDescriptors(
                    DescriptorKindFilter.ALL
                ) { name -> compiledFile.declarations.map { it.name }.contains(name.toString()) }
            } else {
                listOf()
            }
        }.asSequence()

    /**
     * Recompiles all source files that are initialized.
     */
    fun refresh() {
        val snapshot = files.values.toList()
        val initialized = snapshot.any { it.parsed != null }
        if (initialized) {
            LOG.info("Refreshing source path")
            snapshot.forEach { it.clean() }
            snapshot.forEach { it.compile() }
        }
    }

    /**
     * Get parsed trees for all .kt files on source path
     */
    fun all(includeHidden: Boolean = false): Collection<KtFile> =
            files.values.toList()
                .filter { includeHidden || !it.isTemporary }
                .map { checkNotNull(it.apply { parseIfChanged() }.parsed) { "parsed is null in all() for ${it.uri}" } }

    /**
     * Parse all files without compiling them.
     * Used during cached startup to ensure PSI trees are available
     * for lazy compilation without the expensive compilation step.
     */
    fun parseAllFiles() {
        // Snapshot keys so the access-order LRU map and any concurrent edits
        // can't trip us up mid-iteration.
        val snapshot = files.keys.toList()
        snapshot.forEach { files[it]?.parseIfChanged() }
    }

    /**
     * Get content hashes for all tracked files.
     */
    fun fileContentHashes(): Map<URI, Long> {
        // Snapshot keys so the access-order LRU map and any concurrent edits
        // can't trip us up mid-iteration.
        val snapshot = files.keys.toList()
        val out = LinkedHashMap<URI, Long>(snapshot.size)
        for (uri in snapshot) {
            out[uri] = files[uri]?.contentHash ?: 0L
        }
        return out
    }

    override fun close() {
    }

    /**
     * The module descriptor of any currently-compiled file, or `null` if nothing
     * has been compiled yet. Used to resolve descriptors (e.g. external/JAR
     * classes) by fully-qualified name without re-parsing source.
     */
    val module: ModuleDescriptor?
        get() = files.values.firstNotNullOfOrNull { it.multiFileCache?.module ?: it.singleFileCache?.module }

    /**
     * Resolves a [ClassDescriptor] from a fully-qualified name using the
     * module's dependency graph. Returns `null` if no such class is visible on
     * the classpath (e.g. it lives in a JAR that was not indexed).
     *
     * Uses [findClassAcrossModuleDependencies] which handles both top-level
     * and nested classes across module dependencies, as well as JDK platform
     * classes. For nested classes like `kotlin.collections.Map.Entry`, the
     * method tries progressively shorter package prefixes (see loop below)
     * since the FQN alone doesn't reveal where the package ends and the
     * class name begins.
     */
    fun classDescriptorByFqName(fqName: FqName): ClassDescriptor? {
        val module = module ?: return null

        // Try as a top-level class first (most common case):
        // e.g. kotlin.collections.AbstractList -> package=kotlin.collections, class=AbstractList
        val topLevel = module.findClassAcrossModuleDependencies(ClassId.topLevel(fqName))
        if (topLevel != null) return topLevel

        // Try progressively shorter package prefixes for nested classes:
        // e.g. kotlin.collections.Map.Entry -> package=kotlin.collections, class=Map.Entry
        val segments = fqName.pathSegments()
        for (split in (segments.size - 2) downTo 0) {
            val packageFqName = FqName.fromSegments(segments.subList(0, split).map { it.asString() })
            val relativeClassName = FqName.fromSegments(segments.subList(split, segments.size).map { it.asString() })
            val classId = ClassId(packageFqName, relativeClassName, isLocal = false)
            val found = module.findClassAcrossModuleDependencies(classId)
            if (found != null) return found
        }

        return null
    }
}
