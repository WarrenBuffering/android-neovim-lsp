package dev.codex.kotlinls.analysis

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.nio.file.Path

class CollectingMessageCollector(
    private val mirrorToOriginal: Map<Path, Path>,
) : MessageCollector {
    private val _diagnostics = mutableListOf<CompilerDiagnosticRecord>()

    val diagnostics: List<CompilerDiagnosticRecord> get() = _diagnostics

    override fun clear() {
        _diagnostics.clear()
    }

    override fun hasErrors(): Boolean = _diagnostics.any { it.severity == CompilerMessageSeverity.ERROR.name }

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        if (location?.path == null) {
            return
        }
        val original = mirrorToOriginal[Path.of(location.path)] ?: return
        _diagnostics += CompilerDiagnosticRecord(
            path = original,
            line = (location.line - 1).coerceAtLeast(0),
            column = (location.column - 1).coerceAtLeast(0),
            endLine = location.lineEnd.takeIf { it > 0 }?.minus(1),
            endColumn = location.columnEnd.takeIf { it > 0 }?.minus(1),
            severity = severity.name,
            message = message.replace(Regex("\\s+"), " ").trim(),
            code = null,
        )
    }
}
