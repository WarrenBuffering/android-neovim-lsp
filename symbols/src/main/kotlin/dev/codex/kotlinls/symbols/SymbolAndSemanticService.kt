package dev.codex.kotlinls.symbols

import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.protocol.DocumentSymbol
import dev.codex.kotlinls.protocol.DocumentSymbolParams
import dev.codex.kotlinls.protocol.SemanticTokens
import dev.codex.kotlinls.protocol.SymbolInformation
import dev.codex.kotlinls.protocol.WorkspaceSymbolParams
import dev.codex.kotlinls.workspace.LineIndex
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

class SymbolAndSemanticService {
    fun documentSymbols(index: WorkspaceIndex, params: DocumentSymbolParams): List<DocumentSymbol> {
        val symbols = index.symbols.filter { it.uri == params.textDocument.uri }.sortedWith(
            compareBy({ it.range.start.line }, { it.range.start.character }),
        )
        val roots = mutableListOf<DocumentSymbolNode>()
        val stack = mutableListOf<DocumentSymbolNode>()
        symbols.forEach { symbol ->
            val node = DocumentSymbolNode(
                symbol = DocumentSymbol(
                    name = symbol.name,
                    detail = symbol.signature,
                    kind = symbol.kind,
                    range = symbol.range,
                    selectionRange = symbol.selectionRange,
                ),
            )
            while (stack.isNotEmpty() && !contains(stack.last().symbol.range, symbol.range)) {
                stack.removeLast()
            }
            if (stack.isEmpty()) {
                roots += node
            } else {
                stack.last().children += node
            }
            stack += node
        }
        return roots.map { it.toLsp() }
    }

    fun workspaceSymbols(index: WorkspaceIndex, params: WorkspaceSymbolParams): List<SymbolInformation> =
        index.symbols
            .asSequence()
            .filter { it.name.contains(params.query, ignoreCase = true) || it.fqName?.contains(params.query, ignoreCase = true) == true }
            .take(100)
            .map { symbol ->
                SymbolInformation(
                    name = symbol.name,
                    kind = symbol.kind,
                    location = dev.codex.kotlinls.protocol.Location(symbol.uri, symbol.selectionRange),
                    containerName = symbol.containerName,
                )
            }
            .toList()

    fun semanticTokens(snapshot: WorkspaceAnalysisSnapshot, params: dev.codex.kotlinls.protocol.SemanticTokensParams): SemanticTokens {
        val file = snapshot.filesByUri[params.textDocument.uri] ?: return SemanticTokens(emptyList())
        val lineIndex = LineIndex.build(file.text)
        val tuples = mutableListOf<IntArray>()
        fun emit(startOffset: Int, length: Int, tokenType: Int, tokenModifiers: Int = 0) {
            val position = lineIndex.position(startOffset)
            tuples += intArrayOf(position.line, position.character, length, tokenType, tokenModifiers)
        }

        file.ktFile.collectDescendantsOfType<KtNamedDeclaration>().forEach { declaration ->
            val identifier = declaration.nameIdentifier ?: return@forEach
            emit(identifier.textRange.startOffset, identifier.textLength, semanticTokenType(declaration))
        }
        file.ktFile.node.getChildren(null).forEach { child ->
            if (child.elementType == KtTokens.PACKAGE_KEYWORD || child.elementType == KtTokens.IMPORT_KEYWORD) {
                emit(child.startOffset, child.textLength, 15)
            }
        }
        val encoded = mutableListOf<Int>()
        var previousLine = 0
        var previousChar = 0
        tuples.sortedWith(compareBy<IntArray> { it[0] }.thenBy { it[1] }).forEach { token ->
            val lineDelta = token[0] - previousLine
            val charDelta = if (lineDelta == 0) token[1] - previousChar else token[1]
            encoded += lineDelta
            encoded += charDelta
            encoded += token[2]
            encoded += token[3]
            encoded += token[4]
            previousLine = token[0]
            previousChar = token[1]
        }
        return SemanticTokens(encoded)
    }

    private fun semanticTokenType(declaration: KtNamedDeclaration): Int = when (declaration) {
        is org.jetbrains.kotlin.psi.KtClass -> 1
        is org.jetbrains.kotlin.psi.KtObjectDeclaration -> 1
        is org.jetbrains.kotlin.psi.KtNamedFunction -> 12
        is org.jetbrains.kotlin.psi.KtProperty -> 8
        is org.jetbrains.kotlin.psi.KtParameter -> 9
        else -> 9
    }

    private fun contains(outer: dev.codex.kotlinls.protocol.Range, inner: dev.codex.kotlinls.protocol.Range): Boolean =
        (outer.start.line < inner.start.line ||
            (outer.start.line == inner.start.line && outer.start.character <= inner.start.character)) &&
            (outer.end.line > inner.end.line ||
                (outer.end.line == inner.end.line && outer.end.character >= inner.end.character))

    private data class DocumentSymbolNode(
        val symbol: DocumentSymbol,
        val children: MutableList<DocumentSymbolNode> = mutableListOf(),
    ) {
        fun toLsp(): DocumentSymbol = symbol.copy(children = children.map { it.toLsp() }.ifEmpty { null })
    }
}

