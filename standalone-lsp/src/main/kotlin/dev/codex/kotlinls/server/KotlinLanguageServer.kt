package dev.codex.kotlinls.server

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import dev.codex.kotlinls.analysis.KotlinWorkspaceAnalyzer
import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.codeactions.CodeActionService
import dev.codex.kotlinls.completion.CompletionRoute
import dev.codex.kotlinls.completion.CompletionRoutingDecision
import dev.codex.kotlinls.completion.CompletionService
import dev.codex.kotlinls.diagnostics.DiagnosticsService
import dev.codex.kotlinls.diagnostics.DiagnosticsService.FastDiagnosticLookup
import dev.codex.kotlinls.formatting.FormattingService
import dev.codex.kotlinls.hover.HoverAndSignatureService
import dev.codex.kotlinls.index.IndexedSymbol
import dev.codex.kotlinls.index.LightweightWorkspaceIndexBuilder
import dev.codex.kotlinls.index.PersistentSemanticIndexCache
import dev.codex.kotlinls.index.SourceIndexLookup
import dev.codex.kotlinls.index.SupportSymbolIndexBuilder
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.index.WorkspaceIndexBuilder
import dev.codex.kotlinls.index.indexedSymbolCompletionDetail
import dev.codex.kotlinls.index.indexedSymbolDocumentation
import dev.codex.kotlinls.navigation.NavigationService
import dev.codex.kotlinls.projectimport.GradleProjectImporter
import dev.codex.kotlinls.projectimport.ImportedModule
import dev.codex.kotlinls.projectimport.ImportedProject
import dev.codex.kotlinls.projectimport.StableArtifactFingerprint
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
import dev.codex.kotlinls.protocol.TextEdit
import dev.codex.kotlinls.protocol.TypeHierarchyItem
import dev.codex.kotlinls.protocol.WorkspaceSymbolParams
import dev.codex.kotlinls.refactor.RenameService
import dev.codex.kotlinls.symbols.SymbolAndSemanticService
import dev.codex.kotlinls.workspace.LineIndex
import dev.codex.kotlinls.workspace.ProjectRootDetector
import dev.codex.kotlinls.workspace.TextDocumentSnapshot
import dev.codex.kotlinls.workspace.TextDocumentStore
import dev.codex.kotlinls.workspace.documentUriToPath
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    private val supportSymbolIndexBuilder: SupportSymbolIndexBuilder = SupportSymbolIndexBuilder(),
    private val indexBuilder: WorkspaceIndexBuilder = WorkspaceIndexBuilder(includeSupportSymbols = false),
    private val semanticIndexCache: PersistentSemanticIndexCache = PersistentSemanticIndexCache(),
    private val diagnosticsService: DiagnosticsService = DiagnosticsService(),
    private val completionService: CompletionService = CompletionService(),
    private val hoverService: HoverAndSignatureService = HoverAndSignatureService(),
    private val symbolService: SymbolAndSemanticService = SymbolAndSemanticService(),
    private val navigationService: NavigationService = NavigationService(),
    private val renameService: RenameService = RenameService(),
    private val formattingService: FormattingService = FormattingService(),
    private val codeActionService: CodeActionService = CodeActionService(formattingService),
    private val refreshDebounceMillis: Long = 0L,
    private val semanticRefreshDebounceMillis: Long = 0L,
    private val warmupStartDelayMillis: Long = 3_000L,
) {
    private var config: KotlinLsConfig = KotlinLsConfig()
    private var semanticEngine: SemanticEngine = DisabledSemanticEngine(SemanticBackend.DISABLED)
    private var shutdownRequested = false
    private val startupSessionId =
        "${ProcessHandle.current().pid().toString(36)}-${java.lang.Long.toHexString(System.currentTimeMillis()).takeLast(5)}"
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
    private var workspaceIndexReady = false

    @Volatile
    private var supportIndex: WorkspaceIndex = WorkspaceIndex(emptyList(), emptyList(), emptyList())

    @Volatile
    private var combinedIndex: WorkspaceIndex = WorkspaceIndex(emptyList(), emptyList(), emptyList())

    @Volatile
    private var importCompletionCombinedIndex: WorkspaceIndex = WorkspaceIndex(emptyList(), emptyList(), emptyList())

    @Volatile
    private var combinedIndexGeneration = 0

    @Volatile
    private var supportCacheFullyLoaded = false

    @Volatile
    private var supportCacheManifestAvailable: Boolean? = null

    private val loadedSupportPackages = linkedSetOf<String>()
    private val supportPackagesByUri = linkedMapOf<String, Set<String>>()

    @Volatile
    private var moduleSemanticFingerprints: Map<String, String> = emptyMap()

    private val refreshExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "android-neovim-lsp-refresh").apply { isDaemon = true }
    }
    private val supportExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "android-neovim-lsp-support").apply { isDaemon = true }
    }
    private val diagnosticExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "android-neovim-lsp-diagnostics").apply { isDaemon = true }
    }
    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "android-neovim-lsp-persist").apply { isDaemon = true }
    }
    private val semanticIndexExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "android-neovim-lsp-semantic-index").apply { isDaemon = true }
    }
    private val semanticRequestExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "android-neovim-lsp-semantic-request").apply { isDaemon = true }
    }
    private val refreshLock = Any()
    private val semanticStateLock = Any()
    private val warmupLock = Any()
    private val supportRefreshLock = Any()
    private val diagnosticLock = Object()
    private val requestMemoLock = Any()
    private val completionRouteStatsLock = Any()
    private val semanticRequestLock = Any()
    private val completionMemo = linkedMapOf<RequestMemoKey, MemoizedValue<CompletionList>>()
    private val hoverMemo = linkedMapOf<RequestMemoKey, MemoizedValue<dev.codex.kotlinls.protocol.Hover?>>()
    private val definitionMemo =
        linkedMapOf<RequestMemoKey, MemoizedValue<List<dev.codex.kotlinls.protocol.Location>>>()
    private val typeDefinitionMemo =
        linkedMapOf<RequestMemoKey, MemoizedValue<List<dev.codex.kotlinls.protocol.Location>>>()
    private var refreshRunning = false
    private var refreshRequested = false
    private var refreshReimportRequested = false
    private var refreshFastIndexRequested = false
    private var refreshInteractiveSemanticRequested = false
    private val pendingSemanticUris = linkedSetOf<String>()
    private var warmupRunning = false
    private var supportRefreshRunning = false
    private var diagnosticsRefreshRunning = false
    private var pendingSupportRefresh: SupportRefreshRequest? = null
    private var pendingDiagnosticPublishAtMillis = 0L
    private var pendingDiagnosticFullRefresh = false
    private val pendingDiagnosticUris = linkedSetOf<String>()
    private val deferredDiagnosticUris = linkedSetOf<String>()
    private val pendingDiagnosticForceUris = linkedSetOf<String>()
    private val pendingDiagnosticFlushAcknowledgements = linkedMapOf<String, Int>()
    private var warmupGeneration = 0
    private val warmupQueuedModules = linkedSetOf<String>()
    private val warmupAllowedOpenModules = linkedSetOf<String>()
    private val refreshGeneration = AtomicInteger(0)
    private val progressGeneration = AtomicInteger(0)
    private val diagnosticRequestGeneration = AtomicInteger(0)
    private val semanticRequestGeneration = AtomicInteger(0)
    private var publishedDiagnosticUris: Set<String> = emptySet()
    private val publishedDiagnosticFingerprints = linkedMapOf<String, String>()
    private val maxFocusedSemanticPaths = 32
    private val maxSamePackageForegroundPaths = 8
    private val maxBackgroundWarmupModules = 8
    private val requestMemoMaxEntries = 512
    @Volatile
    private var currentProjectGeneration = 0
    private val latestSemanticRequestIds = linkedMapOf<String, Int>()
    private val inFlightSemanticRequests = linkedMapOf<String, CompletableFuture<ModuleSemanticState?>>()
    private val completionRouteStats = linkedMapOf<String, CompletionRouteMetric>()

    fun run() {
        try {
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
        } finally {
            semanticEngine.close()
            refreshExecutor.shutdownNow()
            supportExecutor.shutdownNow()
            diagnosticExecutor.shutdownNow()
            persistExecutor.shutdownNow()
            semanticIndexExecutor.shutdownNow()
            semanticRequestExecutor.shutdownNow()
        }
    }

    private fun handle(message: JsonRpcInboundMessage) {
        try {
            when (message.method) {
                "initialize" -> respond(message) { initialize(read(message.params, InitializeParams::class.java)) }
                "initialized" -> {
                    clientInitialized = true
                    when {
                        project == null -> scheduleRefresh(reimportProject = true, rebuildFastIndex = true)
                        lightweightIndex.symbols.isEmpty() -> scheduleRefresh(reimportProject = false, rebuildFastIndex = true)
                        else -> project?.let { currentProject ->
                            if (lightweightIndexBuilder.requiresBackgroundRefresh(currentProject)) {
                                scheduleRefresh(reimportProject = false, rebuildFastIndex = true)
                            }
                            if (supportIndex.symbols.isEmpty()) {
                                scheduleSupportRefresh(
                                    project = currentProject,
                                    projectGeneration = currentProjectGeneration,
                                    packageNames = supportPackagesForDocuments(documents.openDocuments()),
                                    ensureCache = true,
                                )
                            }
                            semanticEngine.prefetch(
                                project = currentProject,
                                activeDocument = documents.openDocuments().firstOrNull(),
                                openDocuments = documents.openDocuments(),
                                projectGeneration = currentProjectGeneration,
                            )
                            if (usesLocalSemanticRuntime()) {
                                val openDocuments = documents.openDocuments()
                                val backgroundModules = backgroundWarmupModules(currentProject)
                                if (backgroundModules.isNotEmpty()) {
                                    scheduleBackgroundWarmup(currentProject, backgroundModules, includeOpenModules = true)
                                }
                                openDocuments.forEach { document ->
                                    scheduleSemanticRefresh(document.uri)
                                }
                            }
                        }
                    }
                }
                "shutdown" -> respondRaw(message.id) {
                    shutdownRequested = true
                    null
                }
                "textDocument/didOpen" -> {
                    val params = read(message.params, DidOpenTextDocumentParams::class.java)
                    documents.open(params.textDocument)
                    synchronized(diagnosticLock) {
                        deferredDiagnosticUris.remove(params.textDocument.uri)
                    }
                    updateLiveSourceIndex(params.textDocument.uri)
                    semanticEngine.invalidate(params.textDocument.uri, params.textDocument.version)
                    ensureProjectReady(params.textDocument.uri)
                    project?.let { currentProject ->
                        refreshSupportPackages(
                            project = currentProject,
                            uri = params.textDocument.uri,
                            text = params.textDocument.text,
                            force = true,
                        )
                        semanticEngine.prefetch(
                            project = currentProject,
                            activeDocument = documents.get(params.textDocument.uri),
                            openDocuments = documents.openDocuments(),
                            projectGeneration = currentProjectGeneration,
                            syncDocuments = false,
                            startBridge = true,
                        )
                    }
                    scheduleSemanticRefresh(params.textDocument.uri)
                }
                "textDocument/didChange" -> {
                    val params = read(message.params, DidChangeTextDocumentParams::class.java)
                    documents.applyChanges(params)
                    updateLiveSourceIndex(params.textDocument.uri, publishDiagnostics = false)
                    synchronized(diagnosticLock) {
                        deferredDiagnosticUris += params.textDocument.uri
                    }
                    semanticEngine.invalidate(params.textDocument.uri, params.textDocument.version)
                    ensureProjectReady(params.textDocument.uri)
                    project?.let { currentProject ->
                        documents.get(params.textDocument.uri)?.text?.let { currentText ->
                            refreshSupportPackages(
                                project = currentProject,
                                uri = params.textDocument.uri,
                                text = currentText,
                            )
                        }
                    }
                }
                "textDocument/didClose" -> {
                    val uri = read(message.params, DidCloseTextDocumentParams::class.java).textDocument.uri
                    documents.close(uri)
                    semanticEngine.invalidate(uri)
                    synchronized(diagnosticLock) {
                        deferredDiagnosticUris.remove(uri)
                    }
                    synchronized(supportRefreshLock) {
                        supportPackagesByUri.remove(uri)
                    }
                    rebuildCombinedIndex()
                    clearDiagnostics(uri)
                }
                "textDocument/didSave" -> {
                    val uri = read(message.params, DidSaveTextDocumentParams::class.java).textDocument.uri
                    val path = documentUriToPath(uri)
                    synchronized(diagnosticLock) {
                        deferredDiagnosticUris.remove(uri)
                    }
                    ensureProjectReady(uri)
                    if (isProjectModelPath(path)) {
                        scheduleRefresh(reimportProject = true, rebuildFastIndex = true)
                    } else if (isSourceFile(path)) {
                        updateLiveSourceIndex(uri)
                        val currentProject = project
                        val source = sourceView(uri)
                        if (currentProject != null && source != null) {
                            persistExecutor.execute {
                                runCatching {
                                    lightweightIndexBuilder.persistDocument(currentProject, source.first, source.second)
                                }.onFailure { error ->
                                    System.err.println("[android-neovim-lsp] lightweight cache persist failed: ${error.message}")
                                }
                            }
                            semanticEngine.invalidate(uri, documents.get(uri)?.version)
                            semanticEngine.prefetch(
                                project = currentProject,
                                activeDocument = documents.get(uri),
                                openDocuments = documents.openDocuments(),
                                projectGeneration = currentProjectGeneration,
                                syncDocuments = false,
                                startBridge = true,
                            )
                        }
                        scheduleSemanticRefresh(uri, interactive = false)
                    }
                }
                "$/android-neovim/flushDiagnostics" -> {
                    val params = read(message.params, FlushDiagnosticsParams::class.java)
                    flushDeferredDiagnostics(params.textDocument.uri, params.changedLines, params.generation)
                }
                "workspace/didChangeConfiguration" -> scheduleRefresh(reimportProject = true, rebuildFastIndex = true)
                "workspace/didChangeWatchedFiles" -> scheduleRefresh(reimportProject = true, rebuildFastIndex = true)
                "textDocument/completion" -> respond(message) {
                    val params = read(message.params, CompletionParams::class.java)
                    val source = sourceView(params.textDocument.uri)
                    val currentProject = project
                    if (source != null) {
                        currentProject?.let { projectForCompletion ->
                            hydrateFastIndexForCompletion(
                                project = projectForCompletion,
                                path = source.first,
                                text = source.second,
                                params = params,
                            )
                        }
                        val cacheKey = requestMemoKey(params.textDocument.uri, params.position)
                        memoizedCompletion(cacheKey)?.let { return@respond it }
                        val activeIndex = currentIndex()
                        val routeDecision = completionService.classifyCompletionRoute(
                            index = activeIndex,
                            path = source.first,
                            text = source.second,
                            params = params,
                            bridgeAvailable = currentProject != null,
                        )
                        val startedAt = System.nanoTime()
                        val response = when (routeDecision.route) {
                            CompletionRoute.INDEX -> completionService.completeFromIndex(
                                index = completionIndexForRoute(routeDecision),
                                path = source.first,
                                text = source.second,
                                params = params,
                            )

                            CompletionRoute.BRIDGE -> currentProject?.let { current ->
                                availableSemanticState(params.textDocument.uri)
                                    ?.let { semantic ->
                                        completionService.semanticNamedArgumentCompletions(semantic.first, params)
                                    }
                                    ?: completionService.namedArgumentCompletionsFromIndex(
                                        index = currentIndex(),
                                        path = source.first,
                                        text = source.second,
                                        params = params,
                                    )
                                    ?: semanticEngine.complete(
                                        project = current,
                                        path = source.first,
                                        text = source.second,
                                        version = documents.get(params.textDocument.uri)?.version,
                                        params = params,
                                        projectGeneration = currentProjectGeneration,
                                    )
                            } ?: CompletionList(false, emptyList())
                        }
                        recordCompletionRoute(
                            decision = routeDecision,
                            itemCount = response.items.size,
                            latencyMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L),
                        )
                        rememberCompletion(cacheKey, response)
                        return@respond response
                    }
                    CompletionList(false, emptyList())
                }
                "completionItem/resolve" -> respond(message) {
                    val item = read(message.params, CompletionItem::class.java)
                    resolveCompletion(item)
                }
                "textDocument/hover" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val source = sourceView(params.textDocument.uri) ?: return@respond null
                    project?.let { currentProject ->
                        hydrateFastIndexForImportedPackages(currentProject, source.first, source.second)
                    }
                    val cacheKey = requestMemoKey(params.textDocument.uri, params.position)
                    memoizedHover(cacheKey)?.let { return@respond it.value }
                    val indexedSymbol = SourceIndexLookup.resolveSymbol(currentIndex(), source.first, source.second, params.position)
                    if (indexedSymbol != null && shouldPreferIndexForLookup(source.first, source.second, params.position, indexedSymbol)) {
                        val indexedHover = hoverService.hoverFromIndex(currentIndex(), source.first, source.second, params)
                        rememberHover(cacheKey, indexedHover)
                        return@respond indexedHover
                    }
                    semanticStateForRequest(params.textDocument.uri)?.let { current ->
                        hoverService.hover(current.first, current.second, params)
                            ?.let { hover ->
                                rememberHover(cacheKey, hover)
                                return@respond hover
                            }
                    }
                    val response = hoverService.hoverFromIndex(currentIndex(), source.first, source.second, params)
                        ?: project?.let { currentProject ->
                            semanticEngine.hover(
                                project = currentProject,
                                path = source.first,
                                text = source.second,
                                version = documents.get(params.textDocument.uri)?.version,
                                params = params,
                                projectGeneration = currentProjectGeneration,
                            )
                        }
                    rememberHover(cacheKey, response)
                    response
                }
                "textDocument/signatureHelp" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond hoverService.signatureHelp(current.first, current.second, params) }
                    null
                }
                "textDocument/definition" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val source = sourceView(params.textDocument.uri) ?: return@respond emptyList<dev.codex.kotlinls.protocol.Location>()
                    project?.let { currentProject ->
                        hydrateFastIndexForImportedPackages(currentProject, source.first, source.second)
                    }
                    val cacheKey = requestMemoKey(params.textDocument.uri, params.position)
                    memoizedDefinition(cacheKey)?.let { return@respond it }
                    val indexedSymbol = SourceIndexLookup.resolveSymbol(currentIndex(), source.first, source.second, params.position)
                    if (indexedSymbol != null && shouldPreferIndexForLookup(source.first, source.second, params.position, indexedSymbol)) {
                        val indexedDefinition = navigationService.definitionFromIndex(currentIndex(), source.first, source.second, params)
                        rememberDefinition(cacheKey, indexedDefinition)
                        return@respond indexedDefinition
                    }
                    semanticStateForRequest(params.textDocument.uri)?.let { current ->
                        val semantic = navigationService.definition(current.first, current.second, params)
                        if (semantic.isNotEmpty()) {
                            rememberDefinition(cacheKey, semantic)
                            return@respond semantic
                        }
                    }
                    val response = navigationService.definitionFromIndex(currentIndex(), source.first, source.second, params)
                        .takeIf { it.isNotEmpty() }
                        ?: project?.let { currentProject ->
                            semanticEngine.definition(
                                project = currentProject,
                                path = source.first,
                                text = source.second,
                                version = documents.get(params.textDocument.uri)?.version,
                                params = params,
                                projectGeneration = currentProjectGeneration,
                            )?.takeIf { it.isNotEmpty() }
                        }
                        ?: emptyList()
                    rememberDefinition(cacheKey, response)
                    response
                }
                "textDocument/typeDefinition" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    val source = sourceView(params.textDocument.uri) ?: return@respond emptyList<dev.codex.kotlinls.protocol.Location>()
                    project?.let { currentProject ->
                        hydrateFastIndexForImportedPackages(currentProject, source.first, source.second)
                    }
                    val cacheKey = requestMemoKey(params.textDocument.uri, params.position)
                    memoizedTypeDefinition(cacheKey)?.let { return@respond it }
                    val indexedSymbol = SourceIndexLookup.resolveSymbol(currentIndex(), source.first, source.second, params.position)
                    if (indexedSymbol != null && shouldPreferIndexForLookup(source.first, source.second, params.position, indexedSymbol)) {
                        val indexedTypeDefinition = navigationService.typeDefinitionFromIndex(currentIndex(), source.first, source.second, params)
                        rememberTypeDefinition(cacheKey, indexedTypeDefinition)
                        return@respond indexedTypeDefinition
                    }
                    semanticStateForRequest(params.textDocument.uri)?.let { current ->
                        val semantic = navigationService.typeDefinition(current.first, current.second, params)
                        if (semantic.isNotEmpty()) {
                            rememberTypeDefinition(cacheKey, semantic)
                            return@respond semantic
                        }
                    }
                    val response = navigationService.typeDefinitionFromIndex(currentIndex(), source.first, source.second, params)
                        .takeIf { it.isNotEmpty() }
                        ?: emptyList()
                    scheduleSemanticRefresh(params.textDocument.uri)
                    rememberTypeDefinition(cacheKey, response)
                    response
                }
                "textDocument/references" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond navigationService.references(current.first, current.second, params) }
                    scheduleSemanticRefresh(params.textDocument.uri)
                    emptyList<dev.codex.kotlinls.protocol.Location>()
                }
                "textDocument/implementation" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond navigationService.implementations(current.first, current.second, params) }
                    scheduleSemanticRefresh(params.textDocument.uri)
                    emptyList<dev.codex.kotlinls.protocol.Location>()
                }
                "textDocument/documentSymbol" -> respond(message) {
                    symbolService.documentSymbols(currentIndex(), read(message.params, DocumentSymbolParams::class.java))
                }
                "workspace/symbol" -> respond(message) {
                    symbolService.workspaceSymbols(currentIndex(), read(message.params, WorkspaceSymbolParams::class.java))
                }
                "textDocument/semanticTokens/full" -> respond(message) {
                    val params = read(message.params, SemanticTokensParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond symbolService.semanticTokens(current.first, params) }
                    dev.codex.kotlinls.protocol.SemanticTokens(emptyList())
                }
                "textDocument/documentHighlight" -> respond(message) {
                    val params = read(message.params, DocumentHighlightParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond navigationService.documentHighlights(current.first, current.second, params) }
                    emptyList<dev.codex.kotlinls.protocol.DocumentHighlight>()
                }
                "textDocument/foldingRange" -> respond(message) {
                    val params = read(message.params, FoldingRangeParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond navigationService.foldingRanges(current.first, params) }
                    emptyList<dev.codex.kotlinls.protocol.FoldingRange>()
                }
                "textDocument/selectionRange" -> respond(message) {
                    val params = read(message.params, SelectionRangeParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond navigationService.selectionRanges(current.first, params) }
                    emptyList<dev.codex.kotlinls.protocol.SelectionRange>()
                }
                "textDocument/inlayHint" -> respond(message) {
                    val params = read(message.params, InlayHintParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond hoverService.inlayHints(current.first, current.second, params) }
                    emptyList<dev.codex.kotlinls.protocol.InlayHint>()
                }
                "textDocument/prepareRename" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond renameService.prepareRename(current.first, current.second, params) }
                    scheduleSemanticRefresh(params.textDocument.uri)
                    null
                }
                "textDocument/rename" -> respond(message) {
                    val params = read(message.params, RenameParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond renameService.rename(current.first, current.second, params) }
                    scheduleSemanticRefresh(params.textDocument.uri)
                    null
                }
                "textDocument/formatting" -> respond(message) {
                    val params = read(message.params, DocumentFormattingParams::class.java)
                    val source = sourceView(params.textDocument.uri) ?: return@respond emptyList<dev.codex.kotlinls.protocol.TextEdit>()
                    val edits = semanticEngine.formatDocument(
                        projectRoot = project?.root,
                        path = source.first,
                        text = source.second,
                        version = documents.get(params.textDocument.uri)?.version,
                        params = params,
                        projectGeneration = currentProjectGeneration,
                    )
                        ?: emptyList<dev.codex.kotlinls.protocol.TextEdit>()
                    val semanticState = availableSemanticState(params.textDocument.uri)
                    if (semanticState == null) {
                        edits
                    } else {
                        val formattedText = applyTextEdits(source.second, edits)
                        val organizedText = formattingService.organizeImportsForText(
                            snapshot = semanticState.first,
                            uri = params.textDocument.uri,
                            text = formattedText,
                        ) ?: formattedText
                        formattingService.editsForFormattedText(source.second, organizedText)
                    }
                }
                "textDocument/rangeFormatting" -> respond(message) {
                    val params = read(message.params, DocumentRangeFormattingParams::class.java)
                    val source = sourceView(params.textDocument.uri) ?: return@respond emptyList<dev.codex.kotlinls.protocol.TextEdit>()
                    semanticEngine.formatRange(
                        projectRoot = project?.root,
                        path = source.first,
                        text = source.second,
                        version = documents.get(params.textDocument.uri)?.version,
                        params = params,
                        projectGeneration = currentProjectGeneration,
                    )
                        ?: emptyList<dev.codex.kotlinls.protocol.TextEdit>()
                }
                "textDocument/codeAction" -> respond(message) {
                    val params = read(message.params, dev.codex.kotlinls.protocol.CodeActionParams::class.java)
                    availableSemanticState(params.textDocument.uri)?.let { current ->
                        return@respond codeActionService.codeActions(current.first, current.second, params)
                    }
                    scheduleSemanticRefresh(params.textDocument.uri)
                    val source = sourceView(params.textDocument.uri) ?: return@respond emptyList<dev.codex.kotlinls.protocol.CodeAction>()
                    codeActionService.lightweightCodeActions(currentIndex(), params.textDocument.uri, source.second, params)
                }
                "textDocument/prepareCallHierarchy" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond navigationService.prepareCallHierarchy(current.first, current.second, params) }
                    scheduleSemanticRefresh(params.textDocument.uri)
                    emptyList<dev.codex.kotlinls.protocol.CallHierarchyItem>()
                }
                "callHierarchy/incomingCalls" -> respond(message) {
                    navigationService.incomingCalls(currentIndex(), read(message.params, dev.codex.kotlinls.protocol.CallHierarchyItem::class.java))
                }
                "callHierarchy/outgoingCalls" -> respond(message) {
                    navigationService.outgoingCalls(currentIndex(), read(message.params, dev.codex.kotlinls.protocol.CallHierarchyItem::class.java))
                }
                "textDocument/prepareTypeHierarchy" -> respond(message) {
                    val params = read(message.params, TextDocumentPositionParams::class.java)
                    availableSemanticState(params.textDocument.uri)
                        ?.let { current -> return@respond navigationService.prepareTypeHierarchy(current.first, current.second, params) }
                    scheduleSemanticRefresh(params.textDocument.uri)
                    emptyList<TypeHierarchyItem>()
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
            System.err.println("[android-neovim-lsp] ${t::class.java.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
        }
    }

    private fun initialize(params: InitializeParams): InitializeResult {
        config = KotlinLsConfig.fromInitializationOptions(params.initializationOptions)
        semanticEngine.close()
        semanticEngine = BridgeK2SemanticEngine.create(config.semantic)
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
        val semanticCapabilities = semanticEngine.capabilities
        val localSemanticCapabilities = usesLocalSemanticRuntime()
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
                signatureHelpProvider = if (localSemanticCapabilities || semanticCapabilities.signatureHelp) {
                    SignatureHelpOptions(triggerCharacters = listOf("(", ","))
                } else {
                    null
                },
                semanticTokensProvider = if (localSemanticCapabilities || semanticCapabilities.semanticTokens) {
                    SemanticTokensOptions(
                        legend = SemanticTokensLegend(
                            tokenTypes = listOf(
                                "namespace", "type", "class", "enum", "interface", "struct", "typeParameter", "parameter",
                                "variable", "property", "enumMember", "event", "function", "method", "macro", "keyword",
                            ),
                            tokenModifiers = listOf("declaration", "readonly", "static", "deprecated", "abstract", "async"),
                        ),
                    )
                } else {
                    null
                },
                hoverProvider = true,
                definitionProvider = true,
                typeDefinitionProvider = true,
                referencesProvider = localSemanticCapabilities || semanticCapabilities.references,
                implementationProvider = localSemanticCapabilities || semanticCapabilities.implementations,
                documentFormattingProvider = semanticCapabilities.formatting,
                documentRangeFormattingProvider = semanticCapabilities.rangeFormatting,
                renameProvider = if (localSemanticCapabilities || semanticCapabilities.rename) RenameOptions(prepareProvider = true) else false,
                foldingRangeProvider = localSemanticCapabilities || semanticCapabilities.foldingRange,
                selectionRangeProvider = localSemanticCapabilities,
                documentHighlightProvider = localSemanticCapabilities || semanticCapabilities.documentHighlight,
                inlayHintProvider = localSemanticCapabilities || semanticCapabilities.inlayHints,
                callHierarchyProvider = localSemanticCapabilities || semanticCapabilities.callHierarchy,
                typeHierarchyProvider = localSemanticCapabilities || semanticCapabilities.typeHierarchy,
                executeCommandProvider = ExecuteCommandOptions(
                    commands = listOf(
                        "kotlinls.reimport",
                    ),
                ),
            ),
            serverInfo = ServerInfo(name = "android-neovim-lsp", version = "0.1.2"),
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

    private fun ensureProjectReady(uri: String) {
        if (root == null) {
            root = rootDetector.detect(documentUriToPath(uri))
        }
        if (project == null) {
            scheduleRefresh(reimportProject = true, rebuildFastIndex = true)
        }
    }

    private fun preloadPersistedState() {
        val rootPath = root ?: return
        val cachedProject = importer.loadPersistedProject(rootPath) ?: return
        project = cachedProject
        currentProjectGeneration = refreshGeneration.incrementAndGet()
        lightweightIndexBuilder.load(cachedProject)?.let { cachedIndex ->
            lightweightIndex = cachedIndex
            workspaceIndexReady = true
        }
        supportIndex = WorkspaceIndex(emptyList(), emptyList(), emptyList())
        supportCacheFullyLoaded = false
        synchronized(supportRefreshLock) {
            loadedSupportPackages.clear()
        }
        supportCacheManifestAvailable = supportSymbolIndexBuilder.loadPackages(cachedProject, emptySet()) != null
        if (usesLocalSemanticRuntime()) {
            moduleSemanticFingerprints = computeSemanticFingerprints(cachedProject)
            replaceSemanticStates(loadPersistedSemanticStates(cachedProject))
        }
        semanticEngine.onProjectChanged(cachedProject)
        rebuildCombinedIndex()
    }

    private fun updateLiveSourceIndex(uri: String, publishDiagnostics: Boolean = true) {
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
        if (publishDiagnostics) {
            scheduleDiagnosticsPublish()
        }
    }

    private fun hydrateFastIndexForCompletion(
        project: ImportedProject,
        path: Path,
        text: String,
        params: CompletionParams,
    ) {
        val packages = linkedSetOf<String>()
        packages += SourceIndexLookup.importedPackages(text)
        completionService.fastIndexHydrationPackage(text, params)
            ?.takeIf { it.isNotBlank() }
            ?.let(packages::add)
        hydrateFastIndexForPackages(project, path, packages)
    }

    private fun hydrateFastIndexForImportedPackages(
        project: ImportedProject,
        path: Path,
        text: String,
    ) {
        hydrateFastIndexForPackages(project, path, SourceIndexLookup.importedPackages(text))
    }

    private fun hydrateFastIndexForPackages(
        project: ImportedProject,
        path: Path,
        packageNames: Collection<String>,
    ) {
        if (packageNames.isEmpty()) return
        var nextIndex = lightweightIndex
        packageNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .forEach { packageName ->
                nextIndex = lightweightIndexBuilder.hydratePackageNeighborhood(
                    project = project,
                    packageName = packageName,
                    currentIndex = nextIndex,
                    documents = documents,
                    preferredPath = path,
                )
            }
        if (nextIndex !== lightweightIndex) {
            lightweightIndex = nextIndex
            rebuildCombinedIndex()
        }
    }

    private fun scheduleRefresh(
        reimportProject: Boolean,
        rebuildFastIndex: Boolean = reimportProject || lightweightIndex.symbols.isEmpty(),
        semanticUri: String? = null,
        interactiveSemanticRefresh: Boolean = true,
    ) {
        synchronized(refreshLock) {
            refreshRequested = true
            refreshReimportRequested = refreshReimportRequested || reimportProject
            refreshFastIndexRequested = refreshFastIndexRequested || rebuildFastIndex || reimportProject
            semanticUri?.let {
                collapsePendingSemanticUri(it)
                refreshInteractiveSemanticRequested = refreshInteractiveSemanticRequested || interactiveSemanticRefresh
            }
            if (refreshRunning) {
                return
            }
            refreshRunning = true
        }
        try {
            refreshExecutor.execute {
                while (true) {
                    val debounceMillis = synchronized(refreshLock) {
                        when {
                            refreshReimportRequested || refreshFastIndexRequested || refreshInteractiveSemanticRequested -> refreshDebounceMillis
                            pendingSemanticUris.isNotEmpty() -> semanticRefreshDebounceMillis
                            else -> refreshDebounceMillis
                        }
                    }
                    if (debounceMillis > 0) {
                        try {
                            Thread.sleep(debounceMillis)
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
                        val interactiveSemanticFlag = refreshInteractiveSemanticRequested
                        refreshInteractiveSemanticRequested = false
                        RefreshRequest(
                            reimportProject = flag,
                            rebuildFastIndex = fastIndexFlag || flag || lightweightIndex.symbols.isEmpty(),
                            semanticUris = semanticUris,
                            interactiveSemanticRefresh = interactiveSemanticFlag || flag || fastIndexFlag,
                        )
                    }
                    runCatching {
                        refreshWorkspaceNow(
                            reimportProject = doReimport.reimportProject,
                            rebuildFastIndex = doReimport.rebuildFastIndex,
                            requestedSemanticUris = doReimport.semanticUris,
                            interactiveSemanticRefresh = doReimport.interactiveSemanticRefresh,
                        )
                    }
                        .onFailure { error ->
                            if (isBenignCancellation(error)) {
                                return@onFailure
                            }
                            System.err.println("[android-neovim-lsp] refresh failed: ${error.message}")
                            error.printStackTrace(System.err)
                        }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(refreshLock) {
                refreshRunning = false
            }
        }
    }

    private fun scheduleSupportRefresh(
        project: ImportedProject,
        projectGeneration: Int,
        packageNames: Set<String> = emptySet(),
        ensureCache: Boolean = false,
    ) {
        synchronized(supportRefreshLock) {
            pendingSupportRefresh = pendingSupportRefresh
                ?.merge(project, projectGeneration, packageNames, ensureCache)
                ?: SupportRefreshRequest(project, projectGeneration, packageNames, ensureCache)
            if (supportRefreshRunning) {
                return
            }
            supportRefreshRunning = true
        }
        try {
            supportExecutor.execute {
                while (true) {
                    val request = synchronized(supportRefreshLock) {
                        pendingSupportRefresh?.also { pendingSupportRefresh = null }
                    } ?: run {
                        synchronized(supportRefreshLock) {
                            supportRefreshRunning = false
                        }
                        return@execute
                    }
                    runCatching {
                        refreshSupportIndexNow(request)
                    }.onFailure { error ->
                        if (isBenignCancellation(error)) {
                            return@onFailure
                        }
                        System.err.println("[android-neovim-lsp] support index refresh failed: ${error.message}")
                        error.printStackTrace(System.err)
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(supportRefreshLock) {
                supportRefreshRunning = false
            }
        }
    }

    private fun refreshSupportIndexNow(request: SupportRefreshRequest) {
        val activeProject = project ?: return
        if (request.projectGeneration != currentProjectGeneration) return
        if (activeProject.root.normalize() != request.project.root.normalize()) return
        val requestedPackages = request.packageNames
        val partialLayer = requestedPackages.takeIf { it.isNotEmpty() }
            ?.let { supportSymbolIndexBuilder.loadPackages(request.project, it) }
        if (partialLayer != null) {
            supportCacheManifestAvailable = true
        }
        if (partialLayer != null && partialLayer.symbols.isNotEmpty()) {
            if (request.projectGeneration != currentProjectGeneration) return
            supportIndex = mergeIndices(listOf(supportIndex, partialLayer.toWorkspaceIndex()))
            rebuildCombinedIndex()
        }
        if (partialLayer != null) {
            synchronized(supportRefreshLock) {
                loadedSupportPackages.addAll(requestedPackages)
            }
            scheduleDiagnosticsPublishForSupportPackages(requestedPackages)
        }
        if (!request.ensureCache) return

        val hasCache = partialLayer != null || supportSymbolIndexBuilder.loadPackages(request.project, emptySet()) != null
        if (hasCache) {
            supportCacheManifestAvailable = true
        }
        if (hasCache) return

        val supportProgress = beginProgress(
            title = "Dependency Index",
            subtitle = "Building library symbol cache",
            showImmediately = true,
            minTotalToShow = 1,
        )
        try {
            val layer = supportSymbolIndexBuilder.build(request.project)
            if (request.projectGeneration != currentProjectGeneration) return
            val readyLayer = requestedPackages.takeIf { it.isNotEmpty() }
                ?.let { packages ->
                    layer.copy(symbols = layer.symbols.filter { symbol -> symbol.packageName in packages })
                }
                ?: layer
            supportCacheManifestAvailable = true
            supportCacheFullyLoaded = true
            synchronized(supportRefreshLock) {
                loadedSupportPackages.clear()
            }
            supportIndex = mergeIndices(listOf(supportIndex, readyLayer.toWorkspaceIndex()))
            rebuildCombinedIndex()
            scheduleDiagnosticsPublish()
            supportProgress.report("Cached ${layer.symbols.size} library symbols", 1, 1)
            supportProgress.complete("Dependency index ready")
        } catch (t: Throwable) {
            supportProgress.fail(t.message ?: t::class.java.simpleName)
            throw t
        }
    }

    private fun refreshWorkspaceNow(
        reimportProject: Boolean = true,
        rebuildFastIndex: Boolean = true,
        requestedSemanticUris: Set<String> = emptySet(),
        interactiveSemanticRefresh: Boolean = true,
    ) {
        val rootPath = root ?: return
        val previousProject = project
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
            workspaceIndexReady = false
            if (usesLocalSemanticRuntime()) {
                moduleSemanticFingerprints = computeSemanticFingerprints(nextProject)
                val carriedStates = carryForwardSemanticStates(nextProject)
                val validatedStates = loadPersistedSemanticStates(nextProject)
                replaceSemanticStates(carriedStates + validatedStates)
            } else {
                moduleSemanticFingerprints = emptyMap()
                replaceSemanticStates(emptyMap())
            }
        }

        if (rebuildFastIndex || lightweightIndex.symbols.isEmpty()) {
            workspaceIndexReady = false
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
        workspaceIndexReady = true

        if (requiresImport || previousProject?.root?.normalize() != nextProject.root.normalize()) {
            supportIndex = WorkspaceIndex(emptyList(), emptyList(), emptyList())
            supportCacheFullyLoaded = false
            supportCacheManifestAvailable = null
            synchronized(supportRefreshLock) {
                loadedSupportPackages.clear()
            }
        }
        project = nextProject
        if (requiresImport || previousProject !== nextProject) {
            semanticEngine.onProjectChanged(nextProject)
        }
        scheduleSupportRefresh(
            project = nextProject,
            projectGeneration = currentProjectGeneration,
            packageNames = supportPackagesForDocuments(documents.openDocuments()),
            ensureCache = requiresImport || previousProject?.root?.normalize() != nextProject.root.normalize(),
        )
        scheduleDiagnosticsPublish()
        if (usesLocalSemanticRuntime()) {
            val semanticUris = requestedSemanticUris.ifEmpty { documents.openDocuments().map { it.uri }.toSet() }
            val backgroundModules = backgroundWarmupModules(nextProject)
            if (backgroundModules.isNotEmpty()) {
                scheduleBackgroundWarmup(nextProject, backgroundModules, includeOpenModules = true)
            }
            if (semanticUris.isNotEmpty()) {
                refreshSemanticModules(nextProject, semanticUris, interactiveSemanticRefresh)
            }
        }
        val activeDocument = documents.openDocuments().firstOrNull { it.uri in requestedSemanticUris }
            ?: documents.openDocuments().firstOrNull()
        if (activeDocument != null) {
            semanticEngine.prefetch(
                project = nextProject,
                activeDocument = activeDocument,
                openDocuments = documents.openDocuments(),
                projectGeneration = currentProjectGeneration,
                syncDocuments = false,
                startBridge = true,
            )
        }
        rebuildCombinedIndex()
        scheduleDiagnosticsPublish()
    }

    private fun refreshSemanticModules(
        project: ImportedProject,
        requestedSemanticUris: Set<String>,
        interactiveSemanticRefresh: Boolean,
    ) {
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
                !requestedDocumentsChanged
            ) {
                if (currentState.snapshot == null) {
                    scheduleBackgroundWarmup(project, listOf(module.gradlePath), includeOpenModules = true)
                }
                return@forEach
            }
            val focusedPaths = focusedSemanticPaths(project, module, moduleRequestedUris)
            val snapshotState = analyzeModuleSnapshot(
                project = project,
                module = module,
                focusedPaths = focusedPaths,
                background = false,
                showProgress = true,
                analysisTitle = "Full Diagnostics",
                onSnapshotReady = ::publishSemanticSnapshotDiagnostics,
            )
            installSemanticState(
                snapshotState,
                rebuildIndex = false,
                scheduleDiagnostics = false,
                allowBackgroundWarmup = false,
                persistState = false,
            )
            scheduleSemanticIndexBuild(snapshotState, focusedPaths, background = false, showProgress = true)
        }
    }

    private fun analyzeModuleSnapshot(
        project: ImportedProject,
        module: ImportedModule,
        focusedPaths: Set<Path>,
        background: Boolean,
        showProgress: Boolean = !background,
        analysisTitle: String = if (background) "Semantic Warmup" else "Semantic Analysis",
        onSnapshotReady: ((WorkspaceAnalysisSnapshot, Map<String, String>) -> Unit)? = null,
    ): ModuleSemanticState {
        val moduleLabel = moduleLabel(module)
        val semanticProject = project.subsetForModules(listOf(module))
        val requestId = nextSemanticRequestId(module.gradlePath)
        val semanticAnalysisProgress = beginProgress(
            title = analysisTitle,
            subtitle = when {
                background -> "Warming $moduleLabel"
                analysisTitle == "Full Diagnostics" -> "Running compiler diagnostics for $moduleLabel"
                else -> "Resolving $moduleLabel"
            },
            showImmediately = showProgress && !background,
            minTotalToShow = if (background || !showProgress) Int.MAX_VALUE else 1,
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
                    when {
                        background -> "$moduleLabel semantic warmup ready"
                        analysisTitle == "Full Diagnostics" -> "$moduleLabel full diagnostics ready"
                        else -> "$moduleLabel semantic model ready"
                    },
                )
            }
        } catch (t: Throwable) {
            semanticAnalysisProgress.fail(t.message ?: t::class.java.simpleName)
            throw t
        }
        val fileContentHashes = semanticFileContentHashes(nextSnapshot)
        onSnapshotReady?.invoke(nextSnapshot, fileContentHashes)
        return ModuleSemanticState(
            module = module,
            snapshot = nextSnapshot,
            index = WorkspaceIndex(emptyList(), emptyList(), emptyList()),
            projectGeneration = currentProjectGeneration,
            requestId = requestId,
            fullyIndexed = false,
            validated = true,
            fileContentHashes = fileContentHashes,
            openDocumentVersions = openDocumentVersionsForModule(project, module),
        )
    }

    private fun buildSemanticIndex(
        state: ModuleSemanticState,
        focusedPaths: Set<Path>,
        background: Boolean,
        showProgress: Boolean = !background,
    ): ModuleSemanticState {
        val snapshot = state.snapshot ?: return state
        val moduleLabel = moduleLabel(state.module)
        val semanticIndexProgress = beginProgress(
            title = if (background) "Semantic Warmup Index" else "Semantic Index",
            subtitle = if (background) "Warming index for $moduleLabel" else "Indexing $moduleLabel",
            showImmediately = showProgress && !background,
            minTotalToShow = if (background || !showProgress) Int.MAX_VALUE else 1,
        )
        val nextIndex = try {
            indexBuilder.build(snapshot, targetPaths = focusedPaths.takeIf { it.isNotEmpty() }) { subtitle, current, total ->
                semanticIndexProgress.report("$moduleLabel: $subtitle", current, total)
            }.also {
                semanticIndexProgress.complete(
                    if (background) "$moduleLabel semantic warm index ready" else "$moduleLabel semantic index ready",
                )
            }
        } catch (t: Throwable) {
            semanticIndexProgress.fail(t.message ?: t::class.java.simpleName)
            throw t
        }
        return state.copy(
            index = nextIndex,
            fullyIndexed = focusedPaths.isEmpty(),
        )
    }

    private fun scheduleSemanticIndexBuild(
        state: ModuleSemanticState,
        focusedPaths: Set<Path>,
        background: Boolean,
        showProgress: Boolean = !background,
    ) {
        if (state.snapshot == null) return
        try {
            semanticIndexExecutor.execute {
                runCatching {
                    buildSemanticIndex(
                        state = state,
                        focusedPaths = focusedPaths,
                        background = background,
                        showProgress = showProgress,
                    )
                }.onSuccess { indexedState ->
                    installSemanticState(
                        indexedState,
                        rebuildIndex = true,
                        scheduleDiagnostics = false,
                        allowBackgroundWarmup = false,
                    )
                }.onFailure { error ->
                    if (isBenignCancellation(error)) {
                        return@onFailure
                    }
                    System.err.println("[android-neovim-lsp] semantic index build failed for ${state.module.gradlePath}: ${error.message}")
                    error.printStackTrace(System.err)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
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

    private fun backgroundWarmupModules(project: ImportedProject): List<String> =
        project.modules.asSequence()
            .filter(::moduleHasSemanticSources)
            .map { it.gradlePath }
            .filter { modulePath ->
                val state = semanticStates[modulePath]
                state == null ||
                    state.projectGeneration != currentProjectGeneration ||
                    !state.validated ||
                    !state.fullyIndexed
            }
            .toList()

    private fun lightIndexImportsForText(text: String): List<Path> =
        SourceIndexLookup.imports(text)
            .mapNotNull { import -> currentIndex().symbolsByFqName[import.fqName]?.path?.normalize() }
            .filter { path -> Files.exists(path) }
            .distinct()

    private fun sourceTextForPath(path: Path): String? =
        documents.get(path.toUri().toString())?.text ?: runCatching { Files.readString(path) }.getOrNull()

    private fun requestedUrisRequireFreshSemantic(requestedUris: Collection<String>): Boolean =
        requestedUris.any { uri ->
            val state = moduleStateForUri(uri) ?: return@any true
            !semanticStateCurrentForUri(state, uri)
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
                val module = project.modulesByGradlePath[modulePath] ?: return@forEach
                if (!moduleHasSemanticSources(module)) {
                    return@forEach
                }
                val currentState = semanticStates[modulePath]
                if (
                    currentState?.projectGeneration == generation &&
                    currentState.fullyIndexed &&
                    currentState.snapshot != null
                ) {
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
        val worker = Thread({
            if (warmupStartDelayMillis > 0) {
                try {
                    Thread.sleep(warmupStartDelayMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    synchronized(warmupLock) {
                        warmupRunning = false
                    }
                    return@Thread
                }
            }
            while (true) {
                val nextModulePath = synchronized(warmupLock) {
                    if (generation != currentProjectGeneration) {
                        warmupRunning = false
                        return@Thread
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
                        return@Thread
                    }
                    warmupQueuedModules.remove(modulePath)
                    warmupAllowedOpenModules.remove(modulePath)
                    modulePath
                }
                val currentProject = project.takeIf { generation == currentProjectGeneration } ?: break
                val module = currentProject.modulesByGradlePath[nextModulePath] ?: continue
                val currentState = semanticStates[nextModulePath]
                if (
                    currentState?.validated == true &&
                    currentState.projectGeneration == generation &&
                    currentState.fullyIndexed &&
                    currentState.snapshot != null
                ) {
                    continue
                }
                runCatching {
                    val snapshotState = analyzeModuleSnapshot(
                        currentProject,
                        module,
                        focusedPaths = emptySet(),
                        background = true,
                    )
                    buildSemanticIndex(
                        state = snapshotState,
                        focusedPaths = emptySet(),
                        background = true,
                    )
                }.onSuccess { state ->
                    installSemanticState(state)
                }.onFailure { error ->
                    if (isBenignCancellation(error)) {
                        return@onFailure
                    }
                    System.err.println("[android-neovim-lsp] warmup failed for ${module.gradlePath}: ${error.message}")
                    error.printStackTrace(System.err)
                }
            }
            synchronized(warmupLock) {
                warmupRunning = false
            }
        }, "android-neovim-lsp-warmup")
        worker.isDaemon = true
        try {
            worker.start()
        } catch (_: IllegalThreadStateException) {
            synchronized(warmupLock) {
                warmupRunning = false
            }
        }
    }

    private fun publishOpenDiagnostics(
        requestedUris: Set<String> = emptySet(),
        fullRefresh: Boolean = true,
        expectedGeneration: Int? = null,
        forceUris: Set<String> = emptySet(),
        flushAcknowledgements: Map<String, Int> = emptyMap(),
    ) {
        if (!clientInitialized) return
        val openUris = documents.openDocuments().map { it.uri }.toSet()
        val deferredUris = synchronized(diagnosticLock) { deferredDiagnosticUris.toSet() }
        val targetOpenUris = if (fullRefresh) {
            openUris - deferredUris
        } else {
            openUris.intersect(requestedUris) - deferredUris
        }
        if (!fullRefresh && targetOpenUris.isEmpty()) return
        val merged = linkedMapOf<String, MutableList<dev.codex.kotlinls.protocol.Diagnostic>>()
        val currentProject = project
        currentProject?.let { importedProject ->
            targetOpenUris.forEach { uri ->
                val source = sourceView(uri) ?: return@forEach
                if (!isSourceFile(source.first)) return@forEach
                hydrateFastIndexForImportedPackages(importedProject, source.first, source.second)
            }
        }
        val currentIndex = currentIndex()
        val diagnosticLookup = FastDiagnosticLookup(
            importableFqNames = currentIndex.symbols.asSequence()
                .filter { it.importable && !it.fqName.isNullOrBlank() }
                .mapNotNull { it.fqName }
                .toSet(),
            packagePrefixes = currentIndex.symbols.asSequence()
                .map { it.packageName }
                .filter { it.isNotBlank() }
                .flatMap { packageName ->
                    val segments = packageName.split('.').filter { it.isNotBlank() }
                    segments.indices.asSequence().map { index ->
                        segments.take(index + 1).joinToString(".")
                    }
                }
                .toSet(),
        )
        val semanticUris = linkedSetOf<String>()
        semanticStates.values.forEach { state ->
            if (state.projectGeneration != currentProjectGeneration) return@forEach
            val snapshot = state.snapshot ?: return@forEach
            val stateUris = snapshot.files
                .asSequence()
                .filter { analyzed -> analyzed.module.gradlePath == state.module.gradlePath }
                .map { it.uri }
                .filter(targetOpenUris::contains)
                .filter { uri -> semanticStateCurrentForUri(state, uri) }
                .toSet()
            if (stateUris.isEmpty()) return@forEach
            diagnosticsService.publishable(snapshot, stateUris).forEach { params ->
                merged[params.uri] = params.diagnostics.toMutableList()
                semanticUris += params.uri
            }
        }
        targetOpenUris.forEach { uri ->
            if (uri in semanticUris) return@forEach
            val diagnostics = mutableListOf<dev.codex.kotlinls.protocol.Diagnostic>()
            val source = sourceView(uri)
            if (source != null) {
                diagnostics += diagnosticsService.fastDiagnostics(
                    currentProject,
                    source.first,
                    source.second,
                    diagnosticLookup,
                )
            }
            merged[uri] = diagnostics
        }
        if (expectedGeneration != null && diagnosticRequestGeneration.get() != expectedGeneration) {
            if (forceUris.isNotEmpty() || flushAcknowledgements.isNotEmpty()) {
                scheduleDiagnosticsPublish(
                    delayMillis = 0L,
                    uris = requestedUris.ifEmpty { forceUris },
                    fullRefresh = fullRefresh,
                    forceUris = forceUris,
                    flushAcknowledgements = flushAcknowledgements,
                )
            }
            return
        }
        publishPreparedDiagnostics(
            openUris = openUris,
            targetOpenUris = targetOpenUris,
            merged = merged,
            fullRefresh = fullRefresh,
            forceUris = forceUris,
            flushAcknowledgements = flushAcknowledgements,
        )
    }

    private fun publishSemanticSnapshotDiagnostics(
        snapshot: WorkspaceAnalysisSnapshot,
        fileContentHashes: Map<String, String>,
    ) {
        if (!clientInitialized) return
        val openUris = documents.openDocuments().map { it.uri }.toSet()
        val deferredUris = synchronized(diagnosticLock) { deferredDiagnosticUris.toSet() }
        val targetOpenUris = snapshot.files.asSequence()
            .map { it.uri }
            .filter(openUris::contains)
            .filter { uri -> uri !in deferredUris }
            .filter { uri -> fileContentHashes[uri] == currentSourceContentHash(uri) }
            .toSet()
        if (targetOpenUris.isEmpty()) return

        val merged = linkedMapOf<String, MutableList<dev.codex.kotlinls.protocol.Diagnostic>>()
        diagnosticsService.publishable(snapshot, targetOpenUris).forEach { params ->
            merged[params.uri] = params.diagnostics.toMutableList()
        }
        targetOpenUris.forEach { uri ->
            merged.putIfAbsent(uri, mutableListOf())
        }
        publishPreparedDiagnostics(
            openUris = openUris,
            targetOpenUris = targetOpenUris,
            merged = merged,
            fullRefresh = false,
        )
    }

    private fun publishPreparedDiagnostics(
        openUris: Set<String>,
        targetOpenUris: Set<String>,
        merged: Map<String, List<dev.codex.kotlinls.protocol.Diagnostic>>,
        fullRefresh: Boolean,
        forceUris: Set<String> = emptySet(),
        flushAcknowledgements: Map<String, Int> = emptyMap(),
    ) {
        val notifications = mutableListOf<PublishDiagnosticsParams>()
        synchronized(diagnosticLock) {
            val urisToClear = if (fullRefresh) {
                publishedDiagnosticUris - openUris
            } else {
                emptySet()
            }
            val urisToSend = urisToClear + targetOpenUris
            urisToSend.forEach { uri ->
                val diagnostics = merged[uri].orEmpty().sortedWith(
                    compareBy<dev.codex.kotlinls.protocol.Diagnostic> { it.range.start.line }
                        .thenBy { it.range.start.character },
                )
                val fingerprint = diagnosticFingerprint(diagnostics)
                if (uri !in forceUris && publishedDiagnosticFingerprints[uri] == fingerprint && uri in publishedDiagnosticUris) {
                    return@forEach
                }
                notifications += PublishDiagnosticsParams(
                    uri = uri,
                    diagnostics = diagnostics,
                )
                publishedDiagnosticFingerprints[uri] = fingerprint
            }
            publishedDiagnosticUris = if (fullRefresh) {
                openUris
            } else {
                (publishedDiagnosticUris + targetOpenUris) - urisToClear
            }
            urisToClear.forEach { uri ->
                publishedDiagnosticFingerprints.remove(uri)
            }
        }
        notifications.forEach { params ->
            transport.sendNotification("textDocument/publishDiagnostics", params)
        }
        flushAcknowledgements.forEach { (uri, generation) ->
            notifyDiagnosticsFlushed(uri, generation)
        }
    }

    private fun clearDiagnostics(uri: String) {
        transport.sendNotification(
            "textDocument/publishDiagnostics",
            PublishDiagnosticsParams(uri, emptyList()),
        )
        publishedDiagnosticUris = publishedDiagnosticUris - uri
        publishedDiagnosticFingerprints.remove(uri)
    }

    private fun scheduleFastDiagnosticsPublish(uri: String) {
        if (!clientInitialized || !isSourceUri(uri)) return
        scheduleDiagnosticsPublish(uris = setOf(uri), fullRefresh = false)
    }

    private fun flushDeferredDiagnostics(
        uri: String,
        changedLines: List<ChangedLineRange>,
        generation: Int?,
    ) {
        if (!clientInitialized || !isSourceUri(uri)) return
        val hadDeferred = synchronized(diagnosticLock) {
            deferredDiagnosticUris.remove(uri)
        }
        if (!hadDeferred && changedLines.isEmpty()) return
        scheduleDiagnosticsPublish(
            delayMillis = 0L,
            uris = setOf(uri),
            fullRefresh = false,
            forceUris = setOf(uri),
            flushAcknowledgements = generation?.let { mapOf(uri to it) }.orEmpty(),
        )
        val currentProject = project
        if (currentProject != null) {
            semanticEngine.prefetch(
                project = currentProject,
                activeDocument = documents.get(uri),
                openDocuments = documents.openDocuments(),
                projectGeneration = currentProjectGeneration,
                syncDocuments = false,
                startBridge = true,
            )
        }
        scheduleSemanticRefresh(uri, interactive = false)
    }

    private fun scheduleDiagnosticsPublishForSupportPackages(packageNames: Set<String>) {
        if (packageNames.isEmpty()) {
            scheduleDiagnosticsPublish()
            return
        }
        val affectedUris = synchronized(supportRefreshLock) {
            supportPackagesByUri
                .asSequence()
                .filter { (_, requestedPackages) -> requestedPackages.any(packageNames::contains) }
                .map { (uri, _) -> uri }
                .toSet()
        }
        if (affectedUris.isEmpty()) return
        scheduleDiagnosticsPublish(uris = affectedUris, fullRefresh = false)
    }

    private fun scheduleDiagnosticsPublish(
        delayMillis: Long? = null,
        uris: Set<String> = emptySet(),
        fullRefresh: Boolean = true,
        forceUris: Set<String> = emptySet(),
        flushAcknowledgements: Map<String, Int> = emptyMap(),
    ) {
        if (!clientInitialized) return
        val effectiveDelayMillis = (delayMillis ?: config.diagnostics.fastDebounceMillis).coerceAtLeast(0L)
        val targetAt = System.currentTimeMillis() + effectiveDelayMillis
        synchronized(diagnosticLock) {
            diagnosticRequestGeneration.incrementAndGet()
            if (fullRefresh) {
                pendingDiagnosticFullRefresh = true
                pendingDiagnosticUris.clear()
            } else if (!pendingDiagnosticFullRefresh) {
                pendingDiagnosticUris += uris
            }
            pendingDiagnosticForceUris += forceUris
            pendingDiagnosticFlushAcknowledgements.putAll(flushAcknowledgements)
            pendingDiagnosticPublishAtMillis = targetAt
            if (diagnosticsRefreshRunning) {
                diagnosticLock.notifyAll()
                return
            }
            diagnosticsRefreshRunning = true
        }
        try {
            diagnosticExecutor.execute {
                while (true) {
                    var fullRefreshNow = false
                    var urisToPublishNow = emptySet<String>()
                    var forceUrisNow = emptySet<String>()
                    var flushAcknowledgementsNow = emptyMap<String, Int>()
                    var expectedGeneration = 0
                    synchronized(diagnosticLock) {
                        while (true) {
                            val target = pendingDiagnosticPublishAtMillis
                            if (target <= 0L) {
                                diagnosticsRefreshRunning = false
                                return@execute
                            }
                            val remaining = target - System.currentTimeMillis()
                            if (remaining <= 0L) {
                                fullRefreshNow = pendingDiagnosticFullRefresh
                                urisToPublishNow = pendingDiagnosticUris.toSet()
                                forceUrisNow = pendingDiagnosticForceUris.toSet()
                                flushAcknowledgementsNow = pendingDiagnosticFlushAcknowledgements.toMap()
                                expectedGeneration = diagnosticRequestGeneration.get()
                                pendingDiagnosticFullRefresh = false
                                pendingDiagnosticUris.clear()
                                pendingDiagnosticForceUris.clear()
                                pendingDiagnosticFlushAcknowledgements.clear()
                                pendingDiagnosticPublishAtMillis = 0L
                                break
                            }
                            try {
                                diagnosticLock.wait(remaining)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                diagnosticsRefreshRunning = false
                                pendingDiagnosticPublishAtMillis = 0L
                                pendingDiagnosticFullRefresh = false
                                pendingDiagnosticUris.clear()
                                pendingDiagnosticForceUris.clear()
                                pendingDiagnosticFlushAcknowledgements.clear()
                                return@execute
                            }
                        }
                    }
                    publishOpenDiagnostics(
                        urisToPublishNow,
                        fullRefresh = fullRefreshNow,
                        expectedGeneration = expectedGeneration,
                        forceUris = forceUrisNow,
                        flushAcknowledgements = flushAcknowledgementsNow,
                    )
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(diagnosticLock) {
                diagnosticsRefreshRunning = false
                pendingDiagnosticPublishAtMillis = 0L
                pendingDiagnosticFullRefresh = false
                pendingDiagnosticUris.clear()
                pendingDiagnosticForceUris.clear()
                pendingDiagnosticFlushAcknowledgements.clear()
            }
        }
    }

    private fun notifyDiagnosticsFlushed(uri: String, generation: Int) {
        transport.sendNotification(
            "$/android-neovim/diagnosticsFlushed",
            DiagnosticsFlushedParams(
                textDocument = dev.codex.kotlinls.protocol.TextDocumentIdentifier(uri),
                generation = generation,
            ),
        )
    }

    private fun diagnosticFingerprint(diagnostics: List<dev.codex.kotlinls.protocol.Diagnostic>): String =
        buildString {
            diagnostics.forEach { diagnostic ->
                append(diagnostic.range.start.line).append(':')
                append(diagnostic.range.start.character).append(':')
                append(diagnostic.range.end.line).append(':')
                append(diagnostic.range.end.character).append(':')
                append(diagnostic.severity).append(':')
                append(diagnostic.code).append(':')
                append(diagnostic.source).append(':')
                append(diagnostic.message).append('\n')
            }
        }

    private fun availableSemanticState(uri: String): Pair<WorkspaceAnalysisSnapshot, WorkspaceIndex>? =
        if (!usesLocalSemanticRuntime()) {
            null
        } else {
            moduleStateForUri(uri)
                ?.takeIf { semanticStateCurrentForUri(it, uri) }
                ?.snapshot
                ?.let { snapshot -> snapshot to currentIndex() }
        }

    private fun semanticStateForRequest(uri: String): Pair<WorkspaceAnalysisSnapshot, WorkspaceIndex>? =
        availableSemanticState(uri) ?: awaitFocusedSemanticState(uri)

    private fun awaitFocusedSemanticState(uri: String): Pair<WorkspaceAnalysisSnapshot, WorkspaceIndex>? {
        if (!usesLocalSemanticRuntime() || !isSourceUri(uri)) return null
        val currentProject = project ?: return null
        val module = currentProject.moduleForPath(documentUriToPath(uri)) ?: return null
        val requestGeneration = currentProjectGeneration
        val requestKey = semanticRequestKey(currentProject, module)
        val future = synchronized(semanticRequestLock) {
            inFlightSemanticRequests[requestKey] ?: CompletableFuture.supplyAsync(
                {
                    val latest = moduleStateForUri(uri)
                    if (
                        latest != null &&
                        latest.projectGeneration == requestGeneration &&
                        latest.snapshot != null &&
                        semanticStateCurrentForUri(latest, uri)
                    ) {
                        return@supplyAsync latest
                    }
                    val focusedPaths = focusedSemanticPaths(currentProject, module, listOf(uri))
                    val snapshotState = analyzeModuleSnapshot(
                        currentProject,
                        module,
                        focusedPaths,
                        background = false,
                        onSnapshotReady = ::publishSemanticSnapshotDiagnostics,
                    )
                    installSemanticState(
                        snapshotState,
                        rebuildIndex = false,
                        scheduleDiagnostics = false,
                        allowBackgroundWarmup = false,
                        persistState = false,
                    )
                    scheduleSemanticIndexBuild(snapshotState, focusedPaths, background = false, showProgress = true)
                    snapshotState
                },
                semanticRequestExecutor,
            ).whenComplete { _, _ ->
                synchronized(semanticRequestLock) {
                    inFlightSemanticRequests.remove(requestKey)
                }
            }.also { created ->
                inFlightSemanticRequests[requestKey] = created
            }
        }
        return try {
            future.get(config.semantic.requestTimeoutMillis, TimeUnit.MILLISECONDS)
            availableSemanticState(uri)
        } catch (_: TimeoutException) {
            null
        } catch (_: java.util.concurrent.ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun semanticRequestKey(
        project: ImportedProject,
        module: ImportedModule,
    ): String = buildString {
        append(currentProjectGeneration)
        append('|')
        append(project.root.normalize())
        append('|')
        append(module.gradlePath)
        append('|')
        openDocumentVersionsForModule(project, module)
            .toSortedMap()
            .forEach { (uri, version) ->
                append(uri)
                append('@')
                append(version)
                append(';')
        }
    }

    private fun applyTextEdits(
        text: String,
        edits: List<TextEdit>,
    ): String {
        if (edits.isEmpty()) return text
        val lineIndex = LineIndex.build(text)
        val builder = StringBuilder(text)
        edits.sortedByDescending { lineIndex.offset(it.range.start) }.forEach { edit ->
            val start = lineIndex.offset(edit.range.start)
            val end = lineIndex.offset(edit.range.end)
            builder.replace(start, end, edit.newText)
        }
        return builder.toString()
    }

    private fun currentIndex(): WorkspaceIndex =
        combinedIndex.takeIf { it.symbols.isNotEmpty() || it.references.isNotEmpty() || it.callEdges.isNotEmpty() }
            ?: importCompletionCombinedIndex.takeIf {
                it.symbols.isNotEmpty() || it.references.isNotEmpty() || it.callEdges.isNotEmpty()
            }
            ?: mergeIndices(listOf(lightweightIndex, supportIndex))

    private fun completionIndexForRoute(decision: CompletionRoutingDecision): WorkspaceIndex =
        when (decision.reason) {
            "package-context" -> lightweightIndex
            "import-context" -> importCompletionCombinedIndex
            else -> combinedIndex
        }

    private fun requestMemoKey(
        uri: String,
        position: dev.codex.kotlinls.protocol.Position,
    ): RequestMemoKey = RequestMemoKey(
        uri = uri,
        version = documents.get(uri)?.version ?: -1,
        line = position.line,
        character = position.character,
        indexGeneration = combinedIndexGeneration,
        projectGeneration = currentProjectGeneration,
    )

    private fun memoizedCompletion(key: RequestMemoKey): CompletionList? =
        synchronized(requestMemoLock) { completionMemo[key]?.value }

    private fun rememberCompletion(key: RequestMemoKey, value: CompletionList) {
        rememberMemoizedValue(completionMemo, key, value)
    }

    private fun memoizedHover(key: RequestMemoKey): MemoizedValue<dev.codex.kotlinls.protocol.Hover?>? =
        synchronized(requestMemoLock) { hoverMemo[key] }

    private fun rememberHover(key: RequestMemoKey, value: dev.codex.kotlinls.protocol.Hover?) {
        rememberMemoizedValue(hoverMemo, key, value)
    }

    private fun memoizedDefinition(key: RequestMemoKey): List<dev.codex.kotlinls.protocol.Location>? =
        synchronized(requestMemoLock) { definitionMemo[key]?.value }

    private fun rememberDefinition(
        key: RequestMemoKey,
        value: List<dev.codex.kotlinls.protocol.Location>,
    ) {
        rememberMemoizedValue(definitionMemo, key, value)
    }

    private fun memoizedTypeDefinition(key: RequestMemoKey): List<dev.codex.kotlinls.protocol.Location>? =
        synchronized(requestMemoLock) { typeDefinitionMemo[key]?.value }

    private fun rememberTypeDefinition(
        key: RequestMemoKey,
        value: List<dev.codex.kotlinls.protocol.Location>,
    ) {
        rememberMemoizedValue(typeDefinitionMemo, key, value)
    }

    private fun <T> rememberMemoizedValue(
        target: LinkedHashMap<RequestMemoKey, MemoizedValue<T>>,
        key: RequestMemoKey,
        value: T,
    ) {
        synchronized(requestMemoLock) {
            target[key] = MemoizedValue(value)
            while (target.size > requestMemoMaxEntries) {
                val eldestKey = target.entries.firstOrNull()?.key ?: break
                target.remove(eldestKey)
            }
        }
    }

    private fun usesLocalSemanticRuntime(): Boolean = config.semantic.backend != SemanticBackend.DISABLED

    private fun scheduleSemanticRefresh(uri: String, interactive: Boolean = true) {
        if (!usesLocalSemanticRuntime() || !isSourceUri(uri)) return
        scheduleRefresh(
            reimportProject = project == null,
            rebuildFastIndex = false,
            semanticUri = uri,
            interactiveSemanticRefresh = interactive,
        )
    }

    private fun openDocumentVersionsForModule(
        project: ImportedProject,
        module: ImportedModule,
    ): Map<String, Int> =
        documents.openDocuments()
            .filter { document ->
                project.moduleForPath(documentUriToPath(document.uri))?.gradlePath == module.gradlePath
            }
            .associate { document -> document.uri to document.version }

    private fun semanticStateCurrentForUri(
        state: ModuleSemanticState,
        uri: String,
    ): Boolean {
        if (state.projectGeneration != currentProjectGeneration) return false
        val currentHash = currentSourceContentHash(uri) ?: return true
        state.fileContentHashes[uri]?.let { recordedHash ->
            return recordedHash == currentHash
        }
        val currentVersion = documents.get(uri)?.version
        if (currentVersion != null && state.openDocumentVersions[uri] == currentVersion) {
            return true
        }
        if (!state.validated || !state.fullyIndexed) return false
        val diskHash = diskSourceContentHash(documentUriToPath(uri)) ?: return false
        return diskHash == currentHash
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
                    fileContentHashes = emptyMap(),
                    openDocumentVersions = emptyMap(),
                )
            }.toMap()
        }

    private fun rebuildCombinedIndex() {
        val nextSourceSemantic = synchronized(semanticStateLock) {
            mergeIndices(
                listOf(lightweightIndex) +
                    semanticStates.values.filter(::semanticStateEligibleForIndex).map { it.index },
            )
        }
        importCompletionCombinedIndex = mergeIndices(listOf(lightweightIndex, supportIndex))
        combinedIndex = mergeIndices(listOf(nextSourceSemantic, supportIndex))
        combinedIndexGeneration += 1
    }

    private fun refreshSupportPackages(
        project: ImportedProject,
        uri: String,
        text: String,
        force: Boolean = false,
    ) {
        val path = documentUriToPath(uri)
        val requestedPackages = supportPackagesForText(path, text)
        val shouldRefresh = synchronized(supportRefreshLock) {
            val previousPackages = supportPackagesByUri[uri]
            supportPackagesByUri[uri] = requestedPackages
            force || previousPackages != requestedPackages
        }
        if (!shouldRefresh) return
        primeSupportPackagesFromCache(project, path, text)
        scheduleSupportRefresh(
            project = project,
            projectGeneration = currentProjectGeneration,
            packageNames = requestedPackages,
        )
    }

    private fun primeSupportPackagesFromCache(project: ImportedProject, path: Path, text: String) {
        val requestedPackages = supportPackagesForText(path, text)
        if (requestedPackages.isEmpty() || supportCacheFullyLoaded || supportCacheManifestAvailable == false) {
            return
        }
        val missingPackages = synchronized(supportRefreshLock) {
            requestedPackages - loadedSupportPackages
        }
        if (missingPackages.isEmpty()) return
        val partialLayer = supportSymbolIndexBuilder.loadPackages(project, missingPackages)
        if (partialLayer == null) {
            supportCacheManifestAvailable = false
            return
        }
        supportCacheManifestAvailable = true
        synchronized(supportRefreshLock) {
            loadedSupportPackages.addAll(missingPackages)
        }
        if (partialLayer.symbols.isNotEmpty()) {
            supportIndex = mergeIndices(listOf(supportIndex, partialLayer.toWorkspaceIndex()))
            rebuildCombinedIndex()
        }
        scheduleDiagnosticsPublishForSupportPackages(missingPackages)
    }

    private fun collapsePendingSemanticUri(uri: String) {
        val currentProject = project
        if (currentProject != null) {
            val modulePath = currentProject.moduleForPath(documentUriToPath(uri))?.gradlePath
            if (modulePath != null) {
                pendingSemanticUris.removeIf { pendingUri ->
                    currentProject.moduleForPath(documentUriToPath(pendingUri))?.gradlePath == modulePath
                }
            }
        }
        pendingSemanticUris += uri
    }

    private fun semanticStateEligibleForIndex(state: ModuleSemanticState): Boolean {
        if (state.projectGeneration != currentProjectGeneration) return false
        val currentProject = project ?: return state.fullyIndexed
        val openDocumentsForModule = documents.openDocuments()
            .filter { document ->
                currentProject.moduleForPath(documentUriToPath(document.uri))?.gradlePath == state.module.gradlePath
            }
        if (openDocumentsForModule.isEmpty()) {
            return state.fullyIndexed
        }
        return openDocumentsForModule.all { document -> semanticStateCurrentForUri(state, document.uri) }
    }

    private fun moduleHasSemanticSources(module: ImportedModule): Boolean =
        module.sourceRoots.isNotEmpty() || module.javaSourceRoots.isNotEmpty()

    private fun nextSemanticRequestId(modulePath: String): Int =
        synchronized(semanticStateLock) {
            semanticRequestGeneration.incrementAndGet().also { requestId ->
                latestSemanticRequestIds[modulePath] = requestId
            }
        }

    private fun installSemanticState(
        nextState: ModuleSemanticState,
        rebuildIndex: Boolean = true,
        scheduleDiagnostics: Boolean = true,
        allowBackgroundWarmup: Boolean = true,
        persistState: Boolean = true,
    ) {
        var droppedAsStale = false
        val previousState = synchronized(semanticStateLock) {
            if (nextState.projectGeneration != currentProjectGeneration) {
                null
            } else {
                val latestRequestId = latestSemanticRequestIds[nextState.module.gradlePath] ?: Int.MIN_VALUE
                if (nextState.requestId < latestRequestId) {
                    droppedAsStale = true
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
                if (droppedAsStale && persistState) {
                    persistSemanticState(nextState)
                }
                nextState.snapshot?.close()
                return
            }
        } else if (previousState !== nextState) {
            if (previousState.snapshot !== nextState.snapshot) {
                previousState.snapshot?.close()
            }
        }
        if (rebuildIndex) {
            rebuildCombinedIndex()
        }
        if (persistState) {
            persistSemanticState(nextState)
        }
        if (scheduleDiagnostics) {
            scheduleDiagnosticsPublish()
        }
        if (allowBackgroundWarmup && !nextState.fullyIndexed) {
            project?.let { currentProject ->
                if (nextState.projectGeneration == currentProjectGeneration) {
                    scheduleBackgroundWarmup(currentProject, listOf(nextState.module.gradlePath), includeOpenModules = true)
                }
            }
        }
    }

    private fun loadPersistedSemanticStates(project: ImportedProject): Map<String, ModuleSemanticState> =
        project.modules.mapNotNull { module ->
            val fingerprint = moduleSemanticFingerprints[module.gradlePath] ?: return@mapNotNull null
            val cached = semanticIndexCache.loadEntry(project.root, module.gradlePath, fingerprint) ?: return@mapNotNull null
            module.gradlePath to ModuleSemanticState(
                module = module,
                snapshot = null,
                index = cached.index,
                projectGeneration = currentProjectGeneration,
                requestId = 0,
                fullyIndexed = true,
                validated = true,
                fileContentHashes = cached.fileContentHashes,
                openDocumentVersions = emptyMap(),
            )
        }.toMap()

    private fun loadOptimisticSemanticStates(project: ImportedProject): Map<String, ModuleSemanticState> {
        val cachedByModule = semanticIndexCache.loadAllEntries(project.root)
        return project.modules.mapNotNull { module ->
            val cached = cachedByModule[module.gradlePath] ?: return@mapNotNull null
            module.gradlePath to ModuleSemanticState(
                module = module,
                snapshot = null,
                index = cached.index,
                projectGeneration = currentProjectGeneration,
                requestId = 0,
                fullyIndexed = true,
                validated = false,
                fileContentHashes = cached.fileContentHashes,
                openDocumentVersions = emptyMap(),
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
            fileContentHashes = state.fileContentHashes,
        )
    }

    private fun semanticFileContentHashes(snapshot: WorkspaceAnalysisSnapshot): Map<String, String> =
        snapshot.files.associate { file -> file.uri to sha256(file.text) }

    private fun currentSourceContentHash(uri: String): String? =
        documents.get(uri)?.text?.let(::sha256) ?: diskSourceContentHash(documentUriToPath(uri))

    private fun diskSourceContentHash(path: Path): String? =
        runCatching { Files.readString(path.normalize()) }.getOrNull()?.let(::sha256)

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
        if (normalizedPath.extension.lowercase() in setOf("jar", "zip", "aar")) {
            builder.append(StableArtifactFingerprint.fingerprint(normalizedPath))
        } else {
            builder.append(runCatching { Files.getLastModifiedTime(normalizedPath).toMillis() }.getOrDefault(0L))
                .append('|')
                .append(runCatching { Files.size(normalizedPath) }.getOrDefault(0L))
        }
            .append('\n')
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun isBenignCancellation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (
                current is InterruptedException ||
                current is java.io.InterruptedIOException ||
                current.javaClass.name == "org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException"
            ) {
                return true
            }
            current = when (current) {
                is java.lang.reflect.InvocationTargetException -> current.targetException ?: current.cause
                else -> current.cause
            }
        }
        return false
    }

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

    private fun dev.codex.kotlinls.index.SupportSymbolLayer?.toWorkspaceIndex(): WorkspaceIndex =
        if (this == null) {
            WorkspaceIndex(emptyList(), emptyList(), emptyList())
        } else {
            WorkspaceIndex(symbols = symbols, references = emptyList(), callEdges = emptyList())
        }

    private fun supportPackagesForDocuments(documents: Collection<TextDocumentSnapshot>): Set<String> =
        documents.asSequence()
            .flatMap { document -> supportPackagesForText(documentUriToPath(document.uri), document.text).asSequence() }
            .toSet()

    private fun supportPackagesForText(path: Path, text: String): Set<String> =
        SourceIndexLookup.supportPackages(path, text)

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
            detail = item.detail?.takeUnless { it.isBlank() || it == "/**" } ?: indexedSymbolCompletionDetail(symbol),
            documentation = item.documentation ?: indexedSymbolDocumentation(symbol),
        )
    }

    private fun sourceView(uri: String): Pair<Path, String>? {
        val path = documentUriToPath(uri)
        val text = documents.get(uri)?.text ?: runCatching { Files.readString(path) }.getOrNull() ?: return null
        return path.normalize() to text
    }

    private fun shouldPreferIndexForLookup(
        requestPath: Path,
        text: String,
        position: dev.codex.kotlinls.protocol.Position,
        symbol: IndexedSymbol,
    ): Boolean {
        if (isMemberAccessAt(text, position)) return true
        val symbolPath = symbol.path.normalize()
        if (symbolPath == requestPath.normalize()) return false
        val projectRoot = project?.root?.normalize()
        return projectRoot == null || !symbolPath.startsWith(projectRoot)
    }

    private fun isMemberAccessAt(
        text: String,
        position: dev.codex.kotlinls.protocol.Position,
    ): Boolean {
        val offset = dev.codex.kotlinls.workspace.LineIndex.build(text).offset(position).coerceIn(0, text.length)
        var cursor = offset
        while (cursor > 0 && text[cursor - 1].isJavaIdentifierPart()) {
            cursor--
        }
        return cursor > 0 && text[cursor - 1] == '.'
    }

    private fun beginProgress(
        title: String,
        subtitle: String,
        showImmediately: Boolean = true,
        minTotalToShow: Int = 1,
    ): ProgressHandle {
        val token = "progress-${progressGeneration.incrementAndGet()}"
        val shouldShowImmediately = when (config.progress.mode) {
            ProgressMode.OFF -> false
            ProgressMode.MINIMAL -> true
            ProgressMode.VERBOSE -> true
        }
        if (shouldShowImmediately) {
            sendProgress(
                token = token,
                kind = "begin",
                title = title,
                message = subtitle,
                current = 0,
                total = 0,
            )
        }
        return ProgressHandle(
            token = token,
            title = title,
            shown = shouldShowImmediately,
            initialSubtitle = subtitle,
            minTotalToShow = minTotalToShow,
        )
    }

    private fun sendProgress(
        token: String,
        kind: String,
        title: String,
        message: String,
        current: Int?,
        total: Int?,
    ) {
        if (config.progress.mode == ProgressMode.OFF) return
        val percentage = if (current != null && total != null && total > 0) {
            ((current.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else {
            null
        }
        transport.sendNotification(
            "\$/android-neovim/progress",
            mapOf(
                "token" to token,
                "value" to mapOf(
                    "kind" to kind,
                    "title" to title,
                    "message" to message,
                    "percentage" to percentage,
                ),
            ),
        )
    }

    private fun recordCompletionRoute(
        decision: CompletionRoutingDecision,
        itemCount: Int,
        latencyMs: Long,
    ) {
        val key = "${decision.route.name.lowercase()}:${decision.reason}"
        val snapshot = synchronized(completionRouteStatsLock) {
            val metric = completionRouteStats.getOrPut(key) { CompletionRouteMetric() }
            metric.count += 1
            metric.totalItems += itemCount.toLong()
            metric.totalLatencyMs += latencyMs
            metric.copy()
        }
        if (snapshot.count % 25 == 0) {
            val averageItems = snapshot.totalItems.toDouble() / snapshot.count.toDouble()
            val averageLatencyMs = snapshot.totalLatencyMs.toDouble() / snapshot.count.toDouble()
            System.err.println(
                "[android-neovim-lsp] completion route=$key count=${snapshot.count} " +
                    "avgItems=${"%.1f".format(averageItems)} avgLatencyMs=${"%.1f".format(averageLatencyMs)} " +
                    "receiver=${decision.receiverType ?: "-"} chainDepth=${decision.chainDepth ?: 0}",
            )
        }
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
            sendProgress(token, "report", title, subtitle, current, total)
        }

        fun complete(subtitle: String) {
            if (!shown) return
            sendProgress(token, "end", title, subtitle, 1, 1)
        }

        fun fail(subtitle: String) {
            maybeShow(subtitle, 1, 1)
            sendProgress(token, "end", title, subtitle, 1, 1)
        }

        private fun maybeShow(subtitle: String, current: Int?, total: Int?) {
            if (shown) return
            if (total == null || total < minTotalToShow) return
            shown = true
            sendProgress(token, "begin", title, initialSubtitle, 0, total)
        }
    }

    private data class RefreshRequest(
        val reimportProject: Boolean,
        val rebuildFastIndex: Boolean,
        val semanticUris: Set<String>,
        val interactiveSemanticRefresh: Boolean,
    )

    private data class SupportRefreshRequest(
        val project: ImportedProject,
        val projectGeneration: Int,
        val packageNames: Set<String>,
        val ensureCache: Boolean,
    ) {
        fun merge(
            nextProject: ImportedProject,
            nextProjectGeneration: Int,
            nextPackageNames: Set<String>,
            nextEnsureCache: Boolean,
        ): SupportRefreshRequest {
            if (
                project.root.normalize() != nextProject.root.normalize() ||
                projectGeneration != nextProjectGeneration
            ) {
                return SupportRefreshRequest(nextProject, nextProjectGeneration, nextPackageNames, nextEnsureCache)
            }
            return SupportRefreshRequest(
                project = nextProject,
                projectGeneration = nextProjectGeneration,
                packageNames = packageNames + nextPackageNames,
                ensureCache = ensureCache || nextEnsureCache,
            )
        }
    }

    private data class RequestMemoKey(
        val uri: String,
        val version: Int,
        val line: Int,
        val character: Int,
        val indexGeneration: Int,
        val projectGeneration: Int,
    )

    private data class CompletionRouteMetric(
        var count: Int = 0,
        var totalItems: Long = 0,
        var totalLatencyMs: Long = 0,
    )

    private data class MemoizedValue<T>(
        val value: T,
    )

    private data class FlushDiagnosticsParams(
        val textDocument: dev.codex.kotlinls.protocol.TextDocumentIdentifier,
        @param:JsonProperty("changed_lines")
        val changedLines: List<ChangedLineRange> = emptyList(),
        val generation: Int? = null,
    )

    private data class ChangedLineRange(
        @param:JsonProperty("start_line")
        val startLine: Int,
        @param:JsonProperty("end_line")
        val endLine: Int,
    )

    private data class DiagnosticsFlushedParams(
        val textDocument: dev.codex.kotlinls.protocol.TextDocumentIdentifier,
        val generation: Int,
    )

    private data class ModuleSemanticState(
        val module: ImportedModule,
        val snapshot: WorkspaceAnalysisSnapshot?,
        val index: WorkspaceIndex,
        val projectGeneration: Int,
        val requestId: Int,
        val fullyIndexed: Boolean,
        val validated: Boolean,
        val fileContentHashes: Map<String, String>,
        val openDocumentVersions: Map<String, Int>,
    )
}
