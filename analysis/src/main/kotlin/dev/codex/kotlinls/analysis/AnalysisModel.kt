package dev.codex.kotlinls.analysis

import dev.codex.kotlinls.projectimport.ImportedModule
import dev.codex.kotlinls.projectimport.ImportedProject
import dev.codex.kotlinls.workspace.toDocumentUri
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import java.nio.file.Path

data class CompilerDiagnosticRecord(
    val path: Path,
    val line: Int,
    val column: Int,
    val endLine: Int? = null,
    val endColumn: Int? = null,
    val severity: String,
    val message: String,
    val code: String? = null,
)

data class AnalyzedFile(
    val originalPath: Path,
    val module: ImportedModule,
    val text: String,
    val ktFile: KtFile,
) {
    val uri: String = originalPath.toDocumentUri()
}

data class WorkspaceAnalysisSnapshot(
    val project: ImportedProject,
    val files: List<AnalyzedFile>,
    val bindingContext: BindingContext,
    val diagnostics: List<CompilerDiagnosticRecord>,
    val closeHook: (() -> Unit)? = null,
) {
    val filesByPath: Map<Path, AnalyzedFile> = files.associateBy { it.originalPath.normalize() }
    val filesByUri: Map<String, AnalyzedFile> = files.associateBy { it.uri }

    fun close() {
        closeHook?.invoke()
    }
}
