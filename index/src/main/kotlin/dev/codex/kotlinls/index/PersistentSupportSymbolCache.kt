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
        val manifest = loadManifest(projectRoot, fingerprint) ?: return null
        return runCatching {
            SupportSymbolLayer(
                fingerprint = manifest.fingerprint,
                symbols = manifest.packages.flatMap { shard ->
                    loadPackageSymbols(packageFile(projectRoot, shard.fileName))
                },
            )
        }.getOrNull()
    }

    fun loadPackages(
        projectRoot: Path,
        fingerprint: String,
        packageNames: Set<String>,
    ): SupportSymbolLayer? {
        val manifest = loadManifest(projectRoot, fingerprint) ?: return null
        val requested = packageNames.map(::normalizePackageName).toSet()
        if (requested.isEmpty()) {
            return SupportSymbolLayer(fingerprint = manifest.fingerprint, symbols = emptyList())
        }
        return runCatching {
            SupportSymbolLayer(
                fingerprint = manifest.fingerprint,
                symbols = manifest.packages
                    .asSequence()
                    .filter { shard -> normalizePackageName(shard.packageName) in requested }
                    .flatMap { shard ->
                        loadPackageSymbols(packageFile(projectRoot, shard.fileName)).asSequence()
                    }
                    .toList(),
            )
        }.getOrNull()
    }

    fun save(
        projectRoot: Path,
        layer: SupportSymbolLayer,
    ) {
        val cacheFile = cacheFile(projectRoot)
        runCatching {
            cacheFile.parent?.createDirectories()
            val packagesRoot = packageRoot(projectRoot)
            packagesRoot.toFile().deleteRecursively()
            packagesRoot.createDirectories()
            val packageEntries = layer.symbols
                .groupBy { normalizePackageName(it.packageName) }
                .toSortedMap()
                .map { (packageName, symbols) ->
                    val fileName = "${sha256(packageName).take(16)}.json"
                    val packageFile = packageFile(projectRoot, fileName)
                    Json.mapper.writeValue(
                        packageFile.toFile(),
                        symbols.map { symbol ->
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
                    )
                    PersistedSupportPackageShard(
                        packageName = packageName,
                        fileName = fileName,
                        symbolCount = symbols.size,
                    )
                }
            val tempFile = cacheFile.resolveSibling("${cacheFile.fileName}.tmp")
            Json.mapper.writeValue(
                tempFile.toFile(),
                PersistedSupportSymbolCache(
                    schemaVersion = SCHEMA_VERSION,
                    fingerprint = layer.fingerprint,
                    projectRoot = projectRoot.normalize().toString(),
                    packages = packageEntries,
                ),
            )
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun cacheFile(projectRoot: Path): Path =
        cacheRoot.resolve(projectKey(projectRoot)).resolve("support-symbols.json")

    private fun packageRoot(projectRoot: Path): Path =
        cacheRoot.resolve(projectKey(projectRoot)).resolve("packages")

    private fun packageFile(projectRoot: Path, fileName: String): Path =
        packageRoot(projectRoot).resolve(fileName)

    private fun projectKey(root: Path): String = sha256(root.normalize().toString()).take(24)

    private fun loadManifest(
        projectRoot: Path,
        fingerprint: String,
    ): PersistedSupportSymbolCache? {
        val cacheFile = cacheFile(projectRoot)
        if (!cacheFile.exists()) return null
        return runCatching {
            Json.mapper.readValue(cacheFile.toFile(), PersistedSupportSymbolCache::class.java)
        }.getOrNull()
            ?.takeIf { it.schemaVersion == SCHEMA_VERSION }
            ?.takeIf { it.projectRoot == projectRoot.normalize().toString() }
            ?.takeIf { it.fingerprint == fingerprint }
    }

    private fun loadPackageSymbols(packageFile: Path): List<IndexedSymbol> =
        Json.mapper.readValue(packageFile.toFile(), Array<PersistedSupportIndexedSymbol>::class.java)
            .map { symbol ->
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
            }

    private fun normalizePackageName(value: String): String = value.trim()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val SCHEMA_VERSION = 2

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
    val packages: List<PersistedSupportPackageShard>,
)

private data class PersistedSupportPackageShard(
    val packageName: String,
    val fileName: String,
    val symbolCount: Int,
)

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
