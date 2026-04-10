package dev.codex.kotlinls.hover

import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.index.SourceIndexLookup
import dev.codex.kotlinls.index.SymbolResolver
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.protocol.Hover
import dev.codex.kotlinls.protocol.InlayHint
import dev.codex.kotlinls.protocol.InlayHintParams
import dev.codex.kotlinls.protocol.MarkupContent
import dev.codex.kotlinls.protocol.ParameterInformation
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.SignatureHelp
import dev.codex.kotlinls.protocol.SignatureInformation
import dev.codex.kotlinls.protocol.TextDocumentPositionParams
import dev.codex.kotlinls.workspace.LineIndex
import java.nio.file.Path
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils

class HoverAndSignatureService(
    private val resolver: SymbolResolver = SymbolResolver(),
) {
    fun hover(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: TextDocumentPositionParams,
    ): Hover? {
        val symbol = resolver.symbolAt(snapshot, index, params.textDocument.uri, params.position)
        val descriptor = resolver.descriptorAt(snapshot, params.textDocument.uri, params.position)
        val typeText = resolver.enclosingExpressionTypeText(snapshot, params.textDocument.uri, params.position)
        if (symbol == null && descriptor == null && typeText == null) return null
        val markdown = buildString {
            when {
                symbol != null -> {
                    appendLine("```kotlin")
                    appendLine(symbol.signature)
                    appendLine("```")
                    if (!symbol.documentation.isNullOrBlank()) {
                        appendLine()
                        appendLine(symbol.documentation)
                    }
                }

                descriptor != null -> {
                    appendLine("```kotlin")
                    appendLine(renderSignature(descriptor))
                    appendLine("```")
                    val documentation = descriptorDocumentation(index, descriptor)
                        ?: descriptorSourceDocumentation(snapshot, descriptor)
                    documentation?.takeIf { it.isNotBlank() }?.let {
                        appendLine()
                        appendLine(it)
                    }
                }
            }
            if (typeText != null) {
                if (isNotEmpty()) appendLine()
                append("**Type:** `$typeText`")
            }
        }
        return Hover(MarkupContent(kind = "markdown", value = markdown.trim()))
    }

    fun hoverFromIndex(
        index: WorkspaceIndex,
        path: Path,
        text: String,
        params: TextDocumentPositionParams,
    ): Hover? {
        val symbol = SourceIndexLookup.resolveSymbol(index, path, text, params.position) ?: return null
        val markdown = buildString {
            appendLine("```kotlin")
            appendLine(symbol.signature)
            appendLine("```")
            if (!symbol.documentation.isNullOrBlank()) {
                appendLine()
                appendLine(symbol.documentation)
            }
        }
        return Hover(MarkupContent(kind = "markdown", value = markdown.trim()))
    }

    fun signatureHelp(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: TextDocumentPositionParams,
    ): SignatureHelp? {
        val file = snapshot.filesByUri[params.textDocument.uri] ?: return null
        val offset = LineIndex.build(file.text).offset(params.position)
        val leaf = file.ktFile.findElementAt(offset) ?: return null
        val call = leaf.parentsWithSelf.filterIsInstance<KtCallExpression>().firstOrNull() ?: return null
        val descriptor = resolvedCallDescriptor(snapshot, call) ?: return null
        val documentation = descriptorDocumentation(index, descriptor)
        val argumentsText = call.valueArgumentList?.text.orEmpty()
        val activeParameter = argumentsText.takeWhile { it != ')' }.count { it == ',' }
        return SignatureHelp(
            signatures = listOf(
                SignatureInformation(
                    label = renderSignature(descriptor),
                    documentation = documentation?.let { MarkupContent("markdown", it) },
                    parameters = callableParameters(descriptor)?.map { ParameterInformation(it) },
                ),
            ),
            activeSignature = 0,
            activeParameter = activeParameter,
        )
    }

    fun inlayHints(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        params: InlayHintParams,
    ): List<InlayHint> {
        val file = snapshot.filesByUri[params.textDocument.uri] ?: return emptyList()
        val lineIndex = LineIndex.build(file.text)
        val argumentHints = file.ktFile
            .collectDescendantsOfType<KtCallExpression>()
            .flatMap { call ->
                val target = resolvedCallDescriptor(snapshot, call) as? CallableDescriptor ?: return@flatMap emptyList()
                call.valueArguments.mapIndexedNotNull { indexArgument, argument ->
                    val expression = argument.getArgumentExpression() ?: return@mapIndexedNotNull null
                    val parameterName = target.valueParameters
                        .getOrNull(indexArgument)
                        ?.name
                        ?.asString()
                        ?.takeIf { it.isNotBlank() }
                        ?: return@mapIndexedNotNull null
                    InlayHint(
                        position = lineIndex.position(expression.textRange.startOffset),
                        label = "$parameterName:",
                        kind = 2,
                    )
                }
            }
        val typeHints = file.ktFile
            .collectDescendantsOfType<KtProperty>()
            .mapNotNull { property ->
                if (property.typeReference != null) return@mapNotNull null
                val initializer = property.initializer ?: return@mapNotNull null
                val nameIdentifier = property.nameIdentifier ?: return@mapNotNull null
                val typeText = snapshot.bindingContext.getType(initializer)?.toString() ?: return@mapNotNull null
                InlayHint(
                    position = lineIndex.position(nameIdentifier.textRange.endOffset),
                    label = ": $typeText",
                    kind = 1,
                )
            }
        return (argumentHints + typeHints)
            .filter { hint -> contains(params.range, hint.position) }
    }

    private fun resolvedCallDescriptor(
        snapshot: WorkspaceAnalysisSnapshot,
        call: KtCallExpression,
    ): DeclarationDescriptor? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        return snapshot.bindingContext[BindingContext.REFERENCE_TARGET, callee]
    }

    private fun renderSignature(descriptor: DeclarationDescriptor): String =
        when (descriptor) {
            is CallableDescriptor -> DescriptorRenderer.SHORT_NAMES_IN_TYPES.render(descriptor)
            is ClassDescriptor -> "class ${descriptor.name.asString()}"
            else -> descriptor.name.asString()
        }

    private fun callableParameters(descriptor: DeclarationDescriptor): List<String>? =
        (descriptor as? CallableDescriptor)
            ?.valueParameters
            ?.map { parameter -> "${parameter.name.asString()}: ${parameter.type}" }
            ?.takeIf { it.isNotEmpty() }

    private fun descriptorDocumentation(
        index: WorkspaceIndex,
        descriptor: DeclarationDescriptor,
    ): String? {
        val fqName = DescriptorUtils.getFqNameSafe(descriptor.original).asString()
        return index.symbolsByFqName[fqName]?.documentation
    }

    private fun descriptorSourceDocumentation(
        snapshot: WorkspaceAnalysisSnapshot,
        descriptor: DeclarationDescriptor,
    ): String? = resolver.sourceDeclaration(snapshot, descriptor)
        ?.let { declaration -> declaration.docComment?.let(dev.codex.kotlinls.index.KDocMarkdownRenderer::render) }

    private fun contains(range: dev.codex.kotlinls.protocol.Range, position: Position): Boolean =
        compare(range.start, position) <= 0 && compare(position, range.end) <= 0

    private fun compare(left: Position, right: Position): Int =
        when {
            left.line != right.line -> left.line.compareTo(right.line)
            else -> left.character.compareTo(right.character)
        }
}
