package dev.codex.kotlinls.index

import dev.codex.kotlinls.protocol.Location
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.SymbolKind
import java.nio.file.Path

data class IndexedSymbol(
    val id: String,
    val name: String,
    val fqName: String?,
    val kind: Int,
    val path: Path,
    val uri: String,
    val range: Range,
    val selectionRange: Range,
    val containerName: String? = null,
    val containerFqName: String? = null,
    val signature: String,
    val documentation: String? = null,
    val packageName: String,
    val moduleName: String,
    val importable: Boolean,
    val receiverType: String? = null,
    val resultType: String? = null,
    val parameterCount: Int = 0,
    val supertypes: List<String> = emptyList(),
)

data class IndexedReference(
    val symbolId: String,
    val path: Path,
    val uri: String,
    val range: Range,
    val containerSymbolId: String? = null,
)

data class CallEdge(
    val callerSymbolId: String,
    val calleeSymbolId: String,
    val range: Range,
    val path: Path,
)

data class WorkspaceIndex(
    val symbols: List<IndexedSymbol>,
    val references: List<IndexedReference>,
    val callEdges: List<CallEdge>,
) {
    val symbolsById: Map<String, IndexedSymbol> = symbols.associateBy { it.id }
    val symbolsByFqName: Map<String, IndexedSymbol> = symbols.mapNotNull { symbol ->
        symbol.fqName?.let { it to symbol }
    }.toMap()
    val symbolsByName: Map<String, List<IndexedSymbol>> = symbols.groupBy { it.name }
    val symbolsByPath: Map<Path, List<IndexedSymbol>> = symbols.groupBy { it.path.normalize() }
    val referencesBySymbolId: Map<String, List<IndexedReference>> = references.groupBy { it.symbolId }
    val outgoingCalls: Map<String, List<CallEdge>> = callEdges.groupBy { it.callerSymbolId }
    val incomingCalls: Map<String, List<CallEdge>> = callEdges.groupBy { it.calleeSymbolId }
}

fun IndexedSymbol.location(): Location = Location(uri = uri, range = selectionRange)
