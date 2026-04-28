package dev.codex.kotlinls.server

import dev.codex.kotlinls.completion.JetBrainsBridgeCompletion
import dev.codex.kotlinls.completion.JetBrainsCompletionBridge
import dev.codex.kotlinls.formatting.FormattingService
import dev.codex.kotlinls.formatting.JetBrainsFormatterBridge
import dev.codex.kotlinls.projectimport.ImportedProject
import dev.codex.kotlinls.protocol.CompletionItem
import dev.codex.kotlinls.protocol.CompletionItemKind
import dev.codex.kotlinls.protocol.CompletionList
import dev.codex.kotlinls.protocol.CompletionParams
import dev.codex.kotlinls.protocol.DocumentFormattingParams
import dev.codex.kotlinls.protocol.DocumentRangeFormattingParams
import dev.codex.kotlinls.protocol.Hover
import dev.codex.kotlinls.protocol.IntellijHomeLocator
import dev.codex.kotlinls.protocol.Location
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.TextDocumentPositionParams
import dev.codex.kotlinls.protocol.TextEdit
import dev.codex.kotlinls.workspace.LineIndex
import dev.codex.kotlinls.workspace.TextDocumentSnapshot
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class SemanticEngineCapabilities(
    val completion: Boolean = false,
    val formatting: Boolean = false,
    val rangeFormatting: Boolean = false,
    val hover: Boolean = false,
    val signatureHelp: Boolean = false,
    val references: Boolean = false,
    val implementations: Boolean = false,
    val rename: Boolean = false,
    val documentHighlight: Boolean = false,
    val inlayHints: Boolean = false,
    val codeActions: Boolean = false,
    val semanticTokens: Boolean = false,
    val foldingRange: Boolean = false,
    val callHierarchy: Boolean = false,
    val typeHierarchy: Boolean = false,
)

internal interface SemanticEngine : AutoCloseable {
    val requestedBackend: SemanticBackend
    val available: Boolean
    val capabilities: SemanticEngineCapabilities

    fun onProjectChanged(project: ImportedProject?)

    fun invalidate(uri: String, version: Int? = null)

    fun prefetch(
        project: ImportedProject,
        activeDocument: TextDocumentSnapshot?,
        openDocuments: Collection<TextDocumentSnapshot>,
        projectGeneration: Int,
        syncDocuments: Boolean = true,
        startBridge: Boolean = false,
    )

    fun complete(
        project: ImportedProject,
        path: Path,
        text: String,
        version: Int?,
        params: CompletionParams,
        projectGeneration: Int,
    ): CompletionList?

    fun hover(
        project: ImportedProject,
        path: Path,
        text: String,
        version: Int?,
        params: TextDocumentPositionParams,
        projectGeneration: Int,
    ): Hover?

    fun definition(
        project: ImportedProject,
        path: Path,
        text: String,
        version: Int?,
        params: TextDocumentPositionParams,
        projectGeneration: Int,
    ): List<Location>?

    fun formatDocument(
        projectRoot: Path?,
        path: Path,
        text: String,
        version: Int?,
        params: DocumentFormattingParams,
        projectGeneration: Int,
    ): List<TextEdit>?

    fun formatRange(
        projectRoot: Path?,
        path: Path,
        text: String,
        version: Int?,
        params: DocumentRangeFormattingParams,
        projectGeneration: Int,
    ): List<TextEdit>?
}

internal class DisabledSemanticEngine(
    override val requestedBackend: SemanticBackend,
) : SemanticEngine {
    override val available: Boolean = false
    override val capabilities: SemanticEngineCapabilities = SemanticEngineCapabilities()

    override fun onProjectChanged(project: ImportedProject?) = Unit

    override fun invalidate(uri: String, version: Int?) = Unit

    override fun prefetch(
        project: ImportedProject,
        activeDocument: TextDocumentSnapshot?,
        openDocuments: Collection<TextDocumentSnapshot>,
        projectGeneration: Int,
        syncDocuments: Boolean,
        startBridge: Boolean,
    ) = Unit

    override fun complete(
        project: ImportedProject,
        path: Path,
        text: String,
        version: Int?,
        params: CompletionParams,
        projectGeneration: Int,
    ): CompletionList? = null

    override fun hover(
        project: ImportedProject,
        path: Path,
        text: String,
        version: Int?,
        params: TextDocumentPositionParams,
        projectGeneration: Int,
    ): Hover? = null

    override fun definition(
        project: ImportedProject,
        path: Path,
        text: String,
        version: Int?,
        params: TextDocumentPositionParams,
        projectGeneration: Int,
    ): List<Location>? = null

    override fun formatDocument(
        projectRoot: Path?,
        path: Path,
        text: String,
        version: Int?,
        params: DocumentFormattingParams,
        projectGeneration: Int,
    ): List<TextEdit>? = null

    override fun formatRange(
        projectRoot: Path?,
        path: Path,
        text: String,
        version: Int?,
        params: DocumentRangeFormattingParams,
        projectGeneration: Int,
    ): List<TextEdit>? = null

    override fun close() = Unit
}

internal class BridgeK2SemanticEngine private constructor(
    override val requestedBackend: SemanticBackend,
    private val semanticConfig: SemanticConfig,
    private var bridge: JetBrainsCompletionBridge?,
    private val formatterService: FormattingService,
) : SemanticEngine {
    private val liveSyncDebounceMillis = 400L
    private val liveSyncRetryMillis = 100L
    private val requestExecutor = Executors.newFixedThreadPool(
        maxOf(2, minOf(Runtime.getRuntime().availableProcessors(), 4)),
    ) { runnable ->
        Thread(runnable, "android-neovim-lsp-semantic").apply { isDaemon = true }
    }
    private val syncExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "android-neovim-lsp-semantic-sync").apply { isDaemon = true }
    }
    private val inFlight = ConcurrentHashMap<SemanticRequestKey, CompletableFuture<Any?>>()
    private val latestDocumentVersions = ConcurrentHashMap<String, Int>()
    private val foregroundRequests = AtomicInteger(0)
    private val pendingSyncLock = Any()
    private val pendingProjectWarmups = linkedSetOf<Path>()
    private val pendingDocumentSyncs = linkedMapOf<String, PendingBridgeSync>()
    private var syncDrainGeneration = 0L
    private var pendingSyncDrain: ScheduledFuture<*>? = null
    @Volatile
    private var bridgeStartupScheduled = false

    override val available: Boolean
        get() = acquireBridge() != null

    override val capabilities: SemanticEngineCapabilities = SemanticEngineCapabilities(
        completion = true,
        formatting = true,
        rangeFormatting = true,
        hover = true,
    )

    override fun onProjectChanged(project: ImportedProject?) {
        inFlight.clear()
        synchronized(pendingSyncLock) {
            pendingProjectWarmups.clear()
            pendingDocumentSyncs.clear()
            syncDrainGeneration += 1
            pendingSyncDrain?.cancel(false)
            pendingSyncDrain = null
            bridgeStartupScheduled = false
        }
        val currentBridge = bridge
        bridge = null
        runCatching { currentBridge?.close() }
        project?.let { enqueueProjectWarmup(it.root, immediate = true) }
    }

    override fun invalidate(uri: String, version: Int?) {
        version?.let { latestDocumentVersions[uri] = it }
        inFlight.keys.removeIf { key -> key.uri == uri }
        synchronized(pendingSyncLock) {
            pendingDocumentSyncs.remove(uri)
        }
    }

    override fun prefetch(
        project: ImportedProject,
        activeDocument: TextDocumentSnapshot?,
        openDocuments: Collection<TextDocumentSnapshot>,
        projectGeneration: Int,
        syncDocuments: Boolean,
        startBridge: Boolean,
    ) {
        enqueueProjectWarmup(project.root)
        if (startBridge) {
            scheduleBridgeStartup()
        }
        if (!syncDocuments) return
        val targets = prefetchTargets(project, activeDocument, openDocuments)
        if (targets.isEmpty()) return
        synchronized(pendingSyncLock) {
            targets.forEachIndexed { index, (_, document) ->
                latestDocumentVersions[document.uri] = document.version
                pendingDocumentSyncs[document.uri] = PendingBridgeSync(
                    projectRoot = project.root,
                    uri = document.uri,
                    path = Path.of(java.net.URI.create(document.uri)),
                    text = document.text,
                    version = document.version,
                    priority = index,
                )
            }
            if (bridge != null) {
                scheduleSyncDrainLocked(liveSyncDebounceMillis)
            }
        }
    }

    override fun complete(
        project: ImportedProject,
        path: Path,
        text: String,
        version: Int?,
        params: CompletionParams,
        projectGeneration: Int,
    ): CompletionList? {
        val offset = LineIndex.build(text).offset(params.position)
        val key = SemanticRequestKey("completion", params.textDocument.uri, version, projectGeneration, "${params.position.line}:${params.position.character}")
        synchronized(pendingSyncLock) {
            pendingDocumentSyncs.remove(params.textDocument.uri)
        }
        val items = awaitMemoized(key) {
            withForegroundRequest {
                val activeBridge = acquireBridge() ?: return@withForegroundRequest null
                activeBridge.complete(project.root, path, text, offset)
            }
        } ?: return null
        if (!isCurrentVersion(params.textDocument.uri, version)) return null
        return CompletionList(isIncomplete = false, items = bridgeItems(text, items))
    }

    override fun hover(
        project: ImportedProject,
        path: Path,
        text: String,
        version: Int?,
        params: TextDocumentPositionParams,
        projectGeneration: Int,
    ): Hover? {
        val offset = LineIndex.build(text).offset(params.position)
        val key = SemanticRequestKey("hover", params.textDocument.uri, version, projectGeneration, "${params.position.line}:${params.position.character}")
        synchronized(pendingSyncLock) {
            pendingDocumentSyncs.remove(params.textDocument.uri)
        }
        val hover = awaitMemoized(key) {
            withForegroundRequest {
                val activeBridge = acquireBridge() ?: return@withForegroundRequest null
                activeBridge.hover(project.root, path, text, offset)
            }
        } ?: return null
        if (!isCurrentVersion(params.textDocument.uri, version)) return null
        return hover
    }

    override fun definition(
        project: ImportedProject,
        path: Path,
        text: String,
        version: Int?,
        params: TextDocumentPositionParams,
        projectGeneration: Int,
    ): List<Location>? {
        val offset = LineIndex.build(text).offset(params.position)
        val key = SemanticRequestKey("definition", params.textDocument.uri, version, projectGeneration, "${params.position.line}:${params.position.character}")
        synchronized(pendingSyncLock) {
            pendingDocumentSyncs.remove(params.textDocument.uri)
        }
        val locations = awaitMemoized(key) {
            withForegroundRequest {
                val activeBridge = acquireBridge() ?: return@withForegroundRequest null
                activeBridge.definition(project.root, path, text, offset)
            }
        } ?: return null
        if (!isCurrentVersion(params.textDocument.uri, version)) return null
        return locations
    }

    override fun formatDocument(
        projectRoot: Path?,
        path: Path,
        text: String,
        version: Int?,
        params: DocumentFormattingParams,
        projectGeneration: Int,
    ): List<TextEdit>? {
        val uri = params.textDocument.uri
        val key = SemanticRequestKey("format-document", uri, version, projectGeneration, "document")
        val edits = awaitMemoized(key, timeoutMillis = semanticConfig.formatTimeoutMillis) {
            val bridged = withForegroundRequest {
                projectRoot
                    ?.let { root ->
                        val activeBridge = acquireBridge() ?: return@let null
                        activeBridge.formatDocument(root, path, text)
                    }
            }
            if (bridged != null) {
                formatterService.editsForFormattedText(text, bridged)
            } else {
                formatterService.formatDocumentText(
                    path = path,
                    text = text,
                    params = params,
                    requireExternalFormatter = true,
                )
            }
        } ?: throw IllegalStateException("Formatting timed out after ${semanticConfig.formatTimeoutMillis}ms")
        if (!isCurrentVersion(uri, version)) return null
        return edits
    }

    override fun formatRange(
        projectRoot: Path?,
        path: Path,
        text: String,
        version: Int?,
        params: DocumentRangeFormattingParams,
        projectGeneration: Int,
    ): List<TextEdit>? {
        val uri = params.textDocument.uri
        val key = SemanticRequestKey("format-range", uri, version, projectGeneration, "${params.range.start.line}:${params.range.start.character}")
        val edits = awaitMemoized(key, timeoutMillis = semanticConfig.formatTimeoutMillis) {
            val bridged = withForegroundRequest {
                projectRoot
                    ?.let { root ->
                        val activeBridge = acquireBridge() ?: return@let null
                        val lineIndex = LineIndex.build(text)
                        val startOffset = lineIndex.offset(params.range.start)
                        val endOffset = lineIndex.offset(params.range.end)
                        activeBridge.formatRange(root, path, text, startOffset, endOffset)
                    }
            }
            if (bridged != null) {
                formatterService.editsForFormattedText(text, bridged)
            } else {
                formatterService.formatRangeText(
                    path = path,
                    text = text,
                    params = params,
                    requireExternalFormatter = true,
                )
            }
        } ?: throw IllegalStateException("Formatting timed out after ${semanticConfig.formatTimeoutMillis}ms")
        if (!isCurrentVersion(uri, version)) return null
        return edits
    }

    override fun close() {
        inFlight.clear()
        synchronized(pendingSyncLock) {
            pendingProjectWarmups.clear()
            pendingDocumentSyncs.clear()
            syncDrainGeneration += 1
            pendingSyncDrain?.cancel(false)
            pendingSyncDrain = null
            bridgeStartupScheduled = false
        }
        requestExecutor.shutdownNow()
        syncExecutor.shutdownNow()
        val currentBridge = bridge
        bridge = null
        runCatching { currentBridge?.close() }
    }

    private fun acquireBridge(preferredProjectRoot: Path? = null): JetBrainsCompletionBridge? {
        bridge?.let { return it }
        return synchronized(this) {
            bridge ?: JetBrainsCompletionBridge.detect(forceEnable = true, projectRoot = preferredProjectRoot)?.also { detected ->
                bridge = detected
                synchronized(pendingSyncLock) {
                    scheduleSyncDrainLocked(0L)
                }
            }
        }
    }

    private fun enqueueProjectWarmup(projectRoot: Path, immediate: Boolean = false) {
        synchronized(pendingSyncLock) {
            pendingProjectWarmups.add(projectRoot)
            if (bridge != null) {
                scheduleSyncDrainLocked(if (immediate) 0L else liveSyncDebounceMillis)
            }
        }
    }

    private fun scheduleBridgeStartup() {
        var preferredProjectRoot: Path? = null
        synchronized(pendingSyncLock) {
            if (bridge != null || bridgeStartupScheduled) return
            bridgeStartupScheduled = true
            preferredProjectRoot = pendingProjectWarmups.firstOrNull() ?: pendingDocumentSyncs.values.firstOrNull()?.projectRoot
        }
        try {
            syncExecutor.execute {
                try {
                    acquireBridge(preferredProjectRoot)
                } finally {
                    synchronized(pendingSyncLock) {
                        bridgeStartupScheduled = false
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            synchronized(pendingSyncLock) {
                bridgeStartupScheduled = false
            }
        }
    }

    private fun scheduleSyncDrainLocked(delayMillis: Long) {
        val generation = ++syncDrainGeneration
        pendingSyncDrain?.cancel(false)
        try {
            pendingSyncDrain = syncExecutor.schedule(
                { drainPendingSyncs(generation) },
                delayMillis.coerceAtLeast(0L),
                TimeUnit.MILLISECONDS,
            )
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pendingSyncDrain = null
        }
    }

    private fun drainPendingSyncs(generation: Long) {
        val warmups: List<Path>
        val documentSyncs: List<PendingBridgeSync>
        val activeBridge: JetBrainsCompletionBridge
        synchronized(pendingSyncLock) {
            if (generation != syncDrainGeneration) {
                return
            }
            pendingSyncDrain = null
            if (foregroundRequests.get() > 0) {
                scheduleSyncDrainLocked(liveSyncRetryMillis)
                return
            }
            val currentBridge = bridge
            if (currentBridge == null) {
                return
            }
            warmups = pendingProjectWarmups.toList()
            documentSyncs = pendingDocumentSyncs.values.sortedBy { it.priority }.toList()
            pendingProjectWarmups.clear()
            pendingDocumentSyncs.clear()
            activeBridge = currentBridge
        }
        if (warmups.isEmpty() && documentSyncs.isEmpty()) return
        warmups.forEachIndexed { index, projectRoot ->
            if (foregroundRequests.get() > 0) {
                requeueWarmups(warmups.drop(index), liveSyncRetryMillis)
                return
            }
            activeBridge.ensureProject(projectRoot)
        }
        documentSyncs.forEachIndexed { index, sync ->
            if (foregroundRequests.get() > 0) {
                requeueDocumentSyncs(documentSyncs.drop(index), liveSyncRetryMillis)
                return
            }
            if (!isCurrentVersion(sync.uri, sync.version)) return@forEachIndexed
            activeBridge.syncDocument(sync.projectRoot, sync.path, sync.text)
        }
    }

    private fun requeueWarmups(projectRoots: List<Path>, delayMillis: Long) {
        if (projectRoots.isEmpty()) return
        synchronized(pendingSyncLock) {
            projectRoots.forEach(pendingProjectWarmups::add)
            scheduleSyncDrainLocked(delayMillis)
        }
    }

    private fun requeueDocumentSyncs(syncs: List<PendingBridgeSync>, delayMillis: Long) {
        if (syncs.isEmpty()) return
        synchronized(pendingSyncLock) {
            syncs.forEach { sync -> pendingDocumentSyncs[sync.uri] = sync }
            scheduleSyncDrainLocked(delayMillis)
        }
    }

    private fun <T> withForegroundRequest(block: () -> T): T {
        foregroundRequests.incrementAndGet()
        return try {
            block()
        } finally {
            foregroundRequests.decrementAndGet()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> awaitMemoized(
        key: SemanticRequestKey,
        timeoutMillis: Long? = semanticConfig.requestTimeoutMillis,
        supplier: () -> T?,
    ): T? {
        val created = CompletableFuture.supplyAsync(supplier, requestExecutor) as CompletableFuture<Any?>
        val future = inFlight.putIfAbsent(key, created) ?: created
        created.takeIf { future !== it }?.cancel(true)
        return try {
            if (timeoutMillis == null) {
                future.get() as T?
            } else {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS) as T?
            }
        } catch (_: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            if (key.feature.startsWith("format-")) {
                resetBridgeAfterFormattingTimeout(key, timeoutMillis)
            }
            null
        } catch (_: java.util.concurrent.CancellationException) {
            null
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        } finally {
            future.whenComplete { _, _ -> inFlight.remove(key, future) }
        }
    }

    private fun resetBridgeAfterFormattingTimeout(key: SemanticRequestKey, timeoutMillis: Long?) {
        val currentBridge = synchronized(this) {
            bridge.also {
                bridge = null
            }
        }
        if (currentBridge != null) {
            System.err.println(
                "[android-neovim-lsp] ${key.feature} timed out after ${timeoutMillis ?: 0}ms; restarting JetBrains bridge",
            )
            runCatching { currentBridge.close() }
        }
    }

    private fun isCurrentVersion(uri: String, version: Int?): Boolean {
        if (version == null) return true
        return latestDocumentVersions[uri]?.let { current -> current == version } ?: true
    }

    private fun prefetchTargets(
        project: ImportedProject,
        activeDocument: TextDocumentSnapshot?,
        openDocuments: Collection<TextDocumentSnapshot>,
    ): List<Pair<String, TextDocumentSnapshot>> {
        if (activeDocument == null) return emptyList()
        val activePath = Path.of(java.net.URI.create(activeDocument.uri))
        val activeModule = project.moduleForPath(activePath)?.gradlePath
        return when (semanticConfig.prefetch) {
            SemanticPrefetchMode.ACTIVE_FILE -> listOf(activeDocument.uri to activeDocument)
            SemanticPrefetchMode.VISIBLE_FILES -> openDocuments.map { it.uri to it }
            SemanticPrefetchMode.MODULE -> openDocuments
                .filter { document ->
                    val path = Path.of(java.net.URI.create(document.uri))
                    project.moduleForPath(path)?.gradlePath == activeModule
                }
                .map { it.uri to it }
                .ifEmpty { listOf(activeDocument.uri to activeDocument) }
        }
    }

    private fun bridgeItems(text: String, items: List<JetBrainsBridgeCompletion>): List<CompletionItem> {
        val importedFqNames = text.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").substringBefore(" as ").trim() }
            .toSet()
        val packageName = text.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("package ") }
            ?.removePrefix("package ")
            ?.trim()
            .orEmpty()
        return items.mapIndexed { index, candidate ->
            val fqName = candidate.fqName
            val needsImport = candidate.importable &&
                !fqName.isNullOrBlank() &&
                candidate.packageName.orEmpty().isNotBlank() &&
                candidate.packageName != packageName &&
                fqName !in importedFqNames
            CompletionItem(
                label = candidate.label,
                kind = bridgeCompletionKind(candidate.kind),
                detail = candidate.detail,
                sortText = (1000 - (900 - index)).coerceAtLeast(0).toString().padStart(4, '0'),
                filterText = candidate.lookupString,
                additionalTextEdits = if (needsImport) addImportEdits(text, fqName) else null,
                data = mapOf(
                    "provider" to "jetbrains",
                    "fqName" to (fqName ?: ""),
                    "smart" to candidate.smart.toString(),
                ),
            )
        }.take(100)
    }

    private fun bridgeCompletionKind(kind: String?): Int = when (kind) {
        "class", "interface", "enum", "object" -> CompletionItemKind.CLASS
        "function" -> CompletionItemKind.FUNCTION
        "property" -> CompletionItemKind.PROPERTY
        "package" -> CompletionItemKind.MODULE
        else -> CompletionItemKind.VARIABLE
    }

    private fun addImportEdits(text: String, fqName: String): List<TextEdit> {
        val lines = text.lines()
        val existingImportLines = lines.withIndex().filter { it.value.trimStart().startsWith("import ") }
        val insertionLine = when {
            existingImportLines.isNotEmpty() -> existingImportLines.last().index + 1
            lines.firstOrNull()?.startsWith("package ") == true -> 1
            else -> 0
        }
        return listOf(
            TextEdit(
                range = Range(
                    start = Position(insertionLine, 0),
                    end = Position(insertionLine, 0),
                ),
                newText = "import $fqName\n",
            ),
        )
    }

    private data class SemanticRequestKey(
        val feature: String,
        val uri: String,
        val version: Int?,
        val projectGeneration: Int,
        val detail: String,
    )

    private data class PendingBridgeSync(
        val projectRoot: Path,
        val uri: String,
        val path: Path,
        val text: String,
        val version: Int,
        val priority: Int,
    )

    companion object {
        fun create(semanticConfig: SemanticConfig): SemanticEngine {
            if (semanticConfig.backend == SemanticBackend.DISABLED) {
                return DisabledSemanticEngine(semanticConfig.backend)
            }
            return BridgeK2SemanticEngine(
                requestedBackend = SemanticBackend.K2_BRIDGE,
                semanticConfig = semanticConfig,
                bridge = null,
                formatterService = FormattingService(intellijFormatterBridge = detectFormatterBridge()),
            )
        }

        private fun detectFormatterBridge() =
            IntellijHomeLocator.configuredIdeaHome()
                ?.let(JetBrainsFormatterBridge::fromIdeaHome)
                ?: commonIdeaHomes().firstNotNullOfOrNull(JetBrainsFormatterBridge::fromIdeaHome)

        private fun commonIdeaHomes(): List<Path> =
            listOf(
                Path.of("/Applications/Android Studio.app/Contents"),
                Path.of("/Applications/IntelliJ IDEA.app/Contents"),
                Path.of("/Applications/IntelliJ IDEA CE.app/Contents"),
            ).filter(Files::isDirectory)
    }
}
