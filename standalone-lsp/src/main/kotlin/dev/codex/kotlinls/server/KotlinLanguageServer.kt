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
import dev.codex.kotlinls.index.DocumentCacheStatus
import dev.codex.kotlinls.index.LightweightWorkspaceIndexBuilder
import dev.codex.kotlinls.index.PersistentSemanticIndexCache
import dev.codex.kotlinls.index.SemanticIndexCacheEntry
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
import dev.codex.kotlinls.protocol.DidChangeWatchedFilesParams
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
    private var relevantFileRootResolved = false

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
    private val fastLookupHydrationLock = Any()
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
    private val pendingSemanticFlushGenerations = linkedMapOf<String, Int>()
    private var warmupRunning = false
    private var projectWarmupStartedGeneration = 0
    private var supportRefreshRunning = false
    private var diagnosticsRefreshRunning = false
    private var pendingSupportRefresh: SupportRefreshRequest? = null
    private var pendingDiagnosticPublishAtMillis = 0L
    private var pendingDiagnosticFullRefresh = false
    private val pendingDiagnosticUris = linkedSetOf<String>()
    private val deferredDiagnosticUris = linkedSetOf<String>()
    private val pendingDiagnosticForceUris = linkedSetOf<String>()
    private val pendingDiagnosticFlushAcknowledgements = linkedMapOf<String, Int>()
    private val pendingPostDiagnosticsWork = linkedMapOf<String, PendingPostDiagnosticsWork>()
    private val latestFlushGenerationByUri = linkedMapOf<String, Int>()
    private var warmupGeneration = 0
    private val warmupQueuedModules = linkedSetOf<String>()
    private val warmupAllowedOpenModules = linkedSetOf<String>()
    private val warmupIndexRebuildModules = linkedSetOf<String>()
    private val hydratedFastLookupPackages = linkedSetOf<String>()
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
                            if (documents.openDocuments().isNotEmpty()) {
                                semanticEngine.prefetch(
                                    project = currentProject,
                                    activeDocument = documents.openDocuments().firstOrNull(),
                                    openDocuments = documents.openDocuments(),
                                    projectGeneration = currentProjectGeneration,
                                )
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
                        ensureProjectWarmupStarted(currentProject)
                    }
                    announceDiagnosticsKickoff(params.textDocument.uri, "open")
                    scheduleSemanticRefresh(params.textDocument.uri)
                    schedulePersistentDocumentCacheSync(
                        project = project,
                        uri = params.textDocument.uri,
                        reason = "open",
                    )
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
                        pendingPostDiagnosticsWork.remove(uri)
                        latestFlushGenerationByUri.remove(uri)
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
                        schedulePersistentDocumentCacheSync(
                            project = project,
                            uri = uri,
                            reason = "save",
                            notify = false,
                        )
                    }
                }
                "$/android-neovim/flushDiagnostics" -> {
                    val params = read(message.params, FlushDiagnosticsParams::class.java)
                    flushDeferredDiagnostics(params.textDocument.uri, params.changedLines, params.generation)
                }
                "workspace/didChangeConfiguration" -> scheduleRefresh(reimportProject = true, rebuildFastIndex = true)
                "workspace/didChangeWatchedFiles" -> handleWatchedFilesChanged(
                    read(message.params, DidChangeWatchedFilesParams::class.java),
                )
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
                        val indexForCompletion = completionIndexForRoute(routeDecision, activeIndex)
                        val startedAt = System.nanoTime()
                        val response = when (routeDecision.route) {
                            CompletionRoute.INDEX -> completionService.completeFromIndex(
                                index = indexForCompletion,
                                path = source.first,
                                text = source.second,
                                params = params,
                            )

                            CompletionRoute.BRIDGE -> (currentProject?.let { current ->
                                availableSemanticState(params.textDocument.uri)
                                    ?.let { semantic ->
                                        completionService.semanticNamedArgumentCompletions(semantic.first, params)
                                    }
                                    ?: completionService.namedArgumentCompletionsFromIndex(
                                        index = indexForCompletion,
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
                            } ?: CompletionList(false, emptyList())).takeUnless { it.items.isEmpty() }
                                ?: completionService.completeFromIndex(
                                    index = indexForCompletion,
                                    path = source.first,
                                    text = source.second,
                                    params = params,
                                )
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
                    val lookupIndex = currentIndex()
                    val indexedSymbol = SourceIndexLookup.resolveSymbol(lookupIndex, source.first, source.second, params.position)
                    val indexedHover = indexedSymbol?.let {
                        hoverService.hoverFromIndex(lookupIndex, source.first, source.second, params)
                    }
                    if (
                        indexedSymbol != null &&
                        indexedHover != null &&
                        shouldPreferIndexForLookup(source.first, source.second, params.position, indexedSymbol)
                    ) {
                        rememberHover(cacheKey, indexedHover)
                        return@respond indexedHover
                    }
                    availableSemanticState(params.textDocument.uri)?.let { current ->
                        hoverService.hover(current.first, current.second, params)
                            ?.let { hover ->
                                rememberHover(cacheKey, hover)
                                return@respond hover
                            }
                    }
                    if (indexedHover != null) {
                        rememberHover(cacheKey, indexedHover)
                        return@respond indexedHover
                    }
                    val response = project?.let { currentProject ->
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
                    val lookupIndex = currentIndex()
                    val indexedSymbol = SourceIndexLookup.resolveSymbol(lookupIndex, source.first, source.second, params.position)
                    val indexedDefinition = indexedSymbol
                        ?.let { navigationService.definitionFromIndex(lookupIndex, source.first, source.second, params) }
                        .orEmpty()
                    if (
                        indexedSymbol != null &&
                        indexedDefinition.isNotEmpty() &&
                        shouldPreferIndexForLookup(source.first, source.second, params.position, indexedSymbol)
                    ) {
                        rememberDefinition(cacheKey, indexedDefinition)
                        return@respond indexedDefinition
                    }
                    availableSemanticState(params.textDocument.uri)?.let { current ->
                        val semantic = navigationService.definition(current.first, current.second, params)
                        if (semantic.isNotEmpty()) {
                            rememberDefinition(cacheKey, semantic)
                            return@respond semantic
                        }
                    }
                    if (indexedDefinition.isNotEmpty()) {
                        rememberDefinition(cacheKey, indexedDefinition)
                        return@respond indexedDefinition
                    }
                    val response = project?.let { currentProject ->
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
        }?.let(rootDetector::detect)?.normalize()
        relevantFileRootResolved = false
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
                inlayHintProvider = false,
                callHierarchyProvider = localSemanticCapabilities || semanticCapabilities.callHierarchy,
                typeHierarchyProvider = localSemanticCapabilities || semanticCapabilities.typeHierarchy,
                executeCommandProvider = ExecuteCommandOptions(
                    commands = listOf(
                        "kotlinls.reimport",
                        "kotlinls.indexStatus",
                        "kotlinls.indexedFiles",
                        "kotlinls.cacheStatus",
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

            "kotlinls.indexStatus" -> indexStatusReport(includeFiles = true)
            "kotlinls.indexedFiles" -> indexStatusReport(includeFiles = true)
            "kotlinls.cacheStatus" -> indexStatusReport(includeFiles = false)

            else -> null
        }
    }

    private fun indexStatusReport(includeFiles: Boolean): Map<String, Any?> {
        val currentProject = project
            ?: return mapOf(
                "projectLoaded" to false,
                "message" to "Project model has not loaded yet",
                "workspaceIndexReady" to workspaceIndexReady,
            )
        val sourceFiles = projectSourceFiles(currentProject)
        val cachedFiles = lightweightIndexBuilder.cachedFileStatuses(currentProject)
        val indexedPaths = (cachedFiles.keys + lightweightIndex.symbolsByPath.keys)
            .map(Path::normalize)
            .toSet()
        val totalFiles = sourceFiles.size
        val indexedFileCount = sourceFiles.count { entry -> entry.path in indexedPaths }
        val percentage = if (totalFiles == 0) 100.0 else indexedFileCount.toDouble() / totalFiles.toDouble() * 100.0
        val openDocumentUris = documents.openDocuments().map { it.uri }.toSet()
        val files = if (includeFiles) {
            sourceFiles.map { entry ->
                val cached = cachedFiles[entry.path]
                val symbols = cached?.symbolCount ?: lightweightIndex.symbolsByPath[entry.path].orEmpty().size
                mapOf(
                    "path" to entry.path.toString(),
                    "relativePath" to relativePath(currentProject.root, entry.path),
                    "uri" to entry.path.toUri().toString(),
                    "module" to entry.moduleGradlePath,
                    "moduleName" to entry.moduleName,
                    "rootKind" to entry.rootKind,
                    "indexed" to (entry.path in indexedPaths),
                    "symbols" to symbols,
                    "open" to (entry.path.toUri().toString() in openDocumentUris),
                    "cache" to when {
                        cached?.openDocumentVersion != null -> "open-document"
                        cached != null -> "persisted"
                        else -> "missing"
                    },
                )
            }
        } else {
            emptyList<Map<String, Any?>>()
        }
        val semanticModules = currentProject.modules
            .filter(::moduleHasSemanticSources)
            .map { module ->
                val state = semanticStates[module.gradlePath]
                val current = state?.projectGeneration == currentProjectGeneration
                mapOf(
                    "module" to module.gradlePath,
                    "moduleName" to module.name,
                    "loaded" to (state != null),
                    "current" to current,
                    "validated" to (state?.validated == true),
                    "validating" to (state?.validationPending == true),
                    "fullyIndexed" to (state?.fullyIndexed == true),
                    "compilerReady" to (state?.snapshot != null),
                    "symbols" to (state?.index?.symbols?.size ?: 0),
                    "files" to (state?.fileContentHashes?.size ?: 0),
                )
            }
        val supportPackagesSnapshot = synchronized(supportRefreshLock) { loadedSupportPackages.toSet() }
        return mapOf(
            "projectLoaded" to true,
            "root" to currentProject.root.toString(),
            "projectGeneration" to currentProjectGeneration,
            "workspaceIndexReady" to workspaceIndexReady,
            "openDocuments" to documents.openDocuments().size,
            "fastIndex" to mapOf(
                "filesIndexed" to indexedFileCount,
                "filesTotal" to totalFiles,
                "percentage" to percentage,
                "symbols" to lightweightIndex.symbols.size,
                "cachedFiles" to cachedFiles.size,
                "ready" to workspaceIndexReady,
            ),
            "supportCache" to mapOf(
                "manifestAvailable" to supportCacheManifestAvailable,
                "fullyLoaded" to supportCacheFullyLoaded,
                "symbols" to supportIndex.symbols.size,
                "packagesLoaded" to supportPackagesSnapshot.size,
                "packages" to supportPackagesSnapshot.sorted(),
            ),
            "semanticCache" to mapOf(
                "enabled" to usesLocalSemanticRuntime(),
                "modulesTotal" to semanticModules.size,
                "modulesLoaded" to semanticModules.count { it["loaded"] == true },
                "modulesCurrent" to semanticModules.count { it["current"] == true },
                "modulesFullyIndexed" to semanticModules.count { it["fullyIndexed"] == true },
                "modules" to semanticModules,
            ),
            "files" to files,
        )
    }

    private fun projectSourceFiles(project: ImportedProject): List<IndexedSourceFileEntry> =
        project.modules
            .flatMap { module ->
                val kotlinFiles = module.sourceRoots.flatMap { root ->
                    sourceFilesUnder(root).map { path ->
                        IndexedSourceFileEntry(
                            path = path,
                            moduleName = module.name,
                            moduleGradlePath = module.gradlePath,
                            rootKind = "kotlin",
                        )
                    }
                }
                val javaFiles = module.javaSourceRoots.flatMap { root ->
                    sourceFilesUnder(root).map { path ->
                        IndexedSourceFileEntry(
                            path = path,
                            moduleName = module.name,
                            moduleGradlePath = module.gradlePath,
                            rootKind = "java",
                        )
                    }
                }
                kotlinFiles + javaFiles
            }
            .distinctBy { it.path }
            .sortedBy { it.path.toString() }

    private fun sourceFilesUnder(root: Path): List<Path> =
        runCatching {
            if (!Files.exists(root)) {
                emptyList()
            } else {
                root.walk()
                    .filter { path -> path.isRegularFile() && isSourceFile(path) }
                    .map(Path::normalize)
                    .sortedBy(Path::toString)
                    .toList()
            }
        }.getOrDefault(emptyList())

    private fun relativePath(root: Path, path: Path): String =
        runCatching { root.normalize().relativize(path.normalize()).toString() }
            .getOrDefault(path.toString())

    private fun refreshWorkspaceForUri(
        uri: String,
        reimportProject: Boolean,
        rebuildFastIndex: Boolean,
        scheduleSemantic: Boolean,
    ) {
        if (root == null || !relevantFileRootResolved) {
            maybeCorrectRootFromUri(uri)
        }
        scheduleRefresh(
            reimportProject = reimportProject,
            rebuildFastIndex = rebuildFastIndex,
            semanticUri = uri.takeIf { scheduleSemantic && isSourceUri(it) },
        )
    }

    private fun handleWatchedFilesChanged(params: DidChangeWatchedFilesParams) {
        val changedUris = params.changes.map { it.uri }
        val changedPaths = changedUris.map(::documentUriToPath)
        if (changedPaths.any(::isProjectModelPath)) {
            scheduleRefresh(reimportProject = true, rebuildFastIndex = true)
            return
        }
        val sourceUris = changedUris.filter { uri -> isSourceFile(documentUriToPath(uri)) }
        if (sourceUris.isEmpty()) return
        if (project == null) {
            scheduleRefresh(reimportProject = true, rebuildFastIndex = true)
            return
        }
        sourceUris.forEach { uri ->
            updateLiveSourceIndex(uri, publishDiagnostics = false)
            scheduleSemanticRefresh(uri, interactive = false)
        }
        scheduleRefresh(reimportProject = false, rebuildFastIndex = true)
        scheduleDiagnosticsPublish()
    }

    private fun ensureProjectReady(uri: String) {
        val rootChanged = maybeCorrectRootFromUri(uri)
        if (project == null || rootChanged) {
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
            scheduleSemanticCacheValidation(cachedProject, currentProjectGeneration)
        }
        semanticEngine.onProjectChanged(cachedProject)
        rebuildCombinedIndex()
    }

    private fun maybeCorrectRootFromUri(uri: String): Boolean {
        if (!isSourceUri(uri)) return false
        val detectedRoot = rootDetector.detect(documentUriToPath(uri)).normalize()
        val currentRoot = root?.normalize()
        if (currentRoot == null) {
            root = detectedRoot
            relevantFileRootResolved = true
            return false
        }
        if (relevantFileRootResolved) {
            return false
        }
        relevantFileRootResolved = true
        if (currentRoot == detectedRoot) {
            return false
        }
        root = detectedRoot
        resetStateForRootChange()
        showInfoMessage("Project Root: switched to ${detectedRoot.fileName ?: detectedRoot}")
        return true
    }

    private fun resetStateForRootChange() {
        currentProjectGeneration = refreshGeneration.incrementAndGet()
        project = null
        workspaceIndexReady = false
        lightweightIndex = WorkspaceIndex(emptyList(), emptyList(), emptyList())
        supportIndex = WorkspaceIndex(emptyList(), emptyList(), emptyList())
        combinedIndex = WorkspaceIndex(emptyList(), emptyList(), emptyList())
        importCompletionCombinedIndex = WorkspaceIndex(emptyList(), emptyList(), emptyList())
        moduleSemanticFingerprints = emptyMap()
        supportCacheFullyLoaded = false
        supportCacheManifestAvailable = null
        publishedDiagnosticUris = emptySet()
        publishedDiagnosticFingerprints.clear()
        synchronized(supportRefreshLock) {
            loadedSupportPackages.clear()
            supportPackagesByUri.clear()
            pendingSupportRefresh = null
        }
        synchronized(refreshLock) {
            pendingSemanticUris.clear()
            pendingSemanticFlushGenerations.clear()
        }
        synchronized(diagnosticLock) {
            pendingPostDiagnosticsWork.clear()
        }
        replaceSemanticStates(emptyMap())
        clearRequestMemoCaches()
        documents.openDocuments().forEach { document ->
            clearDiagnostics(document.uri)
        }
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
        val moduleName = project.moduleForPath(path.normalize())?.name.orEmpty()
        val projectKey = project.root.normalize().toString()
        val packagesToHydrate = packageNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .filter { packageName ->
                val key = "$projectKey|$moduleName|$packageName"
                synchronized(fastLookupHydrationLock) {
                    hydratedFastLookupPackages.add(key)
                }
            }
            .toList()
        if (packagesToHydrate.isEmpty()) return
        var nextIndex = lightweightIndex
        packagesToHydrate.forEach { packageName ->
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

    private fun clearFastLookupHydration() {
        synchronized(fastLookupHydrationLock) {
            hydratedFastLookupPackages.clear()
        }
    }

    private fun scheduleRefresh(
        reimportProject: Boolean,
        rebuildFastIndex: Boolean = reimportProject || lightweightIndex.symbols.isEmpty(),
        semanticUri: String? = null,
        interactiveSemanticRefresh: Boolean = true,
        semanticFlushGeneration: Int? = null,
    ) {
        synchronized(refreshLock) {
            refreshRequested = true
            refreshReimportRequested = refreshReimportRequested || reimportProject
            refreshFastIndexRequested = refreshFastIndexRequested || rebuildFastIndex || reimportProject
            semanticUri?.let {
                collapsePendingSemanticUri(it)
                semanticFlushGeneration?.let { generation ->
                    pendingSemanticFlushGenerations[it] = generation
                }
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
                        val semanticFlushGenerations = pendingSemanticFlushGenerations.toMap()
                        pendingSemanticUris.clear()
                        pendingSemanticFlushGenerations.clear()
                        val interactiveSemanticFlag = refreshInteractiveSemanticRequested
                        refreshInteractiveSemanticRequested = false
                        RefreshRequest(
                            reimportProject = flag,
                            rebuildFastIndex = fastIndexFlag || flag || lightweightIndex.symbols.isEmpty(),
                            semanticUris = semanticUris,
                            interactiveSemanticRefresh = interactiveSemanticFlag || flag || fastIndexFlag,
                            semanticFlushGenerations = semanticFlushGenerations,
                        )
                    }
                    runCatching {
                        refreshWorkspaceNow(
                            reimportProject = doReimport.reimportProject,
                            rebuildFastIndex = doReimport.rebuildFastIndex,
                            requestedSemanticUris = doReimport.semanticUris,
                            interactiveSemanticRefresh = doReimport.interactiveSemanticRefresh,
                            requestedSemanticFlushGenerations = doReimport.semanticFlushGenerations,
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
            title = "Dependency cache",
            subtitle = "building library symbols",
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
            supportProgress.report("cached ${layer.symbols.size} library symbols", 1, 1)
            supportProgress.complete("dependency cache ready")
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
        requestedSemanticFlushGenerations: Map<String, Int> = emptyMap(),
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
            clearFastLookupHydration()
            if (usesLocalSemanticRuntime()) {
                moduleSemanticFingerprints = computeSemanticFingerprints(nextProject)
                val carriedStates = carryForwardSemanticStates(nextProject)
                val validatedStates = loadPersistedSemanticStates(nextProject)
                replaceSemanticStates(carriedStates + validatedStates)
                scheduleSemanticCacheValidation(nextProject, currentProjectGeneration)
            } else {
                moduleSemanticFingerprints = emptyMap()
                replaceSemanticStates(emptyMap())
            }
        }

        if (rebuildFastIndex || lightweightIndex.symbols.isEmpty()) {
            workspaceIndexReady = false
            clearFastLookupHydration()
            val fastIndexProgress = beginProgress(
                title = "Project source index",
                subtitle = "scanning workspace files",
                showImmediately = false,
                minTotalToShow = 2,
            )
            try {
                lightweightIndex = lightweightIndexBuilder.build(nextProject, documents) { subtitle, current, total ->
                    fastIndexProgress.report(subtitle, current, total)
                }.also {
                    fastIndexProgress.complete("project source index ready")
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
                .filterTo(linkedSetOf()) { uri ->
                    val generation = requestedSemanticFlushGenerations[uri]
                    generation == null || isCurrentFlushGeneration(uri, generation)
                }
            if (semanticUris.isNotEmpty()) {
                ensureProjectWarmupStarted(nextProject)
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
            val reusableIndexState = currentState?.takeIf { state ->
                state.projectGeneration == currentProjectGeneration &&
                    state.validated &&
                    state.fullyIndexed
            }
            val moduleRequestedUris = requestedUrisByModule[module.gradlePath].orEmpty()
            val requestedDocumentsChanged = requestedUrisRequireFreshSemantic(moduleRequestedUris)
            if (
                currentState?.validated == true &&
                currentState.projectGeneration == currentProjectGeneration &&
                currentState.fullyIndexed &&
                !requestedDocumentsChanged
            ) {
                if (currentState.snapshot == null) {
                    scheduleBackgroundWarmup(
                        project,
                        listOf(module.gradlePath),
                        includeOpenModules = true,
                        rebuildIndex = false,
                    )
                }
                return@forEach
            }
            val focusedPaths = focusedSemanticPaths(project, module, moduleRequestedUris)
            val snapshotState = analyzeModuleSnapshot(
                project = project,
                module = module,
                focusedPaths = focusedPaths,
                background = false,
                showProgress = interactiveSemanticRefresh,
                onSnapshotReady = ::publishSemanticSnapshotDiagnostics,
            )
            val stateToInstall = snapshotState.withReusableSemanticIndex(reusableIndexState)
            installSemanticState(
                stateToInstall,
                rebuildIndex = false,
                scheduleDiagnostics = false,
                allowBackgroundWarmup = reusableIndexState == null && interactiveSemanticRefresh,
                persistState = false,
            )
            if (interactiveSemanticRefresh && reusableIndexState == null) {
                scheduleSemanticIndexBuild(snapshotState, focusedPaths, background = false, showProgress = true)
            }
        }
    }

    private fun analyzeModuleSnapshot(
        project: ImportedProject,
        module: ImportedModule,
        focusedPaths: Set<Path>,
        background: Boolean,
        showProgress: Boolean = true,
        analysisTitle: String = "Compiler analysis",
        onSnapshotReady: ((WorkspaceAnalysisSnapshot, Map<String, String>) -> Unit)? = null,
    ): ModuleSemanticState {
        val moduleLabel = moduleLabel(module)
        val semanticProject = project.subsetForModules(listOf(module))
        val requestId = nextSemanticRequestId(module.gradlePath)
        val semanticAnalysisProgress = beginProgress(
            title = analysisTitle,
            subtitle = when {
                analysisTitle == "Full Diagnostics" -> "checking $moduleLabel"
                else -> "preparing $moduleLabel"
            },
            showImmediately = showProgress,
            minTotalToShow = if (showProgress) 1 else Int.MAX_VALUE,
        )
        val nextSnapshot = try {
            analyzer.analyze(
                semanticProject,
                documents,
                includedPaths = focusedPaths.takeIf { background.not() && it.isNotEmpty() },
            ) { subtitle, current, total ->
                semanticAnalysisProgress.report(
                    compilerAnalysisProgressMessage(moduleLabel, subtitle),
                    current,
                    total,
                )
            }.also {
                semanticAnalysisProgress.complete(
                    when {
                        analysisTitle == "Full Diagnostics" -> "$moduleLabel diagnostics ready"
                        else -> "$moduleLabel ready"
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
            indexFileContentHashes = emptyMap(),
            openDocumentVersions = openDocumentVersionsForModule(project, module),
            validationPending = false,
        )
    }

    private fun buildSemanticIndex(
        state: ModuleSemanticState,
        focusedPaths: Set<Path>,
        background: Boolean,
        showProgress: Boolean = true,
    ): ModuleSemanticState {
        val snapshot = state.snapshot ?: return state
        val moduleLabel = moduleLabel(state.module)
        val semanticIndexProgress = beginProgress(
            title = "Symbol index",
            subtitle = if (background) "refreshing $moduleLabel" else "indexing $moduleLabel",
            showImmediately = showProgress,
            minTotalToShow = if (showProgress) 1 else Int.MAX_VALUE,
        )
        val nextIndex = try {
            indexBuilder.build(snapshot, targetPaths = focusedPaths.takeIf { it.isNotEmpty() }) { subtitle, current, total ->
                semanticIndexProgress.report(
                    symbolIndexProgressMessage(moduleLabel, subtitle),
                    current,
                    total,
                )
            }.also {
                semanticIndexProgress.complete(
                    "$moduleLabel symbols ready",
                )
            }
        } catch (t: Throwable) {
            semanticIndexProgress.fail(t.message ?: t::class.java.simpleName)
            throw t
        }
        return state.copy(
            index = nextIndex,
            fullyIndexed = focusedPaths.isEmpty(),
            indexFileContentHashes = if (focusedPaths.isEmpty()) {
                state.fileContentHashes
            } else {
                state.fileContentHashes.filter { (uri, _) ->
                    documentUriToPath(uri).normalize() in focusedPaths
                }
            },
        )
    }

    private fun compilerAnalysisProgressMessage(moduleLabel: String, subtitle: String): String =
        when {
            subtitle == "Running Kotlin compiler resolve" -> "resolving $moduleLabel"
            subtitle.startsWith("Prepared ") -> "$moduleLabel: prepared ${subtitle.removePrefix("Prepared ")}"
            else -> "$moduleLabel: ${subtitle.replaceFirstChar { it.lowercase() }}"
        }

    private fun symbolIndexProgressMessage(moduleLabel: String, subtitle: String): String =
        when {
            subtitle.startsWith("Indexed ") -> "$moduleLabel: indexed ${subtitle.removePrefix("Indexed ")}"
            else -> "$moduleLabel: ${subtitle.replaceFirstChar { it.lowercase() }}"
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

    private fun ensureProjectWarmupStarted(project: ImportedProject) {
        if (!usesLocalSemanticRuntime()) return
        if (documents.openDocuments().none { document -> isSourceUri(document.uri) }) return
        val generation = currentProjectGeneration
        val warmupModules = synchronized(warmupLock) {
            if (projectWarmupStartedGeneration == generation) {
                return
            }
            projectWarmupStartedGeneration = generation
            backgroundWarmupModules(project)
        }
        if (warmupModules.isEmpty()) {
            showInfoMessage("Project cache: saved symbols are current")
            return
        }
        showInfoMessage("Project cache: refreshing saved symbols")
        scheduleBackgroundWarmup(project, warmupModules, includeOpenModules = true)
    }

    private fun announceDiagnosticsKickoff(uri: String, reason: String) {
        if (!clientInitialized || !isSourceUri(uri)) return
        showInfoMessage("Diagnostics: starting ${documentLabel(uri)} ${reasonLabel(reason)}")
    }

    private fun schedulePersistentDocumentCacheSync(
        project: ImportedProject?,
        uri: String,
        reason: String,
        flushGeneration: Int? = null,
        notify: Boolean = true,
    ) {
        if (!clientInitialized || !isSourceUri(uri)) return
        val snapshot = documents.get(uri) ?: return
        val path = documentUriToPath(uri).normalize()
        val reasonLabel = reasonLabel(reason)
        val fileLabel = documentLabel(uri)
        try {
            persistExecutor.execute {
                if (flushGeneration != null && !isCurrentFlushGeneration(uri, flushGeneration)) {
                    return@execute
                }
                val activeProject = awaitProjectForCacheSync(project) ?: run {
                    if (notify) {
                        showInfoMessage("Open file cache: skipped $fileLabel $reasonLabel because the project is still loading")
                    }
                    return@execute
                }
                if (activeProject.moduleForPath(path) == null) {
                    if (notify) {
                        showInfoMessage("Open file cache: skipped $fileLabel $reasonLabel because it is outside the imported project")
                    }
                    return@execute
                }
                val status = lightweightIndexBuilder.persistDocumentIfChanged(
                    project = activeProject,
                    path = path,
                    text = snapshot.text,
                )
                val message = when (status) {
                    DocumentCacheStatus.CURRENT ->
                        "Open file cache: $fileLabel unchanged $reasonLabel"

                    DocumentCacheStatus.MISSING ->
                        "Open file cache: cached $fileLabel $reasonLabel (new entry)"

                    DocumentCacheStatus.STALE ->
                        "Open file cache: refreshed $fileLabel $reasonLabel (contents changed)"
                }
                if (notify) {
                    showInfoMessage(message)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun scheduleBackgroundWarmup(
        project: ImportedProject,
        modulePaths: Collection<String> = project.modules.map { it.gradlePath },
        includeOpenModules: Boolean = false,
        rebuildIndex: Boolean = true,
    ) {
        val generation = currentProjectGeneration
        synchronized(warmupLock) {
            if (warmupGeneration != generation) {
                warmupQueuedModules.clear()
                warmupAllowedOpenModules.clear()
                warmupIndexRebuildModules.clear()
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
                if (rebuildIndex) {
                    warmupIndexRebuildModules += modulePath
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
                val nextWarmup = synchronized(warmupLock) {
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
                    val shouldRebuildIndex = warmupIndexRebuildModules.remove(modulePath)
                    modulePath to shouldRebuildIndex
                }
                val nextModulePath = nextWarmup.first
                val shouldRebuildIndex = nextWarmup.second
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
                    if (shouldRebuildIndex) {
                        buildSemanticIndex(
                            state = snapshotState,
                            focusedPaths = emptySet(),
                            background = true,
                        )
                    } else {
                        val reusableState = synchronized(semanticStateLock) {
                            semanticStates[nextModulePath]
                        }
                            ?.takeIf { state ->
                                state.projectGeneration == generation &&
                                    state.validated &&
                                    state.fullyIndexed
                            }
                        snapshotState.copy(
                            index = reusableState?.index ?: snapshotState.index,
                            fullyIndexed = reusableState != null,
                            fileContentHashes = if (reusableState?.validationPending == true) {
                                reusableState.fileContentHashes
                            } else {
                                snapshotState.fileContentHashes
                            },
                            indexFileContentHashes = reusableState?.indexFileContentHashes.orEmpty(),
                            validationPending = reusableState?.validationPending == true,
                        )
                    }
                }.onSuccess { state ->
                    installSemanticState(state, persistState = shouldRebuildIndex)
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
        if (expectedGeneration != null && diagnosticRequestGeneration.get() != expectedGeneration) {
            return
        }
        val openUris = documents.openDocuments().map { it.uri }.toSet()
        val deferredUris = synchronized(diagnosticLock) { deferredDiagnosticUris.toSet() }
        val targetOpenUris = if (fullRefresh) {
            openUris - deferredUris
        } else {
            openUris.intersect(requestedUris) - deferredUris
        }.filterTo(linkedSetOf()) { uri ->
            val generation = flushAcknowledgements[uri]
            generation == null || isCurrentFlushGeneration(uri, generation)
        }
        if (!fullRefresh && targetOpenUris.isEmpty()) return
        val merged = linkedMapOf<String, MutableList<dev.codex.kotlinls.protocol.Diagnostic>>()
        val currentProject = project
        currentProject?.let { importedProject ->
            targetOpenUris.forEach { uri ->
                if (expectedGeneration != null && diagnosticRequestGeneration.get() != expectedGeneration) {
                    return
                }
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
            if (expectedGeneration != null && diagnosticRequestGeneration.get() != expectedGeneration) {
                return
            }
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
            if (expectedGeneration != null && diagnosticRequestGeneration.get() != expectedGeneration) {
                return
            }
            if (uri in semanticUris) return@forEach
            val diagnostics = mutableListOf<dev.codex.kotlinls.protocol.Diagnostic>()
            val source = sourceView(uri)
            if (source != null) {
                diagnostics += diagnosticsService.fastDiagnostics(
                    currentProject,
                    source.first,
                    source.second,
                    diagnosticLookup.takeUnless { shouldDeferFastImportDiagnostics(uri) },
                )
            }
            merged[uri] = diagnostics
        }
        if (expectedGeneration != null && diagnosticRequestGeneration.get() != expectedGeneration) {
            val currentFlushAcknowledgements = flushAcknowledgements.filter { (uri, generation) ->
                isCurrentFlushGeneration(uri, generation)
            }
            if (forceUris.isNotEmpty() || currentFlushAcknowledgements.isNotEmpty()) {
                scheduleDiagnosticsPublish(
                    delayMillis = 0L,
                    uris = requestedUris.ifEmpty { forceUris },
                    fullRefresh = fullRefresh,
                    forceUris = forceUris,
                    flushAcknowledgements = currentFlushAcknowledgements,
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

    private fun shouldDeferFastImportDiagnostics(uri: String): Boolean {
        if (!usesLocalSemanticRuntime() || !isSourceUri(uri)) return false
        val currentProject = project ?: return false
        val module = currentProject.moduleForPath(documentUriToPath(uri)) ?: return false
        if (!moduleHasSemanticSources(module)) return false
        val state = semanticStates[module.gradlePath] ?: return true
        return state.snapshot == null || !semanticStateCurrentForUri(state, uri)
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

    private fun isCurrentFlushGeneration(uri: String, generation: Int): Boolean =
        synchronized(diagnosticLock) {
            latestFlushGenerationByUri[uri]?.let { latest -> generation >= latest } ?: true
        }

    private fun flushDeferredDiagnostics(
        uri: String,
        changedLines: List<ChangedLineRange>,
        generation: Int?,
    ) {
        if (!clientInitialized || !isSourceUri(uri)) return
        if (generation != null) {
            val isLatest = synchronized(diagnosticLock) {
                val latest = latestFlushGenerationByUri[uri]
                if (latest == null || generation >= latest) {
                    latestFlushGenerationByUri[uri] = generation
                    true
                } else {
                    false
                }
            }
            if (!isLatest) return
        }
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
        if (generation == null) {
            runPostDiagnosticsWork(uri, generation = null)
            return
        }
        synchronized(diagnosticLock) {
            pendingPostDiagnosticsWork[uri] = PendingPostDiagnosticsWork(flushGeneration = generation)
        }
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
        val postDiagnosticsWork = synchronized(diagnosticLock) {
            pendingPostDiagnosticsWork[uri]
                ?.takeIf { it.flushGeneration == generation }
                ?.also { pendingPostDiagnosticsWork.remove(uri) }
        }
        if (postDiagnosticsWork != null) {
            runPostDiagnosticsWork(uri, generation)
        }
    }

    private fun runPostDiagnosticsWork(uri: String, generation: Int?) {
        if (!clientInitialized || !isSourceUri(uri)) return
        if (generation != null && !isCurrentFlushGeneration(uri, generation)) return
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
        scheduleSemanticRefresh(uri, interactive = false, flushGeneration = generation, immediate = true)
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
            val resolvedState = future.get(config.semantic.requestTimeoutMillis, TimeUnit.MILLISECONDS)
            resolvedState
                ?.takeIf { state ->
                    state.projectGeneration == requestGeneration &&
                        state.snapshot != null &&
                        semanticStateCurrentForUri(state, uri)
                }
                ?.snapshot
                ?.let { snapshot -> snapshot to currentIndex() }
                ?: availableSemanticState(uri)
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

    private fun completionIndexForRoute(
        decision: CompletionRoutingDecision,
        fallbackIndex: WorkspaceIndex = currentIndex(),
    ): WorkspaceIndex =
        when (decision.reason) {
            "package-context" -> lightweightIndex
            "import-context" -> importCompletionCombinedIndex
            else -> fallbackIndex
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

    private fun clearRequestMemoCaches() {
        synchronized(requestMemoLock) {
            completionMemo.clear()
            hoverMemo.clear()
            definitionMemo.clear()
            typeDefinitionMemo.clear()
        }
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

    private fun scheduleSemanticRefresh(
        uri: String,
        interactive: Boolean = true,
        flushGeneration: Int? = null,
        immediate: Boolean = false,
    ) {
        if (!usesLocalSemanticRuntime() || !isSourceUri(uri)) return
        if (immediate && project != null) {
            scheduleImmediateSemanticRefresh(
                uri = uri,
                interactive = interactive,
                flushGeneration = flushGeneration,
            )
            return
        }
        scheduleRefresh(
            reimportProject = project == null,
            rebuildFastIndex = false,
            semanticUri = uri,
            interactiveSemanticRefresh = interactive,
            semanticFlushGeneration = flushGeneration,
        )
    }

    private fun scheduleImmediateSemanticRefresh(
        uri: String,
        interactive: Boolean,
        flushGeneration: Int?,
    ) {
        val scheduledProject = project ?: return
        try {
            semanticRequestExecutor.execute {
                if (flushGeneration != null && !isCurrentFlushGeneration(uri, flushGeneration)) {
                    return@execute
                }
                val activeProject = project ?: return@execute
                if (activeProject.root.normalize() != scheduledProject.root.normalize()) {
                    return@execute
                }
                refreshSemanticModules(
                    project = activeProject,
                    requestedSemanticUris = setOf(uri),
                    interactiveSemanticRefresh = interactive,
                )
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
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
                    indexFileContentHashes = state.indexFileContentHashes,
                    validationPending = false,
                )
            }.toMap()
        }

    private fun rebuildCombinedIndex() {
        val currentProject = project
        val nextSourceSemantic = synchronized(semanticStateLock) {
            mergeIndices(
                listOf(lightweightIndex) +
                    semanticStates.values
                        .filter(::semanticStateEligibleForIndex)
                        .map { state -> semanticIndexForOpenDocuments(state, currentProject) },
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
                val removedUris = mutableListOf<String>()
                pendingSemanticUris.removeIf { pendingUri ->
                    val shouldRemove = currentProject.moduleForPath(documentUriToPath(pendingUri))?.gradlePath == modulePath
                    if (shouldRemove) {
                        removedUris += pendingUri
                    }
                    shouldRemove
                }
                removedUris.forEach { removedUri ->
                    pendingSemanticFlushGenerations.remove(removedUri)
                }
            }
        }
        pendingSemanticUris += uri
    }

    private fun semanticStateEligibleForIndex(state: ModuleSemanticState): Boolean {
        if (state.projectGeneration != currentProjectGeneration) return false
        if (!state.validated || !state.fullyIndexed) return false
        return true
    }

    private fun semanticIndexForOpenDocuments(
        state: ModuleSemanticState,
        currentProject: ImportedProject?,
    ): WorkspaceIndex {
        currentProject ?: return state.index
        val staleOpenPaths = documents.openDocuments()
            .mapNotNull { document ->
                val path = documentUriToPath(document.uri).normalize()
                if (currentProject.moduleForPath(path)?.gradlePath != state.module.gradlePath) {
                    return@mapNotNull null
                }
                val currentHash = currentSourceContentHash(document.uri) ?: return@mapNotNull null
                val indexedHash = state.indexFileContentHashes[document.uri]
                if (indexedHash == currentHash) null else path
            }
            .toSet()
        if (staleOpenPaths.isEmpty()) return state.index
        return state.index.withoutPaths(staleOpenPaths)
    }

    private fun WorkspaceIndex.withoutPaths(paths: Set<Path>): WorkspaceIndex {
        if (paths.isEmpty()) return this
        val normalizedPaths = paths.map(Path::normalize).toSet()
        return WorkspaceIndex(
            symbols = symbols.filter { symbol -> symbol.path.normalize() !in normalizedPaths },
            references = references.filter { reference -> reference.path.normalize() !in normalizedPaths },
            callEdges = callEdges.filter { callEdge -> callEdge.path.normalize() !in normalizedPaths },
        )
    }

    private fun ModuleSemanticState.withReusableSemanticIndex(
        reusableState: ModuleSemanticState?,
    ): ModuleSemanticState {
        val reusable = reusableState?.takeIf { state ->
            state.projectGeneration == currentProjectGeneration &&
                state.validated &&
                state.fullyIndexed
        } ?: return this
        return copy(
            index = reusable.index,
            fullyIndexed = true,
            fileContentHashes = if (reusable.validationPending) reusable.fileContentHashes else fileContentHashes,
            indexFileContentHashes = reusable.indexFileContentHashes,
            validationPending = reusable.validationPending,
        )
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
                indexFileContentHashes = cached.fileContentHashes,
                openDocumentVersions = emptyMap(),
                validationPending = true,
            )
        }.toMap()

    private fun scheduleSemanticCacheValidation(
        project: ImportedProject,
        generation: Int,
    ) {
        val modulePaths = synchronized(semanticStateLock) {
            semanticStates.values
                .asSequence()
                .filter { state ->
                    state.projectGeneration == generation &&
                        state.validated &&
                        state.fullyIndexed &&
                        state.validationPending
                }
                .map { state -> state.module.gradlePath }
                .toList()
        }
        if (modulePaths.isEmpty()) return
        showInfoMessage("Project cache: validating saved symbols")
        try {
            semanticRequestExecutor.execute {
                var confirmed = 0
                var stale = 0
                modulePaths.forEach { modulePath ->
                    if (generation != currentProjectGeneration) return@execute
                    val activeProject = this.project ?: return@execute
                    if (activeProject.root.normalize() != project.root.normalize()) return@execute
                    val state = synchronized(semanticStateLock) {
                        semanticStates[modulePath]
                    } ?: return@forEach
                    if (
                        state.projectGeneration != generation ||
                        !state.validated ||
                        !state.fullyIndexed ||
                        !state.validationPending
                    ) {
                        return@forEach
                    }
                    val current = semanticCacheEntryCurrent(
                        activeProject,
                        state.module,
                        SemanticIndexCacheEntry(
                            index = state.index,
                            fileContentHashes = state.fileContentHashes,
                        ),
                    )
                    if (current) {
                        if (markSemanticCacheValidated(modulePath, generation)) {
                            confirmed += 1
                        }
                    } else {
                        if (dropStaleSemanticCache(modulePath, generation)) {
                            stale += 1
                            scheduleBackgroundWarmup(
                                activeProject,
                                listOf(modulePath),
                                includeOpenModules = true,
                                rebuildIndex = true,
                            )
                        }
                    }
                }
                when {
                    stale > 0 -> showInfoMessage("Project cache: refreshing $stale stale saved symbol cache(s)")
                    confirmed > 0 -> showInfoMessage("Project cache: saved symbols validated")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun markSemanticCacheValidated(modulePath: String, generation: Int): Boolean {
        var updated = false
        synchronized(semanticStateLock) {
            val state = semanticStates[modulePath] ?: return false
            if (state.projectGeneration != generation || !state.validationPending) {
                return false
            }
            semanticStates = semanticStates.toMutableMap().apply {
                put(modulePath, state.copy(validationPending = false))
            }.toMap()
            updated = true
        }
        return updated
    }

    private fun dropStaleSemanticCache(modulePath: String, generation: Int): Boolean {
        var dropped = false
        synchronized(semanticStateLock) {
            val state = semanticStates[modulePath] ?: return false
            if (state.projectGeneration != generation || !state.validationPending) {
                return false
            }
            semanticStates = semanticStates.toMutableMap().apply {
                remove(modulePath)
            }.toMap()
            dropped = true
        }
        if (dropped) {
            rebuildCombinedIndex()
            scheduleDiagnosticsPublish()
        }
        return dropped
    }

    private fun semanticCacheEntryCurrent(
        project: ImportedProject,
        module: ImportedModule,
        cached: SemanticIndexCacheEntry,
    ): Boolean {
        val expectedUris = semanticSourceUris(project, module)
        if (expectedUris.isEmpty()) {
            return cached.fileContentHashes.isEmpty()
        }
        if (cached.fileContentHashes.keys != expectedUris) {
            return false
        }
        return cached.fileContentHashes.all { (uri, recordedHash) ->
            currentSourceContentHash(uri) == recordedHash
        }
    }

    private fun semanticSourceUris(project: ImportedProject, module: ImportedModule): Set<String> =
        project.moduleClosure(listOf(module))
            .flatMap { closureModule ->
                closureModule.sourceRoots.flatMap { root ->
                    if (!Files.exists(root)) {
                        emptyList()
                    } else {
                        root.walk()
                            .filter { path -> path.isRegularFile() && path.extension in setOf("kt", "kts") }
                            .map { path -> path.normalize().toUri().toString() }
                            .toList()
                    }
                }
            }
            .toSet()

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
                indexFileContentHashes = cached.fileContentHashes,
                openDocumentVersions = emptyMap(),
                validationPending = false,
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
            fileContentHashes = state.indexFileContentHashes.ifEmpty { state.fileContentHashes },
        )
    }

    private fun semanticFileContentHashes(snapshot: WorkspaceAnalysisSnapshot): Map<String, String> =
        snapshot.files.associate { file -> file.uri to sha256(file.text) }

    private fun currentSourceContentHash(uri: String): String? =
        documents.get(uri)?.text?.let(::sha256) ?: diskSourceContentHash(documentUriToPath(uri))

    private fun diskSourceContentHash(path: Path): String? =
        runCatching { Files.readString(path.normalize()) }.getOrNull()?.let(::sha256)

    private fun awaitProjectForCacheSync(preferredProject: ImportedProject?): ImportedProject? {
        preferredProject?.let { return it }
        repeat(20) {
            project?.let { return it }
            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return project
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
            appendSourceRootFingerprint(builder, "sourceRoot", root)
        }
        module.javaSourceRoots.sortedBy { it.normalize().toString() }.forEach { root ->
            appendSourceRootFingerprint(builder, "javaSourceRoot", root)
        }
        module.classpathJars.sortedBy { it.normalize().toString() }.forEach { path ->
            appendFileFingerprint(builder, "classpathJar", path)
        }
        module.classpathSourceJars.sortedBy { it.normalize().toString() }.forEach { path ->
            appendFileFingerprint(builder, "classpathSourceJar", path)
        }
        return sha256(builder.toString())
    }

    private fun appendSourceRootFingerprint(builder: StringBuilder, label: String, root: Path) {
        val normalizedRoot = root.normalize()
        builder.append(label).append('=').append(normalizedRoot).append('|')
            .append(if (Files.exists(normalizedRoot)) "present" else "missing")
            .append('\n')
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

    private fun documentLabel(uri: String): String =
        documentUriToPath(uri).fileName?.toString().orEmpty().ifBlank { uri }

    private fun reasonLabel(reason: String): String =
        when (reason) {
            "open" -> "on open"
            "insert-leave" -> "after insert leave"
            else -> "after $reason"
        }

    private fun isSourceUri(uri: String): Boolean = isSourceFile(documentUriToPath(uri))

    private fun isSourceFile(path: Path): Boolean =
        path.fileName?.toString()?.substringAfterLast('.', "").orEmpty() in setOf("kt", "kts", "java")

    private fun showInfoMessage(message: String) {
        if (!clientInitialized) return
        sendNotificationSafely(
            "window/showMessage",
            mapOf(
                "type" to 3,
                "message" to message,
            ),
        )
    }

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
        if (containsPosition(symbol.selectionRange, position)) return true
        if (explicitImportMatches(text, position, symbol)) return true
        if (symbol.importable && symbol.packageName == SourceIndexLookup.packageName(text)) return true
        val symbolPath = symbol.path.normalize()
        if (symbolPath == requestPath.normalize()) return false
        val projectRoot = project?.root?.normalize()
        return projectRoot == null || !symbolPath.startsWith(projectRoot)
    }

    private fun explicitImportMatches(
        text: String,
        position: dev.codex.kotlinls.protocol.Position,
        symbol: IndexedSymbol,
    ): Boolean {
        val fqName = symbol.fqName ?: return false
        val token = SourceIndexLookup.identifierAt(text, position) ?: return false
        return SourceIndexLookup.imports(text).any { import ->
            import.fqName == fqName && import.visibleName == token
        }
    }

    private fun containsPosition(
        range: dev.codex.kotlinls.protocol.Range,
        position: dev.codex.kotlinls.protocol.Position,
    ): Boolean =
        comparePosition(range.start, position) <= 0 && comparePosition(position, range.end) <= 0

    private fun comparePosition(
        left: dev.codex.kotlinls.protocol.Position,
        right: dev.codex.kotlinls.protocol.Position,
    ): Int =
        when {
            left.line != right.line -> left.line.compareTo(right.line)
            else -> left.character.compareTo(right.character)
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
        val shouldShowImmediately = showImmediately && when (config.progress.mode) {
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
        sendNotificationSafely(
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

    private fun sendNotificationSafely(method: String, params: Any?) {
        runCatching {
            transport.sendNotification(method, params)
        }
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
            if (config.progress.mode != ProgressMode.VERBOSE) return
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
        val semanticFlushGenerations: Map<String, Int>,
    )

    private data class PendingPostDiagnosticsWork(
        val flushGeneration: Int,
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

    private data class IndexedSourceFileEntry(
        val path: Path,
        val moduleName: String,
        val moduleGradlePath: String,
        val rootKind: String,
    )

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
        val indexFileContentHashes: Map<String, String>,
        val openDocumentVersions: Map<String, Int>,
        val validationPending: Boolean,
    )
}
