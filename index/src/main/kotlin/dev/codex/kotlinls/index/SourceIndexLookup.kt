package dev.codex.kotlinls.index

import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.workspace.LineIndex
import java.nio.file.Path

object SourceIndexLookup {
    fun resolveSymbol(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        position: Position,
    ): IndexedSymbol? {
        val normalizedPath = path.normalize()
        val ownDeclarations = index.symbolsByPath[normalizedPath].orEmpty()
        val currentModuleName = ownDeclarations.firstOrNull()?.moduleName
        ownDeclarations
            .filter { contains(it.selectionRange, position) }
            .minByOrNull { spanSize(it.selectionRange) }
            ?.let { return it }

        val token = identifierAt(text, position) ?: return null
        val imports = imports(text)
        val defaultImportedPackages = defaultImportPackages(normalizedPath)
        resolveImported(index, imports, defaultImportedPackages, token, normalizedPath, currentModuleName)?.let { return it }

        val packageName = packageName(text)
        ownDeclarations.firstOrNull { it.name == token && it.importable.not() }?.let { return it }
        bestCandidate(
            candidates = index.symbolsByName[token].orEmpty(),
            currentPath = normalizedPath,
            packageName = packageName,
            moduleName = currentModuleName,
            importableOnly = true,
        )?.let { return it }
        bestCandidate(
            candidates = index.symbolsByName[token].orEmpty(),
            currentPath = normalizedPath,
            packageName = packageName,
            moduleName = currentModuleName,
            importableOnly = false,
        )?.let { return it }
        return null
    }

    fun supportPackages(
        path: Path,
        text: String,
    ): Set<String> =
        buildSet {
            addAll(defaultImportPackages(path))
            addAll(importedPackages(text))
        }

    fun importedPackages(text: String): Set<String> =
        IMPORT_PATH_REGEX.findAll(text)
            .mapNotNull { match ->
                val importedPath = match.groupValues[1]
                when {
                    importedPath.endsWith(".*") -> importedPath.removeSuffix(".*").takeIf { it.isNotBlank() }
                    else -> importedPath.substringBeforeLast('.', missingDelimiterValue = "").takeIf { it.isNotBlank() }
                }
            }
            .toSet()

    private fun resolveImported(
        index: WorkspaceIndex,
        imports: List<SourceImport>,
        defaultImportedPackages: Set<String>,
        token: String,
        currentPath: Path,
        moduleName: String?,
    ): IndexedSymbol? {
        val importedCandidates = imports
            .filter { it.visibleName == token }
            .mapNotNull { import -> index.symbolsByFqName[import.fqName] }
        val defaultImportedCandidates = index.symbolsByName[token]
            .orEmpty()
            .filter { candidate -> candidate.packageName in defaultImportedPackages }
        return bestCandidate(
            candidates = (importedCandidates + defaultImportedCandidates).distinctBy { it.id },
            currentPath = currentPath,
            packageName = null,
            moduleName = moduleName,
            importableOnly = false,
        )
    }

    private fun bestCandidate(
        candidates: List<IndexedSymbol>,
        currentPath: Path,
        packageName: String?,
        moduleName: String?,
        importableOnly: Boolean,
    ): IndexedSymbol? =
        candidates
            .asSequence()
            .filter { candidate -> !importableOnly || candidate.importable }
            .sortedWith(
                compareBy<IndexedSymbol>(
                    { candidate -> -indexedSymbolQuality(candidate) },
                    { candidate -> if (packageName != null && candidate.packageName == packageName) 0 else 1 },
                    { candidate -> if (moduleName != null && candidate.moduleName == moduleName) 0 else 1 },
                    { candidate -> pathDistance(currentPath, candidate.path.normalize()) },
                    { candidate -> candidate.fqName?.length ?: Int.MAX_VALUE },
                    { candidate -> candidate.selectionRange.start.line },
                ),
            )
            .firstOrNull()

    private fun pathDistance(from: Path, to: Path): Int {
        val left = from.normalize()
        val right = to.normalize()
        val maxCommon = minOf(left.nameCount, right.nameCount)
        var common = 0
        while (common < maxCommon && left.getName(common) == right.getName(common)) {
            common += 1
        }
        return (left.nameCount - common) + (right.nameCount - common)
    }

    fun identifierAt(text: String, position: Position): String? {
        val lineIndex = LineIndex.build(text)
        if (position.line < 0 || position.line >= text.lineSequence().count()) return null
        var offset = lineIndex.offset(position).coerceIn(0, text.length)
        if (offset >= text.length && text.isNotEmpty()) {
            offset = text.length - 1
        }
        if (text.isEmpty()) return null
        if (!text[offset].isIdentifierChar() && offset > 0 && text[offset - 1].isIdentifierChar()) {
            offset -= 1
        }
        if (!text[offset].isIdentifierChar()) return null
        var start = offset
        var end = offset + 1
        while (start > 0 && text[start - 1].isIdentifierChar()) start--
        while (end < text.length && text[end].isIdentifierChar()) end++
        return text.substring(start, end)
    }

    fun packageName(text: String): String =
        PACKAGE_REGEX.find(text)?.groupValues?.get(1).orEmpty()

    fun imports(text: String): List<SourceImport> =
        IMPORT_REGEX.findAll(text)
            .map { match ->
                val fqName = match.groupValues[1]
                val alias = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
                SourceImport(
                    fqName = fqName,
                    visibleName = alias ?: fqName.substringAfterLast('.'),
                )
            }
            .toList()

    private fun contains(range: dev.codex.kotlinls.protocol.Range, position: Position): Boolean =
        compare(range.start, position) <= 0 && compare(position, range.end) <= 0

    private fun compare(left: Position, right: Position): Int =
        when {
            left.line != right.line -> left.line.compareTo(right.line)
            else -> left.character.compareTo(right.character)
        }

    private fun spanSize(range: dev.codex.kotlinls.protocol.Range): Int =
        ((range.end.line - range.start.line) * 10_000) + (range.end.character - range.start.character)

    private fun Char.isIdentifierChar(): Boolean = isLetterOrDigit() || this == '_' || this == '$'

    private fun defaultImportPackages(path: Path): Set<String> =
        when (path.fileName?.toString()?.substringAfterLast('.', "").orEmpty()) {
            "kt", "kts" -> DEFAULT_KOTLIN_IMPORT_PACKAGES
            "java" -> DEFAULT_JAVA_IMPORT_PACKAGES
            else -> emptySet()
        }

    private val PACKAGE_REGEX = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_$.]*)\s*$""")
    private val IMPORT_REGEX = Regex("""(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_$.]*)(?:\s+as\s+([A-Za-z_][A-Za-z0-9_]*))?\s*$""")
    private val IMPORT_PATH_REGEX = Regex("""(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_$.]*(?:\.\*)?)(?:\s+as\s+([A-Za-z_][A-Za-z0-9_]*))?\s*$""")
    private val DEFAULT_KOTLIN_IMPORT_PACKAGES = setOf(
        "java.lang",
        "kotlin",
        "kotlin.annotation",
        "kotlin.collections",
        "kotlin.comparisons",
        "kotlin.io",
        "kotlin.jvm",
        "kotlin.ranges",
        "kotlin.sequences",
        "kotlin.text",
    )
    private val DEFAULT_JAVA_IMPORT_PACKAGES = setOf("java.lang")
}

data class SourceImport(
    val fqName: String,
    val visibleName: String,
)
