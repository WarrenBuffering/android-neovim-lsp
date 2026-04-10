package dev.codex.kotlinls.navigation

import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.index.CallEdge
import dev.codex.kotlinls.index.IndexedSymbol
import dev.codex.kotlinls.index.SourceIndexLookup
import dev.codex.kotlinls.index.SymbolResolver
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.protocol.CallHierarchyIncomingCall
import dev.codex.kotlinls.protocol.CallHierarchyItem
import dev.codex.kotlinls.protocol.CallHierarchyOutgoingCall
import dev.codex.kotlinls.protocol.DocumentHighlight
import dev.codex.kotlinls.protocol.DocumentHighlightParams
import dev.codex.kotlinls.protocol.FoldingRange
import dev.codex.kotlinls.protocol.FoldingRangeParams
import dev.codex.kotlinls.protocol.Location
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.SelectionRange
import dev.codex.kotlinls.protocol.SelectionRangeParams
import dev.codex.kotlinls.protocol.TextDocumentPositionParams
import dev.codex.kotlinls.protocol.TypeHierarchyItem
import dev.codex.kotlinls.workspace.LineIndex
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf

class NavigationService(
    private val resolver: SymbolResolver = SymbolResolver(),
) {
    fun definition(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: TextDocumentPositionParams,
    ): List<Location> {
        val symbol = resolver.symbolAt(snapshot, index, params.textDocument.uri, params.position)
            ?: fallbackSymbol(snapshot, index, params)
        if (symbol != null) {
            return listOf(Location(symbol.uri, symbol.selectionRange))
        }
        val descriptorLocation = resolver.descriptorAt(snapshot, params.textDocument.uri, params.position)
            ?.let { descriptor -> resolver.sourceLocation(snapshot, descriptor) }
        return descriptorLocation?.let(::listOf) ?: emptyList()
    }

    fun definitionFromIndex(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        params: TextDocumentPositionParams,
    ): List<Location> =
        SourceIndexLookup.resolveSymbol(index, path, text, params.position)
            ?.let { listOf(Location(it.uri, it.selectionRange)) }
            ?: emptyList()

    fun typeDefinition(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: TextDocumentPositionParams,
    ): List<Location> {
        val typeCandidate = resolver.expressionTypeSymbolAt(snapshot, index, params.textDocument.uri, params.position)
            ?: resolver.descriptorTypeSymbolAt(snapshot, index, params.textDocument.uri, params.position)
            ?: resolver.symbolAt(snapshot, index, params.textDocument.uri, params.position)
                ?.resultType
                ?.let { resultType ->
                    index.symbolsByFqName[resultType] ?: index.symbolsByName[resultType.substringAfterLast('.')]?.firstOrNull()
                }
            ?: return emptyList()
        return listOf(Location(typeCandidate.uri, typeCandidate.selectionRange))
    }

    fun typeDefinitionFromIndex(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        params: TextDocumentPositionParams,
    ): List<Location> {
        val symbol = SourceIndexLookup.resolveSymbol(index, path, text, params.position) ?: return emptyList()
        val typeCandidate = symbol.resultType
            ?.let { resultType ->
                index.symbolsByFqName[resultType] ?: index.symbolsByName[resultType.substringAfterLast('.')]?.firstOrNull()
            }
            ?: symbol.takeIf {
                it.kind == dev.codex.kotlinls.protocol.SymbolKind.CLASS ||
                    it.kind == dev.codex.kotlinls.protocol.SymbolKind.INTERFACE ||
                    it.kind == dev.codex.kotlinls.protocol.SymbolKind.ENUM ||
                    it.kind == dev.codex.kotlinls.protocol.SymbolKind.OBJECT
            }
            ?: return emptyList()
        return listOf(Location(typeCandidate.uri, typeCandidate.selectionRange))
    }

    fun references(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: TextDocumentPositionParams,
    ): List<Location> {
        val symbol = resolver.symbolAt(snapshot, index, params.textDocument.uri, params.position) ?: return emptyList()
        return buildList {
            add(Location(symbol.uri, symbol.selectionRange))
            addAll(index.referencesBySymbolId[symbol.id].orEmpty().map { Location(it.uri, it.range) })
        }
    }

    fun implementations(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: TextDocumentPositionParams,
    ): List<Location> {
        val symbol = resolver.symbolAt(snapshot, index, params.textDocument.uri, params.position) ?: return emptyList()
        return when (symbol.kind) {
            dev.codex.kotlinls.protocol.SymbolKind.FUNCTION,
            dev.codex.kotlinls.protocol.SymbolKind.PROPERTY,
            dev.codex.kotlinls.protocol.SymbolKind.CONSTRUCTOR,
            -> memberImplementations(index, symbol)

            else -> typeImplementations(index, symbol)
        }
    }

    fun documentHighlights(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: DocumentHighlightParams,
    ): List<DocumentHighlight> {
        val symbol = resolver.symbolAt(snapshot, index, params.textDocument.uri, params.position) ?: return emptyList()
        return buildList {
            if (symbol.uri == params.textDocument.uri) {
                add(DocumentHighlight(symbol.selectionRange, 1))
            }
            addAll(
                index.referencesBySymbolId[symbol.id].orEmpty()
                    .filter { it.uri == params.textDocument.uri }
                    .map { DocumentHighlight(it.range, 2) },
            )
        }
    }

    fun foldingRanges(snapshot: WorkspaceAnalysisSnapshot, params: FoldingRangeParams): List<FoldingRange> {
        val file = snapshot.filesByUri[params.textDocument.uri] ?: return emptyList()
        return file.ktFile.collectFoldables().mapNotNull { declaration ->
            val lineIndex = LineIndex.build(file.text)
            val start = lineIndex.position(declaration.textRange.startOffset)
            val end = lineIndex.position(declaration.textRange.endOffset)
            if (end.line <= start.line) {
                null
            } else {
                FoldingRange(start.line, end.line)
            }
        }
    }

    fun selectionRanges(snapshot: WorkspaceAnalysisSnapshot, params: SelectionRangeParams): List<SelectionRange> {
        val file = snapshot.filesByUri[params.textDocument.uri] ?: return emptyList()
        val lineIndex = LineIndex.build(file.text)
        return params.positions.map { position ->
            val offset = lineIndex.offset(position)
            val leaf = file.ktFile.findElementAt(offset) ?: return@map SelectionRange(range = dev.codex.kotlinls.protocol.Range(position, position))
            var current: SelectionRange? = null
            leaf.parentsWithSelf.forEach { element ->
                val range = lineIndex.range(element.textRange.startOffset, element.textRange.endOffset)
                current = SelectionRange(range = range, parent = current)
            }
            current ?: SelectionRange(dev.codex.kotlinls.protocol.Range(position, position))
        }
    }

    fun prepareCallHierarchy(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: TextDocumentPositionParams,
    ): List<CallHierarchyItem> = resolver.symbolAt(snapshot, index, params.textDocument.uri, params.position)
        ?.let { listOf(it.toCallHierarchyItem()) }
        ?: emptyList()

    fun incomingCalls(index: WorkspaceIndex, item: CallHierarchyItem): List<CallHierarchyIncomingCall> =
        index.incomingCalls[item.data?.get("symbolId")].orEmpty()
            .groupBy { it.callerSymbolId }
            .mapNotNull { (callerId, edges) ->
                val caller = index.symbolsById[callerId] ?: return@mapNotNull null
                CallHierarchyIncomingCall(from = caller.toCallHierarchyItem(), fromRanges = edges.map { it.range })
            }

    fun outgoingCalls(index: WorkspaceIndex, item: CallHierarchyItem): List<CallHierarchyOutgoingCall> =
        index.outgoingCalls[item.data?.get("symbolId")].orEmpty()
            .groupBy { it.calleeSymbolId }
            .mapNotNull { (calleeId, edges) ->
                val callee = index.symbolsById[calleeId] ?: return@mapNotNull null
                CallHierarchyOutgoingCall(to = callee.toCallHierarchyItem(), fromRanges = edges.map { it.range })
            }

    fun prepareTypeHierarchy(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: TextDocumentPositionParams,
    ): List<TypeHierarchyItem> = resolver.symbolAt(snapshot, index, params.textDocument.uri, params.position)
        ?.let { listOf(it.toTypeHierarchyItem()) }
        ?: emptyList()

    fun supertypes(index: WorkspaceIndex, item: TypeHierarchyItem): List<TypeHierarchyItem> {
        val symbol = index.symbolsById[item.data?.get("symbolId")] ?: return emptyList()
        return symbol.supertypes.mapNotNull { supertype ->
            index.symbolsByFqName[supertype] ?: index.symbolsByName[supertype.substringAfterLast('.')]?.firstOrNull()
        }.map { it.toTypeHierarchyItem() }
    }

    fun subtypes(index: WorkspaceIndex, item: TypeHierarchyItem): List<TypeHierarchyItem> {
        val symbol = index.symbolsById[item.data?.get("symbolId")] ?: return emptyList()
        return index.symbols
            .filter { candidate -> inheritsFrom(index, candidate, symbol) }
            .map { it.toTypeHierarchyItem() }
    }

    private fun typeImplementations(index: WorkspaceIndex, symbol: IndexedSymbol): List<Location> =
        index.symbols
            .filter { candidate -> inheritsFrom(index, candidate, symbol) }
            .map { Location(it.uri, it.selectionRange) }

    private fun memberImplementations(index: WorkspaceIndex, symbol: IndexedSymbol): List<Location> {
        val ownerFqName = symbol.containerFqName ?: return emptyList()
        return index.symbols
            .filter { candidate ->
                val candidateOwnerFqName = candidate.containerFqName ?: return@filter false
                candidate.id != symbol.id &&
                    candidate.name == symbol.name &&
                    candidate.kind == symbol.kind &&
                    candidate.parameterCount == symbol.parameterCount &&
                    inheritsFrom(index, candidateOwnerFqName, ownerFqName)
            }
            .map { Location(it.uri, it.selectionRange) }
    }

    private fun fallbackSymbol(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: TextDocumentPositionParams,
    ): IndexedSymbol? {
        val file = snapshot.filesByUri[params.textDocument.uri] ?: return null
        val offset = LineIndex.build(file.text).offset(params.position)
        val leaf = file.ktFile.findElementAt(offset) ?: return null
        val token = leaf.text.takeIf { it.all(Char::isJavaIdentifierPart) } ?: return null
        val importedFqName = file.ktFile.importDirectives
            .firstOrNull { it.importedName?.asString() == token }
            ?.importedFqName
            ?.asString()
        if (importedFqName != null) {
            return index.symbolsByFqName[importedFqName]
        }
        return index.symbolsByName[token]?.singleOrNull()
    }

    private fun KtFile.collectFoldables(): List<org.jetbrains.kotlin.com.intellij.psi.PsiElement> =
        buildList {
            addAll(declarations.filterIsInstance<KtDeclaration>())
            importList?.let { add(it) }
            addAll(collectChildren<KtBlockExpression>())
            addAll(collectChildren<KtClassOrObject>())
            addAll(collectChildren<KtNamedFunction>())
        }.distinctBy { it.textRange.startOffset to it.textRange.endOffset }

    private inline fun <reified T : org.jetbrains.kotlin.com.intellij.psi.PsiElement> KtFile.collectChildren(): List<T> =
        collectDescendantsOfType<T>()

    private fun inheritsFrom(
        index: WorkspaceIndex,
        candidate: IndexedSymbol,
        target: IndexedSymbol,
    ): Boolean {
        val targetFqName = target.fqName ?: return false
        return inheritsFrom(index, candidate, targetFqName)
    }

    private fun inheritsFrom(
        index: WorkspaceIndex,
        candidate: IndexedSymbol,
        targetFqName: String,
    ): Boolean {
        val candidateFqName = candidate.fqName ?: return false
        return inheritsFrom(index, candidateFqName, targetFqName)
    }

    private fun inheritsFrom(
        index: WorkspaceIndex,
        candidateFqName: String,
        targetFqName: String,
        visited: MutableSet<String> = mutableSetOf(),
    ): Boolean {
        if (!visited.add(candidateFqName)) return false
        val candidate = index.symbolsByFqName[candidateFqName] ?: return false
        if (candidate.supertypes.any { it == targetFqName || it.endsWith(".${targetFqName.substringAfterLast('.')}") }) {
            return true
        }
        return candidate.supertypes.any { supertype ->
            supertype == targetFqName || inheritsFrom(index, supertype, targetFqName, visited)
        }
    }
}

private fun IndexedSymbol.toCallHierarchyItem(): CallHierarchyItem =
    CallHierarchyItem(
        name = name,
        kind = kind,
        uri = uri,
        range = range,
        selectionRange = selectionRange,
        detail = signature,
        data = mapOf("symbolId" to id),
    )

private fun IndexedSymbol.toTypeHierarchyItem(): TypeHierarchyItem =
    TypeHierarchyItem(
        name = name,
        kind = kind,
        uri = uri,
        range = range,
        selectionRange = selectionRange,
        detail = signature,
        data = mapOf("symbolId" to id),
    )
