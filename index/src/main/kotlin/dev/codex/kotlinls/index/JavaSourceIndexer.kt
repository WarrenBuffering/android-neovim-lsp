package dev.codex.kotlinls.index

import dev.codex.kotlinls.protocol.SymbolKind
import dev.codex.kotlinls.workspace.LineIndex
import dev.codex.kotlinls.workspace.toDocumentUri
import java.nio.file.Path
import kotlin.io.path.readText

internal object JavaSourceIndexer {
    fun index(path: Path, moduleName: String): List<IndexedSymbol> {
        val text = runCatching { path.readText() }.getOrNull() ?: return emptyList()
        val packageName = PACKAGE_REGEX.find(text)?.groupValues?.get(1).orEmpty()
        val lineIndex = LineIndex.build(text)
        val classes = CLASS_REGEX.findAll(text).map { match ->
            val classKind = when (match.groupValues[1]) {
                "interface" -> SymbolKind.INTERFACE
                "enum" -> SymbolKind.ENUM
                "@interface" -> SymbolKind.CLASS
                else -> SymbolKind.CLASS
            }
            JavaClassMatch(
                name = match.groupValues[2],
                fqName = listOfNotNull(packageName.takeIf { it.isNotBlank() }, match.groupValues[2]).joinToString("."),
                kind = classKind,
                range = lineIndex.range(match.range.first, match.range.last + 1),
                selectionRange = lineIndex.range(match.range.first + match.value.indexOf(match.groupValues[2]), match.range.first + match.value.indexOf(match.groupValues[2]) + match.groupValues[2].length),
                signature = match.value.lineSequence().first().trim(),
                supertypes = (match.groupValues[3].takeIf { it.isNotBlank() }?.let(::listOf).orEmpty() +
                    match.groupValues[4].split(',').map { it.trim() }.filter { it.isNotBlank() }),
                documentation = extractLeadingDoc(text, match.range.first),
            )
        }.toList()
        if (classes.isEmpty()) return emptyList()
        val primaryClass = classes.first()
        val classSymbols = classes.map { classMatch ->
            IndexedSymbol(
                id = classMatch.fqName,
                name = classMatch.name,
                fqName = classMatch.fqName,
                kind = classMatch.kind,
                path = path,
                uri = path.toDocumentUri(),
                range = classMatch.range,
                selectionRange = classMatch.selectionRange,
                signature = classMatch.signature,
                documentation = classMatch.documentation,
                packageName = packageName,
                moduleName = moduleName,
                importable = true,
                resultType = classMatch.fqName,
                supertypes = classMatch.supertypes,
            )
        }
        val methodSymbols = METHOD_REGEX.findAll(text).mapNotNull { match ->
            val name = match.groupValues[2]
            val fqName = "${primaryClass.fqName}.$name"
            val selectionStart = match.range.first + match.value.indexOf(name)
            val parametersText = match.groupValues[3].trim()
            IndexedSymbol(
                id = "$fqName@${match.range.first}",
                name = name,
                fqName = fqName,
                kind = if (name == primaryClass.name) SymbolKind.CONSTRUCTOR else SymbolKind.FUNCTION,
                path = path,
                uri = path.toDocumentUri(),
                range = lineIndex.range(match.range.first, match.range.last + 1),
                selectionRange = lineIndex.range(selectionStart, selectionStart + name.length),
                containerName = primaryClass.name,
                containerFqName = primaryClass.fqName,
                signature = match.value.lineSequence().first().trim(),
                documentation = extractLeadingDoc(text, match.range.first),
                packageName = packageName,
                moduleName = moduleName,
                importable = false,
                resultType = match.groupValues[1].takeIf { it.isNotBlank() && name != primaryClass.name },
                parameterCount = parametersText.split(',').map { it.trim() }.count { it.isNotBlank() },
            )
        }.toList()
        return classSymbols + methodSymbols
    }

    private fun extractLeadingDoc(text: String, declarationOffset: Int): String? {
        val prefix = text.substring(0, declarationOffset)
        val end = prefix.lastIndexOf("*/")
        if (end == -1) return null
        val start = prefix.lastIndexOf("/**", end)
        if (start == -1) return null
        val between = prefix.substring(end + 2)
        if (between.any { !it.isWhitespace() }) return null
        return prefix.substring(start + 3, end)
            .lineSequence()
            .map { it.trim().removePrefix("*").trim() }
            .joinToString("\n")
            .trim()
            .ifBlank { null }
    }

    private data class JavaClassMatch(
        val name: String,
        val fqName: String,
        val kind: Int,
        val range: dev.codex.kotlinls.protocol.Range,
        val selectionRange: dev.codex.kotlinls.protocol.Range,
        val signature: String,
        val supertypes: List<String>,
        val documentation: String?,
    )

    private val PACKAGE_REGEX = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_$.]*)\s*;""")
    private val CLASS_REGEX = Regex(
        """(?m)^\s*(?:public|protected|private|abstract|final|static|sealed|non-sealed|\s)*\b(class|interface|enum|record|@interface)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:extends\s+([A-Za-z0-9_$.]+))?(?:\s+implements\s+([A-Za-z0-9_$. ,]+))?""",
    )
    private val METHOD_REGEX = Regex(
        """(?m)^\s*(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp|\s)*([A-Za-z0-9_$.<>\[\]?]+)?\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:throws\s+[^{;]+)?[;{]""",
    )
}
