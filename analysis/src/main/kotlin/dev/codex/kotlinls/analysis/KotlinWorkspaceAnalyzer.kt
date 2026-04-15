@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package dev.codex.kotlinls.analysis

import dev.codex.kotlinls.projectimport.ImportedModule
import dev.codex.kotlinls.projectimport.ImportedProject
import dev.codex.kotlinls.workspace.LineIndex
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
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.DiagnosticUtils
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import kotlin.jvm.functions.Function1
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

class KotlinWorkspaceAnalyzer(
    private val mirrorBase: Path = Path.of(System.getProperty("java.io.tmpdir"), "android-neovim-lsp"),
) {
    private val mirrorCacheLock = Any()
    private val mirrorCaches = linkedMapOf<Path, MirrorWorkspaceCache>()
    private val focusedSnapshotGeneration = AtomicInteger(0)

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
            val diagnostics = collectDiagnostics(bindingContext, collector, mirrorPlan)
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
                diagnostics = diagnostics,
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

    private fun collectDiagnostics(
        bindingContext: BindingContext,
        collector: CollectingMessageCollector,
        mirrorPlan: MirrorPlan,
    ): List<CompilerDiagnosticRecord> {
        val structured = DiagnosticUtils.sortedDiagnostics(bindingContext.diagnostics.all())
            .mapNotNull { it.toCompilerDiagnosticRecord(mirrorPlan) }
        if (structured.isEmpty()) {
            return collector.diagnostics
        }
        if (collector.diagnostics.isEmpty()) {
            return structured
        }
        val merged = mutableListOf<CompilerDiagnosticRecord>()
        val seenRecords = linkedSetOf<CompilerDiagnosticRecordKey>()
        val structuredLocations = linkedSetOf<CompilerDiagnosticLocationKey>()
        structured.forEach { record ->
            val key = record.recordKey()
            if (seenRecords.add(key)) {
                merged += record
                structuredLocations += record.locationKey()
            }
        }
        collector.diagnostics.forEach { record ->
            if (record.locationKey() in structuredLocations) {
                return@forEach
            }
            val key = record.recordKey()
            if (seenRecords.add(key)) {
                merged += record
            }
        }
        return merged
    }

    private fun Diagnostic.toCompilerDiagnosticRecord(
        mirrorPlan: MirrorPlan,
    ): CompilerDiagnosticRecord? {
        val mirroredPath = psiFile.virtualFile?.path?.let(Path::of)?.normalize() ?: return null
        val originalPath = mirrorPlan.mirrorToOriginal[mirroredPath] ?: return null
        val text = mirrorPlan.textByOriginal[originalPath] ?: return null
        val lineIndex = LineIndex.build(text)
        val range = textRanges.firstOrNull() ?: psiElement.textRange ?: TextRange.EMPTY_RANGE
        val startOffset = range.startOffset.coerceIn(0, text.length)
        val endOffset = if (range.endOffset > startOffset) {
            range.endOffset.coerceIn(startOffset, text.length)
        } else {
            (startOffset + 1).coerceAtMost(text.length)
        }
        val start = lineIndex.position(startOffset)
        val end = lineIndex.position(endOffset)
        val message = runCatching { DefaultErrorMessages.render(this) }
            .getOrElse { factory.name }
            .replace(Regex("\\s+"), " ")
            .trim()
        return CompilerDiagnosticRecord(
            path = originalPath,
            line = start.line,
            column = start.character,
            endLine = end.line,
            endColumn = end.character,
            severity = severity.toCompilerMessageSeverity().name,
            message = message,
            code = factory.name,
        )
    }

    private fun CompilerDiagnosticRecord.locationKey(): CompilerDiagnosticLocationKey =
        CompilerDiagnosticLocationKey(
            path = path.normalize(),
            line = line,
            column = column,
            endLine = endLine,
            endColumn = endColumn,
            severity = severity,
        )

    private fun CompilerDiagnosticRecord.recordKey(): CompilerDiagnosticRecordKey =
        CompilerDiagnosticRecordKey(
            location = locationKey(),
            message = message,
        )

    private data class CompilerDiagnosticLocationKey(
        val path: Path,
        val line: Int,
        val column: Int,
        val endLine: Int?,
        val endColumn: Int?,
        val severity: String,
    )

    private data class CompilerDiagnosticRecordKey(
        val location: CompilerDiagnosticLocationKey,
        val message: String,
    )

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
        val snapshotRoot = if (normalizedIncludedPaths == null) {
            workspaceCache.snapshotRoot
        } else {
            focusedSnapshotRoot(workspaceCache.snapshotRoot, normalizedIncludedPaths)
        }
        val mirrorToOriginal = linkedMapOf<Path, Path>()
        val originalToMirror = linkedMapOf<Path, Path>()
        val textByOriginal = linkedMapOf<Path, String>()
        val liveFiles = linkedSetOf<Path>()
        val sourceFiles = if (normalizedIncludedPaths != null) {
            normalizedIncludedPaths
                .asSequence()
                .map(Path::normalize)
                .filter(Files::isRegularFile)
                .filter { path -> path.extension in setOf("kt", "kts", "java") }
                .filter { path -> project.moduleForPath(path) != null }
                .sortedBy(Path::toString)
                .toList()
        } else {
            buildList {
                project.modules.forEach { module ->
                    (module.sourceRoots + module.javaSourceRoots).forEach { sourceRoot ->
                        if (!Files.exists(sourceRoot)) return@forEach
                        sourceRoot.walk().forEach { path ->
                            if (!Files.isRegularFile(path)) return@forEach
                            if (path.extension !in setOf("kt", "kts", "java")) return@forEach
                            add(path.normalize())
                        }
                    }
                }
            }
        }
        val totalFiles = sourceFiles.size.coerceAtLeast(1)
        sourceFiles.forEachIndexed { index, originalPath ->
            if (project.moduleForPath(originalPath) == null) return@forEachIndexed
            liveFiles.add(originalPath)
            val relative = originalPath.relativeTo(project.root)
            val mirrorPath = snapshotRoot.resolve(relative.toString()).normalize()
            val document = documents.get(originalPath.toUri().toString())
            val current = if (normalizedIncludedPaths != null) {
                mirrorPath.parent?.createDirectories()
                val fileText = document?.text ?: originalPath.readText()
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
                    sourceFingerprint = document?.let { openDocumentFingerprint(it.version, it.text) }
                        ?: fileFingerprint(originalPath),
                )
            } else {
                val sourceFingerprint = document?.let { openDocumentFingerprint(it.version, it.text) }
                    ?: fileFingerprint(originalPath)
                val cached = synchronized(mirrorCacheLock) { workspaceCache.files[originalPath] }
                if (
                    cached == null ||
                    cached.sourceFingerprint != sourceFingerprint ||
                    !Files.exists(mirrorPath)
                ) {
                    mirrorPath.parent?.createDirectories()
                    val fileText = document?.text ?: originalPath.readText()
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
            }
            mirrorToOriginal[current.mirrorPath] = originalPath
            originalToMirror[originalPath] = current.mirrorPath
            textByOriginal[originalPath] = current.text
            if (shouldReportProgress(index + 1, totalFiles)) {
                progress?.invoke("Prepared ${originalPath.fileName}", index + 1, totalFiles)
            }
        }
        if (normalizedIncludedPaths == null) {
            synchronized(mirrorCacheLock) {
                val staleFiles = workspaceCache.files.keys - liveFiles
                staleFiles.forEach { stale ->
                    workspaceCache.files.remove(stale)?.mirrorPath?.let { mirrorPath ->
                        runCatching { Files.deleteIfExists(mirrorPath) }
                    }
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

    private fun focusedSnapshotRoot(
        workspaceSnapshotRoot: Path,
        includedPaths: Set<Path>,
    ): Path {
        val focusedKey = includedPaths
            .map(Path::toString)
            .sorted()
            .joinToString("\n")
            .hashCode()
            .toUInt()
            .toString(16)
        val focusedRoot = workspaceSnapshotRoot.resolve(
            "focused-$focusedKey-${focusedSnapshotGeneration.incrementAndGet()}",
        )
        focusedRoot.createDirectories()
        return focusedRoot
    }

    private fun recreateDirectory(path: Path) {
        if (Files.exists(path)) {
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { candidate ->
                    Files.deleteIfExists(candidate)
                }
            }
        }
        path.createDirectories()
    }

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
