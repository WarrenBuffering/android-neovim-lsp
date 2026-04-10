package dev.codex.kotlinls.diagnostics

import dev.codex.kotlinls.analysis.CompilerDiagnosticRecord
import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.formatting.FormattingService
import dev.codex.kotlinls.protocol.Diagnostic
import dev.codex.kotlinls.protocol.DiagnosticSeverity
import dev.codex.kotlinls.protocol.PublishDiagnosticsParams
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.projectimport.ImportedProject
import dev.codex.kotlinls.workspace.LineIndex
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class DiagnosticsService(
    private val formattingService: FormattingService = FormattingService(),
) {
    private val lintCache = ConcurrentHashMap<LintCacheKey, List<Diagnostic>>()

    fun publishable(
        snapshot: WorkspaceAnalysisSnapshot,
        uriFilter: Set<String>? = null,
    ): List<PublishDiagnosticsParams> {
        val byUri = linkedMapOf<String, MutableList<Diagnostic>>()
        snapshot.files.forEach { file ->
            if (uriFilter != null && file.uri !in uriFilter) return@forEach
            byUri.putIfAbsent(file.uri, mutableListOf())
        }
        snapshot.diagnostics.forEach { diagnostic ->
            val file = snapshot.filesByPath[diagnostic.path.normalize()] ?: return@forEach
            if (uriFilter != null && file.uri !in uriFilter) return@forEach
            byUri.getOrPut(file.uri) { mutableListOf() } += diagnostic.toLsp(file.text)
        }
        snapshot.files.forEach { file ->
            if (uriFilter != null && file.uri !in uriFilter) return@forEach
            val packageWarning = packageMismatchDiagnostic(snapshot, file.originalPath.toString(), file.ktFile.packageFqName.asString(), file.text)
            if (packageWarning != null) {
                byUri.getOrPut(file.uri) { mutableListOf() } += packageWarning
            }
            byUri.getOrPut(file.uri) { mutableListOf() } += lintDiagnostics(file.originalPath, file.text)
        }
        return byUri.map { (uri, diagnostics) ->
            PublishDiagnosticsParams(uri = uri, diagnostics = diagnostics.sortedWith(compareBy<Diagnostic> { it.range.start.line }.thenBy { it.range.start.character }))
        }
    }

    fun fastDiagnostics(
        project: ImportedProject?,
        path: Path,
        text: String,
    ): List<Diagnostic> =
        buildList {
            project?.let { importedProject ->
                packageMismatchDiagnostic(importedProject, path, text)?.let(::add)
            }
            addAll(lintDiagnostics(path, text))
        }

    private fun CompilerDiagnosticRecord.toLsp(text: String): Diagnostic {
        val lineIndex = LineIndex.build(text)
        val start = dev.codex.kotlinls.protocol.Position(line, column)
        val diagnosticEndLine = endLine
        val diagnosticEndColumn = endColumn
        val end = if (diagnosticEndLine != null && diagnosticEndColumn != null) {
            dev.codex.kotlinls.protocol.Position(diagnosticEndLine, diagnosticEndColumn)
        } else {
            lineIndex.position((lineIndex.offset(start) + 1).coerceAtMost(text.length))
        }
        return Diagnostic(
            range = Range(start = start, end = end),
            severity = when (severity) {
                "ERROR" -> DiagnosticSeverity.ERROR
                "WARNING", "STRONG_WARNING" -> DiagnosticSeverity.WARNING
                "INFO" -> DiagnosticSeverity.INFORMATION
                else -> DiagnosticSeverity.HINT
            },
            code = code,
            source = "kotlin",
            message = message,
        )
    }

    private fun packageMismatchDiagnostic(
        snapshot: WorkspaceAnalysisSnapshot,
        path: String,
        packageName: String,
        text: String,
    ): Diagnostic? {
        val file = snapshot.filesByPath[java.nio.file.Path.of(path).normalize()] ?: return null
        val sourceRoot = file.module.sourceRoots.firstOrNull { file.originalPath.startsWith(it) } ?: return null
        val parent = file.originalPath.parent ?: return null
        val expected = sourceRoot.relativize(parent).joinToString(".") { it.toString() }.trim('.')
        if (expected.isBlank() || expected == packageName) return null
        return Diagnostic(
            range = Range(
                start = dev.codex.kotlinls.protocol.Position(0, 0),
                end = dev.codex.kotlinls.protocol.Position(0, text.lineSequence().firstOrNull()?.length ?: 0),
            ),
            severity = DiagnosticSeverity.WARNING,
            code = "package-mismatch",
            source = "kotlin-neovim-lsp",
            message = "Package declaration does not match source root. Expected `$expected`.",
        )
    }

    private fun packageMismatchDiagnostic(
        project: ImportedProject,
        path: Path,
        text: String,
    ): Diagnostic? {
        val module = project.moduleForPath(path) ?: return null
        val sourceRoot = module.sourceRoots.firstOrNull { path.startsWith(it) } ?: return null
        val parent = path.parent ?: return null
        val expected = sourceRoot.relativize(parent).joinToString(".") { it.toString() }.trim('.')
        val packageName = PACKAGE_REGEX.find(text)?.groupValues?.get(1).orEmpty()
        if (expected.isBlank() || expected == packageName) return null
        return Diagnostic(
            range = Range(
                start = dev.codex.kotlinls.protocol.Position(0, 0),
                end = dev.codex.kotlinls.protocol.Position(0, text.lineSequence().firstOrNull()?.length ?: 0),
            ),
            severity = DiagnosticSeverity.WARNING,
            code = "package-mismatch",
            source = "kotlin-neovim-lsp",
            message = "Package declaration does not match source root. Expected `$expected`.",
        )
    }

    private fun lintDiagnostics(path: Path, text: String): List<Diagnostic> {
        if (!formattingService.shouldPublishStyleDiagnostics(path)) {
            return emptyList()
        }
        val key = LintCacheKey(path.normalize(), text.hashCode())
        return lintCache.computeIfAbsent(key) {
            formattingService.lintDocument(path, text)
        }
    }

    private data class LintCacheKey(
        val path: Path,
        val textHash: Int,
    )

    private companion object {
        val PACKAGE_REGEX = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_$.]*)\s*$""")
    }
}
