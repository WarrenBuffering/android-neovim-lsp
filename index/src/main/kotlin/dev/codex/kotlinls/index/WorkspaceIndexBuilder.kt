package dev.codex.kotlinls.index

import dev.codex.kotlinls.analysis.AnalyzedFile
import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.SymbolKind
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk

class WorkspaceIndexBuilder(
    private val includeSupportSymbols: Boolean = true,
    private val supportSymbolIndexBuilder: SupportSymbolIndexBuilder = SupportSymbolIndexBuilder(),
) {
    fun build(
        snapshot: WorkspaceAnalysisSnapshot,
        targetPaths: Set<Path>? = null,
        progress: ((String, Int, Int) -> Unit)? = null,
    ): WorkspaceIndex {
        val filteredFiles = snapshot.files.filter { file ->
            targetPaths == null || file.originalPath.normalize() in targetPaths
        }
        val symbolAccumulator = mutableListOf<IndexedSymbol>()
        val declarationToId = linkedMapOf<KtNamedDeclaration, String>()
        val descriptorToId = linkedMapOf<DeclarationDescriptor, String>()

        val totalFiles = filteredFiles.size.coerceAtLeast(1)
        filteredFiles.forEachIndexed { index, file ->
            file.ktFile.collectDescendantsOfType<KtNamedDeclaration>().forEach { declaration ->
                declaration.name ?: return@forEach
                val symbol = declaration.toIndexedSymbol(file, snapshot.bindingContext)
                symbolAccumulator += symbol
                declarationToId[declaration] = symbol.id
                descriptorFor(snapshot.bindingContext, declaration)?.let { descriptorToId[it.original] = symbol.id }
            }
            if (shouldReportProgress(index + 1, totalFiles)) {
                progress?.invoke("Indexed ${file.originalPath.fileName}", index + 1, totalFiles)
            }
        }
        val references = mutableListOf<IndexedReference>()
        val callEdges = mutableListOf<CallEdge>()

        filteredFiles.forEach { file ->
            file.ktFile.collectDescendantsOfType<KtNameReferenceExpression>().forEach { reference ->
                val target = snapshot.bindingContext[BindingContext.REFERENCE_TARGET, reference] ?: return@forEach
                val symbolId = descriptorToId[target.original]
                    ?: DescriptorUtils.getFqNameSafe(target.original).asString().takeIf { it.isNotBlank() }
                    ?: return@forEach
                val range = file.ktFile.rangeOf(reference.textRange.startOffset, reference.textRange.endOffset)
                val containerId = reference.parentsWithSelf
                    .filterIsInstance<KtNamedDeclaration>()
                    .firstNotNullOfOrNull { declarationToId[it] }
                references += IndexedReference(
                    symbolId = symbolId,
                    path = file.originalPath,
                    uri = file.uri,
                    range = range,
                    containerSymbolId = containerId,
                )
                if (target is CallableDescriptor && containerId != null) {
                    callEdges += CallEdge(
                        callerSymbolId = containerId,
                        calleeSymbolId = symbolId,
                        range = range,
                        path = file.originalPath,
                    )
                }
            }
        }

        val supportSymbols = when {
            includeSupportSymbols -> supportSymbolIndexBuilder.loadOrBuild(snapshot.project).symbols
            else -> emptyList()
        }

        return WorkspaceIndex(
            symbols = (symbolAccumulator + supportSymbols)
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.name }, { it.path.toString() })),
            references = references,
            callEdges = callEdges,
        )
    }

    private fun shouldReportProgress(current: Int, total: Int): Boolean =
        current == 1 || current == total || current % 25 == 0

    private fun KtNamedDeclaration.toIndexedSymbol(
        file: AnalyzedFile,
        bindingContext: BindingContext,
    ): IndexedSymbol {
        val descriptor = descriptorFor(bindingContext, this)
        val descriptorId = descriptor?.let { DescriptorUtils.getFqNameSafe(it.original).asString() }?.takeIf { it.isNotBlank() }
        val fallbackId = "${file.uri}#${name}@${textRange.startOffset}"
        val symbolKind = symbolKind(this, descriptor?.original)
        val selectionTarget = nameIdentifier ?: this
        val supertypes = when (this) {
            is KtClassOrObject -> superTypeListEntries.mapNotNull { entry ->
                bindingContext[BindingContext.TYPE, entry.typeReference]?.constructor?.declarationDescriptor?.let {
                    DescriptorUtils.getFqNameSafe(it).asString()
                } ?: entry.typeReference?.text
            }

            else -> emptyList()
        }
        return IndexedSymbol(
            id = descriptorId ?: fallbackId,
            name = name ?: "<anonymous>",
            fqName = descriptorId,
            kind = symbolKind,
            path = file.originalPath,
            uri = file.uri,
            range = file.ktFile.rangeOf(textRange.startOffset, textRange.endOffset),
            selectionRange = file.ktFile.rangeOf(selectionTarget.textRange.startOffset, selectionTarget.textRange.endOffset),
            containerName = containingClassOrObject?.name,
            containerFqName = containingTypeFqName(descriptor?.descriptor),
            signature = signatureText(),
            documentation = KDocMarkdownRenderer.render(docComment),
            packageName = file.ktFile.packageFqName.asString(),
            moduleName = file.module.name,
            importable = parent is KtFile,
            receiverType = extensionReceiverType(descriptor?.descriptor),
            resultType = resultType(descriptor?.descriptor),
            parameterCount = parameterCount(descriptor?.descriptor),
            supertypes = supertypes,
        )
    }

    private fun symbolKind(
        declaration: KtNamedDeclaration,
        descriptor: DeclarationDescriptor?,
    ): Int = when {
        declaration is KtEnumEntry -> SymbolKind.ENUM_MEMBER
        declaration is KtClass && declaration.isInterface() -> SymbolKind.INTERFACE
        declaration is KtClass && declaration.isEnum() -> SymbolKind.ENUM
        declaration is KtObjectDeclaration -> SymbolKind.OBJECT
        declaration is KtClassOrObject -> SymbolKind.CLASS
        declaration is KtNamedFunction -> SymbolKind.FUNCTION
        declaration is KtConstructor<*> || descriptor is ConstructorDescriptor -> SymbolKind.CONSTRUCTOR
        declaration is KtProperty && declaration.isLocal -> SymbolKind.VARIABLE
        declaration is KtProperty -> SymbolKind.PROPERTY
        declaration is KtParameter -> SymbolKind.VARIABLE
        declaration is KtTypeAlias -> SymbolKind.TYPE_PARAMETER
        descriptor is PropertyDescriptor -> SymbolKind.PROPERTY
        descriptor is FunctionDescriptor -> SymbolKind.FUNCTION
        descriptor is ClassDescriptor && descriptor.kind == ClassKind.INTERFACE -> SymbolKind.INTERFACE
        descriptor is ClassDescriptor -> SymbolKind.CLASS
        else -> SymbolKind.VARIABLE
    }

    private fun descriptorFor(
        bindingContext: BindingContext,
        declaration: KtNamedDeclaration,
    ): DescriptorHandle? {
        val descriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration] ?: return null
        return DescriptorHandle(descriptor, descriptor.original)
    }

    private data class DescriptorHandle(
        val descriptor: DeclarationDescriptor,
        val original: DeclarationDescriptor,
    )

    private fun containingTypeFqName(descriptor: DeclarationDescriptor?): String? =
        when (val container = descriptor?.containingDeclaration) {
            is ClassDescriptor -> DescriptorUtils.getFqNameSafe(container).asString()
            else -> null
        }

    private fun extensionReceiverType(descriptor: DeclarationDescriptor?): String? =
        (descriptor as? CallableDescriptor)
            ?.extensionReceiverParameter
            ?.type
            ?.constructor
            ?.declarationDescriptor
            ?.let { DescriptorUtils.getFqNameSafe(it).asString() }
            ?: (descriptor as? CallableDescriptor)?.extensionReceiverParameter?.type?.toString()

    private fun resultType(descriptor: DeclarationDescriptor?): String? =
        when (descriptor) {
            is ConstructorDescriptor -> descriptor.constructedClass.let { DescriptorUtils.getFqNameSafe(it).asString() }
            is FunctionDescriptor -> descriptor.returnType?.constructor?.declarationDescriptor?.let { DescriptorUtils.getFqNameSafe(it).asString() }
                ?: descriptor.returnType?.toString()

            is PropertyDescriptor -> descriptor.type.constructor.declarationDescriptor?.let { DescriptorUtils.getFqNameSafe(it).asString() }
                ?: descriptor.type.toString()

            is VariableDescriptor -> descriptor.type.constructor.declarationDescriptor?.let { DescriptorUtils.getFqNameSafe(it).asString() }
                ?: descriptor.type.toString()

            is ClassDescriptor -> DescriptorUtils.getFqNameSafe(descriptor).asString()
            else -> null
        }

    private fun parameterCount(descriptor: DeclarationDescriptor?): Int =
        (descriptor as? CallableDescriptor)?.valueParameters?.size ?: 0

}

private fun KtDeclaration.signatureText(): String =
    text.lineSequence()
        .firstOrNull()
        ?.replace('{', ' ')
        ?.trim()
        .orEmpty()

private fun KtFile.rangeOf(startOffset: Int, endOffset: Int): Range {
    val lineIndex = dev.codex.kotlinls.workspace.LineIndex.build(text)
    return lineIndex.range(startOffset, endOffset)
}
