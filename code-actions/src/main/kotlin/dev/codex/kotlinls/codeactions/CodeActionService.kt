package dev.codex.kotlinls.codeactions

import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.formatting.FormattingService
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.protocol.CodeAction
import dev.codex.kotlinls.protocol.CodeActionParams
import dev.codex.kotlinls.protocol.Command
import dev.codex.kotlinls.protocol.Diagnostic
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.TextEdit
import dev.codex.kotlinls.protocol.WorkspaceEdit
import dev.codex.kotlinls.workspace.LineIndex
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingContext

class CodeActionService(
    private val formattingService: FormattingService = FormattingService(),
) {

    fun codeActions(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: CodeActionParams,
    ): List<CodeAction> {
        val actions = mutableListOf<CodeAction>()
        val uri = params.textDocument.uri
        val file = snapshot.filesByUri[uri]
        val requestedKinds = params.context.only.orEmpty()
        val wantsQuickFixes = wantsKind("quickfix", requestedKinds)
        val wantsIntentions = requestedKinds.isNotEmpty() && wantsKind("refactor.rewrite", requestedKinds)
        val wantsOrganizeImports = wantsKind("source.organizeImports", requestedKinds)
        val organized = if (wantsOrganizeImports) formattingService.organizeImportsText(snapshot, uri) else null
        if (file != null && organized != null && organized != file.text) {
            actions += CodeAction(
                title = "Organize imports",
                kind = "source.organizeImports",
                edit = WorkspaceEdit(
                    changes = mapOf(
                        uri to listOf(
                            TextEdit(
                                range = Range(
                                    start = Position(0, 0),
                                    end = Position(Int.MAX_VALUE / 4, 0),
                                ),
                                newText = organized,
                            ),
                        ),
                    ),
                ),
            )
        }
        if (wantsQuickFixes) {
            params.context.diagnostics.forEach { diagnostic ->
                actions += diagnosticFixes(snapshot, index, uri, diagnostic)
            }
        }
        if (file != null && wantsIntentions) {
            actions += explicitTypeActions(snapshot, uri, params.range)
        }
        return actions
            .filter { action -> wantsKind(action.kind ?: "quickfix", requestedKinds) }
            .distinctBy { action ->
            listOf(action.title, action.kind, action.edit?.changes?.toString(), action.command?.command).joinToString("::")
        }
    }

    fun lightweightCodeActions(
        index: WorkspaceIndex,
        uri: String,
        text: String,
        params: CodeActionParams,
    ): List<CodeAction> {
        val requestedKinds = params.context.only.orEmpty()
        val wantsQuickFixes = wantsKind("quickfix", requestedKinds)
        if (!wantsQuickFixes) {
            return emptyList()
        }
        return params.context.diagnostics
            .flatMap { diagnostic -> diagnosticFixesFromText(index, uri, text, diagnostic) }
            .filter { action -> wantsKind(action.kind ?: "quickfix", requestedKinds) }
            .distinctBy { action ->
                listOf(action.title, action.kind, action.edit?.changes?.toString(), action.command?.command).joinToString("::")
            }
    }

    private fun diagnosticFixes(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        uri: String,
        diagnostic: Diagnostic,
    ): List<CodeAction> {
        val actions = mutableListOf<CodeAction>()
        when {
            diagnostic.code == "package-mismatch" -> {
                val expected = Regex("""Expected `([^`]+)`""").find(diagnostic.message)?.groupValues?.get(1) ?: return emptyList()
                actions += CodeAction(
                    title = "Fix package declaration to $expected",
                    kind = "quickfix",
                    diagnostics = listOf(diagnostic),
                    edit = WorkspaceEdit(
                        changes = mapOf(
                            uri to listOf(
                                TextEdit(
                                    range = diagnostic.range,
                                    newText = "package $expected",
                                ),
                            ),
                        ),
                    ),
                )
                return actions
            }

        }

        if (diagnostic.message.contains("Unused import", ignoreCase = true)) {
            unusedImportAction(snapshot, uri, diagnostic)?.let(actions::add)
        }
        if (diagnostic.message.contains("opt-in", ignoreCase = true) || diagnostic.message.contains("@OptIn(", ignoreCase = true)) {
            addFileOptInAction(snapshot, uri, diagnostic)?.let(actions::add)
        }
        if (
            diagnostic.message.contains("module classpath", ignoreCase = true) ||
            diagnostic.message.contains("missing or conflicting dependencies", ignoreCase = true)
        ) {
            actions += CodeAction(
                title = "Reimport Gradle project model",
                kind = "quickfix",
                diagnostics = listOf(diagnostic),
                command = Command(
                    title = "Reimport Gradle project model",
                    command = "kotlinls.reimport",
                    arguments = emptyList(),
                ),
            )
        }
        actions += unresolvedReferenceActions(index, uri, diagnostic)
        return actions
    }

    private fun diagnosticFixesFromText(
        index: WorkspaceIndex,
        uri: String,
        text: String,
        diagnostic: Diagnostic,
    ): List<CodeAction> {
        val actions = mutableListOf<CodeAction>()
        when {
            diagnostic.code == "package-mismatch" -> {
                val expected = Regex("""Expected `([^`]+)`""").find(diagnostic.message)?.groupValues?.get(1) ?: return emptyList()
                actions += CodeAction(
                    title = "Fix package declaration to $expected",
                    kind = "quickfix",
                    diagnostics = listOf(diagnostic),
                    edit = WorkspaceEdit(
                        changes = mapOf(
                            uri to listOf(
                                TextEdit(
                                    range = diagnostic.range,
                                    newText = "package $expected",
                                ),
                            ),
                        ),
                    ),
                )
                return actions
            }
        }
        if (diagnostic.message.contains("Unused import", ignoreCase = true)) {
            unusedImportAction(text, uri, diagnostic)?.let(actions::add)
        }
        if (diagnostic.message.contains("opt-in", ignoreCase = true) || diagnostic.message.contains("@OptIn(", ignoreCase = true)) {
            addFileOptInAction(text, uri, diagnostic)?.let(actions::add)
        }
        if (
            diagnostic.message.contains("module classpath", ignoreCase = true) ||
            diagnostic.message.contains("missing or conflicting dependencies", ignoreCase = true)
        ) {
            actions += CodeAction(
                title = "Reimport Gradle project model",
                kind = "quickfix",
                diagnostics = listOf(diagnostic),
                command = Command(
                    title = "Reimport Gradle project model",
                    command = "kotlinls.reimport",
                    arguments = emptyList(),
                ),
            )
        }
        actions += unresolvedReferenceActions(index, uri, diagnostic)
        return actions
    }

    private fun unresolvedReferenceActions(
        index: WorkspaceIndex,
        uri: String,
        diagnostic: Diagnostic,
    ): List<CodeAction> {
        val unresolvedName = Regex("""Unresolved reference:?\s*([A-Za-z0-9_]+)""").find(diagnostic.message)?.groupValues?.get(1)
            ?: return emptyList()
        return index.symbolsByName[unresolvedName].orEmpty()
            .filter { it.importable && it.fqName != null }
            .take(5)
            .map { symbol ->
                CodeAction(
                    title = "Import ${symbol.fqName}",
                    kind = "quickfix",
                    diagnostics = listOf(diagnostic),
                    edit = WorkspaceEdit(
                        changes = mapOf(
                            uri to listOf(
                                TextEdit(
                                    range = Range(Position(0, 0), Position(0, 0)),
                                    newText = "import ${symbol.fqName}\n",
                                ),
                            ),
                        ),
                    ),
                )
            }
    }

    private fun unusedImportAction(
        snapshot: WorkspaceAnalysisSnapshot,
        uri: String,
        diagnostic: Diagnostic,
    ): CodeAction? =
        snapshot.filesByUri[uri]?.let { file -> unusedImportAction(file.text, uri, diagnostic) }

    private fun unusedImportAction(
        text: String,
        uri: String,
        diagnostic: Diagnostic,
    ): CodeAction? {
        val line = diagnostic.range.start.line.coerceAtLeast(0)
        val lines = text.lines()
        if (line >= lines.size) return null
        val end = if (line + 1 < lines.size) {
            Position(line + 1, 0)
        } else {
            Position(line, lines[line].length)
        }
        return CodeAction(
            title = "Remove unused import",
            kind = "quickfix",
            diagnostics = listOf(diagnostic),
            edit = WorkspaceEdit(
                changes = mapOf(
                    uri to listOf(
                        TextEdit(
                            range = Range(Position(line, 0), end),
                            newText = "",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun addFileOptInAction(
        snapshot: WorkspaceAnalysisSnapshot,
        uri: String,
        diagnostic: Diagnostic,
    ): CodeAction? =
        snapshot.filesByUri[uri]?.let { file -> addFileOptInAction(file.text, uri, diagnostic) }

    private fun addFileOptInAction(
        text: String,
        uri: String,
        diagnostic: Diagnostic,
    ): CodeAction? {
        val annotationClass = Regex("""@OptIn\(([^)]+)::class\)""")
            .find(diagnostic.message)
            ?.groupValues
            ?.get(1)
            ?: Regex("""@([A-Za-z0-9_.]+)""")
                .find(diagnostic.message)
                ?.groupValues
                ?.get(1)
            ?: return null
        val marker = "@file:OptIn($annotationClass::class)"
        if (text.contains(marker)) return null
        return CodeAction(
            title = "Add file opt-in for ${annotationClass.substringAfterLast('.')}",
            kind = "quickfix",
            diagnostics = listOf(diagnostic),
            edit = WorkspaceEdit(
                changes = mapOf(
                    uri to listOf(
                        TextEdit(
                            range = Range(Position(0, 0), Position(0, 0)),
                            newText = "$marker\n",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun wantsKind(
        kind: String,
        requestedKinds: List<String>,
    ): Boolean {
        if (requestedKinds.isEmpty()) return true
        return requestedKinds.any { requested ->
            kind == requested || kind.startsWith("$requested.") || requested.startsWith("$kind.")
        }
    }

    private fun explicitTypeActions(
        snapshot: WorkspaceAnalysisSnapshot,
        uri: String,
        range: Range,
    ): List<CodeAction> {
        val file = snapshot.filesByUri[uri] ?: return emptyList()
        val offset = LineIndex.build(file.text).offset(range.start)
        val leaf = file.ktFile.findElementAt(offset) ?: return emptyList()
        val declaration = leaf.parentsWithSelf.firstOrNull {
            it is KtProperty || it is KtNamedFunction
        }
        return when (declaration) {
            is KtProperty -> explicitTypeActionForProperty(snapshot, file.text, uri, declaration)
            is KtNamedFunction -> explicitReturnTypeAction(snapshot, file.text, uri, declaration)
            else -> emptyList()
        }
    }

    private fun explicitTypeActionForProperty(
        snapshot: WorkspaceAnalysisSnapshot,
        text: String,
        uri: String,
        property: KtProperty,
    ): List<CodeAction> {
        if (property.typeReference != null) return emptyList()
        val initializer = property.initializer ?: return emptyList()
        val typeText = snapshot.bindingContext.getType(initializer)?.toString() ?: return emptyList()
        val nameIdentifier = property.nameIdentifier ?: return emptyList()
        val lineIndex = LineIndex.build(text)
        val insertAt = lineIndex.position(nameIdentifier.textRange.endOffset)
        return listOf(
            CodeAction(
                title = "Add explicit type annotation: $typeText",
                kind = "refactor.rewrite",
                edit = WorkspaceEdit(
                    changes = mapOf(
                        uri to listOf(TextEdit(Range(insertAt, insertAt), ": $typeText")),
                    ),
                ),
            ),
        )
    }

    private fun explicitReturnTypeAction(
        snapshot: WorkspaceAnalysisSnapshot,
        text: String,
        uri: String,
        function: KtNamedFunction,
    ): List<CodeAction> {
        if (function.typeReference != null) return emptyList()
        val descriptor = snapshot.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, function] as? CallableDescriptor
            ?: return emptyList()
        val returnType = descriptor.returnType?.toString()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val anchor = function.valueParameterList ?: function.nameIdentifier ?: return emptyList()
        val lineIndex = LineIndex.build(text)
        val insertAt = lineIndex.position(anchor.textRange.endOffset)
        return listOf(
            CodeAction(
                title = "Add explicit return type: $returnType",
                kind = "refactor.rewrite",
                edit = WorkspaceEdit(
                    changes = mapOf(
                        uri to listOf(TextEdit(Range(insertAt, insertAt), ": $returnType")),
                    ),
                ),
            ),
        )
    }
}
