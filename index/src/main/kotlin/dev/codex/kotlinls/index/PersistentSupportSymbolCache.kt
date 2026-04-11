package dev.codex.kotlinls.index

import dev.codex.kotlinls.protocol.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class PersistentSupportSymbolCache(
    private val cacheRoot: Path = defaultSupportCacheRoot(),
) {
    fun load(
        projectRoot: Path,
        fingerprint: String,
    ): SupportSymbolLayer? {
        val cacheFile = cacheFile(projectRoot)
        if (!cacheFile.exists()) return null
        return runCatching {
            val persisted = Json.mapper.readValue(cacheFile.toFile(), PersistedSupportSymbolCache::class.java)
            if (persisted.schemaVersion != SCHEMA_VERSION) return null
            if (persisted.projectRoot != projectRoot.normalize().toString()) return null
            if (persisted.fingerprint != fingerprint) return null
            persisted.toSupportSymbolLayer()
        }.getOrNull()
    }

    fun save(
        projectRoot: Path,
        layer: SupportSymbolLayer,
    ) {
        val cacheFile = cacheFile(projectRoot)
        runCatching {
            cacheFile.parent?.createDirectories()
            val tempFile = cacheFile.resolveSibling("${cacheFile.fileName}.tmp")
            Json.mapper.writeValue(
                tempFile.toFile(),
                PersistedSupportSymbolCache(
                    schemaVersion = SCHEMA_VERSION,
                    fingerprint = layer.fingerprint,
                    projectRoot = projectRoot.normalize().toString(),
                    symbols = layer.symbols.map { symbol ->
                        PersistedSupportIndexedSymbol(
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
                ),
            )
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun cacheFile(projectRoot: Path): Path =
        cacheRoot.resolve(projectKey(projectRoot)).resolve("support-symbols.json")

    private fun projectKey(root: Path): String = sha256(root.normalize().toString()).take(24)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val SCHEMA_VERSION = 1

        private fun defaultSupportCacheRoot(): Path {
            val userHome = Path.of(System.getProperty("user.home"))
            val osName = System.getProperty("os.name").lowercase()
            return when {
                "mac" in osName -> userHome.resolve("Library/Caches/kotlin-neovim-lsp")
                else -> userHome.resolve(".cache/kotlin-neovim-lsp")
            }.resolve("support-index")
        }
    }
}

private data class PersistedSupportSymbolCache(
    val schemaVersion: Int,
    val fingerprint: String,
    val projectRoot: String,
    val symbols: List<PersistedSupportIndexedSymbol>,
) {
    fun toSupportSymbolLayer(): SupportSymbolLayer =
        SupportSymbolLayer(
            fingerprint = fingerprint,
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
        )
}

private data class PersistedSupportIndexedSymbol(
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
