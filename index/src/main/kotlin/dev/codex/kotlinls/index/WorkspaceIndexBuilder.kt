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
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk

class WorkspaceIndexBuilder(
    private val externalSourceMirror: ExternalSourceMirror = ExternalSourceMirror(),
) {
    fun build(
        snapshot: WorkspaceAnalysisSnapshot,
        targetPaths: Set<java.nio.file.Path>? = null,
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
        symbolAccumulator += javaSourceSymbols(snapshot)
        symbolAccumulator += externalLibrarySymbols(snapshot)
        symbolAccumulator += jdkSourceSymbols()

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

        return WorkspaceIndex(
            symbols = symbolAccumulator.sortedWith(compareBy({ it.name }, { it.path.toString() })),
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

    private fun javaSourceSymbols(snapshot: WorkspaceAnalysisSnapshot): List<IndexedSymbol> =
        snapshot.project.modules.flatMap { module ->
            module.javaSourceRoots.flatMap { root ->
                if (!root.toFile().exists()) return@flatMap emptyList()
                root.walk()
                    .filter { it.isRegularFile() && it.extension == "java" }
                    .flatMap { path -> JavaSourceIndexer.index(path, module.name) }
                    .toList()
            }
        }

    private fun externalLibrarySymbols(snapshot: WorkspaceAnalysisSnapshot): List<IndexedSymbol> {
        val project = snapshot.files.firstOrNull()?.ktFile?.project ?: return emptyList()
        val psiFactory = KtPsiFactory(project, false)
        return snapshot.project.modules
            .flatMap { module -> module.classpathSourceJars.map { jar -> module.name to jar } }
            .distinctBy { (_, jar) -> jar.normalize() }
            .flatMap { (moduleName, jar) ->
                val extractedRoot = runCatching { externalSourceMirror.materialize(jar) }.getOrNull() ?: return@flatMap emptyList()
                extractedRoot.walk()
                    .filter { it.isRegularFile() && it.extension in setOf("kt", "kts", "java") }
                    .flatMap { path ->
                        when (path.extension) {
                            "java" -> JavaSourceIndexer.index(path, moduleName)
                            else -> kotlinSourceSymbols(path, moduleName, psiFactory)
                        }
                    }
                    .toList()
            }
    }

    private fun kotlinSourceSymbols(
        path: java.nio.file.Path,
        moduleName: String,
        psiFactory: KtPsiFactory,
    ): List<IndexedSymbol> {
        val text = runCatching { path.readText() }.getOrNull() ?: return emptyList()
        val ktFile = psiFactory.createFile(path.fileName.toString(), text)
        val packageName = ktFile.packageFqName.asString()
        return ktFile.collectDescendantsOfType<KtNamedDeclaration>()
            .mapNotNull { declaration ->
                if (!declaration.isExternalIndexCandidate()) return@mapNotNull null
                val name = externalDeclarationName(declaration) ?: return@mapNotNull null
                val fqName = externalFqName(packageName, declaration, name)
                val selectionTarget = declaration.nameIdentifier ?: declaration
                IndexedSymbol(
                    id = fqName ?: "${path.toUri()}#$name@${declaration.textRange.startOffset}",
                    name = name,
                    fqName = fqName,
                    kind = externalSymbolKind(declaration),
                    path = path,
                    uri = path.toUri().toString(),
                    range = ktFile.rangeOf(declaration.textRange.startOffset, declaration.textRange.endOffset),
                    selectionRange = ktFile.rangeOf(selectionTarget.textRange.startOffset, selectionTarget.textRange.endOffset),
                    containerName = declaration.containingClassOrObject?.name,
                    containerFqName = declaration.containingClassOrObject?.let { owner ->
                        externalFqName(packageName, owner, owner.name)
                    },
                    signature = declaration.signatureText(),
                    documentation = KDocMarkdownRenderer.render(declaration.docComment),
                    packageName = packageName,
                    moduleName = moduleName,
                    importable = declaration.parent is KtFile,
                    receiverType = (declaration as? KtCallableDeclaration)?.receiverTypeReference?.text,
                    resultType = externalResultType(packageName, declaration, fqName),
                    parameterCount = externalParameterCount(declaration),
                    supertypes = (declaration as? KtClassOrObject)
                        ?.superTypeListEntries
                        ?.mapNotNull { it.typeReference?.text }
                        .orEmpty(),
                )
            }
    }

    private fun symbolKind(
        declaration: KtNamedDeclaration,
        descriptor: DeclarationDescriptor?,
    ): Int = when {
        declaration is KtClass && declaration.isInterface() -> SymbolKind.INTERFACE
        declaration is KtClass && declaration.isEnum() -> SymbolKind.ENUM
        declaration is KtClassOrObject -> SymbolKind.CLASS
        declaration is KtObjectDeclaration -> SymbolKind.OBJECT
        declaration is KtEnumEntry -> SymbolKind.ENUM_MEMBER
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

    private fun jdkSourceSymbols(): List<IndexedSymbol> {
        val srcZip = defaultJdkSourceArchive() ?: return emptyList()
        val extractedRoot = runCatching { externalSourceMirror.materialize(srcZip) }.getOrNull() ?: return emptyList()
        return extractedRoot.walk()
            .filter { path ->
                path.isRegularFile() &&
                    path.extension == "java" &&
                    path.toString().contains("/java.base/java/") &&
                    jdkPackageAllowed(path)
            }
            .flatMap { path -> JavaSourceIndexer.index(path, "jdk") }
            .toList()
    }

    private fun defaultJdkSourceArchive(): java.nio.file.Path? {
        val javaHome = java.nio.file.Path.of(System.getProperty("java.home"))
        val candidates = listOf(
            javaHome.resolve("lib/src.zip"),
            javaHome.resolve("../lib/src.zip").normalize(),
            javaHome.resolve("../../lib/src.zip").normalize(),
        )
        return candidates.firstOrNull { it.isRegularFile() }
    }

    private fun jdkPackageAllowed(path: java.nio.file.Path): Boolean {
        val normalized = path.toString().replace('\\', '/')
        return listOf(
            "/java.base/java/lang/",
            "/java.base/java/util/",
            "/java.base/java/io/",
            "/java.base/java/nio/",
            "/java.base/java/time/",
            "/java.base/java/math/",
        ).any(normalized::contains)
    }
}

private fun externalDeclarationName(declaration: KtNamedDeclaration): String? =
    declaration.name ?: (declaration as? KtConstructor<*>)?.containingClassOrObject?.name

private fun externalFqName(
    packageName: String,
    declaration: KtNamedDeclaration,
    resolvedName: String?,
): String? {
    val name = resolvedName ?: return null
    val owners = declaration.parentsWithSelf
        .filterIsInstance<KtNamedDeclaration>()
        .drop(1)
        .mapNotNull { it.name }
        .toList()
        .asReversed()
    return (listOfNotNull(packageName.takeIf { it.isNotBlank() }) + owners + name).joinToString(".")
}

private fun externalSymbolKind(declaration: KtNamedDeclaration): Int = when {
    declaration is KtClass && declaration.isInterface() -> SymbolKind.INTERFACE
    declaration is KtClass && declaration.isEnum() -> SymbolKind.ENUM
    declaration is KtClassOrObject -> SymbolKind.CLASS
    declaration is KtObjectDeclaration -> SymbolKind.OBJECT
    declaration is KtEnumEntry -> SymbolKind.ENUM_MEMBER
    declaration is KtNamedFunction -> SymbolKind.FUNCTION
    declaration is KtConstructor<*> -> SymbolKind.CONSTRUCTOR
    declaration is KtProperty -> SymbolKind.PROPERTY
    declaration is KtParameter -> SymbolKind.VARIABLE
    declaration is KtTypeAlias -> SymbolKind.TYPE_PARAMETER
    else -> SymbolKind.VARIABLE
}

private fun externalResultType(
    packageName: String,
    declaration: KtNamedDeclaration,
    fqName: String?,
): String? = when (declaration) {
    is KtConstructor<*> -> declaration.containingClassOrObject?.let { owner ->
        externalFqName(packageName, owner, owner.name)
    }

    is KtNamedFunction -> declaration.typeReference?.text
    is KtProperty -> declaration.typeReference?.text
    is KtClassOrObject -> fqName
    else -> null
}

private fun externalParameterCount(declaration: KtNamedDeclaration): Int = when (declaration) {
    is KtNamedFunction -> declaration.valueParameters.size
    is KtConstructor<*> -> declaration.valueParameters.size
    else -> 0
}

private fun KtNamedDeclaration.isExternalIndexCandidate(): Boolean {
    if (this is KtProperty && isLocal) return false
    val parent = parent
    return parent is KtFile || parent is KtClassOrObject || this is KtConstructor<*>
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
