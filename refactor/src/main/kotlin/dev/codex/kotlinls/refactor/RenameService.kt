package dev.codex.kotlinls.refactor

import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.index.SymbolResolver
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.TextEdit
import dev.codex.kotlinls.protocol.TextDocumentPositionParams
import dev.codex.kotlinls.protocol.WorkspaceEdit

class RenameService(
    private val resolver: SymbolResolver = SymbolResolver(),
) {
    fun prepareRename(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: TextDocumentPositionParams,
    ): Range? = resolver.symbolAt(snapshot, index, params.textDocument.uri, params.position)?.selectionRange

    fun rename(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: dev.codex.kotlinls.protocol.RenameParams,
    ): WorkspaceEdit? {
        val symbol = resolver.symbolAt(snapshot, index, params.textDocument.uri, params.position) ?: return null
        val edits = linkedMapOf<String, MutableList<TextEdit>>()
        edits.getOrPut(symbol.uri) { mutableListOf() } += TextEdit(symbol.selectionRange, params.newName)
        index.referencesBySymbolId[symbol.id].orEmpty().forEach { reference ->
            edits.getOrPut(reference.uri) { mutableListOf() } += TextEdit(reference.range, params.newName)
        }
        return WorkspaceEdit(changes = edits)
    }
}

