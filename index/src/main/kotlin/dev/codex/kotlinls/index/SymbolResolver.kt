package dev.codex.kotlinls.index

import dev.codex.kotlinls.analysis.WorkspaceAnalysisSnapshot
import dev.codex.kotlinls.protocol.Location
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.workspace.LineIndex
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.resolve.DescriptorUtils

class SymbolResolver {
    fun symbolAt(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        uri: String,
        position: Position,
    ): IndexedSymbol? {
        val file = snapshot.filesByUri[uri] ?: return null
        val offset = LineIndex.build(file.text).offset(position)
        val leaf = file.ktFile.findElementAt(offset) ?: return null

        leaf.parentsWithSelf.filterIsInstance<KtNameReferenceExpression>().firstOrNull()?.let { reference ->
            val descriptor = snapshot.bindingContext[BindingContext.REFERENCE_TARGET, reference]
            val fqName = descriptor?.let { DescriptorUtils.getFqNameSafe(it.original).asString() }
            if (fqName != null) {
                return index.symbolsByFqName[fqName]
            }
            val referencedName = reference.getReferencedName()
            val imported = file.ktFile.importDirectives.firstOrNull { directive ->
                directive.importedName?.asString() == referencedName
            }?.importedFqName?.asString()
            if (imported != null) {
                return index.symbolsByFqName[imported]
            }
            val samePackage = index.symbolsByName[referencedName]
                ?.filter { it.packageName == file.ktFile.packageFqName.asString() }
                ?.firstOrNull()
            if (samePackage != null) {
                return samePackage
            }
            val uniqueByName = index.symbolsByName[referencedName]?.singleOrNull()
            if (uniqueByName != null) {
                return uniqueByName
            }
        }

        leaf.parentsWithSelf.filterIsInstance<KtNamedDeclaration>().firstOrNull()?.let { declaration ->
            val descriptor = snapshot.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
            val fqName = descriptor?.let { DescriptorUtils.getFqNameSafe(it.original).asString() }
            if (fqName != null) {
                return index.symbolsByFqName[fqName]
            }
            return index.symbolsByPath[file.originalPath.normalize()]
                ?.firstOrNull { symbol ->
                    symbol.name == declaration.name &&
                        symbol.selectionRange.start.line == LineIndex.build(file.text).position(declaration.textRange.startOffset).line
                }
        }

        return null
    }

    fun descriptorAt(
        snapshot: WorkspaceAnalysisSnapshot,
        uri: String,
        position: Position,
    ): DeclarationDescriptor? {
        val file = snapshot.filesByUri[uri] ?: return null
        val offset = LineIndex.build(file.text).offset(position)
        val leaf = file.ktFile.findElementAt(offset) ?: return null

        leaf.parentsWithSelf.filterIsInstance<KtNameReferenceExpression>().firstOrNull()?.let { reference ->
            snapshot.bindingContext[BindingContext.REFERENCE_TARGET, reference]?.let { return it }
            resolvedCallDescriptor(snapshot, reference)?.let { return it }
        }

        leaf.parentsWithSelf.filterIsInstance<KtCallExpression>().firstOrNull()?.calleeExpression
            ?.let { callee ->
                if (callee is KtNameReferenceExpression) {
                    snapshot.bindingContext[BindingContext.REFERENCE_TARGET, callee]?.let { return it }
                }
                resolvedCallDescriptor(snapshot, callee)?.let { return it }
            }

        leaf.parentsWithSelf.filterIsInstance<KtNamedDeclaration>().firstOrNull()?.let { declaration ->
            snapshot.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]?.let { return it }
        }

        return null
    }

    fun sourceDeclaration(
        snapshot: WorkspaceAnalysisSnapshot,
        descriptor: DeclarationDescriptor,
    ): KtDeclaration? = DescriptorToSourceUtils.descriptorToDeclaration(descriptor.original) as? KtDeclaration

    fun sourceLocation(
        snapshot: WorkspaceAnalysisSnapshot,
        descriptor: DeclarationDescriptor,
    ): Location? {
        val declaration = sourceDeclaration(snapshot, descriptor) ?: return null
        val file = analyzedFileForKtFile(snapshot, declaration.containingKtFile) ?: return null
        val lineIndex = LineIndex.build(file.text)
        return Location(file.uri, lineIndex.range(declaration.textRange.startOffset, declaration.textRange.endOffset))
    }

    fun enclosingExpressionTypeText(
        snapshot: WorkspaceAnalysisSnapshot,
        uri: String,
        position: Position,
    ): String? {
        val expression = enclosingExpression(snapshot, uri, position) ?: return null
        val expressionType = snapshot.bindingContext.getType(expression) ?: return null
        return expressionType.toString()
    }

    fun expressionTypeSymbolAt(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        uri: String,
        position: Position,
    ): IndexedSymbol? {
        val expression = enclosingExpression(snapshot, uri, position) ?: return null
        val expressionType = snapshot.bindingContext.getType(expression) ?: return null
        val typeDescriptor = expressionType.constructor.declarationDescriptor
        val fqName = typeDescriptor?.let { DescriptorUtils.getFqNameSafe(it).asString() }
        if (!fqName.isNullOrBlank()) {
            index.symbolsByFqName[fqName]?.let { return it }
        }
        val shortName = fqName?.substringAfterLast('.')
            ?: expressionType.toString().substringAfterLast('.')
        return index.symbolsByName[shortName]?.singleOrNull()
    }

    fun descriptorTypeSymbolAt(
        snapshot: WorkspaceAnalysisSnapshot,
        index: WorkspaceIndex,
        uri: String,
        position: Position,
    ): IndexedSymbol? {
        val descriptor = descriptorAt(snapshot, uri, position) ?: return null
        val typeDescriptor = when (descriptor) {
            is VariableDescriptor -> descriptor.type.constructor.declarationDescriptor
            is CallableDescriptor -> descriptor.returnType?.constructor?.declarationDescriptor
            is ClassifierDescriptor -> descriptor
            else -> null
        } ?: return null
        val fqName = DescriptorUtils.getFqNameSafe(typeDescriptor.original).asString()
        index.symbolsByFqName[fqName]?.let { return it }
        return index.symbolsByName[fqName.substringAfterLast('.')]?.singleOrNull()
    }

    private fun resolvedCallDescriptor(
        snapshot: WorkspaceAnalysisSnapshot,
        element: KtElement,
    ): DeclarationDescriptor? {
        val call = snapshot.bindingContext[BindingContext.CALL, element] ?: return null
        return snapshot.bindingContext[BindingContext.RESOLVED_CALL, call]?.resultingDescriptor
    }

    private fun enclosingExpression(
        snapshot: WorkspaceAnalysisSnapshot,
        uri: String,
        position: Position,
    ): KtExpression? {
        val file = snapshot.filesByUri[uri] ?: return null
        val offset = LineIndex.build(file.text).offset(position)
        val leaf = file.ktFile.findElementAt(offset) ?: return null
        return leaf.parentsWithSelf.filterIsInstance<KtExpression>().firstOrNull()
    }

    private fun analyzedFileForKtFile(
        snapshot: WorkspaceAnalysisSnapshot,
        ktFile: KtFile,
    ) = snapshot.files.firstOrNull { analyzed ->
        analyzed.ktFile == ktFile || analyzed.ktFile.virtualFilePath == ktFile.virtualFilePath
    }
}
