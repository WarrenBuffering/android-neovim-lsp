package dev.codex.kotlinls.completion

import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.index.IndexedSymbol
import dev.codex.kotlinls.index.SymbolResolver
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.protocol.SymbolKind
import dev.codex.kotlinls.protocol.CompletionItem
import dev.codex.kotlinls.protocol.CompletionItemKind
import dev.codex.kotlinls.protocol.CompletionList
import dev.codex.kotlinls.protocol.CompletionParams
import dev.codex.kotlinls.protocol.MarkupContent
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.TextEdit
import dev.codex.kotlinls.workspace.LineIndex
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.lexer.KtTokens
import java.nio.file.Path

class CompletionService {
    private val jetBrainsBridge: JetBrainsCompletionBridge? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        JetBrainsCompletionBridge.detect()
    }
    private val keywords = listOf(
        "class", "interface", "object", "fun", "val", "var", "when", "if", "else", "return", "null",
        "true", "false", "package", "import", "data", "sealed", "enum", "companion", "override", "suspend",
    )

    fun complete(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: CompletionParams,
    ): CompletionList {
        val file = snapshot.filesByUri[params.textDocument.uri] ?: return CompletionList(false, emptyList())
        val lineIndex = LineIndex.build(file.text)
        val offset = lineIndex.offset(params.position)
        val context = completionContext(snapshot, file, offset)
        val prefix = context.prefix
        val importedFqNames = file.ktFile.importDirectives.mapNotNull { it.importedFqName?.asString() }.toSet()
        val importContext = context.importContext
        if (importContext != null) {
            jetBrainsBridge?.complete(snapshot.project.root, file.originalPath, file.text, offset)?.let { bridged ->
                val filtered = bridged.filter { bridgeMatchesImportContext(it, importContext) }
                if (filtered.isNotEmpty()) {
                    return CompletionList(
                        isIncomplete = false,
                        items = bridgeItems(file, filtered, importedFqNames),
                    )
                }
            }
            return importCompletionList(index, file.text, importContext)
        }
        jetBrainsBridge?.complete(snapshot.project.root, file.originalPath, file.text, offset)?.let { bridged ->
            if (bridged.isNotEmpty()) {
                return CompletionList(
                    isIncomplete = false,
                    items = bridgeItems(file, bridged, importedFqNames),
                )
            }
        }
        val localCandidates = visibleLocals(file, offset)

        val ranked = linkedMapOf<String, RankedCompletion>()
        localCandidates.forEach { local ->
            ranked[local.label + "::local"] = RankedCompletion(local, 200)
        }

        val memberCandidates = receiverCandidates(context, index)
        if (memberCandidates.isNotEmpty()) {
            memberCandidates.forEach { symbol ->
                val symbolFqName = symbol.fqName
                val needsImport = symbol.importable &&
                    symbol.packageName.isNotBlank() &&
                    symbol.packageName != file.ktFile.packageFqName.asString() &&
                    symbolFqName !in importedFqNames
                val score = completionScore(context, symbol, file.originalPath.toString(), needsImport) + 140
                ranked.putIfAbsent(symbol.id, rankedSymbol(file.text, symbol, needsImport, score))
            }
        } else {
            keywords.filter { it.startsWith(prefix) }.forEach { keyword ->
                ranked.putIfAbsent(
                    "$keyword::keyword",
                    RankedCompletion(
                        item = CompletionItem(
                            label = keyword,
                            kind = CompletionItemKind.KEYWORD,
                            sortText = scoreToSortKey(50),
                            filterText = keyword,
                        ),
                        score = 50,
                    ),
                )
            }
            index.symbols.asSequence()
                .filter { it.name.startsWith(prefix) && it.importable }
                .take(200)
                .forEach { symbol ->
                    val symbolFqName = symbol.fqName
                    val needsImport = symbol.packageName.isNotBlank() &&
                        symbol.packageName != file.ktFile.packageFqName.asString() &&
                        symbolFqName !in importedFqNames
                    val score = completionScore(context, symbol, file.originalPath.toString(), needsImport)
                    ranked.putIfAbsent(symbol.id, rankedSymbol(file.text, symbol, needsImport, score))
                }
        }
        val items = ranked.values
            .sortedWith(compareByDescending<RankedCompletion> { it.score }.thenBy { it.item.label })
            .take(100)
            .map { it.item }
        return CompletionList(isIncomplete = false, items = items)
    }

    fun completeFromIndex(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        params: CompletionParams,
    ): CompletionList {
        val lineIndex = LineIndex.build(text)
        val offset = lineIndex.offset(params.position)
        val importContext = importCompletionContext(text, offset)
        if (importContext != null) {
            return importCompletionList(index, text, importContext)
        }
        val prefix = currentPrefix(text, offset)
        val ranked = linkedMapOf<String, RankedCompletion>()
        keywords.filter { it.startsWith(prefix) }.forEach { keyword ->
            ranked["$keyword::keyword"] = RankedCompletion(
                item = CompletionItem(
                    label = keyword,
                    kind = CompletionItemKind.KEYWORD,
                    sortText = scoreToSortKey(40),
                    filterText = keyword,
                ),
                score = 40,
            )
        }
        index.symbols.asSequence()
            .filter { it.name.startsWith(prefix) }
            .take(200)
            .forEach { symbol ->
                val needsImport = symbol.importable &&
                    symbol.fqName != null &&
                    symbol.path != path
                val score = when {
                    symbol.path == path -> 170
                    !needsImport -> 150
                    else -> 120
                }
                ranked.putIfAbsent(symbol.id, rankedSymbol(text, symbol, needsImport, score))
            }
        return CompletionList(
            isIncomplete = false,
            items = ranked.values
                .sortedWith(compareByDescending<RankedCompletion> { it.score }.thenBy { it.item.label })
                .take(100)
                .map { it.item },
        )
    }

    private fun bridgeItems(
        file: dev.codex.kotlinls.analysis.AnalyzedFile,
        items: List<JetBrainsBridgeCompletion>,
        importedFqNames: Set<String>,
    ): List<CompletionItem> =
        items.mapIndexedNotNull { index, candidate ->
            val fqName = candidate.fqName
            val needsImport = candidate.importable &&
                !fqName.isNullOrBlank() &&
                candidate.packageName.orEmpty().isNotBlank() &&
                candidate.packageName != file.ktFile.packageFqName.asString() &&
                fqName !in importedFqNames
            CompletionItem(
                label = candidate.label,
                kind = bridgeCompletionKind(candidate.kind),
                detail = candidate.detail,
                sortText = scoreToSortKey(900 - index),
                filterText = candidate.lookupString,
                additionalTextEdits = if (needsImport) addImportEdits(file.text, fqName) else null,
                data = mapOf(
                    "provider" to "jetbrains",
                    "fqName" to (fqName ?: ""),
                    "smart" to candidate.smart.toString(),
                ),
            )
        }.take(100)

    private fun visibleLocals(file: dev.codex.kotlinls.analysis.AnalyzedFile, offset: Int): List<CompletionItem> =
        file.ktFile.collectDescendantsOfType<KtNamedDeclaration>()
            .filter { declaration ->
                declaration.textRange.startOffset <= offset &&
                    declaration.name != null &&
                    declaration !is KtNamedFunction
            }
            .mapNotNull { declaration ->
                val name = declaration.name ?: return@mapNotNull null
                CompletionItem(
                    label = name,
                    kind = when (declaration) {
                        is KtParameter -> CompletionItemKind.VARIABLE
                        is KtProperty -> CompletionItemKind.PROPERTY
                        else -> CompletionItemKind.VARIABLE
                    },
                    detail = declaration.text.lineSequence().firstOrNull()?.trim(),
                    sortText = scoreToSortKey(200),
                    filterText = name,
                )
            }

    private fun currentPrefix(text: String, offset: Int): String {
        var cursor = offset.coerceIn(0, text.length)
        while (cursor > 0 && text[cursor - 1].isJavaIdentifierPart()) {
            cursor--
        }
        return text.substring(cursor, offset.coerceIn(cursor, text.length))
    }

    private fun importCompletionList(
        index: WorkspaceIndex,
        text: String,
        context: ImportCompletionContext,
    ): CompletionList {
        val ranked = linkedMapOf<String, RankedCompletion>()
        importPackageCandidates(index, context).forEach { packageName ->
            val score = when {
                packageName == context.segmentPrefix -> 240
                packageName.startsWith(context.segmentPrefix) -> 220
                else -> 180
            }
            val fqName = listOf(context.qualifier, packageName).filter { it.isNotBlank() }.joinToString(".")
            ranked.putIfAbsent(
                "package::$fqName",
                RankedCompletion(
                    item = CompletionItem(
                        label = packageName,
                        kind = CompletionItemKind.MODULE,
                        detail = fqName,
                        sortText = scoreToSortKey(score),
                        filterText = packageName,
                        insertText = packageName,
                        data = mapOf("package" to fqName),
                    ),
                    score = score,
                ),
            )
        }
        index.symbols.asSequence()
            .filter { symbol ->
                symbol.importable &&
                    symbol.name.startsWith(context.segmentPrefix) &&
                    when {
                        context.qualifier.isBlank() -> symbol.packageName.isBlank()
                        else -> symbol.packageName == context.qualifier
                    }
            }
            .take(200)
            .forEach { symbol ->
                val score = when {
                    symbol.name == context.segmentPrefix -> 230
                    symbol.name.startsWith(context.segmentPrefix) -> 200
                    else -> 160
                } + if (symbol.kind == dev.codex.kotlinls.protocol.SymbolKind.CLASS) 10 else 0
                ranked.putIfAbsent(
                    symbol.id,
                    RankedCompletion(
                        item = CompletionItem(
                            label = symbol.name,
                            kind = completionKind(symbol),
                            detail = symbol.signature,
                            documentation = symbol.documentation?.let { MarkupContent("markdown", it) },
                            sortText = scoreToSortKey(score),
                            filterText = symbol.name,
                            insertText = symbol.name,
                            data = mapOf(
                                "symbolId" to symbol.id,
                                "uri" to symbol.uri,
                            ),
                        ),
                        score = score,
                    ),
                )
            }
        return CompletionList(
            isIncomplete = false,
            items = ranked.values
                .sortedWith(compareByDescending<RankedCompletion> { it.score }.thenBy { it.item.label })
                .take(100)
                .map { it.item },
        )
    }

    private fun importPackageCandidates(
        index: WorkspaceIndex,
        context: ImportCompletionContext,
    ): List<String> =
        index.symbols.asSequence()
            .mapNotNull { symbol -> directImportPackageChild(symbol.packageName, context.qualifier) }
            .filter { child -> child.startsWith(context.segmentPrefix) }
            .distinct()
            .sorted()
            .take(100)
            .toList()

    private fun directImportPackageChild(
        packageName: String,
        qualifier: String,
    ): String? {
        if (packageName.isBlank()) return null
        val remainder = when {
            qualifier.isBlank() -> packageName
            packageName.startsWith("$qualifier.") -> packageName.removePrefix("$qualifier.")
            else -> return null
        }
        return remainder.substringBefore('.').ifBlank { null }
    }

    private fun importCompletionContext(text: String, offset: Int): ImportCompletionContext? {
        val safeOffset = offset.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0)).let { index ->
            if (index == -1) 0 else index + 1
        }
        val linePrefix = text.substring(lineStart, safeOffset)
        val trimmed = linePrefix.trimStart()
        if (!trimmed.startsWith("import ")) return null
        val importedPath = trimmed.removePrefix("import ")
            .substringBefore(" as ")
            .trim()
        if (importedPath.any { !it.isLetterOrDigit() && it != '_' && it != '.' }) return null
        val qualifier = importedPath.substringBeforeLast('.', "")
        val segmentPrefix = importedPath.substringAfterLast('.', importedPath)
        return ImportCompletionContext(
            qualifier = qualifier,
            segmentPrefix = segmentPrefix,
        )
    }

    private fun completionContext(
        snapshot: WorkspaceAnalysisSnapshot,
        file: dev.codex.kotlinls.analysis.AnalyzedFile,
        offset: Int,
    ): CompletionContext {
        val importContext = importCompletionContext(file.text, offset)
        if (importContext != null) {
            return CompletionContext(
                prefix = importContext.segmentPrefix,
                importContext = importContext,
            )
        }
        val prefix = currentPrefix(file.text, offset)
        val leaf = file.ktFile.findElementAt((offset - 1).coerceAtLeast(0))
            ?: file.ktFile.findElementAt(offset.coerceAtMost(file.text.length.coerceAtLeast(1) - 1))
            ?: return CompletionContext(prefix = prefix)
        val reference = leaf.parentsWithSelf.filterIsInstance<KtNameReferenceExpression>().firstOrNull()
            ?: return CompletionContext(prefix = prefix)
        val expectedType = expectedType(reference, snapshot)
        val (receiverExpression, memberAccess) = when (val parent = reference.parent) {
            is KtDotQualifiedExpression ->
                if (parent.selectorExpression == reference) parent.receiverExpression to true else null to false

            is KtSafeQualifiedExpression ->
                if (parent.selectorExpression == reference) parent.receiverExpression to true else null to false

            else -> null to false
        }
        if (!memberAccess || receiverExpression == null) {
            return CompletionContext(
                prefix = prefix,
                expectedType = expectedType?.first,
                expectedTypeFqName = expectedType?.second,
            )
        }
        val smartCastHint = inferredSmartCastType(reference, receiverExpression, file.text, offset)
        val receiverType = snapshot.bindingContext[org.jetbrains.kotlin.resolve.BindingContext.SMARTCAST, receiverExpression]?.defaultType
            ?: snapshot.bindingContext[org.jetbrains.kotlin.resolve.BindingContext.EXPRESSION_TYPE_INFO, receiverExpression]?.type
            ?: snapshot.bindingContext.getType(receiverExpression)
        return CompletionContext(
            prefix = prefix,
            receiverType = receiverType?.constructor?.declarationDescriptor?.let { it.name.asString() } ?: smartCastHint?.first,
            receiverTypeFqName = receiverType?.constructor?.declarationDescriptor?.let { descriptor ->
                org.jetbrains.kotlin.resolve.DescriptorUtils.getFqNameSafe(descriptor).asString()
            } ?: receiverType?.toString() ?: smartCastHint?.second,
            expectedType = expectedType?.first,
            expectedTypeFqName = expectedType?.second,
            memberAccess = true,
        )
    }

    private fun expectedType(
        reference: KtNameReferenceExpression,
        snapshot: WorkspaceAnalysisSnapshot,
    ): Pair<String, String?>? {
        val expression = reference.parentsWithSelf.filterIsInstance<KtExpression>().firstOrNull() ?: return null
        val expectedType = snapshot.bindingContext[org.jetbrains.kotlin.resolve.BindingContext.EXPECTED_EXPRESSION_TYPE, expression]
            ?: return null
        val fqName = expectedType.constructor.declarationDescriptor?.let { descriptor ->
            org.jetbrains.kotlin.resolve.DescriptorUtils.getFqNameSafe(descriptor).asString()
        }
        val shortName = fqName?.substringAfterLast('.')
            ?: expectedType.toString().substringAfterLast('.')
        return shortName to (fqName ?: expectedType.toString())
    }

    private fun receiverCandidates(
        context: CompletionContext,
        index: WorkspaceIndex,
    ): List<IndexedSymbol> {
        if (!context.memberAccess) return emptyList()
        val receiverShortName = context.receiverType ?: return emptyList()
        val receiverFqName = context.receiverTypeFqName
        val indexed = index.symbols.asSequence()
            .filter { symbol ->
                symbol.name.startsWith(context.prefix) &&
                    symbol.kind != dev.codex.kotlinls.protocol.SymbolKind.CLASS &&
                    symbol.kind != dev.codex.kotlinls.protocol.SymbolKind.INTERFACE &&
                    symbol.kind != dev.codex.kotlinls.protocol.SymbolKind.ENUM &&
                    symbol.kind != dev.codex.kotlinls.protocol.SymbolKind.OBJECT &&
                    receiverMatches(symbol, receiverShortName, receiverFqName)
            }
            .take(200)
            .toList()
        val reflected = reflectiveReceiverCandidates(context)
        return (indexed + reflected).distinctBy { it.id }
    }

    private fun receiverMatches(
        symbol: IndexedSymbol,
        receiverShortName: String,
        receiverFqName: String?,
    ): Boolean {
        val normalizedShort = normalizeTypeShortName(receiverShortName)
        val normalizedFqName = receiverFqName?.let(::normalizeTypeFqName)
        val ownerMatches = normalizeTypeShortName(symbol.containerName) == normalizedShort ||
            (normalizedFqName != null && normalizeTypeFqName(symbol.containerFqName) == normalizedFqName)
        if (ownerMatches) return true
        val receiverType = symbol.receiverType ?: return false
        return normalizeTypeFqName(receiverType) == normalizedFqName ||
            normalizeTypeShortName(receiverType) == normalizedShort
    }

    private fun completionScore(
        context: CompletionContext,
        symbol: IndexedSymbol,
        currentPath: String,
        needsImport: Boolean,
    ): Int {
        val prefix = context.prefix
        var score = 0
        score += when {
            symbol.name == prefix -> 220
            symbol.name.startsWith(prefix) -> 160
            symbol.name.contains(prefix, ignoreCase = true) -> 120
            else -> 80
        }
        if (symbol.path.toString() == currentPath) score += 35
        if (!needsImport) score += 20
        if (symbol.packageName.isBlank()) score += 5
        if (symbol.kind == dev.codex.kotlinls.protocol.SymbolKind.CLASS) score += 8
        if (matchesExpectedType(context, symbol)) score += 90
        return score
    }

    private fun matchesExpectedType(
        context: CompletionContext,
        symbol: IndexedSymbol,
    ): Boolean {
        val expectedFqName = context.expectedTypeFqName ?: return false
        val expectedType = context.expectedType ?: return false
        val resultType = symbol.resultType ?: return false
        return normalizeTypeFqName(resultType) == normalizeTypeFqName(expectedFqName) ||
            normalizeTypeShortName(resultType) == normalizeTypeShortName(expectedType)
    }

    private fun normalizeTypeFqName(typeText: String?): String? =
        typeText
            ?.substringBefore('<')
            ?.removeSuffix("?")
            ?.trim()
            ?.ifBlank { null }

    private fun normalizeTypeShortName(typeText: String?): String? =
        normalizeTypeFqName(typeText)
            ?.substringAfterLast('.')
            ?.ifBlank { null }

    private fun reflectiveReceiverCandidates(context: CompletionContext): List<IndexedSymbol> {
        val className = receiverRuntimeClassName(context) ?: return emptyList()
        val runtimeClass = runCatching { Class.forName(className) }.getOrNull() ?: return emptyList()
        return runtimeClass.methods.asSequence()
            .filter { method ->
                java.lang.reflect.Modifier.isPublic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    method.name.startsWith(context.prefix) &&
                    method.declaringClass != Any::class.java
            }
            .map { method ->
                IndexedSymbol(
                    id = "jdk-reflect::$className#${method.name}",
                    name = method.name,
                    fqName = "$className.${method.name}",
                    kind = if (method.parameterCount == 0) SymbolKind.PROPERTY else SymbolKind.FUNCTION,
                    path = Path.of("/jdk-reflection/$className"),
                    uri = "file:///jdk-reflection/$className",
                    range = Range(Position(0, 0), Position(0, 0)),
                    selectionRange = Range(Position(0, 0), Position(0, 0)),
                    containerName = runtimeClass.simpleName,
                    containerFqName = className,
                    signature = buildString {
                        append(method.name)
                        append("(): ")
                        append(method.returnType.simpleName)
                    },
                    documentation = null,
                    packageName = className.substringBeforeLast('.', ""),
                    moduleName = "jdk-reflection",
                    importable = false,
                    resultType = method.returnType.canonicalName ?: method.returnType.simpleName,
                )
            }
            .take(50)
            .toList()
    }

    private fun receiverRuntimeClassName(context: CompletionContext): String? {
        val fqName = normalizeTypeFqName(context.receiverTypeFqName)
        val shortName = normalizeTypeShortName(context.receiverType ?: fqName)
        if (!fqName.isNullOrBlank() && fqName.contains('.') && !fqName.startsWith("kotlin.")) return fqName
        return when (shortName) {
            "String" -> "java.lang.String"
            "List", "MutableList" -> "java.util.List"
            "Set", "MutableSet" -> "java.util.Set"
            "Map", "MutableMap" -> "java.util.Map"
            "Iterator" -> "java.util.Iterator"
            else -> null
        }
    }

    private fun inferredSmartCastType(
        reference: KtNameReferenceExpression,
        receiverExpression: KtExpression,
        fileText: String,
        offset: Int,
    ): Pair<String, String?>? {
        val receiverName = (receiverExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val smartCast = reference.parentsWithSelf
            .filterIsInstance<KtIfExpression>()
            .firstNotNullOfOrNull { ifExpression ->
                val thenBranch = ifExpression.then ?: return@firstNotNullOfOrNull null
                if (!thenBranch.textRange.contains(reference.textOffset)) return@firstNotNullOfOrNull null
                smartCastTypeFromCondition(ifExpression.condition, receiverName)
            }
            ?: inferSmartCastTypeFromText(receiverName, fileText, offset)
            ?: return null
        return smartCast.substringAfterLast('.') to smartCast
    }

    private fun smartCastTypeFromCondition(
        expression: KtExpression?,
        receiverName: String,
    ): String? = when (expression) {
        is KtParenthesizedExpression -> smartCastTypeFromCondition(expression.expression, receiverName)
        is KtBinaryExpression ->
            if (expression.operationToken == KtTokens.ANDAND) {
                smartCastTypeFromCondition(expression.left, receiverName)
                    ?: smartCastTypeFromCondition(expression.right, receiverName)
            } else {
                null
            }

        is KtIsExpression -> {
            val left = expression.leftHandSide as? KtNameReferenceExpression
            if (!expression.isNegated && left?.getReferencedName() == receiverName) {
                expression.typeReference?.text
            } else {
                null
            }
        }

        else -> null
    }

    private fun inferSmartCastTypeFromText(
        receiverName: String,
        fileText: String,
        offset: Int,
    ): String? {
        val prefix = fileText.substring(0, offset.coerceIn(0, fileText.length))
        val pattern = Regex(
            """if\s*\(\s*${Regex.escape(receiverName)}\s+is\s+([A-Za-z_][A-Za-z0-9_.]*)\s*\)\s*\{[\s\S]*\b${Regex.escape(receiverName)}\.[A-Za-z0-9_]*$""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        return pattern.findAll(prefix).lastOrNull()?.groupValues?.get(1)
    }

    private fun rankedSymbol(
        text: String,
        symbol: IndexedSymbol,
        needsImport: Boolean,
        score: Int,
    ): RankedCompletion {
        val symbolFqName = symbol.fqName
        return RankedCompletion(
            item = CompletionItem(
                label = symbol.name,
                kind = completionKind(symbol),
                detail = symbol.signature,
                documentation = symbol.documentation?.let { MarkupContent("markdown", it) },
                sortText = scoreToSortKey(score),
                filterText = symbol.name,
                additionalTextEdits = if (needsImport && symbolFqName != null) {
                    addImportEdits(text, symbolFqName)
                } else {
                    null
                },
                data = mapOf(
                    "symbolId" to symbol.id,
                    "uri" to symbol.uri,
                ),
            ),
            score = score,
        )
    }

    private fun completionKind(symbol: IndexedSymbol): Int = when (symbol.kind) {
        dev.codex.kotlinls.protocol.SymbolKind.CLASS,
        dev.codex.kotlinls.protocol.SymbolKind.OBJECT,
        dev.codex.kotlinls.protocol.SymbolKind.ENUM,
        dev.codex.kotlinls.protocol.SymbolKind.INTERFACE,
        -> CompletionItemKind.CLASS

        dev.codex.kotlinls.protocol.SymbolKind.FUNCTION,
        dev.codex.kotlinls.protocol.SymbolKind.CONSTRUCTOR,
        -> CompletionItemKind.FUNCTION

        dev.codex.kotlinls.protocol.SymbolKind.PROPERTY -> CompletionItemKind.PROPERTY
        else -> CompletionItemKind.VARIABLE
    }

    private fun bridgeCompletionKind(kind: String?): Int = when (kind) {
        "class", "interface", "enum", "object" -> CompletionItemKind.CLASS
        "function" -> CompletionItemKind.FUNCTION
        "property" -> CompletionItemKind.PROPERTY
        "package" -> CompletionItemKind.MODULE
        else -> CompletionItemKind.VARIABLE
    }

    private fun bridgeMatchesImportContext(
        candidate: JetBrainsBridgeCompletion,
        context: ImportCompletionContext,
    ): Boolean {
        if (candidate.kind == "package") {
            val fqPackage = candidate.fqName
                ?: listOfNotNull(candidate.packageName, candidate.label.takeIf { it.isNotBlank() })
                    .filter { it.isNotBlank() }
                    .joinToString(".")
            val child = directImportPackageChild(fqPackage, context.qualifier) ?: return false
            return child.startsWith(context.segmentPrefix)
        }
        val packageName = candidate.packageName
            ?: candidate.fqName?.substringBeforeLast('.', "")
            ?: return false
        return packageName == context.qualifier &&
            candidate.lookupString.startsWith(context.segmentPrefix)
    }

    private fun addImportEdits(text: String, fqName: String): List<TextEdit> {
        val lines = text.lines()
        val existingImportLines = lines.withIndex().filter { it.value.trimStart().startsWith("import ") }
        val insertionLine = when {
            existingImportLines.isNotEmpty() -> existingImportLines.last().index + 1
            lines.firstOrNull()?.startsWith("package ") == true -> 1
            else -> 0
        }
        val importText = "import $fqName\n"
        return listOf(
            TextEdit(
                range = Range(
                    start = Position(insertionLine, 0),
                    end = Position(insertionLine, 0),
                ),
                newText = importText,
            ),
        )
    }

    private fun scoreToSortKey(score: Int): String = (1000 - score).coerceAtLeast(0).toString().padStart(4, '0')

    private data class RankedCompletion(
        val item: CompletionItem,
        val score: Int,
    )

    private data class CompletionContext(
        val prefix: String,
        val receiverType: String? = null,
        val receiverTypeFqName: String? = null,
        val expectedType: String? = null,
        val expectedTypeFqName: String? = null,
        val memberAccess: Boolean = false,
        val importContext: ImportCompletionContext? = null,
    )

    private data class ImportCompletionContext(
        val qualifier: String,
        val segmentPrefix: String,
    )
}
