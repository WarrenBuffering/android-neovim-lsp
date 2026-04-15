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
        lookup: FastDiagnosticLookup? = null,
    ): List<Diagnostic> =
        buildList {
            project?.let { importedProject ->
                packageMismatchDiagnostic(importedProject, path, text)?.let(::add)
            }
            addAll(importDiagnostics(text, lookup))
            addAll(lintDiagnostics(path, text))
        }

    private fun importDiagnostics(
        text: String,
        lookup: FastDiagnosticLookup?,
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        malformedImportLines(text).forEach { malformed ->
            diagnostics += malformed.diagnostic(
                code = "malformed-import",
                message = "Malformed import statement.",
            )
        }
        val availableLookup = lookup
            ?: return diagnostics.sortedWith(compareBy<Diagnostic> { it.range.start.line }.thenBy { it.range.start.character })
        val seenImports = linkedMapOf<ImportIdentity, ImportLine>()
        validImportLines(text).forEach { importLine ->
            val identity = ImportIdentity(importLine.importedPath, importLine.alias)
            val previous = seenImports.putIfAbsent(identity, importLine)
            if (previous != null) {
                diagnostics += importLine.diagnostic(
                    code = "duplicate-import",
                    message = "Duplicate import `${renderImport(identity)}`.",
                )
                return@forEach
            }
            when {
                importLine.isWildcardImport -> {
                    val packageName = importLine.importedPath.removeSuffix(".*")
                    if (packageName.isBlank()) {
                        diagnostics += importLine.diagnostic(
                            code = "malformed-import",
                            message = "Malformed import statement.",
                        )
                    } else if (packageName !in availableLookup.packagePrefixes) {
                        diagnostics += importLine.diagnostic(
                            code = "unresolved-import-package",
                            message = "Unresolved import package `$packageName`.",
                        )
                    }
                }

                importLine.importedPath in availableLookup.importableFqNames -> Unit

                importLine.packageName.isNotBlank() && importLine.packageName in availableLookup.packagePrefixes -> {
                    diagnostics += importLine.diagnostic(
                        code = "unresolved-import-symbol",
                        message = "Unresolved import `${importLine.importedPath}`.",
                    )
                }

                else -> {
                    diagnostics += importLine.diagnostic(
                        code = "unresolved-import-package",
                        message = "Unresolved import package `${importLine.packageName.ifBlank { importLine.importedPath }}`.",
                    )
                }
            }
        }
        return diagnostics.sortedWith(compareBy<Diagnostic> { it.range.start.line }.thenBy { it.range.start.character })
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
            source = "android-neovim-lsp",
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
            source = "android-neovim-lsp",
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

    private data class ImportLine(
        val lineNumber: Int,
        val rawLine: String,
        val importedPath: String,
        val alias: String?,
    ) {
        val packageName: String = importedPath.substringBeforeLast('.', missingDelimiterValue = "")
        val isWildcardImport: Boolean = importedPath.endsWith(".*")

        fun diagnostic(
            code: String,
            message: String,
        ): Diagnostic = Diagnostic(
            range = Range(
                start = dev.codex.kotlinls.protocol.Position(lineNumber, 0),
                end = dev.codex.kotlinls.protocol.Position(lineNumber, rawLine.length),
            ),
            severity = DiagnosticSeverity.ERROR,
            code = code,
            source = "android-neovim-lsp",
            message = message,
        )
    }

    private data class ImportIdentity(
        val importedPath: String,
        val alias: String?,
    )

    data class FastDiagnosticLookup(
        val importableFqNames: Set<String>,
        val packagePrefixes: Set<String>,
    )

    private companion object {
        val PACKAGE_REGEX = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_$.]*)\s*$""")
        val VALID_IMPORT_REGEX = Regex(
            """^\s*import\s+([A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z_][A-Za-z0-9_$]*)*(?:\.\*)?)(?:\s+as\s+([A-Za-z_][A-Za-z0-9_]*))?\s*$""",
        )
        val IMPORT_PREFIX_REGEX = Regex("""^\s*import\b""")

        fun validImportLines(text: String): List<ImportLine> =
            text.lineSequence()
                .mapIndexedNotNull { index, line ->
                    val match = VALID_IMPORT_REGEX.matchEntire(line) ?: return@mapIndexedNotNull null
                    ImportLine(
                        lineNumber = index,
                        rawLine = line,
                        importedPath = match.groupValues[1],
                        alias = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() },
                    )
                }
                .toList()

        fun malformedImportLines(text: String): List<ImportLine> =
            text.lineSequence()
                .mapIndexedNotNull { index, line ->
                    if (!IMPORT_PREFIX_REGEX.containsMatchIn(line) || VALID_IMPORT_REGEX.matches(line)) {
                        return@mapIndexedNotNull null
                    }
                    ImportLine(
                        lineNumber = index,
                        rawLine = line,
                        importedPath = line.trim().removePrefix("import").trim(),
                        alias = null,
                    )
                }
                .toList()

        fun renderImport(identity: ImportIdentity): String =
            buildString {
                append(identity.importedPath)
                if (!identity.alias.isNullOrBlank()) {
                    append(" as ")
                    append(identity.alias)
                }
            }
    }
}
