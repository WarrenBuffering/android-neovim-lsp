package dev.codex.kotlinls.workspace

import dev.codex.kotlinls.protocol.DidChangeTextDocumentParams
import dev.codex.kotlinls.protocol.DocumentUri
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.TextDocumentItem
import java.util.concurrent.ConcurrentHashMap

data class TextDocumentSnapshot(
    val uri: DocumentUri,
    val languageId: String,
    val version: Int,
    val text: String,
) {
    val lineIndex: LineIndex = LineIndex.build(text)
}

class TextDocumentStore {
    private val openDocuments = ConcurrentHashMap<DocumentUri, TextDocumentSnapshot>()

    fun open(document: TextDocumentItem): TextDocumentSnapshot {
        val snapshot = TextDocumentSnapshot(
            uri = document.uri,
            languageId = document.languageId,
            version = document.version,
            text = document.text,
        )
        openDocuments[document.uri] = snapshot
        return snapshot
    }

    fun applyChanges(params: DidChangeTextDocumentParams): TextDocumentSnapshot? {
        val current = openDocuments[params.textDocument.uri] ?: return null
        var nextText = current.text
        for (change in params.contentChanges) {
            nextText = if (change.range == null) {
                change.text
            } else {
                val range = requireNotNull(change.range)
                val lineIndex = LineIndex.build(nextText)
                val start = lineIndex.offset(range.start)
                val end = lineIndex.offset(range.end)
                buildString(nextText.length - (end - start) + change.text.length) {
                    append(nextText, 0, start)
                    append(change.text)
                    append(nextText, end, nextText.length)
                }
            }
        }
        return TextDocumentSnapshot(
            uri = current.uri,
            languageId = current.languageId,
            version = params.textDocument.version,
            text = nextText,
        ).also { openDocuments[it.uri] = it }
    }

    fun close(uri: DocumentUri) {
        openDocuments.remove(uri)
    }

    fun get(uri: DocumentUri): TextDocumentSnapshot? = openDocuments[uri]

    fun positionToOffset(uri: DocumentUri, position: Position): Int? = get(uri)?.lineIndex?.offset(position)

    fun openDocuments(): Collection<TextDocumentSnapshot> = openDocuments.values
}
