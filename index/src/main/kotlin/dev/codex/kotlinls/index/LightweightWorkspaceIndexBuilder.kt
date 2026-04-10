package dev.codex.kotlinls.index

import dev.codex.kotlinls.projectimport.ImportedProject
import dev.codex.kotlinls.protocol.Json
import dev.codex.kotlinls.workspace.TextDocumentStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk

class LightweightWorkspaceIndexBuilder(
    private val kotlinSourceIndexer: KotlinSourceIndexer = KotlinSourceIndexer(),
    private val cacheRoot: Path = defaultIndexCacheRoot(),
) {
    private val cacheLock = Any()
    private val projectCaches = linkedMapOf<Path, CachedProjectIndex>()

    fun build(
        project: ImportedProject,
        documents: TextDocumentStore,
        progress: ((String, Int, Int) -> Unit)? = null,
    ): WorkspaceIndex {
        val projectRoot = project.root.normalize()
        val projectCache = synchronized(cacheLock) {
            projectCaches.getOrPut(projectRoot) { loadProjectCache(projectRoot) }
        }
        val liveFiles = linkedSetOf<Path>()
        val symbols = mutableListOf<IndexedSymbol>()
        var dirty = false
        val workItems = buildList {
            project.modules.forEach { module ->
                (module.sourceRoots + module.javaSourceRoots).forEach { root ->
                    if (!Files.exists(root)) return@forEach
                    root.walk().forEach { path ->
                        if (!path.isRegularFile() || path.extension !in setOf("kt", "kts", "java")) return@forEach
                        add(module to path.normalize())
                    }
                }
            }
        }
        val totalFiles = workItems.size.coerceAtLeast(1)
        workItems.forEachIndexed { index, (module, normalized) ->
            liveFiles.add(normalized)
            val document = documents.get(normalized.toUri().toString())
            val cached = synchronized(cacheLock) { projectCache.files[normalized] }
            val indexedSymbols = if (document != null) {
                val parsed = parseSymbols(normalized, module.name, document.text)
                synchronized(cacheLock) {
                    projectCache.files[normalized] = (cached ?: CachedIndexedFile(moduleName = module.name))
                        .copy(openDocumentVersion = document.version, symbols = parsed)
                }
                parsed
            } else {
                val fileState = fileState(normalized)
                val reused = if (
                    cached != null &&
                    cached.moduleName == module.name &&
                    cached.lastModifiedMillis == fileState.lastModifiedMillis &&
                    cached.fileSize == fileState.fileSize
                ) {
                    cached.symbols
                } else {
                    null
                }
                if (reused != null) {
                    reused
                } else {
                    val text = runCatching { normalized.readText() }.getOrDefault("")
                    val contentHash = sha256(text)
                    val parsed = if (
                        cached != null &&
                        cached.moduleName == module.name &&
                        cached.contentHash == contentHash
                    ) {
                        cached.symbols
                    } else {
                        parseSymbols(normalized, module.name, text)
                    }
                    synchronized(cacheLock) {
                        projectCache.files[normalized] = CachedIndexedFile(
                            moduleName = module.name,
                            lastModifiedMillis = fileState.lastModifiedMillis,
                            fileSize = fileState.fileSize,
                            contentHash = contentHash,
                            openDocumentVersion = null,
                            symbols = parsed,
                        )
                    }
                    dirty = true
                    parsed
                }
            }
            symbols += indexedSymbols
            if (shouldReportProgress(index + 1, totalFiles)) {
                progress?.invoke("Indexed ${normalized.fileName}", index + 1, totalFiles)
            }
        }
        synchronized(cacheLock) {
            val stale = projectCache.files.keys - liveFiles
            if (stale.isNotEmpty()) dirty = true
            stale.forEach(projectCache.files::remove)
        }
        if (dirty) {
            saveProjectCache(projectRoot, projectCache)
        }
        return WorkspaceIndex(
            symbols = symbols.distinctBy { it.id }.sortedWith(compareBy({ it.name }, { it.path.toString() })),
            references = emptyList(),
            callEdges = emptyList(),
        )
    }

    fun load(projectRoot: Path): WorkspaceIndex? {
        val normalizedRoot = projectRoot.normalize()
        val projectCache = synchronized(cacheLock) {
            projectCaches.getOrPut(normalizedRoot) { loadProjectCache(normalizedRoot) }
        }
        if (projectCache.files.isEmpty()) return null
        return WorkspaceIndex(
            symbols = projectCache.files.values
                .flatMap { it.symbols }
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.name }, { it.path.toString() })),
            references = emptyList(),
            callEdges = emptyList(),
        )
    }

    fun updateOpenDocument(
        project: ImportedProject,
        path: Path,
        text: String,
        currentIndex: WorkspaceIndex,
    ): WorkspaceIndex {
        val normalized = path.normalize()
        val module = project.moduleForPath(normalized) ?: return currentIndex
        val parsed = parseSymbols(normalized, module.name, text)
        val mergedSymbols = buildList {
            addAll(currentIndex.symbols.filter { it.path.normalize() != normalized })
            addAll(parsed)
        }
        return WorkspaceIndex(
            symbols = mergedSymbols.distinctBy { it.id }.sortedWith(compareBy({ it.name }, { it.path.toString() })),
            references = currentIndex.references,
            callEdges = currentIndex.callEdges,
        )
    }

    private fun parseSymbols(path: Path, moduleName: String, text: String): List<IndexedSymbol> =
        when (path.extension) {
            "java" -> JavaSourceIndexer.index(path, moduleName)
            else -> kotlinSourceIndexer.index(path, moduleName, text)
        }

    private fun loadProjectCache(projectRoot: Path): CachedProjectIndex {
        val cacheFile = cacheFile(projectRoot)
        if (!cacheFile.exists()) return CachedProjectIndex()
        return runCatching {
            val persisted = Json.mapper.readValue(cacheFile.toFile(), PersistedProjectIndex::class.java)
            if (persisted.schemaVersion != SCHEMA_VERSION) return CachedProjectIndex()
            CachedProjectIndex(
                files = persisted.files.associate { file ->
                    Path.of(file.path).normalize() to CachedIndexedFile(
                        moduleName = file.moduleName,
                        lastModifiedMillis = file.lastModifiedMillis,
                        fileSize = file.fileSize,
                        contentHash = file.contentHash,
                        openDocumentVersion = null,
                        symbols = file.symbols.map { symbol ->
                            IndexedSymbol(
                                id = symbol.id,
                                name = symbol.name,
                                fqName = symbol.fqName,
                                kind = symbol.kind,
                                path = Path.of(symbol.path),
                                uri = symbol.uri,
                                range = symbol.range,
                                selectionRange = symbol.selectionRange,
                                containerName = symbol.containerName,
                                containerFqName = symbol.containerFqName,
                                signature = symbol.signature,
                                documentation = symbol.documentation,
                                packageName = symbol.packageName,
                                moduleName = symbol.moduleName,
                                importable = symbol.importable,
                                receiverType = symbol.receiverType,
                                resultType = symbol.resultType,
                                parameterCount = symbol.parameterCount,
                                supertypes = symbol.supertypes,
                            )
                        },
                    )
                }.toMutableMap(),
            )
        }.getOrDefault(CachedProjectIndex())
    }

    private fun saveProjectCache(projectRoot: Path, projectCache: CachedProjectIndex) {
        val cacheFile = cacheFile(projectRoot)
        runCatching {
            cacheFile.parent?.createDirectories()
            val tempFile = cacheFile.resolveSibling("${cacheFile.fileName}.tmp")
            val payload = PersistedProjectIndex(
                schemaVersion = SCHEMA_VERSION,
                files = projectCache.files
                    .filterValues { it.contentHash != null }
                    .map { (path, file) ->
                        PersistedIndexedFile(
                            path = path.toString(),
                            moduleName = file.moduleName,
                            lastModifiedMillis = file.lastModifiedMillis ?: 0L,
                            fileSize = file.fileSize ?: 0L,
                            contentHash = file.contentHash.orEmpty(),
                            symbols = file.symbols.map { symbol ->
                                PersistedIndexedSymbol(
                                    id = symbol.id,
                                    name = symbol.name,
                                    fqName = symbol.fqName,
                                    kind = symbol.kind,
                                    path = symbol.path.toString(),
                                    uri = symbol.uri,
                                    range = symbol.range,
                                    selectionRange = symbol.selectionRange,
                                    containerName = symbol.containerName,
                                    containerFqName = symbol.containerFqName,
                                    signature = symbol.signature,
                                    documentation = symbol.documentation,
                                    packageName = symbol.packageName,
                                    moduleName = symbol.moduleName,
                                    importable = symbol.importable,
                                    receiverType = symbol.receiverType,
                                    resultType = symbol.resultType,
                                    parameterCount = symbol.parameterCount,
                                    supertypes = symbol.supertypes,
                                )
                            },
                        )
                    }
                    .sortedBy { it.path },
            )
            Json.mapper.writeValue(tempFile.toFile(), payload)
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun cacheFile(projectRoot: Path): Path =
        cacheRoot.resolve(projectKey(projectRoot)).resolve("lightweight-index.json")

    private fun projectKey(root: Path): String = sha256(root.normalize().toString()).take(24)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun fileState(path: Path): FileState =
        FileState(
            lastModifiedMillis = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L),
            fileSize = runCatching { Files.size(path) }.getOrDefault(0L),
        )

    private fun shouldReportProgress(current: Int, total: Int): Boolean =
        current == 1 || current == total || current % 50 == 0

    companion object {
        private const val SCHEMA_VERSION = 1

        private fun defaultIndexCacheRoot(): Path {
            val userHome = Path.of(System.getProperty("user.home"))
            val osName = System.getProperty("os.name").lowercase()
            return when {
                "mac" in osName -> userHome.resolve("Library/Caches/kotlin-neovim-lsp")
                else -> userHome.resolve(".cache/kotlin-neovim-lsp")
            }.resolve("lightweight-index")
        }
    }
}

private data class CachedIndexedFile(
    val moduleName: String,
    val lastModifiedMillis: Long? = null,
    val fileSize: Long? = null,
    val contentHash: String? = null,
    val openDocumentVersion: Int? = null,
    val symbols: List<IndexedSymbol> = emptyList(),
)

private data class CachedProjectIndex(
    val files: MutableMap<Path, CachedIndexedFile> = linkedMapOf(),
)

private data class FileState(
    val lastModifiedMillis: Long,
    val fileSize: Long,
)

private data class PersistedProjectIndex(
    val schemaVersion: Int,
    val files: List<PersistedIndexedFile>,
)

private data class PersistedIndexedFile(
    val path: String,
    val moduleName: String,
    val lastModifiedMillis: Long,
    val fileSize: Long,
    val contentHash: String,
    val symbols: List<PersistedIndexedSymbol>,
)

private data class PersistedIndexedSymbol(
    val id: String,
    val name: String,
    val fqName: String?,
    val kind: Int,
    val path: String,
    val uri: String,
    val range: dev.codex.kotlinls.protocol.Range,
    val selectionRange: dev.codex.kotlinls.protocol.Range,
    val containerName: String?,
    val containerFqName: String?,
    val signature: String,
    val documentation: String?,
    val packageName: String,
    val moduleName: String,
    val importable: Boolean,
    val receiverType: String?,
    val resultType: String?,
    val parameterCount: Int,
    val supertypes: List<String>,
)
