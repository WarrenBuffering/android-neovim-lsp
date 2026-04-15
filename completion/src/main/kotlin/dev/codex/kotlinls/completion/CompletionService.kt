package dev.codex.kotlinls.completion

import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.index.IndexedParameter
import dev.codex.kotlinls.index.IndexedSymbol
import dev.codex.kotlinls.index.SourceIndexLookup
import dev.codex.kotlinls.index.SymbolResolver
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.index.indexedSymbolCompletionDetail
import dev.codex.kotlinls.index.indexedSymbolDocumentation
import dev.codex.kotlinls.index.indexedSymbolQuality
import dev.codex.kotlinls.index.preferredIndexedSymbolComparator
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
import java.nio.file.Path
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
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
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.resolve.BindingContext

class CompletionService {
    @Volatile
    private var jetBrainsBridge: JetBrainsCompletionBridge? = null
    private val keywords = listOf(
        "class", "interface", "object", "fun", "val", "var", "when", "if", "else", "return", "null",
        "true", "false", "package", "import", "data", "sealed", "enum", "companion", "override", "suspend",
    )

    fun complete(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: CompletionParams,
        allowBridge: Boolean = false,
    ): CompletionList {
        val file = snapshot.filesByUri[params.textDocument.uri] ?: return CompletionList(false, emptyList())
        val lineIndex = LineIndex.build(file.text)
        val offset = lineIndex.offset(params.position)
        val context = completionContext(snapshot, file, offset)
        val prefix = context.prefix
        val importedFqNames = file.ktFile.importDirectives.mapNotNull { it.importedFqName?.asString() }.toSet()
        val importContext = context.importContext
        if (importContext != null) {
            if (allowBridge) {
                acquireJetBrainsBridge(snapshot.project.root)?.complete(snapshot.project.root, file.originalPath, file.text, offset)?.let { bridged ->
                    val filtered = bridged.filter { bridgeMatchesImportContext(it, importContext) }
                    if (filtered.isNotEmpty()) {
                        return CompletionList(
                            isIncomplete = false,
                            items = bridgeItems(file, filtered, importedFqNames),
                        )
                    }
                }
            }
            return importCompletionList(index, file.text, importContext)
        }
        semanticNamedArgumentCompletions(snapshot, params)?.let { return it }
        namedArgumentCompletionsFromIndex(index, file.originalPath, file.text, params)?.let { return it }
        if (allowBridge) {
            acquireJetBrainsBridge(snapshot.project.root)?.complete(snapshot.project.root, file.originalPath, file.text, offset)?.let { bridged ->
                if (bridged.isNotEmpty()) {
                    return CompletionList(
                        isIncomplete = false,
                        items = bridgeItems(file, bridged, importedFqNames),
                    )
                }
            }
        }
        val localCandidates = visibleLocals(file, offset)

        val ranked = linkedMapOf<String, RankedCompletion>()
        localCandidates.forEach { local ->
            ranked[local.label + "::local"] = RankedCompletion(local, 200)
        }

        val memberCandidates = receiverCandidates(context, index)
        val syntheticMembers = syntheticSemanticMemberCompletions(context)
        if (memberCandidates.isNotEmpty() || syntheticMembers.isNotEmpty()) {
            memberCandidates.forEach { symbol ->
                val symbolFqName = symbol.fqName
                val needsImport = symbol.importable &&
                    symbol.packageName.isNotBlank() &&
                    symbol.packageName != file.ktFile.packageFqName.asString() &&
                    symbolFqName !in importedFqNames
                val score = completionScore(context, symbol, file.originalPath.toString(), needsImport) + 140 +
                    indexedSymbolQuality(symbol) / 25
                mergeRankedCompletion(
                    ranked = ranked,
                    key = completionCandidateKey(symbol),
                    candidate = rankedSymbol(file.text, symbol, needsImport, score),
                )
            }
            syntheticMembers.forEachIndexed { syntheticIndex, item ->
                val score = 250 - syntheticIndex
                ranked.putIfAbsent(
                    "semantic-synthetic::${item.label}",
                    RankedCompletion(item.copy(sortText = scoreToSortKey(score)), score),
                )
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
                    val score = completionScore(context, symbol, file.originalPath.toString(), needsImport) +
                        indexedSymbolQuality(symbol) / 25
                    mergeRankedCompletion(
                        ranked = ranked,
                        key = completionCandidateKey(symbol),
                        candidate = rankedSymbol(file.text, symbol, needsImport, score),
                    )
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
        resultLimit: Int = 100,
    ): CompletionList {
        val lineIndex = LineIndex.build(text)
        val offset = lineIndex.offset(params.position)
        val packageContext = packageCompletionContext(text, offset)
        if (packageContext != null) {
            return packageCompletionList(index, packageContext)
        }
        val importContext = importCompletionContext(text, offset)
        if (importContext != null) {
            return importCompletionList(index, text, importContext)
        }
        namedArgumentCompletionsFromIndex(index, path, text, params, resultLimit)?.let { return it }
        val importedFqNames = SourceIndexLookup.imports(text).mapTo(linkedSetOf()) { it.fqName }
        val prefix = currentPrefix(text, offset)
        if (isMemberAccessContext(text, offset)) {
            return memberCompletionListFromIndex(
                index = index,
                path = path,
                text = text,
                offset = offset,
                prefix = prefix,
                importedFqNames = importedFqNames,
                resultLimit = resultLimit,
            )
        }
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
        val candidateLimit = maxOf(resultLimit * 4, 250)
        index.completionCandidates(prefix)
            .take(candidateLimit)
            .forEach { symbol ->
                val imported = symbol.fqName != null && symbol.fqName in importedFqNames
                val needsImport = symbol.importable &&
                    symbol.fqName != null &&
                    symbol.path != path &&
                    !imported
                val score = when {
                    symbol.path == path -> 170
                    imported -> 165
                    !needsImport -> 150
                    else -> 120
                } + if (symbol.receiverType != null && imported) 35 else 0 +
                    indexedSymbolQuality(symbol) / 25
                mergeRankedCompletion(
                    ranked = ranked,
                    key = completionCandidateKey(symbol),
                    candidate = rankedSymbol(text, symbol, needsImport, score),
                )
            }
        syntheticMemberCompletions(index, path, text, offset, prefix)
            .forEachIndexed { syntheticIndex, item ->
                val score = 210 - syntheticIndex
                ranked.putIfAbsent(
                    "synthetic::${item.label}",
                    RankedCompletion(item.copy(sortText = scoreToSortKey(score)), score),
                )
            }
        return CompletionList(
            isIncomplete = false,
            items = ranked.values
                .sortedWith(compareByDescending<RankedCompletion> { it.score }.thenBy { it.item.label })
                .take(resultLimit)
                .map { it.item },
        )
    }

    fun semanticNamedArgumentCompletions(
        snapshot: WorkspaceAnalysisSnapshot,
        params: CompletionParams,
        resultLimit: Int = 100,
    ): CompletionList? {
        val file = snapshot.filesByUri[params.textDocument.uri] ?: return null
        val offset = LineIndex.build(file.text).offset(params.position)
        val context = namedArgumentCompletionContext(snapshot, file, offset) ?: return null
        val items = context.descriptor.valueParameters
            .asSequence()
            .mapNotNull { parameter ->
                val parameterName = parameter.name.asString().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (parameterName in context.usedParameterNames) return@mapNotNull null
                if (context.prefix.isNotBlank() && !parameterName.startsWith(context.prefix)) return@mapNotNull null
                val score = when {
                    parameterName == context.prefix -> 980
                    context.prefix.isBlank() -> 930
                    parameterName.startsWith(context.prefix) -> 950
                    else -> 900
                }
                CompletionItem(
                    label = parameterName,
                    kind = CompletionItemKind.PROPERTY,
                    detail = parameter.type.toString(),
                    sortText = scoreToSortKey(score),
                    filterText = parameterName,
                    insertText = "$parameterName = ",
                    insertTextFormat = 1,
                    data = mapOf(
                        "provider" to "semantic-named-arg",
                        "parameter" to parameterName,
                    ),
                )
            }
            .sortedWith(compareBy<CompletionItem> { it.sortText }.thenBy { it.label })
            .take(resultLimit)
            .toList()
        return items.takeIf { it.isNotEmpty() }?.let { CompletionList(isIncomplete = false, items = it) }
    }

    fun namedArgumentCompletionsFromIndex(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        params: CompletionParams,
        resultLimit: Int = 100,
    ): CompletionList? {
        val offset = LineIndex.build(text).offset(params.position)
        val context = indexedNamedArgumentCompletionContext(text, offset) ?: return null
        val ranked = linkedMapOf<String, RankedCompletion>()
        indexedNamedArgumentCandidates(index, path, text, context.calleeName)
            .forEachIndexed { candidateIndex, symbol ->
                symbol.parameters.forEach { parameter ->
                    if (parameter.name.isBlank()) return@forEach
                    if (parameter.name in context.usedParameterNames) return@forEach
                    if (context.prefix.isNotBlank() && !parameter.name.startsWith(context.prefix, ignoreCase = true)) {
                        return@forEach
                    }
                    val score = 320 - (candidateIndex * 10) +
                        when {
                            parameter.name == context.prefix -> 40
                            parameter.name.startsWith(context.prefix, ignoreCase = true) -> 20
                            context.prefix.isBlank() -> 10
                            else -> 0
                        } +
                        indexedSymbolQuality(symbol) / 25
                    mergeRankedCompletion(
                        ranked = ranked,
                        key = "indexed-named-arg::${parameter.name}",
                        candidate = RankedCompletion(
                            item = CompletionItem(
                                label = parameter.name,
                                kind = CompletionItemKind.PROPERTY,
                                detail = indexedNamedArgumentDetail(parameter, symbol),
                                sortText = scoreToSortKey(score),
                                filterText = parameter.name,
                                insertText = "${parameter.name} = ",
                                insertTextFormat = 1,
                                data = mapOf(
                                    "provider" to "index-named-arg",
                                    "symbolId" to symbol.id,
                                    "callee" to context.calleeName,
                                ),
                            ),
                            score = score,
                        ),
                    )
                }
            }
        if (ranked.isEmpty()) return null
        return CompletionList(
            isIncomplete = false,
            items = ranked.values
                .sortedWith(compareByDescending<RankedCompletion> { it.score }.thenBy { it.item.label })
                .take(resultLimit)
                .map { it.item },
        )
    }

    fun classifyCompletionRoute(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        params: CompletionParams,
        bridgeAvailable: Boolean,
    ): CompletionRoutingDecision {
        if (!bridgeAvailable) {
            return CompletionRoutingDecision(
                route = CompletionRoute.INDEX,
                reason = "bridge-unavailable",
            )
        }
        val offset = LineIndex.build(text).offset(params.position)
        if (packageCompletionContext(text, offset) != null) {
            return CompletionRoutingDecision(
                route = CompletionRoute.INDEX,
                reason = "package-context",
            )
        }
        if (importCompletionContext(text, offset) != null) {
            return CompletionRoutingDecision(
                route = CompletionRoute.INDEX,
                reason = "import-context",
            )
        }
        if (isTypePositionContext(text, offset)) {
            return CompletionRoutingDecision(
                route = CompletionRoute.INDEX,
                reason = "type-position",
            )
        }
        if (isExpectedTypeSensitiveExpression(text, offset)) {
            return CompletionRoutingDecision(
                route = CompletionRoute.BRIDGE,
                reason = "expected-type-sensitive-expression",
            )
        }
        if (isFlowSensitiveExpression(text, offset)) {
            return CompletionRoutingDecision(
                route = CompletionRoute.BRIDGE,
                reason = "flow-sensitive-expression",
            )
        }
        if (!isMemberAccessContext(text, offset)) {
            if (isLexicalScopeSensitiveContext(text, offset)) {
                return CompletionRoutingDecision(
                    route = CompletionRoute.BRIDGE,
                    reason = "lexical-scope-sensitive-context",
                )
            }
            return CompletionRoutingDecision(
                route = CompletionRoute.INDEX,
                reason = "top-level-or-lexical",
            )
        }
        val chain = receiverAccessChain(text, offset)
        if (chain == null) {
            return CompletionRoutingDecision(
                route = CompletionRoute.BRIDGE,
                reason = "unknown-member-chain",
            )
        }
        if (chain.size > 1) {
            return CompletionRoutingDecision(
                route = CompletionRoute.BRIDGE,
                reason = "chained-member-access",
                chainDepth = chain.size,
            )
        }
        val receiverName = chain.firstOrNull()
        if (receiverName != null && inferSmartCastTypeFromText(receiverName, text, offset) != null) {
            return CompletionRoutingDecision(
                route = CompletionRoute.BRIDGE,
                reason = "flow-sensitive-smart-cast",
                chainDepth = chain.size,
            )
        }
        val prefix = currentPrefix(text, offset)
        val receiverType = inferReceiverChainType(index, path, text, chain, offset)
            ?: fallbackNumericReceiverType(chain)
        if (receiverType == null) {
            return CompletionRoutingDecision(
                route = CompletionRoute.BRIDGE,
                reason = "unknown-receiver-type",
                chainDepth = chain.size,
            )
        }
        val indexedCandidates = inferredReceiverCandidates(index, path, text, offset, prefix)
        val syntheticCandidates = syntheticMemberCompletions(index, path, text, offset, prefix)
        if (indexedCandidates.isEmpty() && syntheticCandidates.isEmpty()) {
            return CompletionRoutingDecision(
                route = CompletionRoute.BRIDGE,
                reason = "no-indexed-member-candidates",
                receiverType = receiverType,
                chainDepth = chain.size,
            )
        }
        return CompletionRoutingDecision(
            route = CompletionRoute.INDEX,
            reason = "simple-member-access",
            receiverType = receiverType,
            chainDepth = chain.size,
        )
    }

    fun fastIndexHydrationPackage(
        text: String,
        params: CompletionParams,
    ): String? {
        val offset = LineIndex.build(text).offset(params.position)
        packageCompletionContext(text, offset)?.let { return it.qualifier }
        importCompletionContext(text, offset)?.let { return it.qualifier }
        return null
    }

    private fun memberCompletionListFromIndex(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        offset: Int,
        prefix: String,
        importedFqNames: Set<String>,
        resultLimit: Int,
    ): CompletionList {
        val ranked = linkedMapOf<String, RankedCompletion>()
        val memberCandidates = inferredReceiverCandidates(index, path, text, offset, prefix)
        memberCandidates.forEach { symbol ->
            val symbolFqName = symbol.fqName
            val needsImport = symbol.importable &&
                symbolFqName != null &&
                symbolFqName !in importedFqNames &&
                symbol.packageName.isNotBlank()
            val imported = symbolFqName != null && symbolFqName in importedFqNames
            val score = when {
                symbol.path == path -> 280
                imported -> 260
                !needsImport -> 235
                else -> 205
            } + when (symbol.kind) {
                SymbolKind.FUNCTION,
                SymbolKind.PROPERTY,
                SymbolKind.VARIABLE,
                -> 30

                SymbolKind.CLASS,
                SymbolKind.INTERFACE,
                SymbolKind.ENUM,
                SymbolKind.OBJECT,
                -> -40

                else -> 0
            } + indexedSymbolQuality(symbol) / 25
            mergeRankedCompletion(
                ranked = ranked,
                key = completionCandidateKey(symbol),
                candidate = rankedSymbol(text, symbol, needsImport, score),
            )
        }
        syntheticMemberCompletions(index, path, text, offset, prefix)
            .forEachIndexed { syntheticIndex, item ->
                val score = 240 - syntheticIndex
                ranked.putIfAbsent(
                    "synthetic::${item.label}",
                    RankedCompletion(item.copy(sortText = scoreToSortKey(score)), score),
                )
            }
        return CompletionList(
            isIncomplete = false,
            items = ranked.values
                .sortedWith(compareByDescending<RankedCompletion> { it.score }.thenBy { it.item.label })
                .take(resultLimit)
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
                        data = mapOf(
                            "provider" to "index",
                            "package" to fqName,
                        ),
                    ),
                    score = score,
                ),
            )
        }
        index.completionCandidates(context.segmentPrefix)
            .filter { symbol ->
                symbol.importable &&
                    when {
                        context.qualifier.isBlank() -> symbol.packageName.isBlank()
                        else -> symbol.packageName == context.qualifier
                    }
            }
            .take(200)
            .forEach { symbol ->
                val importName = importCompletionName(symbol)
                val score = when {
                    importName == context.segmentPrefix -> 230
                    importName.startsWith(context.segmentPrefix) -> 200
                    else -> 160
                } +
                    if (symbol.kind == dev.codex.kotlinls.protocol.SymbolKind.CLASS) 10 else 0 +
                    if (importName != symbol.name) -20 else 0
                val normalizedScore = score + indexedSymbolQuality(symbol) / 25
                val candidate = RankedCompletion(
                    item = CompletionItem(
                        label = importName,
                        kind = CompletionItemKind.TEXT,
                        detail = indexedSymbolCompletionDetail(symbol),
                        documentation = indexedSymbolDocumentation(symbol),
                        sortText = scoreToSortKey(normalizedScore),
                        filterText = importName,
                        insertText = importName,
                        insertTextFormat = 1,
                        data = mapOf(
                            "provider" to "index",
                            "symbolId" to symbol.id,
                            "uri" to symbol.uri,
                            "fqName" to (symbol.fqName ?: ""),
                            "importContext" to "true",
                        ),
                    ),
                    score = normalizedScore,
                )
                mergeRankedCompletion(
                    ranked = ranked,
                    key = importCompletionCandidateKey(symbol),
                    candidate = candidate,
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

    private fun packageCompletionList(
        index: WorkspaceIndex,
        context: PackageCompletionContext,
    ): CompletionList {
        val ranked = linkedMapOf<String, RankedCompletion>()
        packageSegmentCandidates(index, context.qualifier, context.segmentPrefix)
            .forEach { packageName ->
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
                            data = mapOf(
                                "provider" to "index",
                                "package" to fqName,
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
        packageSegmentCandidates(index, context.qualifier, context.segmentPrefix)

    private fun packageSegmentCandidates(
        index: WorkspaceIndex,
        qualifier: String,
        segmentPrefix: String,
    ): List<String> =
        index.packageNames.asSequence()
            .mapNotNull { packageName -> directImportPackageChild(packageName, qualifier) }
            .filter { child -> child.startsWith(segmentPrefix) }
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

    private fun packageCompletionContext(text: String, offset: Int): PackageCompletionContext? {
        val safeOffset = offset.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0)).let { index ->
            if (index == -1) 0 else index + 1
        }
        val linePrefix = text.substring(lineStart, safeOffset)
        val trimmed = linePrefix.trimStart()
        if (!trimmed.startsWith("package ")) return null
        val packagePath = trimmed.removePrefix("package ").trim()
        if (packagePath.any { !it.isLetterOrDigit() && it != '_' && it != '.' }) return null
        val qualifier = packagePath.substringBeforeLast('.', "")
        val segmentPrefix = packagePath.substringAfterLast('.', packagePath)
        return PackageCompletionContext(
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
        val indexed = receiverCandidates(index, context.prefix, receiverShortName, receiverFqName)
        val reflected = reflectiveReceiverCandidates(
            prefix = context.prefix,
            receiverType = receiverShortName,
            receiverTypeFqName = receiverFqName,
        )
        return (indexed + reflected).distinctBy { it.id }
    }

    private fun syntheticSemanticMemberCompletions(
        context: CompletionContext,
    ): List<CompletionItem> {
        if (!context.memberAccess) return emptyList()
        val receiverType = context.receiverTypeFqName ?: context.receiverType ?: return emptyList()
        return primitiveConversionCandidates(receiverType)
            .filter { method -> method.startsWith(context.prefix) }
            .map { method ->
                CompletionItem(
                    label = method,
                    kind = CompletionItemKind.FUNCTION,
                    detail = "Kotlin primitive conversion",
                    filterText = method,
                    data = mapOf(
                        "provider" to "synthetic",
                        "fqName" to "kotlin.$method",
                    ),
                )
            }
    }

    private fun inferredReceiverCandidates(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        offset: Int,
        prefix: String,
    ): List<IndexedSymbol> {
        val chain = receiverAccessChain(text, offset) ?: return emptyList()
        val receiverType = inferReceiverChainType(index, path, text, chain)
            ?: fallbackNumericReceiverType(chain)
            ?: return emptyList()
        val receiverShortName = normalizeTypeShortName(receiverType) ?: return emptyList()
        val receiverFqName = normalizeTypeFqName(receiverType)
        val indexed = receiverCandidates(index, prefix, receiverShortName, receiverFqName)
        val reflected = reflectiveReceiverCandidates(
            prefix = prefix,
            receiverType = receiverShortName,
            receiverTypeFqName = receiverFqName,
        )
        return (indexed + reflected).distinctBy { it.id }
    }

    private fun receiverCandidates(
        index: WorkspaceIndex,
        prefix: String,
        receiverShortName: String,
        receiverFqName: String?,
    ): List<IndexedSymbol> {
        val hierarchy = receiverTypeHierarchy(index, receiverShortName, receiverFqName)
        return index.completionCandidates(prefix)
            .filter { symbol ->
                symbol.kind != SymbolKind.CLASS &&
                    symbol.kind != SymbolKind.INTERFACE &&
                    symbol.kind != SymbolKind.ENUM &&
                    symbol.kind != SymbolKind.OBJECT &&
                    receiverMatches(symbol, receiverShortName, receiverFqName, hierarchy)
            }
            .take(200)
            .toList()
    }

    private fun receiverMatches(
        symbol: IndexedSymbol,
        receiverShortName: String,
        receiverFqName: String?,
        hierarchy: ReceiverTypeHierarchy,
    ): Boolean {
        val normalizedShort = normalizeTypeShortName(receiverShortName)
        val normalizedFqName = receiverFqName?.let(::normalizeTypeFqName)
        val ownerShort = normalizeTypeShortName(symbol.containerName)
        val ownerFqName = normalizeTypeFqName(symbol.containerFqName)
        val ownerMatches = ownerShort == normalizedShort ||
            ownerShort in hierarchy.shortNames ||
            (normalizedFqName != null && ownerFqName == normalizedFqName) ||
            (ownerFqName != null && ownerFqName in hierarchy.fqNames)
        if (ownerMatches) return true
        val receiverType = symbol.receiverType ?: return false
        val normalizedReceiverFqName = normalizeTypeFqName(receiverType)
        val normalizedReceiverShort = normalizeTypeShortName(receiverType)
        return normalizedReceiverFqName == normalizedFqName ||
            normalizedReceiverShort == normalizedShort ||
            (normalizedReceiverFqName != null && normalizedReceiverFqName in hierarchy.fqNames) ||
            (normalizedReceiverShort != null && normalizedReceiverShort in hierarchy.shortNames)
    }

    private fun receiverTypeHierarchy(
        index: WorkspaceIndex,
        receiverShortName: String,
        receiverFqName: String?,
    ): ReceiverTypeHierarchy {
        val fqNames = linkedSetOf<String>()
        val shortNames = linkedSetOf<String>()
        val pending = ArrayDeque<String>()

        fun enqueue(typeText: String?) {
            val fqName = normalizeTypeFqName(typeText)
            val shortName = normalizeTypeShortName(typeText)
            if (fqName != null && fqNames.add(fqName)) {
                pending.addLast(fqName)
            }
            if (shortName != null) {
                shortNames.add(shortName)
            }
        }

        enqueue(receiverFqName)
        enqueue(receiverShortName)

        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val directMatches = buildList {
                index.symbolsByFqName[current]?.let(::add)
                index.symbolsByName[normalizeTypeShortName(current).orEmpty()].orEmpty()
                    .filter { symbol -> normalizeTypeFqName(symbol.fqName) == current }
                    .forEach(::add)
            }
            directMatches.forEach { symbol ->
                symbol.supertypes.forEach(::enqueue)
            }
        }

        return ReceiverTypeHierarchy(fqNames = fqNames, shortNames = shortNames)
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

    private fun reflectiveReceiverCandidates(
        prefix: String,
        receiverType: String?,
        receiverTypeFqName: String?,
    ): List<IndexedSymbol> {
        val className = receiverRuntimeClassName(receiverType, receiverTypeFqName) ?: return emptyList()
        val runtimeClass = runCatching { Class.forName(className) }.getOrNull() ?: return emptyList()
        return runtimeClass.methods.asSequence()
            .filter { method ->
                java.lang.reflect.Modifier.isPublic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    method.name.startsWith(prefix) &&
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

    private fun receiverRuntimeClassName(
        receiverType: String?,
        receiverTypeFqName: String?,
    ): String? {
        val fqName = normalizeTypeFqName(receiverTypeFqName)
        val shortName = normalizeTypeShortName(receiverType ?: fqName)
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
        insertText: String? = null,
    ): RankedCompletion {
        val symbolFqName = symbol.fqName
        return RankedCompletion(
            item = CompletionItem(
                label = symbol.name,
                kind = completionKind(symbol),
                detail = indexedSymbolCompletionDetail(symbol),
                documentation = indexedSymbolDocumentation(symbol),
                sortText = scoreToSortKey(score),
                filterText = symbol.name,
                insertText = insertText,
                additionalTextEdits = if (needsImport && symbolFqName != null) {
                    addImportEdits(text, symbolFqName)
                } else {
                    null
                },
                data = mapOf(
                    "provider" to "index",
                    "symbolId" to symbol.id,
                    "uri" to symbol.uri,
                    "fqName" to (symbol.fqName ?: ""),
                ),
            ),
            score = score,
        )
    }

    private fun completionCandidateKey(symbol: IndexedSymbol): String =
        symbol.fqName?.takeIf { it.isNotBlank() }
            ?: buildString {
                append(symbol.name)
                append("::")
                append(symbol.kind)
                append("::")
                append(symbol.containerFqName ?: symbol.packageName)
            }

    private fun importCompletionName(symbol: IndexedSymbol): String =
        if (symbol.kind == SymbolKind.FUNCTION && symbol.importable && '-' in symbol.name) {
            symbol.name.substringBefore('-')
        } else {
            symbol.name
        }

    private fun importCompletionCandidateKey(symbol: IndexedSymbol): String =
        symbol.fqName
            ?.substringBefore('-')
            ?.takeIf { it.isNotBlank() }
            ?: buildString {
                append(importCompletionName(symbol))
                append("::")
                append(symbol.kind)
                append("::")
                append(symbol.containerFqName ?: symbol.packageName)
            }

    private fun mergeRankedCompletion(
        ranked: LinkedHashMap<String, RankedCompletion>,
        key: String,
        candidate: RankedCompletion,
    ) {
        val existing = ranked[key]
        ranked[key] = when {
            existing == null -> candidate
            candidate.score > existing.score -> candidate.copy(
                item = mergeCompletionItems(candidate.item, existing.item),
            )

            else -> existing.copy(
                item = mergeCompletionItems(existing.item, candidate.item),
            )
        }
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

    private fun mergeCompletionItems(primary: CompletionItem?, secondary: CompletionItem): CompletionItem {
        if (primary == null) return secondary
        val mergedEdits = listOfNotNull(primary.additionalTextEdits, secondary.additionalTextEdits)
            .flatten()
            .distinctBy { "${it.range.start.line}:${it.range.start.character}:${it.newText}" }
            .takeIf { it.isNotEmpty() }
        val mergedData = linkedMapOf<String, String>().apply {
            primary.data?.let(::putAll)
            secondary.data?.let(::putAll)
        }.takeIf { it.isNotEmpty() }
        return primary.copy(
            kind = primary.kind ?: secondary.kind,
            detail = primary.detail.takeUnless { it.isNullOrBlank() || it == "/**" } ?: secondary.detail,
            documentation = primary.documentation ?: secondary.documentation,
            filterText = primary.filterText ?: secondary.filterText,
            insertText = primary.insertText ?: secondary.insertText,
            insertTextFormat = primary.insertTextFormat ?: secondary.insertTextFormat,
            additionalTextEdits = mergedEdits,
            data = mergedData,
        )
    }

    private fun syntheticMemberCompletions(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        offset: Int,
        prefix: String,
    ): List<CompletionItem> {
        if (!isMemberAccessContext(text, offset) || !prefix.startsWith("to")) return emptyList()
        val chain = receiverAccessChain(text, offset) ?: return emptyList()
        val receiverType = inferReceiverChainType(index, path, text, chain)
            ?: fallbackNumericReceiverType(chain)
            ?: return emptyList()
        return primitiveConversionCandidates(receiverType)
            .filter { it.startsWith(prefix) }
            .map { method ->
                CompletionItem(
                    label = method,
                    kind = CompletionItemKind.FUNCTION,
                    detail = "Kotlin primitive conversion",
                    filterText = method,
                    data = mapOf(
                        "provider" to "synthetic",
                        "fqName" to "kotlin.$method",
                    ),
                )
            }
    }

    private fun receiverAccessChain(text: String, offset: Int): List<String>? {
        var cursor = offset.coerceIn(0, text.length)
        while (cursor > 0 && text[cursor - 1].isJavaIdentifierPart()) {
            cursor--
        }
        if (cursor <= 0 || text[cursor - 1] != '.') return null
        val receiverExpression = text.substring(0, cursor - 1)
            .takeLastWhile { candidate ->
                candidate != '\n' &&
                    candidate != '\r' &&
                    candidate != ';' &&
                    candidate != '{' &&
                    candidate != '}' &&
                    candidate != '='
            }
            .trim()
        return expressionAccessChain(receiverExpression)
    }

    private fun inferReceiverChainType(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        chain: List<String>,
        offset: Int = text.length,
    ): String? {
        if (chain.isEmpty()) return null
        var currentType = resolveRootSymbolType(index, path, text, chain.first(), offset) ?: return null
        chain.drop(1).forEach { memberName ->
            currentType = knownMemberReturnType(currentType, memberName)
                ?: resolveMemberReturnType(index, currentType, memberName)
                ?: return null
        }
        return currentType
    }

    private fun resolveRootSymbolType(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        symbolName: String,
        searchOffset: Int = text.length,
        visitedSymbols: Set<String> = emptySet(),
    ): String? {
        if (symbolName in visitedSymbols) return null
        val normalizedPath = path.normalize()
        index.symbolsByPath[normalizedPath]
            .orEmpty()
            .asSequence()
            .filter { it.name == symbolName }
            .sortedWith(
                compareByDescending<IndexedSymbol> { !it.importable }
                    .thenByDescending { it.selectionRange.start.line }
                    .thenByDescending { it.selectionRange.start.character },
            )
            .mapNotNull { symbol -> symbol.resultType ?: symbol.fqName }
            .firstOrNull()
            ?.let { return it }

        inferDeclaredLocalType(text, symbolName, searchOffset)?.let { return it }
        inferLocalDeclarationType(index, path, text, symbolName, searchOffset, visitedSymbols + symbolName)?.let { return it }

        SourceIndexLookup.imports(text)
            .firstOrNull { it.visibleName == symbolName }
            ?.let { sourceImport ->
                index.symbolsByFqName[sourceImport.fqName]?.let { symbol ->
                    return symbol.resultType ?: symbol.fqName
                }
                return sourceImport.fqName
            }

        return index.symbolsByName[symbolName]
            .orEmpty()
            .asSequence()
            .filter { it.importable }
            .mapNotNull { symbol -> symbol.resultType ?: symbol.fqName }
            .firstOrNull()
    }

    private fun inferDeclaredLocalType(
        text: String,
        symbolName: String,
        searchOffset: Int,
    ): String? {
        val prefixText = text.substring(0, searchOffset.coerceIn(0, text.length))
        val escapedName = Regex.escape(symbolName)
        val typedDeclaration = Regex("""\b(?:val|var)\s+$escapedName\s*:\s*([^=\n]+)""")
        typedDeclaration.findAll(prefixText)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.trimEnd(',', ')') }

        val parameterDeclaration = Regex("""\b$escapedName\s*:\s*([^,)=\n]+)""")
        parameterDeclaration.findAll(prefixText)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.trimEnd(',', ')') }

        return null
    }

    private fun inferLocalDeclarationType(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        symbolName: String,
        searchOffset: Int,
        visitedSymbols: Set<String>,
    ): String? {
        val prefixText = text.substring(0, searchOffset.coerceIn(0, text.length))
        val declarationPattern = Regex("""\b(?:val|var)\s+${Regex.escape(symbolName)}\b[^=\n]*=\s*(.+)$""")
        return prefixText.lineSequence()
            .toList()
            .asReversed()
            .firstNotNullOfOrNull { line ->
                val match = declarationPattern.find(line) ?: return@firstNotNullOfOrNull null
                val chain = expressionAccessChain(match.groupValues[1]) ?: return@firstNotNullOfOrNull null
                inferExpressionChainType(index, path, text, chain, searchOffset, visitedSymbols)
            }
    }

    private fun expressionAccessChain(expressionText: String): List<String>? {
        var sanitized = expressionText.substringBefore("//").trim()
        repeat(6) {
            val next = sanitized
                .replace(Regex("""\([^()]*\)"""), "")
                .replace(Regex("""<[^<>]*>"""), "")
            if (next == sanitized) return@repeat
            sanitized = next
        }
        sanitized = sanitized
            .substringAfterLast('\n')
            .substringAfterLast('\r')
            .trim()
            .substringAfterLast(' ')
            .substringAfterLast('\t')
        val compact = sanitized.replace(Regex("""\s+"""), "")
        if (compact.isBlank()) return null
        val prefixBuilder = StringBuilder()
        for (ch in compact) {
            if (ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '?') {
                prefixBuilder.append(ch)
            } else {
                break
            }
        }
        val prefix = prefixBuilder.toString()
            .replace("?.", ".")
            .trim('.')
        if (prefix.isBlank()) return null
        val segments = prefix.split('.').filter { it.isNotBlank() }
        return segments.takeIf { it.isNotEmpty() }
    }

    private fun inferExpressionChainType(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        chain: List<String>,
        searchOffset: Int,
        visitedSymbols: Set<String>,
    ): String? {
        if (chain.isEmpty()) return null
        var currentType = resolveRootSymbolType(index, path, text, chain.first(), searchOffset, visitedSymbols) ?: return null
        chain.drop(1).forEach { memberName ->
            currentType = knownMemberReturnType(currentType, memberName)
                ?: resolveMemberReturnType(index, currentType, memberName)
                ?: return null
        }
        return currentType
    }

    private fun resolveMemberReturnType(
        index: WorkspaceIndex,
        receiverType: String,
        memberName: String,
    ): String? {
        val receiverShortName = normalizeTypeShortName(receiverType) ?: return null
        val receiverFqName = normalizeTypeFqName(receiverType)
        val hierarchy = receiverTypeHierarchy(index, receiverShortName, receiverFqName)
        return index.symbolsByName[memberName]
            .orEmpty()
            .asSequence()
            .filter { symbol ->
                receiverMatches(symbol, receiverShortName, receiverFqName, hierarchy)
            }
            .mapNotNull { symbol -> symbol.resultType ?: symbol.fqName }
            .firstOrNull()
    }

    private fun knownMemberReturnType(
        receiverType: String,
        memberName: String,
    ): String? {
        val normalized = normalizeTypeShortName(receiverType) ?: return null
        return when (normalized) {
            "IntSize", "IntOffset", "IntRect" -> when (memberName) {
                "width", "height", "x", "y", "left", "right", "top", "bottom" -> "kotlin.Int"
                else -> null
            }

            "Size", "Rect" -> when (memberName) {
                "width", "height", "left", "right", "top", "bottom" -> "kotlin.Float"
                else -> null
            }

            "Dp", "TextUnit" -> when (memberName) {
                "value" -> "kotlin.Float"
                else -> null
            }

            else -> null
        }
    }

    private fun primitiveConversionCandidates(receiverType: String): List<String> =
        when (normalizeTypeShortName(receiverType)) {
            "Byte", "Short", "Int", "Long", "Float", "Double" ->
                listOf("toByte", "toShort", "toInt", "toLong", "toFloat", "toDouble")

            "Char" -> listOf("toInt")
            else -> emptyList()
        }

    private fun fallbackNumericReceiverType(chain: List<String>): String? {
        val tail = chain.lastOrNull() ?: return null
        return when {
            tail in setOf("width", "height", "x", "y", "left", "right", "top", "bottom", "count", "index", "length", "size") ->
                "kotlin.Int"

            tail.endsWith("Px", ignoreCase = true) ||
                tail.endsWith("Count", ignoreCase = true) ||
                tail.endsWith("Index", ignoreCase = true) ||
                tail.endsWith("Size", ignoreCase = true) ->
                "kotlin.Int"

            tail in setOf("alpha", "progress", "fraction", "scale", "value") ->
                "kotlin.Float"

            else -> null
        }
    }

    private fun isMemberAccessContext(text: String, offset: Int): Boolean {
        var cursor = offset.coerceIn(0, text.length)
        while (cursor > 0 && text[cursor - 1].isJavaIdentifierPart()) {
            cursor--
        }
        return cursor > 0 && text[cursor - 1] == '.'
    }

    private fun isTypePositionContext(text: String, offset: Int): Boolean {
        val tokenStart = (offset - currentPrefix(text, offset).length).coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (tokenStart - 1).coerceAtLeast(0)).let { index ->
            if (index == -1) 0 else index + 1
        }
        val prefix = text.substring(lineStart, tokenStart).trimEnd()
        return prefix.endsWith(":") ||
            prefix.endsWith(" as") ||
            prefix.endsWith(" is") ||
            prefix.endsWith("<")
    }

    private fun isExpectedTypeSensitiveExpression(text: String, offset: Int): Boolean {
        val tokenStart = (offset - currentPrefix(text, offset).length).coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (tokenStart - 1).coerceAtLeast(0)).let { index ->
            if (index == -1) 0 else index + 1
        }
        val linePrefix = text.substring(lineStart, tokenStart).trimEnd()
        if (linePrefix.startsWith("return ")) return true
        if (!linePrefix.endsWith("=")) return false
        val leftHandSide = linePrefix.removeSuffix("=").trimEnd()
        if (leftHandSide.contains('(') && !leftHandSide.contains(':')) return false
        return leftHandSide.startsWith("val ") ||
            leftHandSide.startsWith("var ") ||
            leftHandSide.contains(':')
    }

    private fun isFlowSensitiveExpression(text: String, offset: Int): Boolean {
        val prefix = text.substring(0, offset.coerceIn(0, text.length))
        return FLOW_SENSITIVE_IF_REGEX.containsMatchIn(prefix) ||
            FLOW_SENSITIVE_WHEN_REGEX.containsMatchIn(prefix)
    }

    private fun isLexicalScopeSensitiveContext(text: String, offset: Int): Boolean {
        val tokenStart = (offset - currentPrefix(text, offset).length).coerceIn(0, text.length)
        if (tokenStart <= 0) return false
        val structure = structuralContext(text, tokenStart)
        return structure.parenthesisDepth > 0 || structure.braceDepth > 0
    }

    private fun namedArgumentCompletionContext(
        snapshot: WorkspaceAnalysisSnapshot,
        file: dev.codex.kotlinls.analysis.AnalyzedFile,
        offset: Int,
    ): NamedArgumentCompletionContext? {
        val leaf = file.ktFile.findElementAt((offset - 1).coerceAtLeast(0))
            ?: file.ktFile.findElementAt(offset.coerceIn(0, file.text.length.coerceAtLeast(1) - 1))
            ?: return null
        val call = leaf.parentsWithSelf
            .filterIsInstance<KtCallExpression>()
            .firstOrNull { candidate ->
                val range = candidate.valueArgumentList?.textRange ?: return@firstOrNull false
                offset in (range.startOffset + 1)..range.endOffset
            }
            ?: return null
        val argumentList = call.valueArgumentList ?: return null
        val segment = argumentSegmentBeforeCursor(file.text, argumentList.textRange.startOffset + 1, offset) ?: return null
        if (segment.contains('=')) return null
        if (segment.any { !it.isWhitespace() && !it.isJavaIdentifierPart() }) return null
        val descriptor = resolvedCallDescriptor(snapshot, call) ?: return null
        val currentArgument = call.valueArguments.firstOrNull { offset in it.textRange.startOffset..it.textRange.endOffset }
        val editingParameterName = currentArgument
            ?.takeIf { isEditingNamedArgumentName(file.text, it, offset) }
            ?.getArgumentName()
            ?.asName
            ?.asString()
        val usedParameterNames = call.valueArguments
            .mapNotNull { argument -> argument.getArgumentName()?.asName?.asString() }
            .filterNot { parameterName -> parameterName == editingParameterName }
            .toSet()
        return NamedArgumentCompletionContext(
            descriptor = descriptor,
            prefix = currentPrefix(file.text, offset),
            usedParameterNames = usedParameterNames,
        )
    }

    private fun indexedNamedArgumentCompletionContext(
        text: String,
        offset: Int,
    ): IndexedNamedArgumentCompletionContext? {
        val callOpenOffset = enclosingCallParenthesis(text, offset) ?: return null
        val calleeName = callNameBeforeParenthesis(text, callOpenOffset) ?: return null
        val segment = argumentSegmentBeforeCursor(text, callOpenOffset + 1, offset) ?: return null
        if (segment.contains('=')) return null
        if (segment.any { !it.isWhitespace() && !it.isJavaIdentifierPart() }) return null
        val usedParameterNames = topLevelArgumentSegments(text, callOpenOffset + 1, offset)
            .mapNotNull { argument ->
                argument.substringBefore('=', missingDelimiterValue = "")
                    .trim()
                    .takeIf { it.isNotBlank() && argument.contains('=') }
            }
            .toSet()
        return IndexedNamedArgumentCompletionContext(
            calleeName = calleeName,
            prefix = currentPrefix(text, offset),
            usedParameterNames = usedParameterNames,
        )
    }

    private fun indexedNamedArgumentCandidates(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        calleeName: String,
    ): List<IndexedSymbol> {
        val normalizedPath = path.normalize()
        val samePathSymbols = index.symbolsByPath[normalizedPath].orEmpty()
        val currentModuleName = samePathSymbols.firstOrNull()?.moduleName
        val currentPackageName = SourceIndexLookup.packageName(text)
        val visiblePackages = SourceIndexLookup.supportPackages(normalizedPath, text)
        val importedCandidates = SourceIndexLookup.imports(text)
            .asSequence()
            .filter { imported -> imported.visibleName == calleeName }
            .mapNotNull { imported -> index.symbolsByFqName[imported.fqName] }
        val nameCandidates = index.symbolsByName[calleeName].orEmpty().asSequence()
        return (
            samePathSymbols.asSequence().filter { symbol -> symbol.name == calleeName } +
                importedCandidates +
                nameCandidates.filter { symbol ->
                    symbol.name == calleeName && (
                        symbol.packageName == currentPackageName ||
                            symbol.packageName in visiblePackages ||
                            (currentModuleName != null && symbol.moduleName == currentModuleName)
                        )
                }
            )
            .filter(::supportsIndexedNamedArguments)
            .distinctBy { symbol -> symbol.id }
            .sortedWith(preferredIndexedSymbolComparator())
            .toList()
    }

    private fun supportsIndexedNamedArguments(symbol: IndexedSymbol): Boolean =
        symbol.receiverType == null &&
            symbol.parameters.isNotEmpty() &&
            symbol.kind in setOf(SymbolKind.FUNCTION, SymbolKind.CONSTRUCTOR, SymbolKind.CLASS)

    private fun indexedNamedArgumentDetail(
        parameter: IndexedParameter,
        symbol: IndexedSymbol,
    ): String = buildString {
        append(parameter.type ?: "Any?")
        parameter.defaultValue?.takeIf { it.isNotBlank() }?.let { defaultValue ->
            append(" = ")
            append(defaultValue)
        }
        append("  ")
        append(symbol.name)
    }

    private fun argumentSegmentBeforeCursor(
        text: String,
        argumentListStart: Int,
        offset: Int,
    ): String? {
        if (offset < argumentListStart) return null
        var parenthesisDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var segmentStart = argumentListStart
        var cursor = argumentListStart
        while (cursor < offset) {
            val current = text[cursor]
            val next = text.getOrNull(cursor + 1)
            when {
                current == '/' && next == '/' -> {
                    cursor += 2
                    while (cursor < offset && text[cursor] != '\n') {
                        cursor++
                    }
                }

                current == '/' && next == '*' -> {
                    cursor += 2
                    while (cursor + 1 < offset && !(text[cursor] == '*' && text[cursor + 1] == '/')) {
                        cursor++
                    }
                    if (cursor + 1 < offset) {
                        cursor += 2
                    }
                }

                current == '"' && text.startsWith("\"\"\"", cursor) -> {
                    cursor += 3
                    while (cursor + 2 < offset && !text.startsWith("\"\"\"", cursor)) {
                        cursor++
                    }
                    if (cursor + 2 < offset) {
                        cursor += 3
                    }
                }

                current == '"' -> {
                    cursor++
                    var escaped = false
                    while (cursor < offset) {
                        val ch = text[cursor]
                        if (ch == '"' && !escaped) {
                            cursor++
                            break
                        }
                        escaped = ch == '\\' && !escaped
                        if (ch != '\\') escaped = false
                        cursor++
                    }
                }

                current == '\'' -> {
                    cursor++
                    var escaped = false
                    while (cursor < offset) {
                        val ch = text[cursor]
                        if (ch == '\'' && !escaped) {
                            cursor++
                            break
                        }
                        escaped = ch == '\\' && !escaped
                        if (ch != '\\') escaped = false
                        cursor++
                    }
                }

                else -> {
                    when (current) {
                        '(' -> parenthesisDepth++
                        ')' -> parenthesisDepth = (parenthesisDepth - 1).coerceAtLeast(0)
                        '{' -> braceDepth++
                        '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        '[' -> bracketDepth++
                        ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                        ',' -> if (parenthesisDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                            segmentStart = cursor + 1
                        }
                    }
                    cursor++
                }
            }
        }
        return text.substring(segmentStart, offset).trimStart()
    }

    private fun isEditingNamedArgumentName(
        text: String,
        argument: KtValueArgument,
        offset: Int,
    ): Boolean {
        val argumentName = argument.getArgumentName() ?: return false
        val nameRange = argumentName.textRange
        if (offset > nameRange.endOffset) return false
        val segment = text.substring(argument.textRange.startOffset, offset.coerceAtMost(argument.textRange.endOffset))
        return '=' !in segment
    }

    private fun resolvedCallDescriptor(
        snapshot: WorkspaceAnalysisSnapshot,
        call: KtCallExpression,
    ): CallableDescriptor? {
        val callee = call.calleeExpression
        val callInfo = callee?.let { snapshot.bindingContext[BindingContext.CALL, it] }
            ?: snapshot.bindingContext[BindingContext.CALL, call]
            ?: return null
        return snapshot.bindingContext[BindingContext.RESOLVED_CALL, callInfo]?.resultingDescriptor as? CallableDescriptor
    }

    private fun enclosingCallParenthesis(
        text: String,
        offset: Int,
    ): Int? {
        val openParentheses = ArrayDeque<Int>()
        var cursor = 0
        while (cursor < offset.coerceIn(0, text.length)) {
            val current = text[cursor]
            val next = text.getOrNull(cursor + 1)
            when {
                current == '/' && next == '/' -> {
                    cursor += 2
                    while (cursor < offset && text[cursor] != '\n') {
                        cursor++
                    }
                }

                current == '/' && next == '*' -> {
                    cursor += 2
                    while (cursor + 1 < offset && !(text[cursor] == '*' && text[cursor + 1] == '/')) {
                        cursor++
                    }
                    if (cursor + 1 < offset) {
                        cursor += 2
                    }
                }

                current == '"' && text.startsWith("\"\"\"", cursor) -> {
                    cursor += 3
                    while (cursor + 2 < offset && !text.startsWith("\"\"\"", cursor)) {
                        cursor++
                    }
                    if (cursor + 2 < offset) {
                        cursor += 3
                    }
                }

                current == '"' -> {
                    cursor++
                    var escaped = false
                    while (cursor < offset) {
                        val ch = text[cursor]
                        if (ch == '"' && !escaped) {
                            cursor++
                            break
                        }
                        escaped = ch == '\\' && !escaped
                        if (ch != '\\') escaped = false
                        cursor++
                    }
                }

                current == '\'' -> {
                    cursor++
                    var escaped = false
                    while (cursor < offset) {
                        val ch = text[cursor]
                        if (ch == '\'' && !escaped) {
                            cursor++
                            break
                        }
                        escaped = ch == '\\' && !escaped
                        if (ch != '\\') escaped = false
                        cursor++
                    }
                }

                else -> {
                    when (current) {
                        '(' -> openParentheses.addLast(cursor)
                        ')' -> if (openParentheses.isNotEmpty()) {
                            openParentheses.removeLast()
                        }
                    }
                    cursor++
                }
            }
        }
        return openParentheses.lastOrNull()
    }

    private fun callNameBeforeParenthesis(
        text: String,
        parenthesisOffset: Int,
    ): String? {
        var cursor = parenthesisOffset - 1
        while (cursor >= 0 && text[cursor].isWhitespace()) {
            cursor--
        }
        while (cursor >= 0 && text[cursor] == '>') {
            cursor--
            var depth = 1
            while (cursor >= 0 && depth > 0) {
                when (text[cursor]) {
                    '>' -> depth++
                    '<' -> depth--
                }
                cursor--
            }
            while (cursor >= 0 && text[cursor].isWhitespace()) {
                cursor--
            }
        }
        if (cursor < 0 || !text[cursor].isJavaIdentifierPart()) return null
        var start = cursor
        while (start > 0 && text[start - 1].isJavaIdentifierPart()) {
            start--
        }
        return text.substring(start, cursor + 1)
    }

    private fun topLevelArgumentSegments(
        text: String,
        argumentListStart: Int,
        offset: Int,
    ): List<String> {
        val segments = mutableListOf<String>()
        var parenthesisDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var segmentStart = argumentListStart
        var cursor = argumentListStart
        while (cursor < offset.coerceIn(argumentListStart, text.length)) {
            val current = text[cursor]
            val next = text.getOrNull(cursor + 1)
            when {
                current == '/' && next == '/' -> {
                    cursor += 2
                    while (cursor < offset && text[cursor] != '\n') {
                        cursor++
                    }
                }

                current == '/' && next == '*' -> {
                    cursor += 2
                    while (cursor + 1 < offset && !(text[cursor] == '*' && text[cursor + 1] == '/')) {
                        cursor++
                    }
                    if (cursor + 1 < offset) {
                        cursor += 2
                    }
                }

                current == '"' && text.startsWith("\"\"\"", cursor) -> {
                    cursor += 3
                    while (cursor + 2 < offset && !text.startsWith("\"\"\"", cursor)) {
                        cursor++
                    }
                    if (cursor + 2 < offset) {
                        cursor += 3
                    }
                }

                current == '"' -> {
                    cursor++
                    var escaped = false
                    while (cursor < offset) {
                        val ch = text[cursor]
                        if (ch == '"' && !escaped) {
                            cursor++
                            break
                        }
                        escaped = ch == '\\' && !escaped
                        if (ch != '\\') escaped = false
                        cursor++
                    }
                }

                current == '\'' -> {
                    cursor++
                    var escaped = false
                    while (cursor < offset) {
                        val ch = text[cursor]
                        if (ch == '\'' && !escaped) {
                            cursor++
                            break
                        }
                        escaped = ch == '\\' && !escaped
                        if (ch != '\\') escaped = false
                        cursor++
                    }
                }

                else -> {
                    when (current) {
                        '(' -> parenthesisDepth++
                        ')' -> parenthesisDepth = (parenthesisDepth - 1).coerceAtLeast(0)
                        '{' -> braceDepth++
                        '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        '[' -> bracketDepth++
                        ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                        ',' -> if (parenthesisDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                            segments += text.substring(segmentStart, cursor).trim()
                            segmentStart = cursor + 1
                        }
                    }
                    cursor++
                }
            }
        }
        val trailing = text.substring(segmentStart, offset.coerceIn(segmentStart, text.length)).trim()
        if (trailing.isNotBlank()) {
            segments += trailing
        }
        return segments
    }

    private fun structuralContext(text: String, limit: Int): StructuralContext {
        var parenthesisDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var cursor = 0
        while (cursor < limit) {
            val current = text[cursor]
            val next = text.getOrNull(cursor + 1)
            when {
                current == '/' && next == '/' -> {
                    cursor += 2
                    while (cursor < limit && text[cursor] != '\n') {
                        cursor++
                    }
                }

                current == '/' && next == '*' -> {
                    cursor += 2
                    while (cursor + 1 < limit && !(text[cursor] == '*' && text[cursor + 1] == '/')) {
                        cursor++
                    }
                    if (cursor + 1 < limit) {
                        cursor += 2
                    }
                }

                current == '"' && text.startsWith("\"\"\"", cursor) -> {
                    cursor += 3
                    while (cursor + 2 < limit && !text.startsWith("\"\"\"", cursor)) {
                        cursor++
                    }
                    if (cursor + 2 < limit) {
                        cursor += 3
                    }
                }

                current == '"' -> {
                    cursor++
                    var escaped = false
                    while (cursor < limit) {
                        val ch = text[cursor]
                        if (ch == '"' && !escaped) {
                            cursor++
                            break
                        }
                        escaped = ch == '\\' && !escaped
                        if (ch != '\\') escaped = false
                        cursor++
                    }
                }

                current == '\'' -> {
                    cursor++
                    var escaped = false
                    while (cursor < limit) {
                        val ch = text[cursor]
                        if (ch == '\'' && !escaped) {
                            cursor++
                            break
                        }
                        escaped = ch == '\\' && !escaped
                        if (ch != '\\') escaped = false
                        cursor++
                    }
                }

                else -> {
                    when (current) {
                        '(' -> parenthesisDepth++
                        ')' -> parenthesisDepth = (parenthesisDepth - 1).coerceAtLeast(0)
                        '{' -> braceDepth++
                        '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                        '[' -> bracketDepth++
                        ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    }
                    cursor++
                }
            }
        }
        return StructuralContext(
            parenthesisDepth = parenthesisDepth,
            braceDepth = braceDepth,
            bracketDepth = bracketDepth,
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

    private data class PackageCompletionContext(
        val qualifier: String,
        val segmentPrefix: String,
    )

    private data class ReceiverTypeHierarchy(
        val fqNames: Set<String>,
        val shortNames: Set<String>,
    )

    private data class StructuralContext(
        val parenthesisDepth: Int,
        val braceDepth: Int,
        val bracketDepth: Int,
    )

    private data class NamedArgumentCompletionContext(
        val descriptor: CallableDescriptor,
        val prefix: String,
        val usedParameterNames: Set<String>,
    )

    private data class IndexedNamedArgumentCompletionContext(
        val calleeName: String,
        val prefix: String,
        val usedParameterNames: Set<String>,
    )

    private fun acquireJetBrainsBridge(projectRoot: Path): JetBrainsCompletionBridge? {
        jetBrainsBridge?.let { return it }
        return synchronized(this) {
            jetBrainsBridge ?: JetBrainsCompletionBridge.detect(projectRoot = projectRoot)?.also { detected ->
                jetBrainsBridge = detected
            }
        }
    }

    private companion object {
        val FLOW_SENSITIVE_IF_REGEX = Regex(
            """\bif\s*\([^)]*\bis\s+[A-Za-z_][A-Za-z0-9_.<>?]*[^)]*\)\s*\{[\s\S]*$""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val FLOW_SENSITIVE_WHEN_REGEX = Regex(
            """\bwhen\s*\([^)]*\)\s*\{[\s\S]*\bis\s+[A-Za-z_][A-Za-z0-9_.<>?]*\s*->[\s\S]*$""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}

enum class CompletionRoute {
    INDEX,
    BRIDGE,
}

data class CompletionRoutingDecision(
    val route: CompletionRoute,
    val reason: String,
    val receiverType: String? = null,
    val chainDepth: Int? = null,
)
