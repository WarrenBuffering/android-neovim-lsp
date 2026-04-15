package dev.codex.kotlinls.index

import dev.codex.kotlinls.projectimport.ImportedProject
import dev.codex.kotlinls.protocol.Json
import dev.codex.kotlinls.workspace.TextDocumentStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
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
        val rootEntries = projectRoots(project)
        var dirty = pruneStaleRootManifests(projectCache, rootEntries)
        val manifestReady = hasCompleteRootManifests(projectCache, rootEntries)
        val manifestsFresh = manifestReady && rootEntries.none { entry ->
            rootManifestHasUntrackedSources(
                root = entry.root,
                knownPaths = projectCache.roots[entry.root]
                    ?.filePaths
                    .orEmpty()
                    .asSequence()
                    .map(Path::normalize)
                    .toSet(),
            )
        }
        val (workItems, manifestDirty) = if (manifestReady && manifestsFresh) {
            workItemsFromManifests(projectCache, rootEntries)
        } else {
            scanProjectRoots(rootEntries, projectCache)
        }
        dirty = dirty || manifestDirty

        val liveFiles = linkedSetOf<Path>()
        val symbols = mutableListOf<IndexedSymbol>()
        val totalFiles = workItems.size.coerceAtLeast(1)
        workItems.forEachIndexed { index, workItem ->
            liveFiles.add(workItem.path)
            val (indexedSymbols, fileDirty) = indexSymbols(projectCache, workItem, documents)
            symbols += indexedSymbols
            dirty = dirty || fileDirty
            if (shouldReportProgress(index + 1, totalFiles)) {
                progress?.invoke("Indexed ${workItem.path.fileName}", index + 1, totalFiles)
            }
        }

        synchronized(cacheLock) {
            val stale = projectCache.files.keys - liveFiles
            if (stale.isNotEmpty()) dirty = true
            stale.forEach(projectCache.files::remove)
            projectCache.roots.values.forEach { manifest ->
                if (manifest.filePaths.removeIf { candidate -> candidate !in liveFiles }) {
                    dirty = true
                }
            }
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

    fun load(project: ImportedProject): WorkspaceIndex? {
        val projectRoot = project.root.normalize()
        val projectCache = synchronized(cacheLock) {
            projectCaches.getOrPut(projectRoot) { loadProjectCache(projectRoot) }
        }
        val rootEntries = projectRoots(project)
        val files = when {
            projectCache.files.isEmpty() -> return null
            hasCompleteRootManifests(projectCache, rootEntries) -> rootEntries.flatMap { entry ->
                projectCache.roots[entry.root]?.filePaths.orEmpty()
            }

            else -> projectCache.files.keys.toList()
        }
        if (files.isEmpty()) return null
        return WorkspaceIndex(
            symbols = files.asSequence()
                .distinct()
                .mapNotNull { path -> projectCache.files[path.normalize()] }
                .flatMap { it.symbols.asSequence() }
                .toList()
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.name }, { it.path.toString() })),
            references = emptyList(),
            callEdges = emptyList(),
        )
    }

    fun requiresBackgroundRefresh(project: ImportedProject): Boolean {
        val projectRoot = project.root.normalize()
        val projectCache = synchronized(cacheLock) {
            projectCaches.getOrPut(projectRoot) { loadProjectCache(projectRoot) }
        }
        if (projectCache.files.isEmpty()) return true
        val rootEntries = projectRoots(project)
        if (!hasCompleteRootManifests(projectCache, rootEntries)) return true
        return rootEntries.any { entry ->
            val manifest = projectCache.roots[entry.root] ?: return@any true
            manifest.filePaths.any { candidate -> candidate !in projectCache.files } ||
                rootManifestHasUntrackedSources(
                    root = entry.root,
                    knownPaths = manifest.filePaths.asSequence().map(Path::normalize).toSet(),
                )
        }
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

    fun hydratePackageNeighborhood(
        project: ImportedProject,
        packageName: String,
        currentIndex: WorkspaceIndex,
        documents: TextDocumentStore,
        preferredPath: Path? = null,
    ): WorkspaceIndex {
        if (packageName.isBlank()) return currentIndex
        val projectRoot = project.root.normalize()
        val projectCache = synchronized(cacheLock) {
            projectCaches.getOrPut(projectRoot) { loadProjectCache(projectRoot) }
        }
        val preferredModuleName = preferredPath
            ?.normalize()
            ?.let(project::moduleForPath)
            ?.name
        val workItems = packageNeighborhoodWorkItems(project, packageName, preferredModuleName)
        if (workItems.isEmpty()) return currentIndex

        var dirty = false
        var contentChanged = false
        val indexedByPath = linkedMapOf<Path, List<IndexedSymbol>>()
        workItems.forEach { workItem ->
            val normalized = workItem.path.normalize()
            val (indexedSymbols, fileDirty) = indexSymbols(projectCache, workItem, documents)
            indexedByPath[normalized] = indexedSymbols
            dirty = dirty || fileDirty
            if (currentIndex.symbolsByPath[normalized].orEmpty() != indexedSymbols) {
                contentChanged = true
            }
        }

        synchronized(cacheLock) {
            workItems.forEach { workItem ->
                if (Files.exists(workItem.path)) {
                    dirty = updateRootManifestsForPath(projectCache, project, workItem.moduleName, workItem.path) || dirty
                }
            }
        }

        if (dirty) {
            saveProjectCache(projectRoot, projectCache)
        }
        if (!contentChanged) return currentIndex

        val affectedPaths = indexedByPath.keys
        val mergedSymbols = buildList {
            addAll(currentIndex.symbols.filter { it.path.normalize() !in affectedPaths })
            indexedByPath.values.forEach(::addAll)
        }
        return WorkspaceIndex(
            symbols = mergedSymbols.distinctBy { it.id }.sortedWith(compareBy({ it.name }, { it.path.toString() })),
            references = currentIndex.references,
            callEdges = currentIndex.callEdges,
        )
    }

    fun persistDocument(
        project: ImportedProject,
        path: Path,
        text: String,
    ) {
        val normalized = path.normalize()
        val module = project.moduleForPath(normalized) ?: return
        val projectRoot = project.root.normalize()
        val parsed = parseSymbols(normalized, module.name, text)
        val fileState = fileState(normalized)
        val contentHash = sha256(text)
        val projectCache = synchronized(cacheLock) {
            projectCaches.getOrPut(projectRoot) { loadProjectCache(projectRoot) }.also { cache ->
                cache.files[normalized] = CachedIndexedFile(
                    moduleName = module.name,
                    lastModifiedMillis = fileState.lastModifiedMillis,
                    fileSize = fileState.fileSize,
                    contentHash = contentHash,
                    openDocumentVersion = null,
                    symbols = parsed,
                )
                updateRootManifestsForPath(cache, project, module.name, normalized)
            }
        }
        saveProjectCache(projectRoot, projectCache)
    }

    private fun indexSymbols(
        projectCache: CachedProjectIndex,
        workItem: IndexedWorkItem,
        documents: TextDocumentStore,
    ): Pair<List<IndexedSymbol>, Boolean> {
        val normalized = workItem.path.normalize()
        val document = documents.get(normalized.toUri().toString())
        val cached = synchronized(cacheLock) { projectCache.files[normalized] }
        if (document != null) {
            val parsed = parseSymbols(normalized, workItem.moduleName, document.text)
            synchronized(cacheLock) {
                projectCache.files[normalized] = (cached ?: CachedIndexedFile(moduleName = workItem.moduleName))
                    .copy(openDocumentVersion = document.version, symbols = parsed)
            }
            return parsed to false
        }

        val fileState = fileState(normalized)
        val reused = if (
            cached != null &&
            cached.moduleName == workItem.moduleName &&
            cached.lastModifiedMillis == fileState.lastModifiedMillis &&
            cached.fileSize == fileState.fileSize
        ) {
            cached.symbols
        } else {
            null
        }
        if (reused != null) {
            return reused to false
        }

        val text = runCatching { normalized.readText() }.getOrDefault("")
        val contentHash = sha256(text)
        val parsed = if (
            cached != null &&
            cached.moduleName == workItem.moduleName &&
            cached.contentHash == contentHash
        ) {
            cached.symbols
        } else {
            parseSymbols(normalized, workItem.moduleName, text)
        }
        synchronized(cacheLock) {
            projectCache.files[normalized] = CachedIndexedFile(
                moduleName = workItem.moduleName,
                lastModifiedMillis = fileState.lastModifiedMillis,
                fileSize = fileState.fileSize,
                contentHash = contentHash,
                openDocumentVersion = null,
                symbols = parsed,
            )
        }
        return parsed to true
    }

    private fun workItemsFromManifests(
        projectCache: CachedProjectIndex,
        rootEntries: List<RootEntry>,
    ): Pair<List<IndexedWorkItem>, Boolean> {
        var dirty = false
        val workItems = buildList {
            rootEntries.forEach { entry ->
                val manifest = projectCache.roots[entry.root] ?: return@forEach
                val existingPaths = manifest.filePaths
                    .map(Path::normalize)
                    .filter { path ->
                        val exists = Files.exists(path)
                        if (!exists) dirty = true
                        exists
                    }
                    .distinct()
                    .sortedBy(Path::toString)
                existingPaths.forEach { path ->
                    add(IndexedWorkItem(moduleName = entry.moduleName, path = path))
                }
            }
        }
        return workItems to dirty
    }

    private fun scanProjectRoots(
        rootEntries: List<RootEntry>,
        projectCache: CachedProjectIndex,
    ): Pair<List<IndexedWorkItem>, Boolean> {
        val workItems = buildList {
            rootEntries.forEach { entry ->
                val files = if (!Files.exists(entry.root)) {
                    emptyList()
                } else {
                    entry.root.walk()
                        .filter { path -> path.isRegularFile() && path.extension in setOf("kt", "kts", "java") }
                        .map(Path::normalize)
                        .sortedBy(Path::toString)
                        .toList()
                }
                synchronized(cacheLock) {
                    projectCache.roots[entry.root] = CachedRootManifest(
                        moduleName = entry.moduleName,
                        rootKind = entry.rootKind,
                        filePaths = files.toMutableList(),
                    )
                }
                files.forEach { path ->
                    add(IndexedWorkItem(moduleName = entry.moduleName, path = path))
                }
            }
        }
        return workItems to true
    }

    private fun updateRootManifestsForPath(
        projectCache: CachedProjectIndex,
        project: ImportedProject,
        moduleName: String,
        path: Path,
    ): Boolean {
        var changed = false
        projectRoots(project)
            .filter { entry -> entry.moduleName == moduleName && path.startsWith(entry.root) }
            .forEach { entry ->
                val manifest = projectCache.roots.getOrPut(entry.root) {
                    CachedRootManifest(
                        moduleName = entry.moduleName,
                        rootKind = entry.rootKind,
                        filePaths = mutableListOf(),
                    )
                }
                if (path !in manifest.filePaths) {
                    manifest.filePaths.add(path)
                    manifest.filePaths.sortBy(Path::toString)
                    changed = true
                }
            }
        return changed
    }

    private fun packageNeighborhoodWorkItems(
        project: ImportedProject,
        packageName: String,
        preferredModuleName: String?,
    ): List<IndexedWorkItem> =
        orderedProjectRoots(project, preferredModuleName)
            .asSequence()
            .flatMap { entry ->
                packageNeighborhoodFiles(entry.root, packageName)
                    .asSequence()
                    .map { path -> IndexedWorkItem(moduleName = entry.moduleName, path = path) }
            }
            .distinctBy { it.path.normalize() }
            .sortedBy { it.path.toString() }
            .toList()

    private fun orderedProjectRoots(
        project: ImportedProject,
        preferredModuleName: String?,
    ): List<RootEntry> =
        projectRoots(project).sortedWith(
            compareByDescending<RootEntry> { it.moduleName == preferredModuleName }
                .thenBy { it.root.toString() },
        )

    private fun packageNeighborhoodFiles(root: Path, packageName: String): List<Path> {
        val baseDirectory = root.resolve(packageName.replace('.', '/')).normalize()
        if (!Files.exists(baseDirectory)) return emptyList()
        val directories = buildList {
            add(baseDirectory)
            addAll(immediateDirectories(baseDirectory))
        }
        return directories
            .asSequence()
            .flatMap { directory -> immediateSourceFiles(directory).asSequence() }
            .distinct()
            .sortedBy(Path::toString)
            .toList()
    }

    private fun immediateDirectories(path: Path): List<Path> =
        runCatching {
            Files.list(path).use { entries ->
                entries
                    .filter(Files::isDirectory)
                    .map(Path::normalize)
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())

    private fun immediateSourceFiles(path: Path): List<Path> =
        runCatching {
            Files.list(path).use { entries ->
                entries
                    .filter { candidate -> candidate.isRegularFile() && candidate.extension in SOURCE_FILE_EXTENSIONS }
                    .map(Path::normalize)
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())

    private fun pruneStaleRootManifests(
        projectCache: CachedProjectIndex,
        rootEntries: List<RootEntry>,
    ): Boolean {
        val validRoots = rootEntries.associateBy { it.root }
        var dirty = false
        synchronized(cacheLock) {
            val staleRoots = projectCache.roots.keys.filter { root ->
                val entry = validRoots[root] ?: return@filter true
                val manifest = projectCache.roots[root] ?: return@filter true
                manifest.moduleName != entry.moduleName || manifest.rootKind != entry.rootKind
            }
            if (staleRoots.isNotEmpty()) dirty = true
            staleRoots.forEach(projectCache.roots::remove)
        }
        return dirty
    }

    private fun hasCompleteRootManifests(
        projectCache: CachedProjectIndex,
        rootEntries: List<RootEntry>,
    ): Boolean =
        rootEntries.isNotEmpty() && rootEntries.all { entry ->
            val manifest = projectCache.roots[entry.root] ?: return@all false
            manifest.moduleName == entry.moduleName && manifest.rootKind == entry.rootKind
        }

    private fun rootManifestHasUntrackedSources(
        root: Path,
        knownPaths: Set<Path>,
    ): Boolean {
        if (!Files.exists(root)) return false
        return root.walk()
            .filter { path -> path.isRegularFile() && path.extension in SOURCE_FILE_EXTENSIONS }
            .map(Path::normalize)
            .any { path -> path !in knownPaths }
    }

    private fun projectRoots(project: ImportedProject): List<RootEntry> =
        buildList {
            project.modules.forEach { module ->
                module.sourceRoots.forEach { root ->
                    add(RootEntry(module.name, "kotlin", root.normalize()))
                }
                module.javaSourceRoots.forEach { root ->
                    add(RootEntry(module.name, "java", root.normalize()))
                }
            }
        }.distinctBy { entry -> entry.root }

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
                                parameters = symbol.parameters,
                                enumEntries = symbol.enumEntries,
                                enumValue = symbol.enumValue,
                            )
                        },
                    )
                }.toMutableMap(),
                roots = persisted.roots.associate { root ->
                    Path.of(root.root).normalize() to CachedRootManifest(
                        moduleName = root.moduleName,
                        rootKind = root.rootKind,
                        filePaths = root.filePaths.map(Path::of).map(Path::normalize).toMutableList(),
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
                roots = projectCache.roots
                    .map { (root, manifest) ->
                        PersistedRootManifest(
                            root = root.toString(),
                            moduleName = manifest.moduleName,
                            rootKind = manifest.rootKind,
                            filePaths = manifest.filePaths.map(Path::toString).sorted(),
                        )
                    }
                    .sortedBy { it.root },
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
                                    parameters = symbol.parameters,
                                    enumEntries = symbol.enumEntries,
                                    enumValue = symbol.enumValue,
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
        private const val SCHEMA_VERSION = 5
        private val SOURCE_FILE_EXTENSIONS = setOf("kt", "kts", "java")

        private fun defaultIndexCacheRoot(): Path {
            val userHome = Path.of(System.getProperty("user.home"))
            val osName = System.getProperty("os.name").lowercase()
            return when {
                "mac" in osName -> userHome.resolve("Library/Caches/android-neovim-lsp")
                else -> userHome.resolve(".cache/android-neovim-lsp")
            }.resolve("lightweight-index")
        }
    }
}

private data class RootEntry(
    val moduleName: String,
    val rootKind: String,
    val root: Path,
)

private data class IndexedWorkItem(
    val moduleName: String,
    val path: Path,
)

private data class CachedIndexedFile(
    val moduleName: String,
    val lastModifiedMillis: Long? = null,
    val fileSize: Long? = null,
    val contentHash: String? = null,
    val openDocumentVersion: Int? = null,
    val symbols: List<IndexedSymbol> = emptyList(),
)

private data class CachedRootManifest(
    val moduleName: String,
    val rootKind: String,
    val filePaths: MutableList<Path> = mutableListOf(),
)

private data class CachedProjectIndex(
    val files: MutableMap<Path, CachedIndexedFile> = linkedMapOf(),
    val roots: MutableMap<Path, CachedRootManifest> = linkedMapOf(),
)

private data class FileState(
    val lastModifiedMillis: Long,
    val fileSize: Long,
)

private data class PersistedProjectIndex(
    val schemaVersion: Int,
    val roots: List<PersistedRootManifest> = emptyList(),
    val files: List<PersistedIndexedFile> = emptyList(),
)

private data class PersistedRootManifest(
    val root: String,
    val moduleName: String,
    val rootKind: String,
    val filePaths: List<String>,
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
    val parameters: List<IndexedParameter> = emptyList(),
    val enumEntries: List<IndexedEnumEntry> = emptyList(),
    val enumValue: IndexedEnumEntry? = null,
)
