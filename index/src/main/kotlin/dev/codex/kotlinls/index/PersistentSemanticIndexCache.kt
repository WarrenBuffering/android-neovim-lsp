package dev.codex.kotlinls.index

import dev.codex.kotlinls.protocol.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class PersistentSemanticIndexCache(
    private val cacheRoot: Path = defaultSemanticCacheRoot(),
) {
    fun loadAll(projectRoot: Path): Map<String, WorkspaceIndex> {
        val projectDir = cacheRoot.resolve(projectKey(projectRoot))
        if (!projectDir.exists()) return emptyMap()
        return runCatching {
            Files.list(projectDir).use { children ->
                children.iterator().asSequence()
                    .filter { Files.isDirectory(it) }
                    .mapNotNull { moduleDir ->
                        val cacheFile = moduleDir.resolve("semantic-index.json")
                        if (!cacheFile.exists()) return@mapNotNull null
                        val persisted = runCatching {
                            Json.mapper.readValue(cacheFile.toFile(), PersistedSemanticIndexCache::class.java)
                        }.getOrNull() ?: return@mapNotNull null
                        if (persisted.schemaVersion != SCHEMA_VERSION) return@mapNotNull null
                        if (persisted.projectRoot != projectRoot.normalize().toString()) return@mapNotNull null
                        persisted.moduleGradlePath to persisted.toWorkspaceIndex()
                    }
                    .toList()
                    .toMap()
            }
        }.getOrDefault(emptyMap())
    }

    fun load(
        projectRoot: Path,
        moduleGradlePath: String,
        fingerprint: String,
    ): WorkspaceIndex? {
        val cacheFile = cacheFile(projectRoot, moduleGradlePath)
        if (!cacheFile.exists()) return null
        return runCatching {
            val persisted = Json.mapper.readValue(cacheFile.toFile(), PersistedSemanticIndexCache::class.java)
            if (persisted.schemaVersion != SCHEMA_VERSION) return null
            if (persisted.projectRoot != projectRoot.normalize().toString()) return null
            if (persisted.moduleGradlePath != moduleGradlePath) return null
            if (persisted.fingerprint != fingerprint) return null
            persisted.toWorkspaceIndex()
        }.getOrNull()
    }

    fun save(
        projectRoot: Path,
        moduleGradlePath: String,
        fingerprint: String,
        index: WorkspaceIndex,
    ) {
        val cacheFile = cacheFile(projectRoot, moduleGradlePath)
        runCatching {
            cacheFile.parent?.createDirectories()
            val tempFile = cacheFile.resolveSibling("${cacheFile.fileName}.tmp")
            Json.mapper.writeValue(
                tempFile.toFile(),
                PersistedSemanticIndexCache(
                    schemaVersion = SCHEMA_VERSION,
                    fingerprint = fingerprint,
                    projectRoot = projectRoot.normalize().toString(),
                    moduleGradlePath = moduleGradlePath,
                    symbols = index.symbols.map { symbol ->
                        PersistedSemanticIndexedSymbol(
                            id = symbol.id,
                            name = symbol.name,
                            fqName = symbol.fqName,
                            kind = symbol.kind,
                            path = symbol.path.normalize().toString(),
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
                    references = index.references.map { reference ->
                        PersistedSemanticIndexedReference(
                            symbolId = reference.symbolId,
                            path = reference.path.normalize().toString(),
                            uri = reference.uri,
                            range = reference.range,
                            containerSymbolId = reference.containerSymbolId,
                        )
                    },
                    callEdges = index.callEdges.map { edge ->
                        PersistedSemanticCallEdge(
                            callerSymbolId = edge.callerSymbolId,
                            calleeSymbolId = edge.calleeSymbolId,
                            range = edge.range,
                            path = edge.path.normalize().toString(),
                        )
                    },
                ),
            )
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun cacheFile(projectRoot: Path, moduleGradlePath: String): Path =
        cacheRoot.resolve(projectKey(projectRoot)).resolve(moduleKey(moduleGradlePath)).resolve("semantic-index.json")

    private fun projectKey(root: Path): String = sha256(root.normalize().toString()).take(24)

    private fun moduleKey(moduleGradlePath: String): String = sha256(moduleGradlePath).take(16)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val SCHEMA_VERSION = 1

        private fun defaultSemanticCacheRoot(): Path {
            val userHome = Path.of(System.getProperty("user.home"))
            val osName = System.getProperty("os.name").lowercase()
            return when {
                "mac" in osName -> userHome.resolve("Library/Caches/android-neovim-lsp")
                else -> userHome.resolve(".cache/android-neovim-lsp")
            }.resolve("semantic-index")
        }
    }
}

private data class PersistedSemanticIndexCache(
    val schemaVersion: Int,
    val fingerprint: String,
    val projectRoot: String,
    val moduleGradlePath: String,
    val symbols: List<PersistedSemanticIndexedSymbol>,
    val references: List<PersistedSemanticIndexedReference>,
    val callEdges: List<PersistedSemanticCallEdge>,
) {
    fun toWorkspaceIndex(): WorkspaceIndex =
        WorkspaceIndex(
            symbols = symbols.map { symbol ->
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
            references = references.map { reference ->
                IndexedReference(
                    symbolId = reference.symbolId,
                    path = Path.of(reference.path),
                    uri = reference.uri,
                    range = reference.range,
                    containerSymbolId = reference.containerSymbolId,
                )
            },
            callEdges = callEdges.map { edge ->
                CallEdge(
                    callerSymbolId = edge.callerSymbolId,
                    calleeSymbolId = edge.calleeSymbolId,
                    range = edge.range,
                    path = Path.of(edge.path),
                )
            },
        )
}

private data class PersistedSemanticIndexedSymbol(
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

private data class PersistedSemanticIndexedReference(
    val symbolId: String,
    val path: String,
    val uri: String,
    val range: dev.codex.kotlinls.protocol.Range,
    val containerSymbolId: String?,
)

private data class PersistedSemanticCallEdge(
    val callerSymbolId: String,
    val calleeSymbolId: String,
    val range: dev.codex.kotlinls.protocol.Range,
    val path: String,
)
