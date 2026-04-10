package dev.codex.kotlinls.formatting

import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.protocol.Diagnostic
import dev.codex.kotlinls.protocol.DocumentFormattingParams
import dev.codex.kotlinls.protocol.DocumentRangeFormattingParams
import dev.codex.kotlinls.protocol.FormattingOptions
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.TextEdit
import dev.codex.kotlinls.workspace.LineIndex
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtImportList
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameterList
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.w3c.dom.Node
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class FormattingService(
    intellijFormatterBridge: FormatterBridge? = null,
    intellijFormatterCommand: List<String>? = null,
    private val formatterTimeoutMillis: Long = 30_000,
) {
    private val userIntellijStyleSettings: IntellijKotlinStyleSettings? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        detectUserIntellijStyleFile()?.let(::parseIntellijKotlinStyle)
    }
    private val resolvedIntellijFormatterBridge: FormatterBridge? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        intellijFormatterBridge ?: JetBrainsFormatterBridge.detect()
    }
    private val resolvedIntellijFormatterCommand: List<String>? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        intellijFormatterCommand ?: detectExternalFormatterCommand()
    }

    fun formatDocument(snapshot: WorkspaceAnalysisSnapshot, params: DocumentFormattingParams): List<TextEdit> {
        val file = snapshot.filesByUri[params.textDocument.uri] ?: return emptyList()
        val formatted = formatText(
            file.originalPath,
            file.text,
            params.options,
            file.ktFile.importDirectives.toList(),
            usedImports(snapshot.bindingContext, file),
        )
        return diffEdits(file.text, formatted)
    }

    fun formatRange(snapshot: WorkspaceAnalysisSnapshot, params: DocumentRangeFormattingParams): List<TextEdit> {
        val file = snapshot.filesByUri[params.textDocument.uri] ?: return emptyList()
        val scopeElement = formattingScopeElement(file, params.range)
        val scopeRange = scopeElement.textRange
        val replacement = formatScopeText(
            file = file,
            options = params.options,
            scopeElement = scopeElement,
            usedImports = usedImports(snapshot.bindingContext, file),
        )
        if (replacement == file.text.substring(scopeRange.startOffset, scopeRange.endOffset)) return emptyList()
        return listOf(
            TextEdit(
                range = LineIndex.build(file.text).range(scopeRange.startOffset, scopeRange.endOffset),
                newText = replacement,
            ),
        )
    }

    fun organizeImportsText(snapshot: WorkspaceAnalysisSnapshot, uri: String): String? {
        val file = snapshot.filesByUri[uri] ?: return null
        return organizeImports(
            file.originalPath,
            file.text,
            file.ktFile.importDirectives.toList(),
            usedImports(snapshot.bindingContext, file),
        )
    }

    fun lintDocument(path: Path, text: String): List<Diagnostic> = emptyList()

    fun shouldPublishStyleDiagnostics(path: Path): Boolean = false

    private fun formatText(
        path: Path,
        text: String,
        options: FormattingOptions,
        imports: List<KtImportDirective>,
        usedImports: UsedImportInfo,
    ): String =
        commandLineFormat(path, text)
            ?: bridgeFormat(path, text)
            ?: normalizeWhitespace(organizeImports(path, text, imports, usedImports))

    private fun formatScopeText(
        file: dev.codex.kotlinls.analysis.AnalyzedFile,
        options: FormattingOptions,
        scopeElement: PsiElement,
        usedImports: UsedImportInfo,
    ): String {
        val formattedDocument = formatText(
            path = file.originalPath,
            text = file.text,
            options = options,
            imports = file.ktFile.importDirectives.toList(),
            usedImports = usedImports,
        )
        if (scopeElement is KtFile) {
            return formattedDocument
        }
        relocateFormattedScopeText(scopeElement, formattedDocument)?.let { return it }
        val scope = TextSpan(
            start = scopeElement.textRange.startOffset,
            endExclusive = scopeElement.textRange.endOffset,
        )
        return formatScopeTextWithMarkers(
            path = file.originalPath,
            text = file.text,
            options = options,
            imports = file.ktFile.importDirectives.toList(),
            usedImports = usedImports,
            scope = scope,
        )
    }

    private fun formatScopeTextWithMarkers(
        path: Path,
        text: String,
        options: FormattingOptions,
        imports: List<KtImportDirective>,
        usedImports: UsedImportInfo,
        scope: TextSpan,
    ): String {
        val startMarker = "/*__KLS_RANGE_START__${UUID.randomUUID()}__*/"
        val endMarker = "/*__KLS_RANGE_END__${UUID.randomUUID()}__*/"
        val textWithMarkers = buildString(text.length + startMarker.length + endMarker.length) {
            append(text, 0, scope.start)
            append(startMarker)
            append(text, scope.start, scope.endExclusive)
            append(endMarker)
            append(text, scope.endExclusive, text.length)
        }
        val formattedWithMarkers = formatText(path, textWithMarkers, options, imports, usedImports)
        val formattedStart = formattedWithMarkers.indexOf(startMarker)
        val formattedEnd = formattedWithMarkers.indexOf(endMarker)
        if (formattedStart == -1 || formattedEnd == -1 || formattedEnd < formattedStart) {
            return text.substring(scope.start, scope.endExclusive)
        }
        return formattedWithMarkers.substring(formattedStart + startMarker.length, formattedEnd)
    }

    private fun bridgeFormat(path: Path, text: String): String? {
        val bridge = preferredFormatterBridge(path) ?: return null
        return runCatching {
            val tempFile = createFormatterTempFile(path)
            try {
                Files.createDirectories(tempFile.parent)
                Files.writeString(tempFile, text, StandardCharsets.UTF_8)
                bridge.format(tempFile, text, formatterTimeoutMillis)
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }.getOrNull()
    }

    private fun commandLineFormat(path: Path, text: String): String? {
        val command = resolvedIntellijFormatterCommand ?: return null
        return runCatching {
            val tempFile = createFormatterTempFile(path)
            try {
                Files.createDirectories(tempFile.parent)
                Files.writeString(tempFile, text, StandardCharsets.UTF_8)
                val process = ProcessBuilder(command + listOf("-allowDefaults", tempFile.toString()))
                    .redirectErrorStream(true)
                    .start()
                if (!process.waitFor(formatterTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    return@runCatching null
                }
                process.inputStream.readAllBytes()
                if (process.exitValue() != 0) {
                    return@runCatching null
                }
                Files.readString(tempFile, StandardCharsets.UTF_8)
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }.getOrNull()
    }

    private fun hasEditorConfig(path: Path): Boolean =
        generateSequence(path.toAbsolutePath().parent) { current -> current.parent }
            .any { current -> current.resolve(".editorconfig").toFile().isFile }

    private fun formattingScopeElement(
        file: dev.codex.kotlinls.analysis.AnalyzedFile,
        range: Range,
    ): PsiElement {
        if (file.text.isEmpty()) return file.ktFile
        val lineIndex = LineIndex.build(file.text)
        val startOffset = lineIndex.offset(range.start).coerceIn(0, file.text.length)
        val endOffset = lineIndex.offset(range.end).coerceIn(startOffset, file.text.length)
        val safeStart = startOffset.coerceAtMost((file.text.length - 1).coerceAtLeast(0))
        val safeEnd = (endOffset - 1).coerceAtLeast(safeStart).coerceAtMost((file.text.length - 1).coerceAtLeast(0))
        val startLeaf = file.ktFile.findElementAt(safeStart) ?: return file.ktFile
        val endLeaf = file.ktFile.findElementAt(safeEnd) ?: startLeaf
        return commonAncestor(startLeaf, endLeaf)
            ?.parentsWithSelf
            ?.firstOrNull { it.isFormattingScope() }
            ?: file.ktFile
    }

    private fun relocateFormattedScopeText(scopeElement: PsiElement, formattedDocument: String): String? {
        val scopePath = structuralPath(scopeElement)
        if (scopePath.isEmpty()) return null
        val formattedFile = KtPsiFactory(scopeElement.project, false)
            .createFile(scopeElement.containingFile.name, formattedDocument)
        return locateByStructuralPath(formattedFile, scopePath)?.text
    }

    private fun structuralPath(element: PsiElement): List<StructuralStep> {
        val path = mutableListOf<StructuralStep>()
        var current = element
        while (current !is KtFile) {
            val parent = current.parent ?: break
            val siblings = structuralChildren(parent)
            val index = siblings.indexOf(current)
            if (index == -1) return emptyList()
            path += StructuralStep(index = index, className = current.javaClass.name)
            current = parent
        }
        return path.asReversed()
    }

    private fun locateByStructuralPath(root: KtFile, path: List<StructuralStep>): PsiElement? {
        var current: PsiElement = root
        path.forEach { step ->
            val children = structuralChildren(current)
            val direct = children.getOrNull(step.index)
            current = when {
                direct?.javaClass?.name == step.className -> direct
                else -> children.firstOrNull { it.javaClass.name == step.className } ?: return null
            }
        }
        return current
    }

    private fun structuralChildren(element: PsiElement): List<PsiElement> =
        element.children.filterNot { child -> child is PsiWhiteSpace || child is PsiComment }

    private fun findIntellijKotlinStyle(path: Path): IntellijKotlinStyleSettings? {
        val styleFile = generateSequence(path.toAbsolutePath().parent) { current -> current.parent }
            .mapNotNull { current ->
                listOf(
                    current.resolve(".idea/codeStyles/Project.xml"),
                    current.resolve(".idea/codeStyles/Default.xml"),
                ).firstOrNull(Files::isRegularFile)
            }
            .firstOrNull()
        return styleFile?.let(::parseIntellijKotlinStyle) ?: userIntellijStyleSettings
    }

    private fun preferredFormatterBridge(path: Path): FormatterBridge? {
        resolvedIntellijFormatterBridge?.let { return it }
        if (intellijBridgeDisabled()) return null
        if (findIntellijKotlinStyle(path) == null && !hasEditorConfig(path)) return null
        return commonIdeaHomes()
            .asSequence()
            .filter(Files::isDirectory)
            .mapNotNull(JetBrainsFormatterBridge::fromIdeaHome)
            .firstOrNull()
    }

    private fun intellijBridgeDisabled(): Boolean =
        (System.getProperty("kotlinls.disableIntellijBridge")
            ?: System.getenv("KOTLINLS_DISABLE_INTELLIJ_BRIDGE"))
            ?.equals("true", ignoreCase = true) == true

    private fun detectUserIntellijStyleFile(): Path? {
        val home = System.getProperty("user.home") ?: return null
        val appSupport = Path.of(home, "Library", "Application Support")
        val vendorRoots = listOf(
            appSupport.resolve("Google"),
            appSupport.resolve("JetBrains"),
        )
        return vendorRoots.asSequence()
            .filter(Files::isDirectory)
            .flatMap { root -> userStyleCandidateFiles(root).asSequence() }
            .maxByOrNull { candidate ->
                runCatching { Files.getLastModifiedTime(candidate).toMillis() }.getOrDefault(Long.MIN_VALUE)
            }
    }

    private fun userStyleCandidateFiles(root: Path): List<Path> {
        val ideDirs = runCatching {
            Files.list(root).use { stream ->
                stream
                    .filter(Files::isDirectory)
                    .toList()
            }
        }.getOrDefault(emptyList())
        return ideDirs.flatMap { ideDir ->
            buildList {
                val codeStylesDir = ideDir.resolve("codestyles")
                if (Files.isDirectory(codeStylesDir)) {
                    addAll(
                        runCatching {
                            Files.list(codeStylesDir).use { stream ->
                                stream
                                    .filter(Files::isRegularFile)
                                    .filter { it.fileName.toString().endsWith(".xml") }
                                    .toList()
                            }
                        }.getOrDefault(emptyList()),
                    )
                }
                val optionsFile = ideDir.resolve("options/code.style.schemes.xml")
                if (Files.isRegularFile(optionsFile)) {
                    add(optionsFile)
                }
            }
        }
    }

    private fun parseIntellijKotlinStyle(styleFile: Path): IntellijKotlinStyleSettings? =
        runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(styleFile.toFile())
            val optionNodes = document.getElementsByTagName("option")
            val optionValues = linkedMapOf<String, String>()
            for (index in 0 until optionNodes.length) {
                val node = optionNodes.item(index)
                val attributes = node.attributes ?: continue
                val name = attributes.getNamedItem("name")?.nodeValue ?: continue
                val value = attributes.getNamedItem("value")?.nodeValue ?: continue
                optionValues[name] = value
            }
            val indentOptions = kotlinIndentOptions(document)
            IntellijKotlinStyleSettings(
                codeStyleDefaults = optionValues["CODE_STYLE_DEFAULTS"],
                nameCountToUseStarImport = optionValues.intOption("NAME_COUNT_TO_USE_STAR_IMPORT"),
                nameCountToUseStarImportForMembers = optionValues.intOption("NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS"),
                importNestedClasses = optionValues.booleanOption("IMPORT_NESTED_CLASSES"),
                packagesToUseStarImport = document.packageOptions("PACKAGES_TO_USE_STAR_IMPORTS"),
                useTabCharacter = indentOptions.booleanOption("USE_TAB_CHARACTER"),
                indentSize = indentOptions.intOption("INDENT_SIZE"),
                tabSize = indentOptions.intOption("TAB_SIZE"),
                continuationIndentSize = indentOptions.intOption("CONTINUATION_INDENT_SIZE"),
            ).takeIf { it != IntellijKotlinStyleSettings() }
        }.getOrNull()

    private fun kotlinIndentOptions(document: org.w3c.dom.Document): Map<String, String> {
        val settingsNodes = document.getElementsByTagName("codeStyleSettings")
        var kotlinSettings: Node? = null
        var fallbackSettings: Node? = null
        for (index in 0 until settingsNodes.length) {
            val node = settingsNodes.item(index)
            val language = node.attributes?.getNamedItem("language")?.nodeValue
            when {
                language.equals("kotlin", ignoreCase = true) -> {
                    kotlinSettings = node
                    break
                }
                language == null && fallbackSettings == null -> fallbackSettings = node
            }
        }
        return indentOptions(kotlinSettings ?: fallbackSettings)
    }

    private fun indentOptions(settingsNode: Node?): Map<String, String> {
        val indentNode = settingsNode
            ?.childNodes
            ?.let { nodes ->
                (0 until nodes.length)
                    .map(nodes::item)
                    .firstOrNull { it.nodeName == "indentOptions" }
            }
            ?: return emptyMap()
        val values = linkedMapOf<String, String>()
        val childNodes = indentNode.childNodes
        for (index in 0 until childNodes.length) {
            val child = childNodes.item(index)
            if (child.nodeName != "option") continue
            val name = child.attributes?.getNamedItem("name")?.nodeValue ?: continue
            val value = child.attributes?.getNamedItem("value")?.nodeValue ?: continue
            values[name] = value
        }
        return values
    }

    private fun organizeImports(
        path: Path,
        text: String,
        imports: List<KtImportDirective>,
        usedImports: UsedImportInfo,
    ): String {
        if (imports.isEmpty()) return normalizeWhitespace(text)
        val style = findIntellijKotlinStyle(path)
        val keptImports = imports
            .mapNotNull { it.toImportEntry(usedImports) }
            .let { collapseImports(it, style) }
        val lines = text.lines().toMutableList()
        val firstImportLine = imports.minOfOrNull { it.getLineNumber(text) } ?: return text
        val lastImportLine = imports.maxOfOrNull { it.getEndLineNumber(text) } ?: firstImportLine
        val rebuiltImportBlock = keptImports.joinToString("\n") { it.render() }
        val replacementLines = mutableListOf<String>()
        if (rebuiltImportBlock.isNotBlank()) {
            replacementLines += rebuiltImportBlock.lines()
        }
        lines.subList(firstImportLine, lastImportLine + 1).clear()
        lines.addAll(firstImportLine, replacementLines)
        return lines.joinToString("\n")
    }

    private fun usedImports(
        bindingContext: BindingContext,
        file: dev.codex.kotlinls.analysis.AnalyzedFile,
    ): UsedImportInfo {
        val simpleNames = linkedSetOf<String>()
        val fqNames = linkedSetOf<String>()
        file.ktFile.collectDescendantsOfType<KtNameReferenceExpression>().forEach { reference ->
            simpleNames += reference.getReferencedName()
            val descriptor = bindingContext[BindingContext.REFERENCE_TARGET, reference]
                ?: bindingContext[BindingContext.SHORT_REFERENCE_TO_COMPANION_OBJECT, reference]
                ?: return@forEach
            val fqName = DescriptorUtils.getFqNameSafe(descriptor.original).asString()
            if (fqName.isNotBlank()) {
                fqNames += fqName
            }
        }
        return UsedImportInfo(simpleNames = simpleNames, fqNames = fqNames)
    }

    private fun KtImportDirective.toImportEntry(usedImports: UsedImportInfo): ImportEntry? {
        val importPath = importPath ?: return null
        val alias = importPath.alias?.asString()
        val importedName = importedName?.asString()
        val fqName = importedFqName?.asString() ?: importPath.fqName.asString()
        val keep = when {
            importPath.isAllUnder -> true
            alias != null -> alias in usedImports.simpleNames || fqName in usedImports.fqNames
            fqName in usedImports.fqNames -> true
            importedName != null && importedName in usedImports.simpleNames -> true
            else -> false
        }
        if (!keep) return null
        return ImportEntry(
            path = importPath.pathStr,
            alias = alias,
            isWildcard = importPath.isAllUnder,
            parentPath = importPath.fqName.parent().asString().ifBlank { null },
            memberLike = importPath.fqName.pathSegments().dropLast(1).lastOrNull()?.asString()?.firstOrNull()?.isUpperCase() == true,
            nestedClassLike = importPath.fqName.pathSegments().takeLast(2).all { segment ->
                segment.asString().firstOrNull()?.isUpperCase() == true
            },
        )
    }

    private fun collapseImports(
        imports: List<ImportEntry>,
        style: IntellijKotlinStyleSettings?,
    ): List<ImportEntry> {
        if (imports.isEmpty()) return emptyList()
        val importsByKey = linkedMapOf<String, ImportEntry>()
        val explicitByParent = linkedMapOf<String, MutableList<ImportEntry>>()
        val wildcardParents = linkedSetOf<String>()
        imports.forEach { entry ->
            when {
                entry.isWildcard -> {
                    entry.parentPath?.let(wildcardParents::add)
                    importsByKey.putIfAbsent(entry.key(), entry)
                }

                entry.alias != null || entry.parentPath == null -> importsByKey.putIfAbsent(entry.key(), entry)
                else -> explicitByParent.getOrPut(entry.parentPath) { mutableListOf() }.add(entry)
            }
        }
        explicitByParent.forEach { (parentPath, entries) ->
            val uniqueEntries = entries.distinctBy { it.key() }
            val starEligible = uniqueEntries.filter { it.canUseStarImport(style) }
            val explicitOnly = uniqueEntries - starEligible.toSet()
            if (wildcardParents.contains(parentPath) || shouldUseStarImport(parentPath, starEligible, style)) {
                if (starEligible.isNotEmpty()) {
                    importsByKey.putIfAbsent("$parentPath.*", ImportEntry.star(parentPath))
                }
            } else {
                starEligible.forEach { importsByKey.putIfAbsent(it.key(), it) }
            }
            explicitOnly.forEach { importsByKey.putIfAbsent(it.key(), it) }
        }
        return importsByKey.values.sortedWith(
            compareBy<ImportEntry>(
                { if (it.alias != null) 1 else 0 },
                { it.path.replace("`", "") },
                { it.alias.orEmpty() },
            ),
        )
    }

    private fun shouldUseStarImport(
        parentPath: String,
        imports: List<ImportEntry>,
        style: IntellijKotlinStyleSettings?,
    ): Boolean {
        if (imports.isEmpty()) return false
        val threshold = if (imports.any { it.memberLike }) {
            style?.nameCountToUseStarImportForMembers ?: Int.MAX_VALUE
        } else {
            style?.nameCountToUseStarImport ?: Int.MAX_VALUE
        }
        return imports.size >= threshold || style?.packagesToUseStarImport?.any { it.matches(parentPath) } == true
    }

    private fun normalizeWhitespace(text: String): String =
        text.lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .let { if (it.endsWith('\n')) it else "$it\n" }

    private fun diffEdits(original: String, formatted: String): List<TextEdit> {
        if (formatted == original) return emptyList()
        val prefixLength = original.commonPrefixWith(formatted).length
        val suffixLength = commonSuffixLength(
            original = original,
            formatted = formatted,
            prefixLength = prefixLength,
        )
        val originalEnd = original.length - suffixLength
        val formattedEnd = formatted.length - suffixLength
        val lineIndex = LineIndex.build(original)
        return listOf(
            TextEdit(
                range = lineIndex.range(prefixLength, originalEnd),
                newText = formatted.substring(prefixLength, formattedEnd),
            ),
        )
    }

    private fun commonSuffixLength(
        original: String,
        formatted: String,
        prefixLength: Int,
    ): Int {
        var suffix = 0
        val originalMax = original.length - prefixLength
        val formattedMax = formatted.length - prefixLength
        while (suffix < originalMax && suffix < formattedMax) {
            if (original[original.length - 1 - suffix] != formatted[formatted.length - 1 - suffix]) {
                break
            }
            suffix++
        }
        return suffix
    }

    private fun rangesOverlap(left: Range, right: Range): Boolean =
        comparePositions(left.start, right.end) <= 0 && comparePositions(right.start, left.end) <= 0

    private fun comparePositions(left: Position, right: Position): Int =
        when {
            left.line != right.line -> left.line.compareTo(right.line)
            else -> left.character.compareTo(right.character)
        }

    private fun createFormatterTempFile(path: Path): Path {
        val fileName = path.fileName.toString()
        val extension = fileName.substringAfterLast('.', "")
        val tempName = buildString {
            append(".")
            append(fileName.substringBeforeLast('.', fileName))
            append(".kls-format.")
            append(UUID.randomUUID().toString().replace("-", ""))
            if (extension.isNotEmpty()) {
                append(".")
                append(extension)
            }
        }
        val siblingParent = path.parent
        if (siblingParent != null && Files.isDirectory(siblingParent) && Files.isWritable(siblingParent)) {
            return siblingParent.resolve(tempName)
        }
        val fallbackParent = Files.createTempDirectory("kotlin-neovim-lsp-format")
        return fallbackParent.resolve(fileName.ifBlank { "snippet.kt" })
    }

    companion object {
        private fun commonIdeaHomes(): List<Path> =
            listOf(
                Path.of("/Applications/Android Studio.app/Contents"),
                Path.of("/Applications/IntelliJ IDEA.app/Contents"),
                Path.of("/Applications/IntelliJ IDEA CE.app/Contents"),
            )

        private fun detectExternalFormatterCommand(): List<String>? {
            val configured = System.getProperty("kotlinls.intellijFormatterCommand")
                ?: System.getenv("KOTLINLS_INTELLIJ_FORMATTER")
            if (!configured.isNullOrBlank()) {
                return listOf(configured)
            }
            return null
        }
    }
}

private data class TextSpan(
    val start: Int,
    val endExclusive: Int,
)

private data class StructuralStep(
    val index: Int,
    val className: String,
)

private data class IntellijKotlinStyleSettings(
    val codeStyleDefaults: String? = null,
    val nameCountToUseStarImport: Int? = null,
    val nameCountToUseStarImportForMembers: Int? = null,
    val importNestedClasses: Boolean? = null,
    val packagesToUseStarImport: List<StarImportPackage> = emptyList(),
    val useTabCharacter: Boolean? = null,
    val indentSize: Int? = null,
    val tabSize: Int? = null,
    val continuationIndentSize: Int? = null,
)

private data class UsedImportInfo(
    val simpleNames: Set<String>,
    val fqNames: Set<String>,
)

private data class ImportEntry(
    val path: String,
    val alias: String? = null,
    val isWildcard: Boolean = false,
    val parentPath: String? = null,
    val memberLike: Boolean = false,
    val nestedClassLike: Boolean = false,
) {
    fun key(): String = if (alias != null) "$path as $alias" else path

    fun render(): String = buildString {
        append("import ")
        append(path)
        if (!alias.isNullOrBlank()) {
            append(" as ")
            append(alias)
        }
    }

    fun canUseStarImport(style: IntellijKotlinStyleSettings?): Boolean =
        !isWildcard && alias == null && parentPath != null && (style?.importNestedClasses != false || !nestedClassLike)

    companion object {
        fun star(parentPath: String): ImportEntry =
            ImportEntry(
                path = "$parentPath.*",
                isWildcard = true,
                parentPath = parentPath,
            )
    }
}

private data class StarImportPackage(
    val packageName: String,
    val withSubpackages: Boolean,
) {
    fun matches(fqName: String): Boolean =
        fqName == packageName || (withSubpackages && fqName.startsWith("$packageName."))
}

private fun PsiElement.isFormattingScope(): Boolean =
    this is KtDeclaration ||
        this is KtBlockExpression ||
        this is KtClassBody ||
        this is KtWhenEntry ||
        this is KtPropertyAccessor ||
        this is KtValueArgumentList ||
        this is KtParameterList ||
        this is KtImportList

private fun commonAncestor(first: PsiElement, second: PsiElement): PsiElement? {
    val firstAncestors = first.parentsWithSelf.toSet()
    return second.parentsWithSelf.firstOrNull { it in firstAncestors }
}

private fun KtImportDirective.getLineNumber(text: String): Int =
    dev.codex.kotlinls.workspace.LineIndex.build(text).position(textRange.startOffset).line

private fun KtImportDirective.getEndLineNumber(text: String): Int =
    dev.codex.kotlinls.workspace.LineIndex.build(text).position(textRange.endOffset).line

private fun Map<String, String>.booleanOption(name: String): Boolean? =
    this[name]?.lowercase()?.let { value ->
        when (value) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

private fun Map<String, String>.intOption(name: String): Int? =
    this[name]?.toIntOrNull()

private fun org.w3c.dom.Document.packageOptions(optionName: String): List<StarImportPackage> {
    val optionNodes = getElementsByTagName("option")
    for (index in 0 until optionNodes.length) {
        val optionNode = optionNodes.item(index)
        val optionAttributes = optionNode.attributes ?: continue
        val name = optionAttributes.getNamedItem("name")?.nodeValue ?: continue
        if (name != optionName) continue
        val packageNodes = optionNode.childNodes
        val packages = mutableListOf<StarImportPackage>()
        collectPackageOptions(packageNodes, packages)
        return packages
    }
    return emptyList()
}

private fun collectPackageOptions(
    nodes: org.w3c.dom.NodeList,
    packages: MutableList<StarImportPackage>,
) {
    for (index in 0 until nodes.length) {
        val node = nodes.item(index)
        if (node.nodeName == "package") {
            val attributes = node.attributes
            val packageName = attributes?.getNamedItem("name")?.nodeValue?.trim().orEmpty()
            if (packageName.isNotBlank()) {
                packages += StarImportPackage(
                    packageName = packageName,
                    withSubpackages = attributes?.getNamedItem("withSubpackages")?.nodeValue?.lowercase() == "true",
                )
            }
        }
        val childNodes = node.childNodes
        if (childNodes != null && childNodes.length > 0) {
            collectPackageOptions(childNodes, packages)
        }
    }
}
