package dev.codex.kotlinls.index

import dev.codex.kotlinls.projectimport.ImportedProject
import dev.codex.kotlinls.projectimport.StableArtifactFingerprint
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

class SupportSymbolIndexBuilder(
    private val externalSourceMirror: ExternalSourceMirror = ExternalSourceMirror(),
    private val persistentSupportCache: PersistentSupportSymbolCache = PersistentSupportSymbolCache(),
    private val kotlinSourceIndexer: KotlinSourceIndexer = KotlinSourceIndexer(),
    private val binaryClasspathSymbolIndexer: BinaryClasspathSymbolIndexer = BinaryClasspathSymbolIndexer(),
) {
    private val cacheLock = Any()
    private val supportSymbolLayers = linkedMapOf<Path, SupportSymbolLayer>()

    fun load(project: ImportedProject): SupportSymbolLayer? {
        val projectRoot = project.root.normalize()
        val fingerprint = supportLayerFingerprint(project)
        cachedLayer(projectRoot, fingerprint)?.let { cached ->
            if (sourcePathsPresent(cached)) return cached
            clearCachedLayer(projectRoot)
        }
        val persisted = persistentSupportCache.load(projectRoot, fingerprint)
            ?.takeIf(::sourcePathsPresent)
            ?: return null
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
            if (!sourcePathsPresent(cached)) {
                clearCachedLayer(projectRoot)
                return build(project).filterPackages(packageNames)
            }
            val requested = packageNames.map(String::trim).toSet()
            return cached.filterPackages(requested)
        }
        val persisted = persistentSupportCache.loadPackages(projectRoot, fingerprint, packageNames)
        if (persisted != null && sourcePathsPresent(persisted)) {
            return persisted
        }
        return build(project).filterPackages(packageNames)
    }

    fun build(project: ImportedProject): SupportSymbolLayer {
        val projectRoot = project.root.normalize()
        val fingerprint = supportLayerFingerprint(project)
        cachedLayer(projectRoot, fingerprint)?.let { cached ->
            if (sourcePathsPresent(cached)) return cached
            clearCachedLayer(projectRoot)
        }
        persistentSupportCache.load(projectRoot, fingerprint)?.takeIf(::sourcePathsPresent)?.let { cached ->
            synchronized(cacheLock) {
                supportSymbolLayers[projectRoot] = cached
            }
            return cached
        }

        val computed = SupportSymbolLayer(
            fingerprint = fingerprint,
            symbols = (
                buildJavaSourceSymbols(project) +
                    buildExternalLibrarySymbols(project) +
                    buildBinaryLibrarySymbols(project) +
                    buildJdkSourceSymbols().ifEmpty { buildJdkBinarySymbols() }
                ).distinctBy { it.id },
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

    private fun clearCachedLayer(projectRoot: Path) {
        synchronized(cacheLock) {
            supportSymbolLayers.remove(projectRoot)
        }
    }

    private fun sourcePathsPresent(layer: SupportSymbolLayer): Boolean =
        layer.symbols.asSequence()
            .filter { symbol -> symbol.path.extension in setOf("kt", "kts", "java") }
            .all { symbol -> symbol.path.isRegularFile() }

    private fun SupportSymbolLayer.filterPackages(packageNames: Set<String>): SupportSymbolLayer {
        val requested = packageNames.map(String::trim).toSet()
        return copy(
            symbols = symbols.filter { symbol -> symbol.packageName.trim() in requested },
        )
    }

    private fun supportLayerFingerprint(project: ImportedProject): String =
        buildString {
            val projectRoot = project.root.normalize()
            val sourceBackedBinaryNames = sourceBackedBinaryNames(project)
            append("support-index-v6-durable-source-mirror")
            append('\n')
            project.modules.sortedBy { it.gradlePath }.forEach { module ->
                append(module.gradlePath)
                append('|')
                append(module.externalDependencies.map { it.notation }.sorted())
                append('|')
                module.javaSourceRoots.sortedBy(Path::toString).forEach { root ->
                    appendTreeFingerprint(
                        builder = this,
                        label = "java",
                        projectRoot = projectRoot,
                        root = root.normalize(),
                    )
                }
                append('|')
                module.classpathSourceJars.sortedBy(Path::toString).forEach { jar ->
                    appendFileFingerprint(this, "sourceJar", jar.normalize())
                }
                append('|')
                module.classpathJars
                    .filter { jar ->
                        jar.isRegularFile() &&
                            jar.extension == "jar" &&
                            jar.fileName.toString().removeSuffix(".jar") !in sourceBackedBinaryNames
                    }
                    .sortedBy(Path::toString)
                    .forEach { jar ->
                        appendFileFingerprint(this, "binaryJar", jar.normalize())
                    }
                append('\n')
            }
            defaultJdkSourceArchive()?.let { srcZip ->
                appendFileFingerprint(this, "jdk", srcZip.normalize())
            } ?: run {
                append("jdk-runtime=")
                append(System.getProperty("java.version"))
                append('\n')
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

    private fun buildBinaryLibrarySymbols(project: ImportedProject): List<IndexedSymbol> {
        val classpathEntries = project.modules
            .flatMap { module -> module.classpathJars }
            .distinctBy { it.normalize() }
        return indexedBinaryJars(project)
            .flatMap { (moduleName, jar) ->
                runCatching {
                    binaryClasspathSymbolIndexer.index(jar, moduleName, classpathEntries)
                }.getOrDefault(emptyList())
            }
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

    private fun buildJdkBinarySymbols(): List<IndexedSymbol> {
        val jrtFs = runCatching { FileSystems.getFileSystem(URI.create("jrt:/")) }
            .recoverCatching { FileSystems.newFileSystem(URI.create("jrt:/"), emptyMap<String, Any>()) }
            .getOrNull()
            ?: return emptyList()
        val javaBaseRoot = jrtFs.getPath("/modules/java.base")
        if (!Files.exists(javaBaseRoot)) return emptyList()
        val classNames = javaBaseRoot.walk()
            .filter { path ->
                path.isRegularFile() &&
                    path.extension == "class" &&
                    jdkPackageAllowed(path)
            }
            .map { path ->
                javaBaseRoot.relativize(path)
                    .toString()
                    .replace('\\', '/')
                    .removeSuffix(".class")
                    .replace('/', '.')
            }
        return binaryClasspathSymbolIndexer.indexRuntimeClasses(
            originPath = Path.of("/jdk-runtime/java.base"),
            moduleName = "jdk",
            classNames = classNames,
        )
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

    private fun sourceBackedBinaryNames(project: ImportedProject): Set<String> =
        project.modules
            .flatMap { module -> module.classpathSourceJars }
            .map { sourceJar -> sourceJar.fileName.toString().removeSuffix(".jar").removeSuffix("-sources") }
            .toSet()

    private fun indexedBinaryJars(project: ImportedProject): List<Pair<String, Path>> {
        val sourceBackedBinaryNames = sourceBackedBinaryNames(project)
        return project.modules
            .flatMap { module -> module.classpathJars.map { jar -> module.name to jar } }
            .distinctBy { (_, jar) -> jar.normalize() }
            .filter { (_, jar) ->
                jar.isRegularFile() &&
                    jar.extension == "jar" &&
                    jar.fileName.toString().removeSuffix(".jar") !in sourceBackedBinaryNames
            }
    }

    private fun appendTreeFingerprint(
        builder: StringBuilder,
        label: String,
        projectRoot: Path,
        root: Path,
    ) {
        val normalizedRoot = root.normalize()
        builder.append(label).append('=').append(projectRelativePath(projectRoot, normalizedRoot)).append('|')
        if (!Files.exists(normalizedRoot)) {
            builder.append("missing").append(';')
            return
        }
        normalizedRoot.walk()
            .filter { it.isRegularFile() && it.extension == "java" }
            .map(Path::normalize)
            .sortedBy { path -> normalizedRoot.relativize(path).toString() }
            .forEach { path ->
                appendSourceFingerprint(builder, label, normalizedRoot, path)
            }
    }

    private fun appendFileFingerprint(builder: StringBuilder, label: String, path: Path) {
        val normalized = path.normalize()
        builder.append(label).append(':').append(normalized).append(':')
        if (normalized.extension.lowercase() in setOf("jar", "zip", "aar")) {
            builder.append(StableArtifactFingerprint.fingerprint(normalized))
        } else {
            builder.append(runCatching { Files.size(normalized) }.getOrDefault(0L))
            builder.append(':')
            builder.append(runCatching { Files.getLastModifiedTime(normalized).toMillis() }.getOrDefault(0L))
        }
        builder.append(';')
    }

    private fun appendSourceFingerprint(
        builder: StringBuilder,
        label: String,
        root: Path,
        path: Path,
    ) {
        val normalized = path.normalize()
        builder.append(label)
            .append(':')
            .append(root.relativize(normalized).toString().replace('\\', '/'))
            .append(':')
            .append(StableArtifactFingerprint.fingerprint(normalized))
            .append(';')
    }

    private fun projectRelativePath(projectRoot: Path, path: Path): String =
        if (path.startsWith(projectRoot)) {
            projectRoot.relativize(path).toString().replace('\\', '/')
        } else {
            path.toString().replace('\\', '/')
        }
}

data class SupportSymbolLayer(
    val fingerprint: String,
    val symbols: List<IndexedSymbol>,
)
