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
    val symbolsByFqName: Map<String, IndexedSymbol> = symbols.asSequence()
        .filter { it.fqName != null }
        .groupBy { requireNotNull(it.fqName) }
        .mapValues { (_, group) -> preferredIndexedSymbol(group) }
    val symbolsByName: Map<String, List<IndexedSymbol>> = symbols
        .groupBy { it.name }
        .mapValues { (_, group) -> group.sortedWith(preferredIndexedSymbolComparator()) }
    private val symbolsByPrefix: Map<String, List<IndexedSymbol>> = buildPrefixIndex(symbols)
    val symbolsByPath: Map<Path, List<IndexedSymbol>> = symbols.groupBy { it.path.normalize() }
    val referencesBySymbolId: Map<String, List<IndexedReference>> = references.groupBy { it.symbolId }
    val outgoingCalls: Map<String, List<CallEdge>> = callEdges.groupBy { it.callerSymbolId }
    val incomingCalls: Map<String, List<CallEdge>> = callEdges.groupBy { it.calleeSymbolId }
    val packageNames: Set<String> = symbols.asSequence()
        .map { it.packageName }
        .filter { it.isNotBlank() }
        .toSet()

    fun completionCandidates(prefix: String): Sequence<IndexedSymbol> {
        if (prefix.isBlank()) return symbols.asSequence()
        val normalizedPrefix = prefix.lowercase()
        val bucketKey = normalizedPrefix.take(minOf(3, normalizedPrefix.length))
        return symbolsByPrefix[bucketKey]
            .orEmpty()
            .asSequence()
            .filter { symbol -> symbol.name.startsWith(prefix) }
    }
}

fun IndexedSymbol.location(): Location = Location(uri = uri, range = selectionRange)

fun preferredIndexedSymbol(candidates: Collection<IndexedSymbol>): IndexedSymbol =
    candidates.minWithOrNull(preferredIndexedSymbolComparator()) ?: error("Expected at least one symbol candidate")

fun preferredIndexedSymbolComparator(): Comparator<IndexedSymbol> =
    compareByDescending<IndexedSymbol> { indexedSymbolQuality(it) }
        .thenByDescending { it.documentation?.isNotBlank() == true }
        .thenByDescending { sourceLikeUri(it.uri) }
        .thenByDescending { sourceLikePath(it.path) }
        .thenByDescending { it.selectionRange.start.line != 0 || it.selectionRange.start.character != 0 || it.range.end.line != 0 || it.range.end.character != 0 }
        .thenBy { it.fqName?.length ?: Int.MAX_VALUE }
        .thenBy { it.path.toString() }

fun indexedSymbolQuality(symbol: IndexedSymbol): Int {
    var score = 0
    if (sourceLikePath(symbol.path)) score += 600
    if (sourceLikeUri(symbol.uri)) score += 400
    if (symbol.documentation?.isNotBlank() == true) score += 220
    if (symbol.selectionRange.start.line != 0 || symbol.selectionRange.start.character != 0 || symbol.range.end.line != 0 || symbol.range.end.character != 0) {
        score += 120
    }
    if (symbol.uri.startsWith("jar:") && symbol.uri.endsWith(".class")) score -= 900
    if (symbol.path.toString().startsWith("/binary-libraries/")) score -= 900
    if (symbol.uri.startsWith("file:///jdk-reflection/")) score -= 1_000
    return score
}

private fun sourceLikeUri(uri: String): Boolean =
    uri.endsWith(".kt") || uri.endsWith(".kts") || uri.endsWith(".java")

private fun sourceLikePath(path: Path): Boolean =
    path.fileName?.toString()?.substringAfterLast('.', "").orEmpty() in setOf("kt", "kts", "java")

private fun buildPrefixIndex(symbols: List<IndexedSymbol>): Map<String, List<IndexedSymbol>> {
    val buckets = linkedMapOf<String, MutableList<IndexedSymbol>>()
    symbols.forEach { symbol ->
        val normalizedName = symbol.name.lowercase()
        if (normalizedName.isBlank()) return@forEach
        for (length in 1..minOf(3, normalizedName.length)) {
            val prefix = normalizedName.take(length)
            buckets.getOrPut(prefix) { mutableListOf() }.add(symbol)
        }
    }
    return buckets.mapValues { (_, group) -> group.sortedWith(preferredIndexedSymbolComparator()) }
}
