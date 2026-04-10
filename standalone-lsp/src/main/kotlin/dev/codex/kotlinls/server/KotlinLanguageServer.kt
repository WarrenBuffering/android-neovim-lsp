package dev.codex.kotlinls.server

import com.fasterxml.jackson.databind.JsonNode
import dev.codex.kotlinls.analysis.KotlinWorkspaceAnalyzer
import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.codeactions.CodeActionService
import dev.codex.kotlinls.completion.CompletionService
import dev.codex.kotlinls.diagnostics.DiagnosticsService
import dev.codex.kotlinls.formatting.FormattingService
import dev.codex.kotlinls.hover.HoverAndSignatureService
import dev.codex.kotlinls.index.LightweightWorkspaceIndexBuilder
import dev.codex.kotlinls.index.PersistentSemanticIndexCache
import dev.codex.kotlinls.index.SourceIndexLookup
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.index.WorkspaceIndexBuilder
import dev.codex.kotlinls.navigation.NavigationService
import dev.codex.kotlinls.projectimport.GradleProjectImporter
import dev.codex.kotlinls.projectimport.ImportedModule
import dev.codex.kotlinls.projectimport.ImportedProject
import dev.codex.kotlinls.protocol.CodeActionOptions
import dev.codex.kotlinls.protocol.CompletionItem
import dev.codex.kotlinls.protocol.CompletionList
import dev.codex.kotlinls.protocol.CompletionOptions
import dev.codex.kotlinls.protocol.CompletionParams
import dev.codex.kotlinls.protocol.DidChangeTextDocumentParams
import dev.codex.kotlinls.protocol.DidCloseTextDocumentParams
import dev.codex.kotlinls.protocol.DidOpenTextDocumentParams
import dev.codex.kotlinls.protocol.DidSaveTextDocumentParams
import dev.codex.kotlinls.protocol.DocumentFormattingParams
import dev.codex.kotlinls.protocol.DocumentHighlightParams
import dev.codex.kotlinls.protocol.DocumentRangeFormattingParams
import dev.codex.kotlinls.protocol.DocumentSymbolParams
import dev.codex.kotlinls.protocol.ExecuteCommandOptions
import dev.codex.kotlinls.protocol.FoldingRangeParams
import dev.codex.kotlinls.protocol.InitializeParams
import dev.codex.kotlinls.protocol.InitializeResult
import dev.codex.kotlinls.protocol.InlayHintParams
import dev.codex.kotlinls.protocol.Json
import dev.codex.kotlinls.protocol.JsonRpcErrorCodes
import dev.codex.kotlinls.protocol.JsonRpcInboundMessage
import dev.codex.kotlinls.protocol.JsonRpcTransport
import dev.codex.kotlinls.protocol.PublishDiagnosticsParams
import dev.codex.kotlinls.protocol.RenameOptions
import dev.codex.kotlinls.protocol.RenameParams
import dev.codex.kotlinls.protocol.SelectionRangeParams
import dev.codex.kotlinls.protocol.SemanticTokensLegend
import dev.codex.kotlinls.protocol.SemanticTokensOptions
import dev.codex.kotlinls.protocol.SemanticTokensParams
import dev.codex.kotlinls.protocol.ServerCapabilities
import dev.codex.kotlinls.protocol.ServerInfo
import dev.codex.kotlinls.protocol.SignatureHelpOptions
import dev.codex.kotlinls.protocol.TextDocumentPositionParams
import dev.codex.kotlinls.protocol.TextDocumentSyncOptions
import dev.codex.kotlinls.protocol.TypeHierarchyItem
import dev.codex.kotlinls.protocol.WorkspaceSymbolParams
import dev.codex.kotlinls.refactor.RenameService
import dev.codex.kotlinls.symbols.SymbolAndSemanticService
import dev.codex.kotlinls.workspace.ProjectRootDetector
import dev.codex.kotlinls.workspace.TextDocumentStore
import dev.codex.kotlinls.workspace.documentUriToPath
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

class KotlinLanguageServer(
    private val transport: JsonRpcTransport,
    private val documents: TextDocumentStore = TextDocumentStore(),
    private val rootDetector: ProjectRootDetector = ProjectRootDetector(),
    private val importer: GradleProjectImporter = GradleProjectImporter(rootDetector),
    private val analyzer: KotlinWorkspaceAnalyzer = KotlinWorkspaceAnalyzer(),
    private val lightweightIndexBuilder: LightweightWorkspaceIndexBuilder = LightweightWorkspaceIndexBuilder(),
    private val indexBuilder: WorkspaceIndexBuilder = WorkspaceIndexBuilder(),
    private val semanticIndexCache: PersistentSemanticIndexCache = PersistentSemanticIndexCache(),
    private val diagnosticsService: DiagnosticsService = DiagnosticsService(),
    private val completionService: CompletionService = CompletionService(),
    private val hoverService: HoverAndSignatureService = HoverAndSignatureService(),
    private val symbolService: SymbolAndSemanticService = SymbolAndSemanticService(),
    private val navigationService: NavigationService = NavigationService(),
    private val renameService: RenameService = RenameService(),
    private val formattingService: FormattingService = FormattingService(),
    private val codeActionService: CodeActionService = CodeActionService(formattingService),
    private val refreshDebounceMillis: Long = 200L,
    private val warmupStartDelayMillis: Long = 3_000L,
) {
    private var shutdownRequested = false
    @Volatile
    private var clientInitialized = false

    @Volatile
    private var root: Path? = null

    @Volatile
    private var project: ImportedProject? = null

    @Volatile
    private var semanticStates: Map<String, ModuleSemanticState> = emptyMap()

    @Volatile
    private var lightweightIndex: WorkspaceIndex = WorkspaceIndex(emptyList(), emptyList(), emptyList())

    @Volatile
    private var combinedIndex: WorkspaceIndex = WorkspaceIndex(emptyList(), emptyList(), emptyList())

    @Volatile
    private var moduleSemanticFingerprints: Map<String, String> = emptyMap()

    private val refreshExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kotlin-neovim-lsp-refresh").apply { isDaemon = true }
    }
    private val warmupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kotlin-neovim-lsp-warmup").apply { isDaemon = true }
    }
    private val refreshLock = Any()
    private val semanticStateLock = Any()
    private val warmupLock = Any()
    private var refreshRunning = false
    private var refreshRequested = false
    private var refreshReimportRequested = false
    private var refreshFastIndexRequested = false
    private val pendingSemanticUris = linkedSetOf<String>()
    private var warmupRunning = false
    private var warmupGeneration = 0
    private val warmupQueuedModules = linkedSetOf<String>()
    private val warmupAllowedOpenModules = linkedSetOf<String>()
    private val refreshGeneration = AtomicInteger(0)
    private val progressGeneration = AtomicInteger(0)
    private val semanticRequestGeneration = AtomicInteger(0)
    private var publishedDiagnosticUris: Set<String> = emptySet()
    private val maxFocusedSemanticPaths = 32
    private val maxSamePackageForegroundPaths = 8
    @Volatile
    private var currentProjectGeneration = 0
    private val latestSemanticRequestIds = linkedMapOf<String, Int>()

    fun run() {
        while (true) {
            val message = transport.readMessage() ?: break
            if (shutdownRequested && message.method == "exit") {
                break
            }
            if (message.method == "exit") {
                break
            }
            handle(message)
        }
    }

    private fun handle(message: JsonRpcInboundMessage) {
        try {
            when (message.method) {
                "initialize" -> respond(message) { initialize(read(message.params, InitializeParams::class.java)) }
                "initialized" -> {
                    clientInitialized = true
                    scheduleRefresh(
                        reimportProject = true,
                        rebuildFastIndex = project == null || lightweightIndex.symbols.isEmpty(),
                    )
                }
                "shutdown" -> respondRaw(message.id) {
                    shutdownRequested = true
                    null
                }
                "textDocument/didOpen" -> {
                    val params = read(message.params, DidOpenTextDocumentParams::class.java)
                    documents.open(params.textDocument)
                    updateLiveSourceIndex(params.textDocument.uri)
                    refreshWorkspaceForUri(params.textDocument.uri, reimportProject = false, rebuildFastIndex = false, scheduleSemantic = false)
                }
                "textDocument/didChange" -> {
                    val params = read(message.params, DidChangeTextDocumentParams::class.java)
                    documents.applyChanges(params)
                    updateLiveSourceIndex(params.textDocument.uri)
                    refreshWorkspaceForUri(params.textDocument.uri, reimportProject = false, rebuildFastIndex = false, scheduleSemantic = false)
                }
                "textDocument/didClose" -> {
                    val uri = read(message.params, DidCloseTextDocumentParams::class.java).textDocument.uri
                    documents.close(uri)
                    clearDiagnostics(uri)
                }
                "textDocument/didSave" -> {
                    val uri = read(message.params, DidSaveTextDocumentParams::class.java).textDocument.uri
                    val path = documentUriToPath(uri)
                    refreshWorkspaceForUri(
                        uri = uri,
                        reimportProject = isProjectModelPath(path),
                        rebuildFastIndex = isSourceFile(path) || isProjectModelPath(path),
                        scheduleSemantic = isSourceFile(path),
                    )
                }
                "workspace/didChangeConfiguration" -> scheduleRefresh(reimportProject = true, rebuildFastIndex = true)
                "workspace/didChangeWatchedFiles" -> scheduleRefresh(reimportProject = true, rebuildFastIndex = true)
                "textDocument/completion" -> respond(message) {
                    val params = read(message.params, CompletionParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri)
                    if (current != null) {
                        val semanticItems = completionService.complete(current.first, current.second, params)
                        if (semanticItems.items.isNotEmpty()) {
                            return@respond semanticItems
                        }
                    }
                    val source = sourceView(params.textDocument.uri)
                    if (source != null) {
                        return@respond completionService.completeFromIndex(currentIndex(), source.first, source.second, params)
                    }
                    CompletionList(false, emptyList())
                }
                "completionItem/resolve" -> respond(message) {
                    val item = read(message.params, CompletionItem::class.java)
                    resolveCompletion(item)
                }
                "textDocument/hover" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri)
                    if (current != null) {
                        hoverService.hover(current.first, current.second, params)
                            ?.let { return@respond it }
                    }
                    val source = sourceView(params.textDocument.uri) ?: return@respond null
                    hoverService.hoverFromIndex(currentIndex(), source.first, source.second, params)
                }
                "textDocument/signatureHelp" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri, timeoutMillis = 1200L) ?: return@respond null
                    hoverService.signatureHelp(current.first, current.second, params)
                }
                "textDocument/definition" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri)
                    if (current != null) {
                        val semantic = navigationService.definition(current.first, current.second, params)
                        if (semantic.isNotEmpty()) {
                            return@respond semantic
                        }
                    }
                    val source = sourceView(params.textDocument.uri) ?: return@respond emptyList<dev.codex.kotlinls.protocol.Location>()
                    navigationService.definitionFromIndex(currentIndex(), source.first, source.second, params)
                }
                "textDocument/typeDefinition" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri)
                    if (current != null) {
                        val semantic = navigationService.typeDefinition(current.first, current.second, params)
                        if (semantic.isNotEmpty()) {
                            return@respond semantic
                        }
                    }
                    val source = sourceView(params.textDocument.uri) ?: return@respond emptyList<dev.codex.kotlinls.protocol.Location>()
                    navigationService.typeDefinitionFromIndex(currentIndex(), source.first, source.second, params)
                }
                "textDocument/references" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri, timeoutMillis = 1200L)
                        ?: return@respond emptyList<dev.codex.kotlinls.protocol.Location>()
                    navigationService.references(current.first, current.second, params)
                }
                "textDocument/implementation" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri, timeoutMillis = 1200L)
                        ?: return@respond emptyList<dev.codex.kotlinls.protocol.Location>()
                    navigationService.implementations(current.first, current.second, params)
                }
                "textDocument/documentSymbol" -> respond(message) {
                    symbolService.documentSymbols(currentIndex(), read(message.params, DocumentSymbolParams::class.java))
                }
                "workspace/symbol" -> respond(message) {
                    symbolService.workspaceSymbols(currentIndex(), read(message.params, WorkspaceSymbolParams::class.java))
                }
                "textDocument/semanticTokens/full" -> respond(message) {
                    val params = read(message.params, SemanticTokensParams::class.java)
                    symbolService.semanticTokens(currentSnapshot(params.textDocument.uri, 1200L) ?: return@respond dev.codex.kotlinls.protocol.SemanticTokens(emptyList()), params)
                }
                "textDocument/documentHighlight" -> respond(message) {
                    val params = read(message.params, DocumentHighlightParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri, timeoutMillis = 1200L)
                        ?: return@respond emptyList<dev.codex.kotlinls.protocol.DocumentHighlight>()
                    navigationService.documentHighlights(current.first, current.second, params)
                }
                "textDocument/foldingRange" -> respond(message) {
                    val params = read(message.params, FoldingRangeParams::class.java)
                    navigationService.foldingRanges(
                        currentSnapshot(params.textDocument.uri, 1200L)
                            ?: return@respond emptyList<dev.codex.kotlinls.protocol.FoldingRange>(),
                        params,
                    )
                }
                "textDocument/selectionRange" -> respond(message) {
                    val params = read(message.params, SelectionRangeParams::class.java)
                    val uri = params.textDocument.uri
                    navigationService.selectionRanges(
                        currentSnapshot(uri, 1200L) ?: return@respond emptyList<dev.codex.kotlinls.protocol.SelectionRange>(),
                        params,
                    )
                }
                "textDocument/inlayHint" -> respond(message) {
                    val params = read(message.params, InlayHintParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri, timeoutMillis = 1200L)
                        ?: return@respond emptyList<dev.codex.kotlinls.protocol.InlayHint>()
                    hoverService.inlayHints(current.first, current.second, params)
                }
                "textDocument/prepareRename" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri, timeoutMillis = 1200L) ?: return@respond null
                    renameService.prepareRename(current.first, current.second, params)
                }
                "textDocument/rename" -> respond(message) {
                    val params = read(message.params, RenameParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri, timeoutMillis = 1200L) ?: return@respond null
                    renameService.rename(current.first, current.second, params)
                }
                "textDocument/formatting" -> respond(message) {
                    val params = read(message.params, DocumentFormattingParams::class.java)
                    formattingService.formatDocument(
                        currentSnapshot(params.textDocument.uri, 1200L)
                            ?: return@respond emptyList<dev.codex.kotlinls.protocol.TextEdit>(),
                        params,
                    )
                }
                "textDocument/rangeFormatting" -> respond(message) {
                    val params = read(message.params, DocumentRangeFormattingParams::class.java)
                    formattingService.formatRange(
                        currentSnapshot(params.textDocument.uri, 1200L)
                            ?: return@respond emptyList<dev.codex.kotlinls.protocol.TextEdit>(),
                        params,
                    )
                }
                "textDocument/codeAction" -> respond(message) {
                    val params = read(message.params, dev.codex.kotlinls.protocol.CodeActionParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri, timeoutMillis = 1200L)
                        ?: return@respond emptyList<dev.codex.kotlinls.protocol.CodeAction>()
                    codeActionService.codeActions(current.first, current.second, params)
                }
                "textDocument/prepareCallHierarchy" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri, timeoutMillis = 1200L)
                        ?: return@respond emptyList<dev.codex.kotlinls.protocol.CallHierarchyItem>()
                    navigationService.prepareCallHierarchy(current.first, current.second, params)
                }
                "callHierarchy/incomingCalls" -> respond(message) {
                    navigationService.incomingCalls(currentIndex(), read(message.params, dev.codex.kotlinls.protocol.CallHierarchyItem::class.java))
                }
                "callHierarchy/outgoingCalls" -> respond(message) {
                    navigationService.outgoingCalls(currentIndex(), read(message.params, dev.codex.kotlinls.protocol.CallHierarchyItem::class.java))
                }
                "textDocument/prepareTypeHierarchy" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val current = awaitSemanticState(params.textDocument.uri, timeoutMillis = 1200L)
                        ?: return@respond emptyList<TypeHierarchyItem>()
                    navigationService.prepareTypeHierarchy(current.first, current.second, params)
                }
                "typeHierarchy/supertypes" -> respond(message) {
                    navigationService.supertypes(currentIndex(), read(message.params, TypeHierarchyItem::class.java))
                }
                "typeHierarchy/subtypes" -> respond(message) {
                    navigationService.subtypes(currentIndex(), read(message.params, TypeHierarchyItem::class.java))
                }
                "workspace/executeCommand" -> respond(message) {
                    executeCommand(message.params)
                }
                "\$/cancelRequest" -> Unit
                else -> if (message.id != null) {
                    transport.sendError(message.id, JsonRpcErrorCodes.METHOD_NOT_FOUND, "Unknown method: ${message.method}")
                }
            }
        } catch (t: Throwable) {
            if (message.id != null) {
                transport.sendError(message.id, JsonRpcErrorCodes.INTERNAL_ERROR, t.message ?: t::class.java.simpleName)
            }
            System.err.println("[kotlin-neovim-lsp] ${t::class.java.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
        }
    }

    private fun initialize(params: InitializeParams): InitializeResult {
        val workspaceFolders = params.workspaceFolders
        val rootUri = params.rootUri
        val rootPath = params.rootPath
        root = when {
            workspaceFolders?.isNotEmpty() == true -> documentUriToPath(workspaceFolders.first().uri)
            rootUri != null -> documentUriToPath(rootUri)
            rootPath != null -> Path.of(rootPath)
            else -> null
        }
        preloadPersistedState()
        return InitializeResult(
            capabilities = ServerCapabilities(
                textDocumentSync = TextDocumentSyncOptions(save = true),
                completionProvider = CompletionOptions(triggerCharacters = listOf(".", ":", "@")),
                codeActionProvider = CodeActionOptions(
                    codeActionKinds = listOf(
                        "quickfix",
                        "refactor.rewrite",
                        "source.organizeImports",
                        "source.fixAll",
                    ),
                ),
                signatureHelpProvider = SignatureHelpOptions(triggerCharacters = listOf("(", ",")),
                semanticTokensProvider = SemanticTokensOptions(
                    legend = SemanticTokensLegend(
                        tokenTypes = listOf(
                            "namespace", "type", "class", "enum", "interface", "struct", "typeParameter", "parameter",
                            "variable", "property", "enumMember", "event", "function", "method", "macro", "keyword",
                        ),
                        tokenModifiers = listOf("declaration", "readonly", "static", "deprecated", "abstract", "async"),
                    ),
                ),
                renameProvider = RenameOptions(prepareProvider = true),
                executeCommandProvider = ExecuteCommandOptions(
                    commands = listOf(
                        "kotlinls.reimport",
                        "kotlinls.organizeImports",
                    ),
                ),
            ),
            serverInfo = ServerInfo(name = "kotlin-neovim-lsp", version = "0.1.0-dev"),
        )
    }

    private fun executeCommand(params: JsonNode?): Any? {
        val method = params?.get("command")?.asText() ?: return null
        return when (method) {
            "kotlinls.reimport" -> {
                refreshWorkspaceNow(
                    reimportProject = true,
                    rebuildFastIndex = true,
                    requestedSemanticUris = documents.openDocuments().map { it.uri }.toSet(),
                )
                true
            }

            "kotlinls.organizeImports" -> {
                val uri = params.get("arguments")
                    ?.elements()
                    ?.asSequence()
                    ?.firstOrNull()
                    ?.asText()
                    ?: return null
                formattingService.organizeImportsText(currentSnapshot(uri, 1200L) ?: return null, uri)?.let { text ->
                    dev.codex.kotlinls.protocol.WorkspaceEdit(
                        changes = mapOf(
                            uri to listOf(
                                dev.codex.kotlinls.protocol.TextEdit(
                                    range = dev.codex.kotlinls.protocol.Range(
                                        start = dev.codex.kotlinls.protocol.Position(0, 0),
                                        end = dev.codex.kotlinls.protocol.Position(Int.MAX_VALUE / 4, 0),
                                    ),
                                    newText = text,
                                ),
                            ),
                        ),
                    )
                }
            }

            else -> null
        }
    }

    private fun refreshWorkspaceForUri(
        uri: String,
        reimportProject: Boolean,
        rebuildFastIndex: Boolean,
        scheduleSemantic: Boolean,
    ) {
        if (root == null) {
            root = rootDetector.detect(documentUriToPath(uri))
        }
        scheduleRefresh(
            reimportProject = reimportProject,
            rebuildFastIndex = rebuildFastIndex,
            semanticUri = uri.takeIf { scheduleSemantic && isSourceUri(it) },
        )
    }

    private fun preloadPersistedState() {
        val rootPath = root ?: return
        val cachedProject = importer.loadPersistedProject(rootPath) ?: return
        project = cachedProject
        currentProjectGeneration = refreshGeneration.incrementAndGet()
        lightweightIndexBuilder.load(cachedProject.root)?.let { cachedIndex ->
            lightweightIndex = cachedIndex
        }
        val optimisticSemanticStates = loadOptimisticSemanticStates(cachedProject)
        replaceSemanticStates(optimisticSemanticStates)
        rebuildCombinedIndex()
    }

    private fun updateLiveSourceIndex(uri: String) {
        val currentProject = project ?: return
        val source = sourceView(uri) ?: return
        if (!isSourceFile(source.first)) return
        lightweightIndex = lightweightIndexBuilder.updateOpenDocument(
            project = currentProject,
            path = source.first,
            text = source.second,
            currentIndex = lightweightIndex,
        )
        rebuildCombinedIndex()
        publishOpenDiagnostics()
    }

    private fun scheduleRefresh(
        reimportProject: Boolean,
        rebuildFastIndex: Boolean = reimportProject || lightweightIndex.symbols.isEmpty(),
        semanticUri: String? = null,
    ) {
        synchronized(refreshLock) {
            refreshRequested = true
            refreshReimportRequested = refreshReimportRequested || reimportProject
            refreshFastIndexRequested = refreshFastIndexRequested || rebuildFastIndex || reimportProject
            semanticUri?.let(pendingSemanticUris::add)
            if (refreshRunning) {
                return
            }
            refreshRunning = true
        }
        refreshExecutor.execute {
            while (true) {
                if (refreshDebounceMillis > 0) {
                    try {
                        Thread.sleep(refreshDebounceMillis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        synchronized(refreshLock) {
                            refreshRunning = false
                        }
                        return@execute
                    }
                }
                val doReimport = synchronized(refreshLock) {
                    if (!refreshRequested) {
                        refreshRunning = false
                        return@execute
                    }
                    refreshRequested = false
                    val flag = refreshReimportRequested
                    refreshReimportRequested = false
                    val fastIndexFlag = refreshFastIndexRequested
                    refreshFastIndexRequested = false
                    val semanticUris = pendingSemanticUris.toSet()
                    pendingSemanticUris.clear()
                    RefreshRequest(
                        reimportProject = flag,
                        rebuildFastIndex = fastIndexFlag || flag || lightweightIndex.symbols.isEmpty(),
                        semanticUris = semanticUris,
                    )
                }
                runCatching {
                    refreshWorkspaceNow(
                        reimportProject = doReimport.reimportProject,
                        rebuildFastIndex = doReimport.rebuildFastIndex,
                        requestedSemanticUris = doReimport.semanticUris,
                    )
                }
                    .onFailure { error ->
                        System.err.println("[kotlin-neovim-lsp] refresh failed: ${error.message}")
                        error.printStackTrace(System.err)
                    }
            }
        }
    }

    private fun refreshWorkspaceNow(
        reimportProject: Boolean = true,
        rebuildFastIndex: Boolean = true,
        requestedSemanticUris: Set<String> = emptySet(),
    ) {
        val rootPath = root ?: return
        val requiresImport = reimportProject || project == null
        val nextProject = if (requiresImport) {
            val importProgress = beginProgress(title = "Project Import", subtitle = "Scanning Gradle modules")
            try {
                importer.importProject(rootPath) { subtitle, current, total ->
                    importProgress.report(subtitle, current, total)
                }.also {
                    importProgress.complete("Project model ready")
                }
            } catch (t: Throwable) {
                importProgress.fail(t.message ?: t::class.java.simpleName)
                throw t
            }
        } else {
            project ?: return
        }

        if (requiresImport) {
            currentProjectGeneration = refreshGeneration.incrementAndGet()
            moduleSemanticFingerprints = computeSemanticFingerprints(nextProject)
            val carriedStates = carryForwardSemanticStates(nextProject)
            val validatedStates = loadPersistedSemanticStates(nextProject)
            replaceSemanticStates(carriedStates + validatedStates)
        }

        if (rebuildFastIndex || lightweightIndex.symbols.isEmpty()) {
            val fastIndexProgress = beginProgress(
                title = "Fast Source Index",
                subtitle = "Scanning workspace files",
                showImmediately = false,
                minTotalToShow = 2,
            )
            try {
                lightweightIndex = lightweightIndexBuilder.build(nextProject, documents) { subtitle, current, total ->
                    fastIndexProgress.report(subtitle, current, total)
                }.also {
                    fastIndexProgress.complete("Fast source index ready")
                }
            } catch (t: Throwable) {
                fastIndexProgress.fail(t.message ?: t::class.java.simpleName)
                throw t
            }
        }

        project = nextProject
        publishOpenDiagnostics()
        refreshSemanticModules(nextProject, requestedSemanticUris)
        scheduleBackgroundWarmup(nextProject)
        rebuildCombinedIndex()
        publishOpenDiagnostics()
    }

    private fun refreshSemanticModules(project: ImportedProject, requestedSemanticUris: Set<String>) {
        val requestedModules = semanticModulesForUris(project, requestedSemanticUris)
        if (requestedModules.isEmpty()) return
        val requestedUrisByModule = requestedSemanticUris
            .filter(::isSourceUri)
            .groupBy { uri ->
                project.moduleForPath(documentUriToPath(uri))?.gradlePath
            }
        requestedModules.forEach { module ->
            val currentState = semanticStates[module.gradlePath]
            val moduleRequestedUris = requestedUrisByModule[module.gradlePath].orEmpty()
            val requestedDocumentsChanged = requestedUrisRequireFreshSemantic(moduleRequestedUris)
            if (
                currentState?.validated == true &&
                currentState.projectGeneration == currentProjectGeneration &&
                currentState.fullyIndexed &&
                currentState.snapshot == null &&
                !requestedDocumentsChanged
            ) {
                return@forEach
            }
            val focusedPaths = focusedSemanticPaths(project, module, moduleRequestedUris)
            val nextState = analyzeModule(project, module, focusedPaths, background = false)
            installSemanticState(nextState)
            scheduleBackgroundWarmup(project, listOf(module.gradlePath), includeOpenModules = true)
        }
    }

    private fun analyzeModule(
        project: ImportedProject,
        module: ImportedModule,
        focusedPaths: Set<Path>,
        background: Boolean,
    ): ModuleSemanticState {
        val moduleLabel = moduleLabel(module)
        val semanticProject = project.subsetForModules(listOf(module))
        val requestId = nextSemanticRequestId(module.gradlePath)
        val semanticAnalysisProgress = beginProgress(
            title = if (background) "Semantic Warmup" else "Semantic Analysis",
            subtitle = if (background) "Warming $moduleLabel" else "Resolving $moduleLabel",
        )
        val nextSnapshot = try {
            analyzer.analyze(
                semanticProject,
                documents,
                includedPaths = focusedPaths.takeIf { background.not() && it.isNotEmpty() },
            ) { subtitle, current, total ->
                semanticAnalysisProgress.report("$moduleLabel: $subtitle", current, total)
            }.also {
                semanticAnalysisProgress.complete(
                    if (background) "$moduleLabel semantic warmup ready" else "$moduleLabel semantic model ready",
                )
            }
        } catch (t: Throwable) {
            semanticAnalysisProgress.fail(t.message ?: t::class.java.simpleName)
            throw t
        }

        val semanticIndexProgress = beginProgress(
            title = if (background) "Semantic Warmup Index" else "Semantic Index",
            subtitle = if (background) "Warming index for $moduleLabel" else "Indexing $moduleLabel",
        )
        val nextIndex = try {
            indexBuilder.build(nextSnapshot, targetPaths = focusedPaths.takeIf { it.isNotEmpty() }) { subtitle, current, total ->
                semanticIndexProgress.report("$moduleLabel: $subtitle", current, total)
            }.also {
                semanticIndexProgress.complete(
                    if (background) "$moduleLabel semantic warm index ready" else "$moduleLabel semantic index ready",
                )
            }
        } catch (t: Throwable) {
            semanticIndexProgress.fail(t.message ?: t::class.java.simpleName)
            nextSnapshot.close()
            throw t
        }
        return ModuleSemanticState(
            module = module,
            snapshot = nextSnapshot,
            index = nextIndex,
            projectGeneration = currentProjectGeneration,
            requestId = requestId,
            fullyIndexed = focusedPaths.isEmpty(),
            validated = true,
        )
    }

    private fun semanticModulesForUris(
        project: ImportedProject,
        requestedSemanticUris: Set<String>,
    ): List<ImportedModule> {
        if (requestedSemanticUris.isEmpty()) return emptyList()
        return requestedSemanticUris
            .asSequence()
            .filter(::isSourceUri)
            .map(::documentUriToPath)
            .mapNotNull(project::moduleForPath)
            .distinctBy { it.gradlePath }
            .toList()
    }

    private fun focusedSemanticPaths(
        project: ImportedProject,
        module: ImportedModule,
        requestedUris: Collection<String>,
    ): Set<Path> {
        val openPaths = documents.openDocuments()
            .map { documentUriToPath(it.uri).normalize() }
            .filter { path -> project.moduleForPath(path)?.gradlePath == module.gradlePath }
        val requestedPaths = requestedUris
            .map(::documentUriToPath)
            .map(Path::normalize)
            .filter { path -> project.moduleForPath(path)?.gradlePath == module.gradlePath }
        val queue = ArrayDeque((openPaths + requestedPaths).distinct())
        val selected = linkedSetOf<Path>()
        while (queue.isNotEmpty() && selected.size < maxFocusedSemanticPaths) {
            val path = queue.removeFirst().normalize()
            if (!selected.add(path)) continue
            val text = sourceTextForPath(path) ?: continue
            val currentModule = project.moduleForPath(path)
            val packageName = SourceIndexLookup.packageName(text)
            lightIndexImportsForText(text).forEach { importedPath ->
                if (selected.size + queue.size >= maxFocusedSemanticPaths) return@forEach
                queue.addLast(importedPath)
            }
            if (packageName.isNotBlank()) {
                var samePackageAdded = 0
                currentIndex().symbols
                    .asSequence()
                    .filter { symbol ->
                        symbol.packageName == packageName &&
                            project.moduleForPath(symbol.path)?.gradlePath == currentModule?.gradlePath
                    }
                    .map { it.path.normalize() }
                    .distinct()
                    .forEach { candidatePath ->
                        if (selected.size + queue.size >= maxFocusedSemanticPaths) return@forEach
                        if (samePackageAdded >= maxSamePackageForegroundPaths) return@forEach
                        queue.addLast(candidatePath)
                        samePackageAdded += 1
                    }
            }
        }
        return selected
    }

    private fun lightIndexImportsForText(text: String): List<Path> =
        SourceIndexLookup.imports(text)
            .mapNotNull { import -> currentIndex().symbolsByFqName[import.fqName]?.path?.normalize() }
            .filter { path -> Files.exists(path) }
            .distinct()

    private fun sourceTextForPath(path: Path): String? =
        documents.get(path.toUri().toString())?.text ?: runCatching { Files.readString(path) }.getOrNull()

    private fun requestedUrisRequireFreshSemantic(requestedUris: Collection<String>): Boolean =
        requestedUris.any { uri ->
            val document = documents.get(uri) ?: return@any false
            if (document.version > 1) {
                return@any true
            }
            val path = documentUriToPath(uri)
            runCatching { Files.readString(path) }.getOrNull() != document.text
        }

    private fun scheduleBackgroundWarmup(
        project: ImportedProject,
        modulePaths: Collection<String> = project.modules.map { it.gradlePath },
        includeOpenModules: Boolean = false,
    ) {
        val generation = currentProjectGeneration
        synchronized(warmupLock) {
            if (warmupGeneration != generation) {
                warmupQueuedModules.clear()
                warmupAllowedOpenModules.clear()
                warmupGeneration = generation
                warmupRunning = false
            }
            modulePaths.forEach { modulePath ->
                val currentState = semanticStates[modulePath]
                if (currentState?.projectGeneration == generation && currentState.fullyIndexed) {
                    return@forEach
                }
                warmupQueuedModules += modulePath
                if (includeOpenModules) {
                    warmupAllowedOpenModules += modulePath
                }
            }
            if (warmupRunning || warmupQueuedModules.isEmpty()) {
                return
            }
            warmupRunning = true
        }
        warmupExecutor.execute {
            if (warmupStartDelayMillis > 0) {
                try {
                    Thread.sleep(warmupStartDelayMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    synchronized(warmupLock) {
                        warmupRunning = false
                    }
                    return@execute
                }
            }
            while (true) {
                val nextModulePath = synchronized(warmupLock) {
                    if (generation != currentProjectGeneration) {
                        warmupRunning = false
                        return@execute
                    }
                    val openModulePaths = documents.openDocuments()
                        .mapNotNull { document ->
                            project.moduleForPath(documentUriToPath(document.uri))?.gradlePath
                        }
                        .toSet()
                    val modulePath = warmupQueuedModules.firstOrNull { candidate ->
                        candidate !in openModulePaths || candidate in warmupAllowedOpenModules
                    }
                    if (modulePath == null) {
                        warmupRunning = false
                        return@execute
                    }
                    warmupQueuedModules.remove(modulePath)
                    warmupAllowedOpenModules.remove(modulePath)
                    modulePath
                }
                val currentProject = project.takeIf { generation == currentProjectGeneration } ?: break
                val module = currentProject.modulesByGradlePath[nextModulePath] ?: continue
                val currentState = semanticStates[nextModulePath]
                if (currentState?.validated == true && currentState.projectGeneration == generation && currentState.fullyIndexed) {
                    continue
                }
                runCatching {
                    analyzeModule(currentProject, module, focusedPaths = emptySet(), background = true)
                }.onSuccess { state ->
                    installSemanticState(state)
                }.onFailure { error ->
                    System.err.println("[kotlin-neovim-lsp] warmup failed for ${module.gradlePath}: ${error.message}")
                    error.printStackTrace(System.err)
                }
            }
        }
    }

    private fun publishOpenDiagnostics() {
        if (!clientInitialized) return
        val openUris = documents.openDocuments().map { it.uri }.toSet()
        val merged = linkedMapOf<String, MutableList<dev.codex.kotlinls.protocol.Diagnostic>>()
        val currentProject = project
        openUris.forEach { uri ->
            val diagnostics = mutableListOf<dev.codex.kotlinls.protocol.Diagnostic>()
            val source = sourceView(uri)
            if (source != null) {
                diagnostics += diagnosticsService.fastDiagnostics(currentProject, source.first, source.second)
            }
            merged[uri] = diagnostics
        }
        semanticStates.values.forEach { state ->
            val snapshot = state.snapshot ?: return@forEach
            val stateUris = snapshot.files
                .asSequence()
                .filter { it.module.gradlePath == state.module.gradlePath }
                .map { it.uri }
                .filter(openUris::contains)
                .toSet()
            diagnosticsService.publishable(snapshot, stateUris).forEach { params ->
                merged.getOrPut(params.uri) { mutableListOf() }.addAll(params.diagnostics)
            }
        }
        val urisToSend = publishedDiagnosticUris + openUris
        urisToSend.forEach { uri ->
            transport.sendNotification(
                "textDocument/publishDiagnostics",
                PublishDiagnosticsParams(
                    uri = uri,
                    diagnostics = merged[uri].orEmpty().sortedWith(
                        compareBy<dev.codex.kotlinls.protocol.Diagnostic> { it.range.start.line }
                            .thenBy { it.range.start.character },
                    ),
                ),
            )
        }
        publishedDiagnosticUris = openUris
    }

    private fun clearDiagnostics(uri: String) {
        transport.sendNotification("textDocument/publishDiagnostics", PublishDiagnosticsParams(uri, emptyList()))
        publishedDiagnosticUris = publishedDiagnosticUris - uri
    }

    private fun currentSnapshot(uri: String, timeoutMillis: Long = 400L): WorkspaceAnalysisSnapshot? =
        awaitSemanticState(uri, timeoutMillis)?.first

    private fun currentIndex(): WorkspaceIndex =
        combinedIndex.takeIf { it.symbols.isNotEmpty() || it.references.isNotEmpty() || it.callEdges.isNotEmpty() }
            ?: lightweightIndex

    private fun awaitSemanticState(
        uri: String,
        timeoutMillis: Long = 400L,
    ): Pair<WorkspaceAnalysisSnapshot, WorkspaceIndex>? {
        moduleStateForUri(uri)?.let { state ->
            state.snapshot?.let { return it to currentIndex() }
            scheduleRefresh(
                reimportProject = project == null,
                rebuildFastIndex = lightweightIndex.symbols.isEmpty(),
                semanticUri = uri.takeIf(::isSourceUri),
            )
            return null
        }
        if (root == null) {
            root = rootDetector.detect(documentUriToPath(uri))
        }
        scheduleRefresh(
            reimportProject = project == null,
            rebuildFastIndex = lightweightIndex.symbols.isEmpty(),
            semanticUri = uri.takeIf(::isSourceUri),
        )
        val deadline = System.nanoTime() + (timeoutMillis * 1_000_000L)
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            moduleStateForUri(uri)?.snapshot?.let { return it to currentIndex() }
        }
        return null
    }

    private fun moduleStateForUri(uri: String): ModuleSemanticState? {
        val currentProject = project ?: return null
        val module = currentProject.moduleForPath(documentUriToPath(uri)) ?: return null
        return semanticStates[module.gradlePath]
    }

    private fun replaceSemanticStates(nextStates: Map<String, ModuleSemanticState>) {
        val previousStates = synchronized(semanticStateLock) {
            val previous = semanticStates
            semanticStates = nextStates
            latestSemanticRequestIds.clear()
            previous
        }
        previousStates.values.forEach { state -> state.snapshot?.close() }
        rebuildCombinedIndex()
    }

    private fun carryForwardSemanticStates(project: ImportedProject): Map<String, ModuleSemanticState> =
        synchronized(semanticStateLock) {
            semanticStates.mapNotNull { (gradlePath, state) ->
                val nextModule = project.modulesByGradlePath[gradlePath] ?: return@mapNotNull null
                gradlePath to ModuleSemanticState(
                    module = nextModule,
                    snapshot = null,
                    index = state.index,
                    projectGeneration = currentProjectGeneration,
                    requestId = state.requestId,
                    fullyIndexed = state.fullyIndexed,
                    validated = false,
                )
            }.toMap()
        }

    private fun rebuildCombinedIndex() {
        combinedIndex = synchronized(semanticStateLock) {
            mergeIndices(listOf(lightweightIndex) + semanticStates.values.map { it.index })
        }
    }

    private fun nextSemanticRequestId(modulePath: String): Int =
        synchronized(semanticStateLock) {
            semanticRequestGeneration.incrementAndGet().also { requestId ->
                latestSemanticRequestIds[modulePath] = requestId
            }
        }

    private fun installSemanticState(nextState: ModuleSemanticState) {
        val previousState = synchronized(semanticStateLock) {
            if (nextState.projectGeneration != currentProjectGeneration) {
                null
            } else {
                val latestRequestId = latestSemanticRequestIds[nextState.module.gradlePath] ?: Int.MIN_VALUE
                if (nextState.requestId < latestRequestId) {
                    null
                } else {
                    val previous = semanticStates[nextState.module.gradlePath]
                    semanticStates = semanticStates.toMutableMap().apply {
                        put(nextState.module.gradlePath, nextState)
                    }.toMap()
                    previous
                }
            }
        }
        if (previousState == null) {
            val existing = semanticStates[nextState.module.gradlePath]
            if (existing !== nextState) {
                nextState.snapshot?.close()
                return
            }
        } else if (previousState !== nextState) {
            previousState.snapshot?.close()
        }
        rebuildCombinedIndex()
        persistSemanticState(nextState)
        publishOpenDiagnostics()
    }

    private fun loadPersistedSemanticStates(project: ImportedProject): Map<String, ModuleSemanticState> =
        project.modules.mapNotNull { module ->
            val fingerprint = moduleSemanticFingerprints[module.gradlePath] ?: return@mapNotNull null
            val index = semanticIndexCache.load(project.root, module.gradlePath, fingerprint) ?: return@mapNotNull null
            module.gradlePath to ModuleSemanticState(
                module = module,
                snapshot = null,
                index = index,
                projectGeneration = currentProjectGeneration,
                requestId = 0,
                fullyIndexed = true,
                validated = true,
            )
        }.toMap()

    private fun loadOptimisticSemanticStates(project: ImportedProject): Map<String, ModuleSemanticState> {
        val cachedByModule = semanticIndexCache.loadAll(project.root)
        return project.modules.mapNotNull { module ->
            val index = cachedByModule[module.gradlePath] ?: return@mapNotNull null
            module.gradlePath to ModuleSemanticState(
                module = module,
                snapshot = null,
                index = index,
                projectGeneration = currentProjectGeneration,
                requestId = 0,
                fullyIndexed = true,
                validated = false,
            )
        }.toMap()
    }

    private fun persistSemanticState(state: ModuleSemanticState) {
        if (!state.fullyIndexed || state.snapshot == null) return
        val currentProject = project ?: return
        val fingerprint = moduleSemanticFingerprints[state.module.gradlePath] ?: return
        semanticIndexCache.save(
            projectRoot = currentProject.root,
            moduleGradlePath = state.module.gradlePath,
            fingerprint = fingerprint,
            index = state.index,
        )
    }

    private fun computeSemanticFingerprints(project: ImportedProject): Map<String, String> =
        project.modules.associate { module ->
            module.gradlePath to fingerprintModule(module)
        }

    private fun fingerprintModule(module: ImportedModule): String {
        val builder = StringBuilder()
        builder.append("module=").append(module.gradlePath).append('\n')
        builder.append("name=").append(module.name).append('\n')
        builder.append("dir=").append(module.dir.normalize()).append('\n')
        builder.append("compiler=").append(module.compilerOptions).append('\n')
        builder.append("projectDeps=").append(module.projectDependencies.sorted()).append('\n')
        builder.append("externalDeps=").append(module.externalDependencies.map { it.notation }.sorted()).append('\n')
        appendFileFingerprint(builder, "buildFile", module.buildFile)
        module.sourceRoots.sortedBy { it.normalize().toString() }.forEach { root ->
            appendTreeFingerprint(builder, "sourceRoot", root)
        }
        module.javaSourceRoots.sortedBy { it.normalize().toString() }.forEach { root ->
            appendTreeFingerprint(builder, "javaSourceRoot", root)
        }
        module.classpathJars.sortedBy { it.normalize().toString() }.forEach { path ->
            appendFileFingerprint(builder, "classpathJar", path)
        }
        module.classpathSourceJars.sortedBy { it.normalize().toString() }.forEach { path ->
            appendFileFingerprint(builder, "classpathSourceJar", path)
        }
        return sha256(builder.toString())
    }

    private fun appendTreeFingerprint(builder: StringBuilder, label: String, root: Path) {
        val normalizedRoot = root.normalize()
        builder.append(label).append('=').append(normalizedRoot).append('\n')
        if (!Files.exists(normalizedRoot)) {
            builder.append(label).append(":missing").append('\n')
            return
        }
        normalizedRoot.walk()
            .filter { it.isRegularFile() && it.extension in setOf("kt", "kts", "java") }
            .map(Path::normalize)
            .sortedBy { it.toString() }
            .forEach { path ->
                appendFileFingerprint(builder, label, path)
            }
    }

    private fun appendFileFingerprint(builder: StringBuilder, label: String, path: Path?) {
        val normalizedPath = path?.normalize()
        builder.append(label).append('=').append(normalizedPath ?: "<missing>").append('|')
        if (normalizedPath == null || !Files.exists(normalizedPath)) {
            builder.append("missing").append('\n')
            return
        }
        builder.append(runCatching { Files.getLastModifiedTime(normalizedPath).toMillis() }.getOrDefault(0L))
            .append('|')
            .append(runCatching { Files.size(normalizedPath) }.getOrDefault(0L))
            .append('\n')
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun mergeIndices(indices: List<WorkspaceIndex>): WorkspaceIndex {
        val symbolMap = linkedMapOf<String, dev.codex.kotlinls.index.IndexedSymbol>()
        val references = linkedSetOf<dev.codex.kotlinls.index.IndexedReference>()
        val callEdges = linkedSetOf<dev.codex.kotlinls.index.CallEdge>()
        indices.forEach { candidate ->
            candidate.symbols.forEach { symbolMap[it.id] = it }
            references += candidate.references
            callEdges += candidate.callEdges
        }
        return WorkspaceIndex(
            symbols = symbolMap.values.toList(),
            references = references.toList(),
            callEdges = callEdges.toList(),
        )
    }

    private fun moduleLabel(module: ImportedModule): String =
        module.gradlePath.removePrefix(":").ifBlank { module.name }

    private fun isSourceUri(uri: String): Boolean = isSourceFile(documentUriToPath(uri))

    private fun isSourceFile(path: Path): Boolean =
        path.fileName?.toString()?.substringAfterLast('.', "").orEmpty() in setOf("kt", "kts", "java")

    private fun isProjectModelPath(path: Path): Boolean {
        val normalized = path.normalize()
        val fileName = normalized.fileName?.toString().orEmpty()
        if (fileName in setOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradle.properties")) {
            return true
        }
        return fileName == "libs.versions.toml" && normalized.parent?.fileName?.toString() == "gradle"
    }

    private fun resolveCompletion(item: CompletionItem): CompletionItem {
        val symbolId = item.data?.get("symbolId") ?: return item
        val symbol = currentIndex().symbolsById[symbolId] ?: return item
        return item.copy(
            detail = symbol.signature,
            documentation = symbol.documentation?.let { dev.codex.kotlinls.protocol.MarkupContent("markdown", it) },
        )
    }

    private fun sourceView(uri: String): Pair<Path, String>? {
        val path = documentUriToPath(uri)
        val text = documents.get(uri)?.text ?: runCatching { Files.readString(path) }.getOrNull() ?: return null
        return path.normalize() to text
    }

    private fun beginProgress(
        title: String,
        subtitle: String,
        showImmediately: Boolean = true,
        minTotalToShow: Int = 1,
    ): ProgressHandle {
        val token = "progress-${progressGeneration.incrementAndGet()}"
        if (showImmediately) {
            transport.sendNotification(
                "kotlinls/progress",
                mapOf(
                    "token" to token,
                    "event" to "begin",
                    "title" to title,
                    "subtitle" to subtitle,
                    "current" to 0,
                    "total" to 0,
                    "indeterminate" to true,
                ),
            )
        }
        return ProgressHandle(
            token = token,
            title = title,
            shown = showImmediately,
            initialSubtitle = subtitle,
            minTotalToShow = minTotalToShow,
        )
    }

    private fun notifyUser(message: String) {
        transport.sendNotification(
            "window/showMessage",
            mapOf(
                "type" to 3,
                "message" to "kotlin-neovim-lsp: $message",
            ),
        )
    }

    private fun <T : Any> read(node: JsonNode?, type: Class<T>): T {
        requireNotNull(node) { "Missing params for $type" }
        return Json.mapper.treeToValue(node, type)
    }

    private fun respond(message: JsonRpcInboundMessage, block: () -> Any?) {
        val id = requireNotNull(message.id) { "Request missing id for ${message.method}" }
        transport.sendResponse(id, block())
    }

    private fun respondRaw(id: JsonNode?, block: () -> Any?) {
        if (id != null) {
            transport.sendResponse(id, block())
        }
    }

    private inner class ProgressHandle(
        private val token: String,
        private val title: String,
        private var shown: Boolean,
        private val initialSubtitle: String,
        private val minTotalToShow: Int,
    ) {
        fun report(subtitle: String, current: Int?, total: Int?) {
            maybeShow(subtitle, current, total)
            if (!shown) return
            transport.sendNotification(
                "kotlinls/progress",
                mapOf(
                    "token" to token,
                    "event" to "report",
                    "title" to title,
                    "subtitle" to subtitle,
                    "current" to current,
                    "total" to total,
                    "indeterminate" to (current == null || total == null || total <= 0),
                ),
            )
        }

        fun complete(subtitle: String) {
            if (!shown) return
            transport.sendNotification(
                "kotlinls/progress",
                mapOf(
                    "token" to token,
                    "event" to "end",
                    "title" to title,
                    "subtitle" to subtitle,
                    "current" to 1,
                    "total" to 1,
                    "indeterminate" to false,
                ),
            )
        }

        fun fail(subtitle: String) {
            maybeShow(subtitle, 1, 1)
            transport.sendNotification(
                "kotlinls/progress",
                mapOf(
                    "token" to token,
                    "event" to "error",
                    "title" to title,
                    "subtitle" to subtitle,
                    "current" to 1,
                    "total" to 1,
                    "indeterminate" to false,
                ),
            )
        }

        private fun maybeShow(subtitle: String, current: Int?, total: Int?) {
            if (shown) return
            if (total == null || total < minTotalToShow) return
            shown = true
            transport.sendNotification(
                "kotlinls/progress",
                mapOf(
                    "token" to token,
                    "event" to "begin",
                    "title" to title,
                    "subtitle" to initialSubtitle,
                    "current" to 0,
                    "total" to total,
                    "indeterminate" to false,
                ),
            )
        }
    }

    private data class RefreshRequest(
        val reimportProject: Boolean,
        val rebuildFastIndex: Boolean,
        val semanticUris: Set<String>,
    )

    private data class ModuleSemanticState(
        val module: ImportedModule,
        val snapshot: WorkspaceAnalysisSnapshot?,
        val index: WorkspaceIndex,
        val projectGeneration: Int,
        val requestId: Int,
        val fullyIndexed: Boolean,
        val validated: Boolean,
    )
}
