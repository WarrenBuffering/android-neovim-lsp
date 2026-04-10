@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package dev.codex.kotlinls.analysis

import dev.codex.kotlinls.projectimport.ImportedModule
import dev.codex.kotlinls.projectimport.ImportedProject
import dev.codex.kotlinls.workspace.TextDocumentStore
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.config.addJavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.configureJdkClasspathRoots
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import kotlin.jvm.functions.Function1
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

class KotlinWorkspaceAnalyzer(
    private val mirrorBase: Path = Path.of(System.getProperty("java.io.tmpdir"), "kotlin-neovim-lsp"),
) {
    private val mirrorCacheLock = Any()
    private val mirrorCaches = linkedMapOf<Path, MirrorWorkspaceCache>()

    fun analyze(
        project: ImportedProject,
        documents: TextDocumentStore,
        includedPaths: Set<Path>? = null,
        progress: ((String, Int?, Int?) -> Unit)? = null,
    ): WorkspaceAnalysisSnapshot {
        if (project.modules.none { it.sourceRoots.isNotEmpty() || it.javaSourceRoots.isNotEmpty() }) {
            return WorkspaceAnalysisSnapshot(project, emptyList(), BindingContext.EMPTY, emptyList())
        }

        val mirrorPlan = mirrorProject(project, documents, includedPaths, progress)
        val collector = CollectingMessageCollector(mirrorPlan.mirrorToOriginal)
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, project.root.name.ifBlank { "workspace" })
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, collector)
            configureJdkClasspathRoots()
            addJvmClasspathRoots(project.modules.flatMap { it.classpathJars }.distinct().map(Path::toFile))
            mirrorPlan.mirroredKotlinRoots.forEach { addKotlinSourceRoot(it.toString()) }
            mirrorPlan.mirroredJavaRoots.forEach { addJavaSourceRoot(it.toFile()) }
        }
        val disposable = Disposer.newDisposable("kotlinls-analyzer")
        try {
            progress?.invoke("Running Kotlin compiler resolve", null, null)
            val environment = createEnvironment(disposable, configuration)
            val sourceFiles = environment.getSourceFiles()
            val trace = NoScopeRecordCliBindingTrace(environment.project)
            val analyzer = AnalyzerWithCompilerReport(configuration)
            analyzer.analyzeAndReport(sourceFiles) {
                analyzeFilesWithJavaIntegration(
                    environment = environment,
                    sourceFiles = sourceFiles,
                    trace = trace,
                    configuration = configuration,
                )
            }
            val analysisResult: AnalysisResult = analyzer.analysisResult
            val bindingContext = analysisResult.bindingContext
            val files = sourceFiles.mapNotNull { ktFile ->
                val originalPath = mirrorPlan.originalForMirror(ktFile.virtualFilePath) ?: return@mapNotNull null
                val module = mirrorPlan.moduleForOriginal(originalPath) ?: return@mapNotNull null
                val text = mirrorPlan.textByOriginal[originalPath] ?: ""
                AnalyzedFile(
                    originalPath = originalPath,
                    module = module,
                    text = text,
                    ktFile = ktFile,
                )
            }
            return WorkspaceAnalysisSnapshot(
                project = project,
                files = files,
                bindingContext = bindingContext,
                diagnostics = collector.diagnostics,
                closeHook = { safeDispose(disposable) },
            )
        } catch (t: Throwable) {
            safeDispose(disposable)
            throw t
        }
    }

    private fun safeDispose(disposable: Disposable) {
        try {
            Disposer.dispose(disposable)
        } catch (_: Throwable) {
            // Some Kotlin compiler embedded disposal paths can fail on specific Android-heavy workspaces.
            // Keep the server alive and let process shutdown reclaim the abandoned disposable graph.
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun analyzeFilesWithJavaIntegration(
        environment: KotlinCoreEnvironment,
        sourceFiles: Collection<org.jetbrains.kotlin.psi.KtFile>,
        trace: BindingTrace,
        configuration: CompilerConfiguration,
    ): AnalysisResult {
        val method = Class.forName("org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM")
            .methods
            .first { candidate ->
                candidate.name == "analyzeFilesWithJavaIntegration" && candidate.parameterCount == 5
            }
        return method.invoke(
            null,
            environment.project,
            sourceFiles,
            trace,
            configuration,
            object : Function1<GlobalSearchScope, PackagePartProvider> {
                override fun invoke(scope: GlobalSearchScope): PackagePartProvider =
                    environment.createPackagePartProvider(scope)
            },
        ) as AnalysisResult
    }

    private fun createEnvironment(
        disposable: Disposable,
        configuration: CompilerConfiguration,
    ): KotlinCoreEnvironment {
        val method = KotlinCoreEnvironment::class.java.getMethod(
            "createForProduction",
            Disposable::class.java,
            CompilerConfiguration::class.java,
            EnvironmentConfigFiles::class.java,
        )
        return method.invoke(null, disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES) as KotlinCoreEnvironment
    }

    private fun mirrorProject(
        project: ImportedProject,
        documents: TextDocumentStore,
        includedPaths: Set<Path>? = null,
        progress: ((String, Int?, Int?) -> Unit)? = null,
    ): MirrorPlan {
        mirrorBase.createDirectories()
        val projectRoot = project.root.normalize()
        val normalizedIncludedPaths = includedPaths
            ?.map(Path::normalize)
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
        val workspaceCache = synchronized(mirrorCacheLock) {
            mirrorCaches.getOrPut(projectRoot) {
                val cacheKey = projectRoot.toString().hashCode().toUInt().toString(16)
                MirrorWorkspaceCache(
                    snapshotRoot = mirrorBase.resolve("snapshot-$cacheKey").also { it.createDirectories() },
                )
            }
        }
        val snapshotRoot = workspaceCache.snapshotRoot
        val mirrorToOriginal = linkedMapOf<Path, Path>()
        val originalToMirror = linkedMapOf<Path, Path>()
        val textByOriginal = linkedMapOf<Path, String>()
        val liveFiles = linkedSetOf<Path>()
        val sourceFiles = buildList {
            project.modules.forEach { module ->
                (module.sourceRoots + module.javaSourceRoots).forEach { sourceRoot ->
                    if (!Files.exists(sourceRoot)) return@forEach
                    sourceRoot.walk().forEach { path ->
                        if (!Files.isRegularFile(path)) return@forEach
                        if (path.extension !in setOf("kt", "kts", "java")) return@forEach
                        val normalized = path.normalize()
                        if (normalizedIncludedPaths != null && normalized !in normalizedIncludedPaths) return@forEach
                        add(normalized)
                    }
                }
            }
        }
        val totalFiles = sourceFiles.size.coerceAtLeast(1)
        var processedFiles = 0

        project.modules.forEach { module ->
            (module.sourceRoots + module.javaSourceRoots).forEach { sourceRoot ->
                if (!Files.exists(sourceRoot)) return@forEach
                sourceRoot.walk().forEach { path ->
                    if (!Files.isRegularFile(path)) return@forEach
                    if (path.extension !in setOf("kt", "kts", "java")) return@forEach
                    val originalPath = path.normalize()
                    if (normalizedIncludedPaths != null && originalPath !in normalizedIncludedPaths) return@forEach
                    liveFiles.add(originalPath)
                    val relative = originalPath.relativeTo(project.root)
                    val mirrorPath = snapshotRoot.resolve(relative.toString()).normalize()
                    val document = documents.get(originalPath.toUri().toString())
                    val sourceFingerprint = document?.let { openDocumentFingerprint(it.version, it.text) }
                        ?: fileFingerprint(originalPath)
                    val cached = synchronized(mirrorCacheLock) { workspaceCache.files[originalPath] }
                    val current = if (
                        cached == null ||
                        cached.sourceFingerprint != sourceFingerprint ||
                        !Files.exists(mirrorPath)
                    ) {
                        mirrorPath.parent?.createDirectories()
                        val fileText = document?.text ?: path.readText()
                        Files.writeString(
                            mirrorPath,
                            fileText,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                        )
                        MirroredFileState(
                            mirrorPath = mirrorPath,
                            text = fileText,
                            sourceFingerprint = sourceFingerprint,
                        ).also { state ->
                            synchronized(mirrorCacheLock) {
                                workspaceCache.files[originalPath] = state
                            }
                        }
                    } else {
                        cached
                    }
                    mirrorToOriginal[current.mirrorPath] = originalPath
                    originalToMirror[originalPath] = current.mirrorPath
                    textByOriginal[originalPath] = current.text
                    processedFiles += 1
                    if (shouldReportProgress(processedFiles, totalFiles)) {
                        progress?.invoke("Prepared ${originalPath.fileName}", processedFiles, totalFiles)
                    }
                }
            }
        }
        synchronized(mirrorCacheLock) {
            val staleFiles = workspaceCache.files.keys - liveFiles
            staleFiles.forEach { stale ->
                workspaceCache.files.remove(stale)?.mirrorPath?.let { mirrorPath ->
                    runCatching { Files.deleteIfExists(mirrorPath) }
                }
            }
        }

        val mirroredKotlinRoots = mirroredSourceRoots(
            project = project,
            snapshotRoot = snapshotRoot,
            sourceFiles = sourceFiles,
            roots = project.modules.flatMap { it.sourceRoots },
            extensions = setOf("kt", "kts"),
        )
        val mirroredJavaRoots = mirroredSourceRoots(
            project = project,
            snapshotRoot = snapshotRoot,
            sourceFiles = sourceFiles,
            roots = project.modules.flatMap { it.javaSourceRoots },
            extensions = setOf("java"),
        )
        return MirrorPlan(
            project = project,
            snapshotRoot = snapshotRoot,
            mirroredKotlinRoots = mirroredKotlinRoots,
            mirroredJavaRoots = mirroredJavaRoots,
            mirrorToOriginal = mirrorToOriginal,
            originalToMirror = originalToMirror,
            textByOriginal = textByOriginal,
        )
    }

    private fun openDocumentFingerprint(version: Int, text: String): String = "open:$version:${text.hashCode()}"

    private fun fileFingerprint(path: Path): String =
        "file:${runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)}:${runCatching { Files.size(path) }.getOrDefault(0L)}"

    private fun shouldReportProgress(current: Int, total: Int): Boolean =
        current == 1 || current == total || current % 50 == 0

    private fun mirroredSourceRoots(
        project: ImportedProject,
        snapshotRoot: Path,
        sourceFiles: List<Path>,
        roots: List<Path>,
        extensions: Set<String>,
    ): List<Path> =
        roots.asSequence()
            .map(Path::normalize)
            .filter { root ->
                sourceFiles.any { path -> path.startsWith(root) && path.extension in extensions }
            }
            .mapNotNull { root ->
                runCatching { snapshotRoot.resolve(root.relativeTo(project.root).toString()).normalize() }.getOrNull()
            }
            .filter(Files::exists)
            .distinct()
            .toList()
}

internal data class MirrorPlan(
    val project: ImportedProject,
    val snapshotRoot: Path,
    val mirroredKotlinRoots: List<Path>,
    val mirroredJavaRoots: List<Path>,
    val mirrorToOriginal: Map<Path, Path>,
    val originalToMirror: Map<Path, Path>,
    val textByOriginal: Map<Path, String>,
) {
    fun originalForMirror(mirrorPath: String): Path? = mirrorToOriginal[Path.of(mirrorPath).normalize()]

    fun moduleForOriginal(path: Path): ImportedModule? =
        project.modules.firstOrNull { module ->
            (module.sourceRoots + module.javaSourceRoots + module.testRoots).any { root ->
                path.normalize().startsWith(root.normalize())
            }
        }
}

private data class MirrorWorkspaceCache(
    val snapshotRoot: Path,
    val files: MutableMap<Path, MirroredFileState> = linkedMapOf(),
)

private data class MirroredFileState(
    val mirrorPath: Path,
    val text: String,
    val sourceFingerprint: String,
)
