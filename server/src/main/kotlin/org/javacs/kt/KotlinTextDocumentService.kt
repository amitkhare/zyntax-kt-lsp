package org.javacs.kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.javacs.kt.callhierarchy.incomingCalls
import org.javacs.kt.callhierarchy.outgoingCalls
import org.javacs.kt.callhierarchy.prepareCallHierarchy
import org.javacs.kt.typehierarchy.TypeHierarchyContext
import org.javacs.kt.typehierarchy.prepareTypeHierarchy
import org.javacs.kt.typehierarchy.supertypes
import org.javacs.kt.typehierarchy.subtypes
import org.javacs.kt.codeaction.codeActions
import org.javacs.kt.codelens.findCodeLenses
import org.javacs.kt.completion.completions
import org.javacs.kt.definition.goToDefinition
import org.javacs.kt.declaration.GotoDeclarationContext
import org.javacs.kt.declaration.goToDeclaration
import org.javacs.kt.typedefinition.GoToTypeDefinitionContext
import org.javacs.kt.typedefinition.goToTypeDefinition
import org.javacs.kt.diagnostic.convertDiagnostic
import org.javacs.kt.diagnostic.convertParserErrors
import org.javacs.kt.folding.foldingRanges
import org.javacs.kt.exception.extractDiagnosticInfo
import org.javacs.kt.exception.toDisplayString
import org.javacs.kt.formatting.FormattingService
import org.javacs.kt.highlight.documentHighlightsAt
import org.javacs.kt.hover.hoverAt
import org.javacs.kt.implementation.findImplementations
import org.javacs.kt.implementation.findSubclasses
import org.javacs.kt.inlayhints.provideHints
import org.javacs.kt.position.extractRange
import org.javacs.kt.position.offset
import org.javacs.kt.position.position
import org.javacs.kt.references.findReferences
import org.javacs.kt.rename.renameSymbol
import org.javacs.kt.rename.prepareRename
import org.javacs.kt.semantictokens.encodedSemanticTokens
import org.javacs.kt.signaturehelp.fetchSignatureHelpAt
import org.javacs.kt.symbols.documentSymbols
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.Debouncer
import org.javacs.kt.util.TemporaryDirectory
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.describeURIs
import org.javacs.kt.util.filePath
import org.javacs.kt.util.noResult
import org.javacs.kt.util.parseURI
import org.javacs.kt.util.preOrderTraversal
import org.javacs.kt.docs.findDoc
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics

import java.net.URI
import java.io.Closeable
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class KotlinTextDocumentService(
    private val sf: SourceFiles,
    private val sp: SourcePath,
    private val config: Configuration,
    private val tempDirectory: TemporaryDirectory,
    private val uriContentProvider: URIContentProvider,
    private val cp: CompilerClassPath
) : TextDocumentService, Closeable {
    private lateinit var client: LanguageClient
    private val async = AsyncExecutor
    private val formattingService = FormattingService(config.formatting)

    companion object {
        /** Maximum number of entries to cache for completion item documentation lookups */
        private const val COMPLETION_DOC_CACHE_SIZE = 1000
    }

    /** Cache for resolved completion item documentation */
    private val completionDocCache = ConcurrentHashMap<FqName, String?>(COMPLETION_DOC_CACHE_SIZE)

    var debounceLint = Debouncer(Duration.ofMillis(config.diagnostics.debounceTime))
    val lintTodo = mutableSetOf<URI>()
    var lintCount = 0

    /** Regex to match import detail format: "(import from package.name)" */
    private val importDetailRegex = "\\(import from ([^)]+)\\)".toRegex()

    var lintRecompilationCallback: () -> Unit
        get() = sp.beforeCompileCallback
        set(callback) { sp.beforeCompileCallback = callback }

    private val TextDocumentIdentifier.filePath: Path?
        get() = parseURI(uri).filePath

    private val TextDocumentIdentifier.content: String
        get() = sp.content(parseURI(uri))

    fun connect(client: LanguageClient) {
        this.client = client
    }

    /**
     * Executes [action] and returns its result. If any [Exception] is thrown the exception is logged
     * and [errorResult] is returned instead.
     *
     * @param errorMsg message prefix used when logging the caught exception
     * @param errorResult value to return when [action] throws an exception
     * @param action lambda to execute - its result is returned on success
     * @return the result of [action] if it completes successfully, otherwise [errorResult]
     */
    private inline fun <T> catching(errorMsg: String, errorResult: T, action: () -> T): T {
        try {
            return action()
        } catch (e: Exception) {
            LOG.warn("$errorMsg: {}", e.message)
            LOG.printStackTrace(e)
            return errorResult
        }
    }

    private enum class Recompile {
        ALWAYS, AFTER_DOT, NEVER
    }

    private fun recover(position: TextDocumentPositionParams, recompile: Recompile): Pair<CompiledFile, Int>? {
        return recover(position.textDocument.uri, position.position, recompile)
    }

    /**
     * Attempts to recover a compiled file and the character offset corresponding to a given position
     * in the source code, optionally triggering recompilation based on the specified [recompile] policy.
     *
     * The function first parses the provided [uriString] into a URI and checks whether it is included
     * in the source files. If the URI is excluded, a warning is logged and `null` is returned.
     * It then calculates the offset of the given [position] (line and character) within the file content.
     * Depending on the [recompile] policy, it may return the current version of the compiled file
     * or the latest compiled version without recompilation.
     *
     * @param uriString the URI of the source file as a string
     * @param position the line and character position within the source file
     * @param recompile the policy determining whether to recompile the file
     * @return a [Pair] containing:
     *   - the compiled file ([CompiledFile]) determined by the recompile policy
     *   - the offset (Int) corresponding to the given [position] in the source content
     *   or `null` if the URI is excluded or cannot be recovered
     */
    private fun recover(uriString: String, position: Position, recompile: Recompile): Pair<CompiledFile, Int>? {
        val uri = parseURI(uriString)
        if (!sf.isIncluded(uri)) {
            LOG.warn("URI is excluded, therefore cannot be recovered: $uri")
            return null
        }
        val content = sp.content(uri)
        val offset = offset(content, position.line, position.character)
        val compiled = when (recompile) {
            Recompile.ALWAYS -> sp.compileVersion(uri)
            Recompile.AFTER_DOT -> {
                val shouldForceRecompile = offset > 0 && content[offset - 1] == '.'
                if (shouldForceRecompile) sp.compileVersion(uri) else sp.latestCompiledVersion(uri)
            }
            Recompile.NEVER -> sp.latestCompiledVersion(uri)
        }
        return Pair(compiled, offset)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> = async.compute {
        catching("Error during code action", emptyList()) {
            val (file, _) = recover(params.textDocument.uri, params.range.start, Recompile.NEVER) ?: return@compute emptyList()
            codeActions(file, sp.index, params.range, params.context, config.completion.filteredTypes)
        }
    }

    // NOTE: This function isn't wrapped in catching() because this handler
    // uses runCatching + logInlayHintError() which extracts structured diagnostic info
    // from the exception, rather than the generic warn + printStackTrace pattern.
    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> = async.compute {
        runCatching {
            val (file, _) = recover(
                params.textDocument.uri,
                params.range.start,
                Recompile.ALWAYS
            ) ?: return@compute emptyList()

            provideHints(file, config.inlayHints)
        }.onFailure {
            logInlayHintError(it)
        }.getOrDefault(emptyList())
    }

    override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<List<FoldingRange>> = async.compute {
        catching("Error during folding ranges", emptyList()) {
            reportTime {
                LOG.info("Computing folding ranges for {}", describeURI(params.textDocument.uri))

                val uri = parseURI(params.textDocument.uri)
                val parsed = sp.parsedFile(uri) ?: return@compute emptyList()

                foldingRanges(parsed)
            }
        }
    }

    /**
     * Logs diagnostic information for errors that occur while computing inlay hints.
     *
     * If the diagnostic information can be extracted from the given [Throwable], the
     * function logs the message, the location (if available), and any additional details.
     * Otherwise, it logs the exception message and stack trace.
     *
     * @param e The exception thrown during inlay hint computation.
     */
    private fun logInlayHintError(e: Throwable) {
        val diagnostic = extractDiagnosticInfo(e)

        if (diagnostic == null) {
            LOG.debug("Error during inlay hint: {}", e.message, e)
            return
        }

        LOG.debug(
            "Inlay hint failed: {} (location: {})",
            diagnostic.message,
            diagnostic.range?.toDisplayString() ?: "unknown"
        )

        diagnostic.details?.let {
            LOG.debug("Details: {}", it)
        }
    }

    override fun hover(position: HoverParams): CompletableFuture<Hover?> = async.compute {
        catching("Error during hover", null) {
            reportTime {
                LOG.info("Hovering at {}", describePosition(position))

                val (file, cursor) = recover(position, Recompile.NEVER) ?: return@compute null
                hoverAt(file, cursor, uriContentProvider.classContentProvider, cp) ?: noResult("No hover found at ${describePosition(position)}", null)
            }
        }
    }

    override fun documentHighlight(position: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> = async.compute {
        catching("Error during document highlight", emptyList()) {
            val (file, cursor) = recover(position.textDocument.uri, position.position, Recompile.NEVER) ?: return@compute emptyList()
            documentHighlightsAt(file, cursor)
        }
    }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture<List<TextEdit>> = async.compute {
        catching("Error during on-type formatting", emptyList()) {
            val code = params.textDocument.content
            val position = params.position
            val offset = offset(code, position.line, position.character)

            // Get the line up to the cursor position (not the entire line to avoid reformatting existing code)
            val lineStart = code.lastIndexOf('\n', offset - 1) + 1
            val lineContentUpToCursor = code.substring(lineStart, offset)

            // Format only the content up to cursor
            val formattedContent = formattingService.formatKotlinCode(lineContentUpToCursor, params.options)

            // Create a range for just the content up to cursor
            val range = Range(
                Position(position.line, 0),
                Position(position.line, lineContentUpToCursor.length)
            )

            listOf(TextEdit(range, formattedContent))
        }
    }

    override fun definition(position: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> = async.compute {
        catching("Error during go-to-definition", Either.forLeft(emptyList())) {
            reportTime {
                LOG.info("Go-to-definition at {}", describePosition(position))

                val (file, cursor) = recover(position, Recompile.NEVER) ?: return@compute Either.forLeft(emptyList())
                goToDefinition(file, cursor, uriContentProvider.classContentProvider, tempDirectory, config.externalSources, cp)
                    ?.let(::listOf)
                    ?.let { Either.forLeft(it) }
                    ?: noResult("Couldn't find definition at ${describePosition(position)}", Either.forLeft(emptyList()))
            }
        }
    }

    override fun declaration(position: DeclarationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> = async.compute {
        catching("Error during go-to-declaration", Either.forLeft(emptyList())) {
            reportTime {
                LOG.info("Go-to-declaration at {}", describePosition(position))

                val (file, cursor) = recover(position, Recompile.NEVER) ?: return@compute Either.forLeft(emptyList())
                goToDeclaration(file, cursor, GotoDeclarationContext(uriContentProvider.classContentProvider, tempDirectory, config.externalSources, cp))
                    ?.let(::listOf)
                    ?.let { Either.forLeft(it) }
                    ?: noResult("Couldn't find declaration at ${describePosition(position)}", Either.forLeft(emptyList()))
            }
        }
    }

    override fun typeDefinition(position: TypeDefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> = async.compute {
        catching("Error during go-to-type-definition", Either.forLeft(emptyList())) {
            reportTime {
                LOG.info("Go-to-type-definition at {}", describePosition(position))

                val (file, cursor) = recover(position, Recompile.NEVER) ?: return@compute Either.forLeft(emptyList())
                goToTypeDefinition(file, cursor, GoToTypeDefinitionContext(uriContentProvider.classContentProvider, tempDirectory, config.externalSources, cp))
                    ?.let(::listOf)
                    ?.let { Either.forLeft(it) }
                    ?: noResult("Couldn't find type definition at ${describePosition(position)}", Either.forLeft(emptyList()))
            }
        }
    }

    override fun implementation(position: ImplementationParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> = async.compute {
        reportTime {
            LOG.info("Go-to-implementation at {}", describePosition(position))

            position.textDocument.filePath
                ?.let { findImplementations(it, offset(sp.content(parseURI(position.textDocument.uri)), position.position.line, position.position.character), sp) }
                ?.let { Either.forLeft(it) }
                ?: Either.forLeft(emptyList())
        }
    }

    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<List<CallHierarchyItem>> = async.compute {
        catching("Error during prepare call hierarchy", emptyList()) {
            params.textDocument.filePath
                ?.let { file ->
                    val offset = offset(sp.content(parseURI(params.textDocument.uri)), params.position.line, params.position.character)
                    prepareCallHierarchy(file, offset, sp)
                }
                ?: emptyList()
        }
    }

    override fun callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture<List<CallHierarchyIncomingCall>> = async.compute {
        catching("Error during call hierarchy incoming calls", emptyList()) {
            incomingCalls(params.item, sp)
        }
    }

    override fun callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): CompletableFuture<List<CallHierarchyOutgoingCall>> = async.compute {
        catching("Error during call hierarchy outgoing calls", emptyList()) {
            outgoingCalls(params.item, sp)
        }
    }

    override fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture<List<TypeHierarchyItem>> = async.compute {
        catching("Error during prepare type hierarchy", emptyList()) {
            params.textDocument.filePath
                ?.let { file ->
                    val offset = offset(sp.content(parseURI(params.textDocument.uri)), params.position.line, params.position.character)
                    val ctx = TypeHierarchyContext(uriContentProvider.classContentProvider, tempDirectory, config.externalSources, cp)
                    prepareTypeHierarchy(file, offset, sp, ctx)
                }
                ?: emptyList()
        }
    }

    override fun typeHierarchySupertypes(params: TypeHierarchySupertypesParams): CompletableFuture<List<TypeHierarchyItem>> = async.compute {
        catching("Error during type hierarchy supertypes", emptyList()) {
            val ctx = TypeHierarchyContext(uriContentProvider.classContentProvider, tempDirectory, config.externalSources, cp)
            supertypes(params.item, sp, ctx)
        }
    }

    override fun typeHierarchySubtypes(params: TypeHierarchySubtypesParams): CompletableFuture<List<TypeHierarchyItem>> = async.compute {
        catching("Error during type hierarchy subtypes", emptyList()) {
            val ctx = TypeHierarchyContext(uriContentProvider.classContentProvider, tempDirectory, config.externalSources, cp)
            subtypes(params.item, sp, ctx)
        }
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> = async.compute {
        val code = extractRange(params.textDocument.content, params.range)
        listOf(TextEdit(
            params.range,
            formattingService.formatKotlinCode(code, params.options)
        ))
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> = async.compute {
        catching("Error during code lens", emptyList()) {
            reportTime {
                LOG.info("Finding code lenses in {}", describeURI(params.textDocument.uri))

                val uri = parseURI(params.textDocument.uri)
                val file = sp.currentVersion(uri)
                return@compute findCodeLenses(file, sp)
            }
        }
    }

    override fun rename(params: RenameParams) = async.compute {
        catching("Error during rename", null) {
            val (file, cursor) = recover(params, Recompile.NEVER) ?: return@compute null
            renameSymbol(file, cursor, sp, params.newName)
        }
    }

    override fun prepareRename(params: PrepareRenameParams): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> = async.compute {
        catching("Error during prepare rename", null) {
            reportTime {
                LOG.info("Prepare rename at {}", describePosition(params))

                val (file, cursor) = recover(params, Recompile.NEVER) ?: return@compute null
                val result = prepareRename(file, cursor)

                if (result != null) {
                    val (range, placeholder) = result
                    val prepareResult = PrepareRenameResult(range, placeholder)
                    Either3.forSecond(prepareResult)
                } else {
                    null
                }
            }
        }
    }

    override fun completion(position: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> = async.compute {
        catching("Error during completion", Either.forRight(CompletionList())) {
            reportTime {
                LOG.info("Completing at {}", describePosition(position))

                val (file, cursor) = recover(position, Recompile.NEVER) ?: return@compute Either.forRight(CompletionList())
                val completions = completions(file, cursor, sp.index, config.completion, config.scripts)
                LOG.info("Found {} items", completions.items.size)

                Either.forRight(completions)
            }
        }
    }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> = async.compute {
        catching("Error resolving completion item", unresolved) {
            LOG.debug("Resolving completion item: {} with detail: {}", unresolved.label, unresolved.detail)

            // Try to find documentation for the completion item
            val documentation = resolveCompletionDocumentation(unresolved)

            if (documentation != null) {
                unresolved.documentation = Either.forLeft(documentation)
            }

            unresolved
        }
    }

    /**
     * Attempts to resolve documentation for a completion item.
     */
    private fun resolveCompletionDocumentation(item: CompletionItem): String? {
        val label = item.label
        val detail = item.detail

        if (label == null || detail == null) {
            return null
        }

        // Parse import detail to get FQ name
        val packageMatch = importDetailRegex.find(detail) ?: return null

        val packageName = packageMatch.groupValues[1]
        val fqName = FqName("$packageName.$label")

        // Check cache first
        completionDocCache[fqName]?.let { return it }

        // Use dependency tracker to find relevant files
        val packageFqName = fqName.parent()
        val relevantUris = sp.dependencyTracker.filesInPackageOrImporting(packageFqName)

        val result = if (relevantUris.isEmpty()) {
            null
        } else {
            // Search in parallel using virtual threads
            async.ioMapFirstOrNull(relevantUris.toList()) { uri ->
                findDocForDeclaration(uri, fqName)
            }
        }

        // Cache and return
        completionDocCache[fqName] = result
        return result
    }

    /**
     * Finds a declaration by FQ name in a specific file and returns its documentation.
     * Uses findDoc to get documentation from both source files and external JARs.
     */
    private fun findDocForDeclaration(uri: URI, fqName: FqName): String? {
        val parsedFile = sp.parsedFile(uri) ?: return null
        val compiled = sp.currentVersion(uri)

        // Find the declaration
        val declaration = parsedFile.preOrderTraversal()
            .filterIsInstance<KtNamedDeclaration>()
            .firstOrNull { it.fqName == fqName } ?: return null

        // Convert PSI to descriptor using binding context
        val descriptor = compiled.compile[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
            as? DeclarationDescriptorWithSource ?: return null

        // Use findDoc to get documentation (handles both source files and external JARs)
        return findDoc(descriptor, null, cp)
    }

    @Suppress("DEPRECATION")
    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = async.compute {
        catching("Error during document symbols", emptyList()) {
            LOG.info("Find symbols in {}", describeURI(params.textDocument.uri))

            reportTime {
                val uri = parseURI(params.textDocument.uri)
                val parsed = sp.parsedFile(uri)

                documentSymbols(parsed)
            }
        }
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.open(uri, params.textDocument.text, params.textDocument.version)
        lintNow(uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)

        // Refresh content from disk (only increments version if content changed)
        val content = uriContentProvider.contentOf(uri)
        sf.refreshContent(uri, content)

        // Run lint immediately - no need to debounce on save
        lintNow(uri)
    }

    override fun signatureHelp(position: SignatureHelpParams): CompletableFuture<SignatureHelp?> = async.compute {
        catching("Error during signature help", null) {
            reportTime {
                LOG.info("Signature help at {}", describePosition(position))

                val (file, cursor) = recover(position, Recompile.NEVER) ?: return@compute null
                fetchSignatureHelpAt(file, cursor, uriContentProvider.classContentProvider, cp) ?: noResult("No function call around ${describePosition(position)}", null)
            }
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.close(uri)
        clearDiagnostics(uri, null) // Pass null since we're closing
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> = async.compute {
        catching("Error during formatting", emptyList()) {
            val code = params.textDocument.content
            LOG.info("Formatting {}", describeURI(params.textDocument.uri))
            listOf(TextEdit(
                Range(Position(0, 0), position(code, code.length)),
                formattingService.formatKotlinCode(code, params.options)
            ))
        }
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sf.edit(uri, params.textDocument.version, params.contentChanges)
        lintLater(uri)
    }

    override fun references(position: ReferenceParams) = async.compute {
        catching("Error during find references", null) {
            position.textDocument.filePath
                ?.let { file ->
                    val content = sp.content(parseURI(position.textDocument.uri))
                    val offset = offset(content, position.position.line, position.position.character)
                    findReferences(file, offset, sp)
                }
        }
    }

    override fun semanticTokensFull(params: SemanticTokensParams) = async.compute {
        catching("Error during semantic tokens full", SemanticTokens(emptyList())) {
            LOG.info("Full semantic tokens in {}", describeURI(params.textDocument.uri))

            reportTime {
                val uri = parseURI(params.textDocument.uri)
                val file = sp.currentVersion(uri)

                val tokens = encodedSemanticTokens(file)
                LOG.info("Found {} tokens", tokens.size)

                SemanticTokens(tokens)
            }
        }
    }

    override fun semanticTokensRange(params: SemanticTokensRangeParams) = async.compute {
        catching("Error during semantic tokens range", SemanticTokens(emptyList())) {
            LOG.info("Ranged semantic tokens in {}", describeURI(params.textDocument.uri))

            reportTime {
                val uri = parseURI(params.textDocument.uri)
                val file = sp.currentVersion(uri)

                val tokens = encodedSemanticTokens(file, params.range)
                LOG.info("Found {} tokens", tokens.size)

                SemanticTokens(tokens)
            }
        }
    }

    override fun resolveCodeLens(unresolved: CodeLens): CompletableFuture<CodeLens> = async.compute {
        catching("Error during code lens resolve", unresolved) {
            reportTime {
                LOG.info("Resolving code lens {}", unresolved.command?.command)

                val command = unresolved.command ?: return@compute unresolved

                val args = command.arguments as List<*>
                if (args.size != 3) {
                    return@compute unresolved
                }

                val uri = args[0] as String
                val line = unresolved.range.start.line
                val character = unresolved.range.start.character

                val content = sp.content(parseURI(uri))
                val offset = offset(content, line, character)

                when (command.command) {
                    "kotlin.showImplementations" -> {
                        val filePath = parseURI(uri).filePath
                        if (filePath != null) {
                            val implementations = findImplementations(filePath, offset, sp)
                            if (implementations.isNotEmpty()) {
                                unresolved.command = Command(
                                    command.title,
                                    command.command,
                                    listOf(uri, line, character, implementations)
                                )
                            }
                        }
                    }
                    "kotlin.showSubclasses" -> {
                        val filePath = parseURI(uri).filePath
                        if (filePath != null) {
                            val subclasses = findSubclasses(filePath, offset, sp)
                            if (subclasses.isNotEmpty()) {
                                unresolved.command = Command(
                                    command.title,
                                    command.command,
                                    listOf(uri, line, character, subclasses)
                                )
                            }
                        }
                    }
                    "kotlin.showReferences" -> {
                        val filePath = parseURI(uri).filePath
                        if (filePath != null) {
                            val references = findReferences(filePath, offset, sp)
                            if (references.isNotEmpty()) {
                                unresolved.command = Command(
                                    command.title,
                                    command.command,
                                    listOf(uri, line, character, references)
                                )
                            }
                        }
                    }
                }

                return@compute unresolved
            }
        }
    }

    private fun describePosition(position: TextDocumentPositionParams): String {
        return "${describeURI(position.textDocument.uri)} ${position.position.line + 1}:${position.position.character + 1}"
    }

    fun updateDebouncer() {
        debounceLint = Debouncer(Duration.ofMillis(config.diagnostics.debounceTime))
    }

    fun lintAll(onComplete: () -> Unit = {}) {
        debounceLint.submitImmediately {
            sp.compileAllFiles()
            sp.saveAllFiles()
            sp.refreshDependencyIndexes(compileFirst = false)
            onComplete()
        }
    }

    private fun lintLater(uri: URI) {
        lintTodo.add(uri)
        debounceLint.schedule(::doLint)
    }

    private fun lintNow(file: URI) {
        lintTodo.add(file)
        debounceLint.submitImmediately(::doLint)
    }

    private fun doLint(cancelCallback: () -> Boolean) {
        LOG.info("Linting {}", describeURIs(lintTodo))
        val files = lintTodo.toMutableList()
        lintTodo.clear()

        if (files.isEmpty()) {
            LOG.info("doLint: no files to lint")
            return
        }

        // Capture versions at the START of analysis (before async compilation)
        val startVersions = files.associateWith { sf.version(it) }

        val context = compileWithErrorHandling(files, cancelCallback, startVersions) ?: return

        LOG.info("doLint: got context with diagnostics, files={}", files.size)
        if (cancelCallback.invoke()) return

        val parsedFiles = files.mapNotNull { uri ->
            try { sp.parsedFile(uri) }
            catch (e: Exception) { LOG.error("Error while linting file {}: {}", uri, e); null }
        }
        reportDiagnostics(files, context.diagnostics, parsedFiles, startVersions)
        lintCount++
    }

    private fun compileWithErrorHandling(files: List<URI>, cancelCallback: () -> Boolean, startVersions: Map<URI, Int?>): BindingContext? {
        return try {
            sp.compileFiles(files)
        } catch (e: Exception) {
            LOG.error("Compilation failed with exception: {}", e.message)
            LOG.printStackTrace(e)
            if (!cancelCallback.invoke()) {
                publishCompilationErrorDiagnostics(files, e, startVersions)
            }
            null
        }
    }

    private fun publishCompilationErrorDiagnostics(files: List<URI>, e: Exception, startVersions: Map<URI, Int?>) {
        val errorMsg = e.message ?: e.toString()
        for (file in files) {
            if (sf.isOpen(file)) {
                val capturedVersion = startVersions[file]
                val currentVersion = sf.version(file)

                // Discard stale diagnostics if document changed during compilation
                if (capturedVersion != null && capturedVersion != currentVersion) {
                    continue
                }

                val diagnostic = Diagnostic(
                    Range(Position(0, 0), Position(0, 1)),
                    "Compilation failed: $errorMsg",
                    DiagnosticSeverity.Error,
                    "kotlin"
                )
                client.publishDiagnostics(PublishDiagnosticsParams(file.toString(), listOf(diagnostic)))
            }
        }
    }

    private fun reportDiagnostics(compiled: Collection<URI>, kotlinDiagnostics: Diagnostics, parsedFiles: List<KtFile>, startVersions: Map<URI, Int?>) {

        // Get semantic diagnostics from the compiler
        val langServerDiagnostics = kotlinDiagnostics
            .flatMap(::convertDiagnostic)
            .filter { config.diagnostics.enabled && it.second.severity <= config.diagnostics.level }

        LOG.info("Semantic diagnostics: {}", langServerDiagnostics.size)

        // Get parser/syntax errors from parsed PSI trees
        val parserDiagnostics = parsedFiles
            .flatMap(::convertParserErrors)
            .filter { config.diagnostics.enabled && it.second.severity <= config.diagnostics.level }

        LOG.info("Parser diagnostics: {}", parserDiagnostics.size)

        // Combine both diagnostic sources
        val allDiagnostics = langServerDiagnostics + parserDiagnostics
        LOG.info("Total diagnostics after filtering: {}", allDiagnostics.size)

        val byFile = allDiagnostics.groupBy({ it.first }, { it.second })

        for ((uri, diagnostics) in byFile) {
            if (sf.isOpen(uri)) {
                val capturedVersion = startVersions[uri]
                val currentVersion = sf.version(uri)

                // Discard stale diagnostics if document changed during compilation
                if (capturedVersion != null && capturedVersion != currentVersion) {
                    continue
                }

                client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), diagnostics))
            }
            else LOG.info("Ignore {} diagnostics in {} because it's not open", diagnostics.size, describeURI(uri))
        }

        val noErrors = compiled - byFile.keys
        for (file in noErrors) {
            clearDiagnostics(file, startVersions[file])

            LOG.info("No diagnostics in {}", file)
        }
    }

    private fun clearDiagnostics(uri: URI, capturedVersion: Int?) {
        val currentVersion = sf.version(uri)

        // Only clear diagnostics if the version hasn't changed during compilation
        if (capturedVersion != null && capturedVersion != currentVersion) {
            return
        }

        client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), listOf()))
    }

    override fun close() {
        debounceLint.shutdown(true)
    }
}

private inline fun<T> reportTime(block: () -> T): T {
    val started = System.currentTimeMillis()
    try {
        return block()
    } finally {
        val finished = System.currentTimeMillis()
        LOG.info("Finished in {} ms", finished - started)
    }
}
