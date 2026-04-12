package dev.codex.kotlinls.index

import dev.codex.kotlinls.projectimport.ImportedProject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

class SupportSymbolIndexBuilder(
    private val externalSourceMirror: ExternalSourceMirror = ExternalSourceMirror(),
    private val persistentSupportCache: PersistentSupportSymbolCache = PersistentSupportSymbolCache(),
    private val kotlinSourceIndexer: KotlinSourceIndexer = KotlinSourceIndexer(),
) {
    private val cacheLock = Any()
    private val supportSymbolLayers = linkedMapOf<Path, SupportSymbolLayer>()

    fun load(project: ImportedProject): SupportSymbolLayer? {
        val projectRoot = project.root.normalize()
        val fingerprint = supportLayerFingerprint(project)
        cachedLayer(projectRoot, fingerprint)?.let { return it }
        val persisted = persistentSupportCache.load(projectRoot, fingerprint) ?: return null
        synchronized(cacheLock) {
            supportSymbolLayers[projectRoot] = persisted
        }
        return persisted
    }

    fun loadPackages(
        project: ImportedProject,
        packageNames: Set<String>,
    ): SupportSymbolLayer? {
        val projectRoot = project.root.normalize()
        val fingerprint = supportLayerFingerprint(project)
        val cached = cachedLayer(projectRoot, fingerprint)
        if (cached != null) {
            val requested = packageNames.map(String::trim).toSet()
            return cached.copy(
                symbols = cached.symbols.filter { symbol -> symbol.packageName.trim() in requested },
            )
        }
        return persistentSupportCache.loadPackages(projectRoot, fingerprint, packageNames)
    }

    fun build(project: ImportedProject): SupportSymbolLayer {
        val projectRoot = project.root.normalize()
        val fingerprint = supportLayerFingerprint(project)
        cachedLayer(projectRoot, fingerprint)?.let { return it }
        persistentSupportCache.load(projectRoot, fingerprint)?.let { cached ->
            synchronized(cacheLock) {
                supportSymbolLayers[projectRoot] = cached
            }
            return cached
        }

        val computed = SupportSymbolLayer(
            fingerprint = fingerprint,
            symbols = buildJavaSourceSymbols(project) +
                buildExternalLibrarySymbols(project) +
                buildJdkSourceSymbols(),
        )
        synchronized(cacheLock) {
            supportSymbolLayers[projectRoot] = computed
        }
        persistentSupportCache.save(projectRoot, computed)
        return computed
    }

    fun loadOrBuild(
        project: ImportedProject,
        allowCached: Boolean = true,
    ): SupportSymbolLayer =
        if (allowCached) {
            load(project) ?: build(project)
        } else {
            build(project)
        }

    private fun cachedLayer(projectRoot: Path, fingerprint: String): SupportSymbolLayer? =
        synchronized(cacheLock) {
            supportSymbolLayers[projectRoot]
                ?.takeIf { it.fingerprint == fingerprint }
        }

    private fun supportLayerFingerprint(project: ImportedProject): String =
        buildString {
            project.modules.sortedBy { it.gradlePath }.forEach { module ->
                append(module.gradlePath)
                append('|')
                module.javaSourceRoots.sortedBy(Path::toString).forEach { root ->
                    appendTreeFingerprint(this, "java", root.normalize())
                }
                append('|')
                module.classpathSourceJars.sortedBy(Path::toString).forEach { jar ->
                    appendFileFingerprint(this, "sourceJar", jar.normalize())
                }
                append('\n')
            }
            defaultJdkSourceArchive()?.let { srcZip ->
                appendFileFingerprint(this, "jdk", srcZip.normalize())
            }
        }

    private fun buildJavaSourceSymbols(project: ImportedProject): List<IndexedSymbol> =
        project.modules.flatMap { module ->
            module.javaSourceRoots.flatMap { root ->
                if (!root.toFile().exists()) return@flatMap emptyList()
                root.walk()
                    .filter { it.isRegularFile() && it.extension == "java" }
                    .flatMap { path -> JavaSourceIndexer.index(path, module.name) }
                    .toList()
            }
        }

    private fun buildExternalLibrarySymbols(project: ImportedProject): List<IndexedSymbol> =
        project.modules
            .flatMap { module -> module.classpathSourceJars.map { jar -> module.name to jar } }
            .distinctBy { (_, jar) -> jar.normalize() }
            .flatMap { (moduleName, jar) ->
                val extractedRoot = runCatching { externalSourceMirror.materialize(jar) }.getOrNull() ?: return@flatMap emptyList()
                extractedRoot.walk()
                    .filter { it.isRegularFile() && it.extension in setOf("kt", "kts", "java") }
                    .flatMap { path ->
                        when (path.extension) {
                            "java" -> JavaSourceIndexer.index(path, moduleName)
                            else -> {
                                val text = runCatching { Files.readString(path) }.getOrNull() ?: return@flatMap emptyList()
                                kotlinSourceIndexer.index(path, moduleName, text)
                            }
                        }
                    }
                    .toList()
            }

    private fun buildJdkSourceSymbols(): List<IndexedSymbol> {
        val srcZip = defaultJdkSourceArchive() ?: return emptyList()
        val extractedRoot = runCatching { externalSourceMirror.materialize(srcZip) }.getOrNull() ?: return emptyList()
        return extractedRoot.walk()
            .filter { path ->
                path.isRegularFile() &&
                    path.extension == "java" &&
                    path.toString().contains("/java.base/java/") &&
                    jdkPackageAllowed(path)
            }
            .flatMap { path -> JavaSourceIndexer.index(path, "jdk") }
            .toList()
    }

    private fun defaultJdkSourceArchive(): Path? {
        val javaHome = Path.of(System.getProperty("java.home"))
        val candidates = listOf(
            javaHome.resolve("lib/src.zip"),
            javaHome.resolve("../lib/src.zip").normalize(),
            javaHome.resolve("../../lib/src.zip").normalize(),
        )
        return candidates.firstOrNull { it.isRegularFile() }
    }

    private fun jdkPackageAllowed(path: Path): Boolean {
        val normalized = path.toString().replace('\\', '/')
        return listOf(
            "/java.base/java/lang/",
            "/java.base/java/util/",
            "/java.base/java/io/",
            "/java.base/java/nio/",
            "/java.base/java/time/",
            "/java.base/java/math/",
        ).any(normalized::contains)
    }

    private fun appendTreeFingerprint(builder: StringBuilder, label: String, root: Path) {
        val normalizedRoot = root.normalize()
        builder.append(label).append('=').append(normalizedRoot).append('|')
        if (!Files.exists(normalizedRoot)) {
            builder.append("missing").append(';')
            return
        }
        normalizedRoot.walk()
            .filter { it.isRegularFile() && it.extension == "java" }
            .sortedBy(Path::toString)
            .forEach { path ->
                appendFileFingerprint(builder, label, path.normalize())
            }
    }

    private fun appendFileFingerprint(builder: StringBuilder, label: String, path: Path) {
        val normalized = path.normalize()
        builder.append(label).append(':').append(normalized).append(':')
        builder.append(runCatching { Files.size(normalized) }.getOrDefault(0L))
        builder.append(':')
        builder.append(runCatching { Files.getLastModifiedTime(normalized).toMillis() }.getOrDefault(0L))
        builder.append(';')
    }
}

data class SupportSymbolLayer(
    val fingerprint: String,
    val symbols: List<IndexedSymbol>,
)
