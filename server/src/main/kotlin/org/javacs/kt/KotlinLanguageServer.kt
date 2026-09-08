package org.javacs.kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.javacs.kt.command.ALL_COMMANDS
import org.javacs.kt.database.DatabaseService
import org.javacs.kt.progress.LanguageClientProgress
import org.javacs.kt.progress.Progress
import org.javacs.kt.semantictokens.semanticTokensLegend
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.parseURI
import org.javacs.kt.externalsources.*
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture

class KotlinLanguageServer(
    val config: Configuration = Configuration()
) : LanguageServer, LanguageClientAware, Closeable {
    val databaseService = DatabaseService()
    val classPath = CompilerClassPath(config.compiler, config.scripts, config.codegen, databaseService)

    private val tempDirectory = TemporaryDirectory()
    private val uriContentProvider = URIContentProvider(ClassContentProvider(config.externalSources, classPath, tempDirectory, CompositeSourceArchiveProvider(JdkSourceArchiveProvider(classPath), ClassPathSourceArchiveProvider(classPath))))
    val sourcePath = SourcePath(classPath, uriContentProvider, config.indexing, databaseService, config.cache)
    val sourceFiles = SourceFiles(sourcePath, uriContentProvider, config.scripts, config.externalSources)

    private val textDocuments = KotlinTextDocumentService(sourceFiles, sourcePath, config, tempDirectory, uriContentProvider, classPath)
    private val workspaces = KotlinWorkspaceService(sourceFiles, sourcePath, classPath, textDocuments, config)
    private val protocolExtensions = KotlinProtocolExtensionService(uriContentProvider, classPath, sourcePath)

    private lateinit var client: LanguageClient

    private val async = AsyncExecutor
    private var progressFactory: Progress.Factory = Progress.Factory.None
        set(factory) {
            field = factory
            sourcePath.progressFactory = factory
        }

    companion object {
        val VERSION: String? = System.getProperty("kotlinLanguageServer.version")
    }

    init {
        LOG.info("ktlsp: Version ${VERSION ?: "?"}")
    }

    override fun connect(client: LanguageClient) {
        this.client = client

        workspaces.connect(client)
        textDocuments.connect(client)

        LOG.info("Connected to client")
    }

    override fun getTextDocumentService(): KotlinTextDocumentService = textDocuments

    override fun getWorkspaceService(): KotlinWorkspaceService = workspaces

    @JsonDelegate
    fun getProtocolExtensionService(): KotlinProtocolExtensions = protocolExtensions

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        @Suppress("DEPRECATION")
        val folders = params.workspaceFolders?.takeIf { it.isNotEmpty() }
            ?: params.rootUri?.let { WorkspaceFolder(it, "") }?.let { listOf(it) }
            ?: params.rootPath?.let(Paths::get)?.toUri()?.toString()?.let { WorkspaceFolder(it, "") }?.let { listOf(it) }
            ?: listOf()
        val workspaceRoot = folders.firstOrNull()?.let { Paths.get(parseURI(it.uri)) }

        // NOTE: init_options.storagePath is deprecated and ignored. All persistent
        // state (database, logs, exclusions) lives in <root>/.kls/ per the .kls/
        // design. getStoragePath(params) is still called so the deprecation WARN is
        // emitted for clients that set it (e.g., nvim-lspconfig's default), but its
        // return value is discarded.
        getStoragePath(params)

        workspaceRoot?.let { root ->
            KlsFolder.migrateLegacyDatabase(root)

            val hasCustomLogDir =
                System.getProperty("KLS_LOG_DIR") != null ||
                    System.getenv("KLS_LOG_DIR") != null

            if (KlsFolder.shouldLogToFile() && !hasCustomLogDir) {
                val logsPath = KlsFolder.getLogsPath(root)
                System.setProperty("KLS_LOG_DIR", logsPath.toString())
            }
        }

        LOG.initializeFileLogging()

        val result = async.compute {
            val serverCapabilities = ServerCapabilities().apply {
                setTextDocumentSync(TextDocumentSyncKind.Incremental)
                workspace = WorkspaceServerCapabilities()
                workspace.workspaceFolders = WorkspaceFoldersOptions()
                workspace.workspaceFolders.supported = true
                workspace.workspaceFolders.changeNotifications = Either.forRight(true)
                inlayHintProvider = Either.forLeft(true)
                hoverProvider = Either.forLeft(true)
                foldingRangeProvider = Either.forLeft(true)
                renameProvider = Either.forLeft(true)
                completionProvider = CompletionOptions(false, listOf("."))
                signatureHelpProvider = SignatureHelpOptions(listOf("(", ","))
                definitionProvider = Either.forLeft(true)
                declarationProvider = Either.forLeft(true)
                typeDefinitionProvider = Either.forLeft(true)
                implementationProvider = Either.forLeft(true)
                documentSymbolProvider = Either.forLeft(true)
                workspaceSymbolProvider = Either.forLeft(true)
                referencesProvider = Either.forLeft(true)
                semanticTokensProvider = SemanticTokensWithRegistrationOptions(semanticTokensLegend, true, true)
                codeActionProvider = Either.forLeft(true)
                documentFormattingProvider = Either.forLeft(true)
                documentRangeFormattingProvider = Either.forLeft(true)
                // We could also use `\n` as a possible input trigger to format the line, but this should be the safest
                documentOnTypeFormattingProvider = DocumentOnTypeFormattingOptions("}")
                executeCommandProvider = ExecuteCommandOptions(ALL_COMMANDS)
                documentHighlightProvider = Either.forLeft(true)
                callHierarchyProvider = Either.forLeft(true)
                typeHierarchyProvider = Either.forLeft(true)
                codeLensProvider = CodeLensOptions(true)
            }

            val clientCapabilities = params.capabilities
            config.completion.snippets.enabled = clientCapabilities?.textDocument?.completion?.completionItem?.snippetSupport ?: false

            if (clientCapabilities?.window?.workDoneProgress ?: false) {
                progressFactory = LanguageClientProgress.Factory(client)
            }

            if (clientCapabilities?.textDocument?.rename?.prepareSupport ?: false) {
                serverCapabilities.renameProvider = Either.forRight(RenameOptions(true))
            }

            workspaces.initialize(clientCapabilities)

            val progress = params.workDoneToken?.let { LanguageClientProgress("Workspace folders", it, client) }

            val rawStoragePath = workspaceRoot?.let { KlsFolder.getOrCreatePath(it) }
            val storagePath =
                // DatabaseService already has logic to handle the case where we have a malformed .kls structure
                if (rawStoragePath != null && !Files.isDirectory(rawStoragePath)) {
                    LOG.warn("'{}' is not a directory, falling back to in-memory database", rawStoragePath)
                    null
                } else {
                    rawStoragePath
                }

            // Always run this
            try {
                databaseService.setup(storagePath)
            } catch (e: Exception) {
                LOG.error("Failed to initialize database, falling back to in-memory database: {}", e.message)
                LOG.printStackTrace(e)
                databaseService.setup(null)
            }

            // Initialize JAR index after database is set up
            classPath.jarIndex.setup()

            // Create workspace cache after database is initialized
            val workspaceCache = databaseService.db?.let { WorkspaceCache(it) }
            sourcePath.workspaceCache = workspaceCache

            folders.forEachIndexed { i, folder ->
                LOG.info("Adding workspace folder {}", folder.name)
                val progressPrefix = "[${i + 1}/${folders.size}] ${folder.name ?: ""}"
                val progressPercent = (100 * i) / folders.size

                progress?.update("$progressPrefix: Updating source path", progressPercent)
                val root = Paths.get(parseURI(folder.uri))
                sourceFiles.addWorkspaceRoot(root)

                progress?.update("$progressPrefix: Updating class path", progressPercent)
                val refreshed = classPath.addWorkspaceRoot(root)
                if (refreshed) {
                    progress?.update("$progressPrefix: Refreshing source path", progressPercent)
                    sourcePath.refresh()
                }
            }
            progress?.close()

            if (workspaceCache != null && config.cache.workspaceCacheEnabled) {
                val buildFileVersion = classPath.currentBuildFileVersion

                // Compute the initial fingerprint from the pre-lint file state.
                //
                // If it matches the stored one, no source or build files have changed, and we
                // can skip the expensive initial compilation.
                val initialHashes = sourcePath.fileContentHashes()
                val initialPairs = initialHashes.entries.map { it.key to it.value }

                if (workspaceCache.isCacheValid(initialPairs, buildFileVersion)) {
                    LOG.info("Workspace cache is valid, skipping initial compilation. {} files cached.", initialHashes.size)
                    // Parse all files so module context is available for lazy compilation.
                    sourcePath.parseAllFiles()
                } else {
                    LOG.info("Workspace cache invalid or not found, running full initialization")
                    // Snapshot is recomputed INSIDE the callback so the saved
                    // fingerprint matches the post-lint state.

                    // lintAll's `submitImmediately` runs compileAllFiles / saveAllFiles /
                    // refreshDependencyIndexes synchronously in the same task, so this
                    // is the latest stable snapshot.
                    textDocuments.lintAll {
                        val post = sourcePath.fileContentHashes().entries.map { it.key to it.value }
                        workspaceCache.saveFingerprint(post, classPath.currentBuildFileVersion)
                    }
                }
            } else {
                textDocuments.lintAll()
            }

            val serverInfo = ServerInfo("ktlsp", VERSION)

            LOG.info("initialize: returning InitializeResult")
            InitializeResult(serverCapabilities, serverInfo)
        }

        return result
    }

    override fun initialized(params: InitializedParams) {
        connectLoggingBackend()
    }

    private fun connectLoggingBackend() {
        val backend: (LogMessage) -> Unit = {
            client.logMessage(MessageParams().apply {
                type = it.level.toLSPMessageType()
                message = it.message
            })
        }
        LOG.connectOutputBackend(backend)
        LOG.connectErrorBackend(backend)
    }

    private fun LogLevel.toLSPMessageType(): MessageType = when (this) {
        LogLevel.ERROR -> MessageType.Error
        LogLevel.WARN -> MessageType.Warning
        LogLevel.INFO -> MessageType.Info
        else -> MessageType.Log
    }

    override fun close() {
        textDocumentService.close()
        classPath.close()
        sourcePath.close()
        databaseService.close()
        tempDirectory.close()
        LOG.shutdown()
    }

    override fun shutdown(): CompletableFuture<Any> {
        close()
        return completedFuture(null)
    }

    override fun exit() {}
}
