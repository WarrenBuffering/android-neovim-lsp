package dev.codex.kotlinls.protocol

import com.fasterxml.jackson.annotation.JsonInclude

typealias DocumentUri = String

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Position(
    val line: Int,
    val character: Int,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Range(
    val start: Position,
    val end: Position,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Location(
    val uri: DocumentUri,
    val range: Range,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TextEdit(
    val range: Range,
    val newText: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkspaceEdit(
    val changes: Map<DocumentUri, List<TextEdit>>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TextDocumentIdentifier(
    val uri: DocumentUri,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class VersionedTextDocumentIdentifier(
    val uri: DocumentUri,
    val version: Int,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TextDocumentItem(
    val uri: DocumentUri,
    val languageId: String,
    val version: Int,
    val text: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TextDocumentPositionParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DidOpenTextDocumentParams(
    val textDocument: TextDocumentItem,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TextDocumentContentChangeEvent(
    val range: Range? = null,
    val rangeLength: Int? = null,
    val text: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DidChangeTextDocumentParams(
    val textDocument: VersionedTextDocumentIdentifier,
    val contentChanges: List<TextDocumentContentChangeEvent>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DidCloseTextDocumentParams(
    val textDocument: TextDocumentIdentifier,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DidSaveTextDocumentParams(
    val textDocument: TextDocumentIdentifier,
    val text: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DidChangeWatchedFilesParams(
    val changes: List<FileEvent>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FileEvent(
    val uri: DocumentUri,
    val type: Int,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkspaceFolder(
    val uri: String,
    val name: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InitializeParams(
    val processId: Long? = null,
    val rootUri: String? = null,
    val rootPath: String? = null,
    val workspaceFolders: List<WorkspaceFolder>? = null,
    val initializationOptions: Map<String, Any?>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InitializeResult(
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ServerInfo(
    val name: String,
    val version: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TextDocumentSyncOptions(
    val openClose: Boolean = true,
    val change: Int = 2,
    val save: Boolean = true,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CompletionOptions(
    val resolveProvider: Boolean = true,
    val triggerCharacters: List<String>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CodeActionOptions(
    val codeActionKinds: List<String>? = null,
    val resolveProvider: Boolean = false,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RenameOptions(
    val prepareProvider: Boolean = true,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExecuteCommandOptions(
    val commands: List<String>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SemanticTokensLegend(
    val tokenTypes: List<String>,
    val tokenModifiers: List<String>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SemanticTokensOptions(
    val legend: SemanticTokensLegend,
    val full: Boolean = true,
    val range: Boolean = false,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ServerCapabilities(
    val textDocumentSync: TextDocumentSyncOptions,
    val hoverProvider: Boolean = true,
    val definitionProvider: Boolean = true,
    val typeDefinitionProvider: Boolean = true,
    val referencesProvider: Boolean = true,
    val implementationProvider: Boolean = true,
    val documentSymbolProvider: Boolean = true,
    val workspaceSymbolProvider: Boolean = true,
    val documentFormattingProvider: Boolean = true,
    val documentRangeFormattingProvider: Boolean = true,
    val renameProvider: Any? = RenameOptions(),
    val codeActionProvider: CodeActionOptions? = null,
    val completionProvider: CompletionOptions? = null,
    val signatureHelpProvider: SignatureHelpOptions? = null,
    val semanticTokensProvider: SemanticTokensOptions? = null,
    val foldingRangeProvider: Boolean = true,
    val selectionRangeProvider: Boolean = true,
    val documentHighlightProvider: Boolean = true,
    val inlayHintProvider: Boolean = true,
    val callHierarchyProvider: Boolean = true,
    val typeHierarchyProvider: Boolean = true,
    val executeCommandProvider: ExecuteCommandOptions? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Diagnostic(
    val range: Range,
    val severity: Int,
    val code: String? = null,
    val source: String? = null,
    val message: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PublishDiagnosticsParams(
    val uri: DocumentUri,
    val diagnostics: List<Diagnostic>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MarkupContent(
    val kind: String,
    val value: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Hover(
    val contents: MarkupContent,
    val range: Range? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CompletionParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CompletionItem(
    val label: String,
    val kind: Int? = null,
    val detail: String? = null,
    val documentation: MarkupContent? = null,
    val sortText: String? = null,
    val filterText: String? = null,
    val insertText: String? = null,
    val insertTextFormat: Int? = null,
    val additionalTextEdits: List<TextEdit>? = null,
    val data: Map<String, String>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CompletionList(
    val isIncomplete: Boolean,
    val items: List<CompletionItem>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DocumentSymbolParams(
    val textDocument: TextDocumentIdentifier,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DocumentSymbol(
    val name: String,
    val detail: String? = null,
    val kind: Int,
    val range: Range,
    val selectionRange: Range,
    val children: List<DocumentSymbol>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorkspaceSymbolParams(
    val query: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SymbolInformation(
    val name: String,
    val kind: Int,
    val location: Location,
    val containerName: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReferenceParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RenameParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val newName: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CodeActionContext(
    val diagnostics: List<Diagnostic> = emptyList(),
    val only: List<String>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CodeActionParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
    val context: CodeActionContext,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Command(
    val title: String,
    val command: String,
    val arguments: List<Any?>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CodeAction(
    val title: String,
    val kind: String? = null,
    val diagnostics: List<Diagnostic>? = null,
    val edit: WorkspaceEdit? = null,
    val command: Command? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FormattingOptions(
    val tabSize: Int,
    val insertSpaces: Boolean,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DocumentFormattingParams(
    val textDocument: TextDocumentIdentifier,
    val options: FormattingOptions,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DocumentRangeFormattingParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
    val options: FormattingOptions,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FoldingRangeParams(
    val textDocument: TextDocumentIdentifier,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class FoldingRange(
    val startLine: Int,
    val endLine: Int,
    val kind: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SelectionRangeParams(
    val textDocument: TextDocumentIdentifier,
    val positions: List<Position>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SelectionRange(
    val range: Range,
    val parent: SelectionRange? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DocumentHighlightParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DocumentHighlight(
    val range: Range,
    val kind: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SignatureHelpOptions(
    val triggerCharacters: List<String>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ParameterInformation(
    val label: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SignatureInformation(
    val label: String,
    val documentation: MarkupContent? = null,
    val parameters: List<ParameterInformation>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SignatureHelp(
    val signatures: List<SignatureInformation>,
    val activeSignature: Int = 0,
    val activeParameter: Int = 0,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SemanticTokensParams(
    val textDocument: TextDocumentIdentifier,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SemanticTokens(
    val data: List<Int>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InlayHintParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InlayHint(
    val position: Position,
    val label: String,
    val kind: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CallHierarchyPrepareParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CallHierarchyItem(
    val name: String,
    val kind: Int,
    val uri: String,
    val range: Range,
    val selectionRange: Range,
    val detail: String? = null,
    val data: Map<String, String>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CallHierarchyIncomingCall(
    val from: CallHierarchyItem,
    val fromRanges: List<Range>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CallHierarchyOutgoingCall(
    val to: CallHierarchyItem,
    val fromRanges: List<Range>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TypeHierarchyItem(
    val name: String,
    val kind: Int,
    val uri: String,
    val range: Range,
    val selectionRange: Range,
    val detail: String? = null,
    val data: Map<String, String>? = null,
)

object DiagnosticSeverity {
    const val ERROR = 1
    const val WARNING = 2
    const val INFORMATION = 3
    const val HINT = 4
}

object CompletionItemKind {
    const val TEXT = 1
    const val METHOD = 2
    const val FUNCTION = 3
    const val CONSTRUCTOR = 4
    const val FIELD = 5
    const val VARIABLE = 6
    const val CLASS = 7
    const val INTERFACE = 8
    const val MODULE = 9
    const val PROPERTY = 10
    const val UNIT = 11
    const val VALUE = 12
    const val ENUM = 13
    const val KEYWORD = 14
    const val SNIPPET = 15
}

object SymbolKind {
    const val FILE = 1
    const val MODULE = 2
    const val NAMESPACE = 3
    const val PACKAGE = 4
    const val CLASS = 5
    const val METHOD = 6
    const val PROPERTY = 7
    const val FIELD = 8
    const val CONSTRUCTOR = 9
    const val ENUM = 10
    const val INTERFACE = 11
    const val FUNCTION = 12
    const val VARIABLE = 13
    const val CONSTANT = 14
    const val STRING = 15
    const val NUMBER = 16
    const val BOOLEAN = 17
    const val ARRAY = 18
    const val OBJECT = 19
    const val KEY = 20
    const val NULL = 21
    const val ENUM_MEMBER = 22
    const val STRUCT = 23
    const val EVENT = 24
    const val OPERATOR = 25
    const val TYPE_PARAMETER = 26
}
