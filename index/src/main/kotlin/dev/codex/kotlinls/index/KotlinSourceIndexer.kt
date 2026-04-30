package dev.codex.kotlinls.index

import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.SymbolKind
import dev.codex.kotlinls.workspace.LineIndex
import dev.codex.kotlinls.workspace.toDocumentUri
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.lexer.KtTokens
import java.nio.file.Path

class KotlinSourceIndexer : AutoCloseable {
    private val disposable: Disposable = Disposer.newDisposable("kotlinls-light-source-index")
    private val psiFactory: KtPsiFactory = createPsiFactory(disposable)

    fun index(
        path: Path,
        moduleName: String,
        text: String,
    ): List<IndexedSymbol> {
        val ktFile = psiFactory.createFile(path.fileName.toString(), text)
        val packageName = ktFile.packageFqName.asString()
        return ktFile.declarations
            .flatMap { declaration -> declaration.collectIndexableDeclarations() }
            .mapNotNull { declaration ->
                if (!declaration.isIndexCandidate()) return@mapNotNull null
                val name = declaration.indexedName() ?: return@mapNotNull null
                val fqName = declaration.indexedFqName(packageName, name)
                val selectionTarget = declaration.nameIdentifier ?: declaration
                IndexedSymbol(
                    id = fqName ?: "${path.toDocumentUri()}#$name@${declaration.textRange.startOffset}",
                    name = name,
                    fqName = fqName,
                    kind = declaration.indexedKind(),
                    path = path,
                    uri = path.toDocumentUri(),
                    range = ktFile.rangeOf(declaration.textRange.startOffset, declaration.textRange.endOffset),
                    selectionRange = ktFile.rangeOf(selectionTarget.textRange.startOffset, selectionTarget.textRange.endOffset),
                    containerName = declaration.indexedContainer()?.name,
                    containerFqName = declaration.indexedContainer()?.let { owner ->
                        owner.indexedFqName(packageName, owner.name)
                    },
                    signature = declaration.signatureText(),
                    documentation = KDocMarkdownRenderer.render(declaration.docComment),
                    packageName = packageName,
                    moduleName = moduleName,
                    importable = declaration.parent is KtFile,
                    receiverType = (declaration as? KtCallableDeclaration)?.receiverTypeReference?.text,
                    resultType = declaration.resultType(packageName, fqName),
                    parameterCount = declaration.parameterCount(),
                    supertypes = (declaration as? KtClassOrObject)
                        ?.superTypeListEntries
                        ?.mapNotNull { it.typeReference?.text }
                        .orEmpty(),
                    parameters = declaration.indexedParameters(),
                    enumEntries = (declaration as? KtClass)
                        ?.takeIf { it.isEnum() }
                        ?.indexedEnumEntries()
                        .orEmpty(),
                    enumValue = (declaration as? KtEnumEntry)?.toIndexedEnumEntry(),
                )
            }
    }

    override fun close() {
        runCatching { Disposer.dispose(disposable) }
    }

    private fun KtDeclaration.collectIndexableDeclarations(): List<KtNamedDeclaration> =
        buildList {
            when (this@collectIndexableDeclarations) {
                is KtNamedDeclaration -> add(this@collectIndexableDeclarations)
            }
            if (this@collectIndexableDeclarations is KtClassOrObject) {
                if (this@collectIndexableDeclarations is KtClass && isEnum()) {
                    body?.children
                        ?.filterIsInstance<KtEnumEntry>()
                        ?.forEach { entry -> add(entry) }
                }
                declarations.forEach { child ->
                    addAll(child.collectIndexableDeclarations())
                }
                if (this@collectIndexableDeclarations is KtClass) {
                    primaryConstructorParameters
                        .filter { parameter -> parameter.hasValOrVar() }
                        .forEach { parameter -> add(parameter) }
                }
                primaryConstructor?.let { add(it) }
                secondaryConstructors.forEach { add(it) }
            }
        }

    private fun KtNamedDeclaration.isIndexCandidate(): Boolean {
        if (this is KtProperty && isLocal) return false
        if (this is KtParameter && hasValOrVar() && containingClassOrObject is KtClass) return true
        if (this is KtEnumEntry) return true
        val parent = parent
        return parent is KtFile || parent is KtClassOrObject || this is KtConstructor<*>
    }

    private fun KtNamedDeclaration.indexedName(): String? =
        name ?: (this as? KtConstructor<*>)?.containingClassOrObject?.name

    private fun KtNamedDeclaration.indexedFqName(
        packageName: String,
        resolvedName: String?,
    ): String? {
        val name = resolvedName ?: return null
        val owners = parentsWithSelf
            .filterIsInstance<KtNamedDeclaration>()
            .drop(1)
            .mapNotNull { it.name }
            .toList()
            .asReversed()
        return (listOfNotNull(packageName.takeIf { it.isNotBlank() }) + owners + name).joinToString(".")
    }

    private fun KtNamedDeclaration.indexedContainer(): KtClassOrObject? =
        containingClassOrObject ?: parentsWithSelf.filterIsInstance<KtClassOrObject>().firstOrNull()

    private fun KtNamedDeclaration.indexedKind(): Int = when {
        this is KtEnumEntry -> SymbolKind.ENUM_MEMBER
        this is KtClass && isInterface() -> SymbolKind.INTERFACE
        this is KtClass && isEnum() -> SymbolKind.ENUM
        this is KtObjectDeclaration -> SymbolKind.OBJECT
        this is KtClassOrObject -> SymbolKind.CLASS
        this is KtNamedFunction -> SymbolKind.FUNCTION
        this is KtConstructor<*> -> SymbolKind.CONSTRUCTOR
        this is KtProperty -> SymbolKind.PROPERTY
        this is KtParameter -> SymbolKind.VARIABLE
        this is KtTypeAlias -> SymbolKind.TYPE_PARAMETER
        else -> SymbolKind.VARIABLE
    }

    private fun KtNamedDeclaration.resultType(
        packageName: String,
        fqName: String?,
    ): String? = when (this) {
        is KtConstructor<*> -> containingClassOrObject?.indexedFqName(packageName, containingClassOrObject?.name)
        is KtNamedFunction -> typeReference?.text
        is KtProperty -> typeReference?.text ?: initializer?.inferredResultType(containingKtFile, packageName)
        is KtParameter -> typeReference?.text
        is KtClassOrObject -> fqName
        else -> null
    }

    private fun KtExpression.inferredResultType(
        file: KtFile,
        packageName: String,
    ): String? = when (this) {
        is KtCallExpression -> calleeExpression?.text?.let { visibleName ->
            resolveVisibleType(file, visibleName, packageName)
        }

        is KtQualifiedExpression -> selectorExpression?.inferredResultType(file, packageName)
        is KtSafeQualifiedExpression -> selectorExpression?.inferredResultType(file, packageName)
        is KtNameReferenceExpression -> resolveVisibleType(file, getReferencedName(), packageName)
        else -> null
    }

    private fun resolveVisibleType(
        file: KtFile,
        visibleName: String,
        packageName: String,
    ): String? {
        SourceIndexLookup.imports(file.text)
            .firstOrNull { it.visibleName == visibleName }
            ?.let { return it.fqName }
        return when {
            visibleName.contains('.') -> visibleName
            visibleName.firstOrNull()?.isUpperCase() == true && packageName.isNotBlank() -> "$packageName.$visibleName"
            else -> visibleName
        }
    }

    private fun KtNamedDeclaration.parameterCount(): Int = when (this) {
        is KtNamedFunction -> valueParameters.size
        is KtConstructor<*> -> valueParameters.size
        is KtClass -> primaryConstructorParameters.size
        else -> 0
    }

    private fun KtNamedDeclaration.indexedParameters(): List<IndexedParameter> = when (this) {
        is KtNamedFunction -> valueParameters.map { it.toIndexedParameter() }
        is KtConstructor<*> -> valueParameters.map { it.toIndexedParameter() }
        is KtClass -> primaryConstructorParameters.map { it.toIndexedParameter() }
        else -> emptyList()
    }

    private fun KtParameter.toIndexedParameter(): IndexedParameter =
        IndexedParameter(
            name = name ?: "<anonymous>",
            type = typeReference?.text,
            defaultValue = defaultValue?.text?.trim(),
            isVararg = hasModifier(KtTokens.VARARG_KEYWORD),
            isNullable = typeReference?.text?.contains('?') == true,
        )

    private fun KtClass.indexedEnumEntries(): List<IndexedEnumEntry> =
        body?.children
            ?.filterIsInstance<KtEnumEntry>()
            ?.map { entry -> entry.toIndexedEnumEntry() }
            .orEmpty()

    private fun KtEnumEntry.toIndexedEnumEntry(): IndexedEnumEntry {
        val arguments = enumEntryArguments(text, name)
        return IndexedEnumEntry(
            name = name ?: "<anonymous>",
            arguments = arguments,
            stringValue = arguments.firstNotNullOfOrNull(::stringLiteralValue),
        )
    }

    private fun enumEntryArguments(text: String, name: String?): List<String> {
        val resolvedName = name ?: return emptyList()
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        val remainder = firstLine.removePrefix(resolvedName).trimStart()
        if (!remainder.startsWith("(")) return emptyList()
        val closing = remainder.lastIndexOf(')')
        if (closing <= 1) return emptyList()
        return remainder.substring(1, closing)
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    private fun stringLiteralValue(argument: String): String? =
        argument
            .takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }
            ?.substring(1, argument.length - 1)

    private fun KtDeclaration.signatureText(): String =
        text.lineSequence()
            .firstOrNull()
            ?.replace('{', ' ')
            ?.trim()
            .orEmpty()

    private fun KtFile.rangeOf(startOffset: Int, endOffset: Int): Range {
        val lineIndex = LineIndex.build(text)
        return lineIndex.range(startOffset, endOffset)
    }

    private fun createPsiFactory(disposable: Disposable): KtPsiFactory {
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "kotlinls-light-index")
        }
        val method = KotlinCoreEnvironment::class.java.getMethod(
            "createForProduction",
            Disposable::class.java,
            CompilerConfiguration::class.java,
            EnvironmentConfigFiles::class.java,
        )
        val environment = method.invoke(
            null,
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        ) as KotlinCoreEnvironment
        return KtPsiFactory(environment.project, false)
    }
}
