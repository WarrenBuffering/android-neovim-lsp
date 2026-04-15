package dev.codex.kotlinls.index

import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.SymbolKind
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.jvm.kotlinFunction
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

class BinaryClasspathSymbolIndexer(
    private val runtimeSourceMirror: RuntimeClassSourceMirror = RuntimeClassSourceMirror(),
) {
    fun index(
        jar: Path,
        moduleName: String,
        classpathEntries: List<Path>,
    ): List<IndexedSymbol> {
        val normalizedJar = jar.normalize()
        if (!normalizedJar.isRegularFile() || normalizedJar.extension != "jar") return emptyList()
        val loader = URLClassLoader(classpathEntries.distinct().map(Path::toUri).map { it.toURL() }.toTypedArray(), javaClass.classLoader)
        return loader.use { classLoader ->
            JarFile(normalizedJar.toFile()).use { jarFile ->
                jarFile.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .mapNotNull { entry ->
                        entry.name
                            .removeSuffix(".class")
                            .replace('/', '.')
                            .takeIf(::indexableClassName)
                    }
                    .flatMap { className ->
                        val runtimeClass = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                            ?: return@flatMap emptySequence<IndexedSymbol>()
                        buildSymbols(
                            moduleName = moduleName,
                            runtimeClass = runtimeClass,
                            classPath = syntheticPath(normalizedJar, runtimeClass.name),
                            classUri = syntheticUri(normalizedJar, runtimeClass.name),
                        ).asSequence()
                    }
                    .toList()
            }
        }
    }

    fun indexRuntimeClasses(
        originPath: Path,
        moduleName: String,
        classNames: Sequence<String>,
    ): List<IndexedSymbol> {
        val classLoader = ClassLoader.getPlatformClassLoader()
        return classNames
            .flatMap { className ->
                val runtimeClass = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
                    ?: return@flatMap emptySequence<IndexedSymbol>()
                val mirroredSource = runCatching { runtimeSourceMirror.materialize(runtimeClass) }.getOrNull()
                buildSymbols(
                    moduleName = moduleName,
                    runtimeClass = runtimeClass,
                    classPath = mirroredSource ?: runtimePath(originPath, runtimeClass.name),
                    classUri = mirroredSource?.toUri()?.toString() ?: runtimeUri(runtimeClass),
                ).asSequence()
            }
            .toList()
    }

    private fun buildSymbols(
        moduleName: String,
        runtimeClass: Class<*>,
        classPath: Path,
        classUri: String,
    ): List<IndexedSymbol> {
        if (!Modifier.isPublic(runtimeClass.modifiers) && !runtimeClass.isEnum) return emptyList()
        val packageName = runtimeClass.packageName.orEmpty()
        val classFqName = runtimeClass.name.replace('$', '.')
        val classSymbol = IndexedSymbol(
            id = "binary::$classFqName",
            name = runtimeClass.simpleName.ifBlank { classFqName.substringAfterLast('.') },
            fqName = classFqName,
            kind = when {
                runtimeClass.isInterface -> SymbolKind.INTERFACE
                runtimeClass.isEnum -> SymbolKind.ENUM
                else -> SymbolKind.CLASS
            },
            path = classPath,
            uri = classUri,
            range = ZERO_RANGE,
            selectionRange = ZERO_RANGE,
            signature = classDeclarationSignature(runtimeClass),
            documentation = null,
            packageName = packageName,
            moduleName = moduleName,
            importable = true,
            resultType = classFqName,
            supertypes = buildList {
                runtimeClass.superclass?.name?.replace('$', '.')?.let(::add)
                runtimeClass.interfaces.map { it.name.replace('$', '.') }.forEach(::add)
            },
            enumEntries = runtimeClass.enumConstants
                ?.mapNotNull { constant ->
                    (constant as? Enum<*>)?.let { enumValue ->
                        IndexedEnumEntry(
                            name = enumValue.name,
                            stringValue = enumValue.toString(),
                        )
                    }
                }
                .orEmpty(),
        )
        val constructorSymbols = runtimeClass.constructors
            .asSequence()
            .filter { Modifier.isPublic(it.modifiers) }
            .mapIndexed { index, constructor -> constructorSymbol(moduleName, runtimeClass, constructor, index, classPath, classUri) }
            .toList()
        val fieldSymbols = runtimeClass.fields
            .asSequence()
            .filter { field -> Modifier.isPublic(field.modifiers) && !field.isSynthetic }
            .map { field -> fieldSymbol(moduleName, runtimeClass, field, classPath, classUri) }
            .toList()
        val methodSymbols = runtimeClass.methods
            .asSequence()
            .filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    !method.isSynthetic &&
                    method.declaringClass != Any::class.java
            }
            .map { method -> methodSymbol(moduleName, runtimeClass, method, classPath, classUri) }
            .toList()
        return listOf(classSymbol) + constructorSymbols + fieldSymbols + methodSymbols
    }

    private fun constructorSymbol(
        moduleName: String,
        runtimeClass: Class<*>,
        constructor: Constructor<*>,
        index: Int,
        classPath: Path,
        classUri: String,
    ): IndexedSymbol {
        val ownerFqName = runtimeClass.name.replace('$', '.')
        val ownerName = runtimeClass.simpleName.ifBlank { ownerFqName.substringAfterLast('.') }
        val kotlinValueParameters = runCatching {
            constructor.kotlinFunction?.parameters
                ?.filter { kotlinParameter -> kotlinParameter.kind.name == "VALUE" }
        }.getOrNull()
        return IndexedSymbol(
            id = "binary::$ownerFqName#<init>/$index",
            name = ownerName,
            fqName = ownerFqName,
            kind = SymbolKind.CONSTRUCTOR,
            path = classPath,
            uri = classUri,
            range = ZERO_RANGE,
            selectionRange = ZERO_RANGE,
            containerName = ownerName,
            containerFqName = ownerFqName,
            signature = buildString {
                append(ownerName)
                append('(')
                append(constructor.parameterTypes.joinToString(", ") { it.simpleName })
                append(')')
            },
            documentation = null,
            packageName = runtimeClass.packageName.orEmpty(),
            moduleName = moduleName,
            importable = false,
            resultType = ownerFqName,
            parameterCount = constructor.parameterCount,
            parameters = constructor.parameters.mapIndexed { index, parameter ->
                IndexedParameter(
                    name = kotlinValueParameters
                        ?.getOrNull(index)
                        ?.name
                        ?: parameter.name.takeUnless { it.isNullOrBlank() }
                        ?: "arg${index + 1}",
                    type = constructor.genericParameterTypes.getOrNull(index)?.typeName?.normalizeBinaryType()
                        ?: parameter.type.typeName.normalizeBinaryType(),
                    isVararg = constructor.isVarArgs && index == constructor.parameterCount - 1,
                    isNullable = false,
                )
            },
        )
    }

    private fun fieldSymbol(
        moduleName: String,
        runtimeClass: Class<*>,
        field: Field,
        classPath: Path,
        classUri: String,
    ): IndexedSymbol {
        val ownerFqName = runtimeClass.name.replace('$', '.')
        val ownerName = runtimeClass.simpleName.ifBlank { ownerFqName.substringAfterLast('.') }
        return IndexedSymbol(
            id = "binary::$ownerFqName#field:${field.name}",
            name = field.name,
            fqName = "$ownerFqName.${field.name}",
            kind = if (field.isEnumConstant) SymbolKind.ENUM_MEMBER else SymbolKind.PROPERTY,
            path = classPath,
            uri = classUri,
            range = ZERO_RANGE,
            selectionRange = ZERO_RANGE,
            containerName = ownerName,
            containerFqName = ownerFqName,
            signature = "${field.name}: ${field.type.typeName.normalizeBinaryType()}",
            documentation = null,
            packageName = runtimeClass.packageName.orEmpty(),
            moduleName = moduleName,
            importable = false,
            resultType = field.type.typeName.normalizeBinaryType(),
            enumValue = field.takeIf { it.isEnumConstant }
                ?.runCatching { get(null) as? Enum<*> }
                ?.getOrNull()
                ?.let { enumValue ->
                    IndexedEnumEntry(
                        name = enumValue.name,
                        stringValue = enumValue.toString(),
                    )
                },
        )
    }

    private fun methodSymbol(
        moduleName: String,
        runtimeClass: Class<*>,
        method: Method,
        classPath: Path,
        classUri: String,
    ): IndexedSymbol {
        val ownerFqName = runtimeClass.name.replace('$', '.')
        val ownerName = runtimeClass.simpleName.ifBlank { ownerFqName.substringAfterLast('.') }
        val kotlinFunction = runCatching { method.kotlinFunction }.getOrNull()
        val receiverType = kotlinFunction?.extensionReceiverParameter?.type?.toString()?.normalizeBinaryType()
        val topLevelImportable =
            Modifier.isStatic(method.modifiers) &&
                runtimeClass.simpleName.endsWith("Kt") &&
                runtimeClass.packageName.isNotBlank()
        val packageName = runtimeClass.packageName.orEmpty()
        val fqName = when {
            topLevelImportable -> "$packageName.${method.name}"
            else -> "$ownerFqName.${method.name}"
        }
        return IndexedSymbol(
            id = buildString {
                append("binary::")
                append(fqName)
                append('/')
                append(method.parameterTypes.joinToString(",") { it.typeName.normalizeBinaryType() })
            },
            name = method.name,
            fqName = fqName,
            kind = SymbolKind.FUNCTION,
            path = classPath,
            uri = classUri,
            range = ZERO_RANGE,
            selectionRange = ZERO_RANGE,
            containerName = if (topLevelImportable) null else ownerName,
            containerFqName = if (topLevelImportable) null else ownerFqName,
            signature = buildString {
                append(method.name)
                append('(')
                append(method.parameterTypes.joinToString(", ") { it.simpleName })
                append("): ")
                append(method.returnType.typeName.normalizeBinaryType())
            },
            documentation = null,
            packageName = packageName,
            moduleName = moduleName,
            importable = topLevelImportable,
            receiverType = receiverType,
            resultType = method.genericReturnType.typeName.normalizeBinaryType(),
            parameterCount = method.parameterCount,
            parameters = method.parameters.mapIndexed { index, parameter ->
                IndexedParameter(
                    name = kotlinFunction?.parameters
                        ?.filter { kotlinParameter -> kotlinParameter.kind.name == "VALUE" }
                        ?.getOrNull(index)
                        ?.name
                        ?: parameter.name.takeUnless { it.isNullOrBlank() }
                        ?: "arg${index + 1}",
                    type = method.genericParameterTypes.getOrNull(index)?.typeName?.normalizeBinaryType()
                        ?: parameter.type.typeName.normalizeBinaryType(),
                    isVararg = method.isVarArgs && index == method.parameterCount - 1,
                    isNullable = false,
                )
            },
        )
    }

    private fun classDeclarationSignature(runtimeClass: Class<*>): String =
        buildString {
            append(
                when {
                    runtimeClass.isInterface -> "interface "
                    runtimeClass.isEnum -> "enum class "
                    else -> "class "
                },
            )
            append(runtimeClass.simpleName.ifBlank { runtimeClass.name.substringAfterLast('.') })
        }

    private fun syntheticPath(jar: Path, className: String): Path =
        Path.of("/binary-libraries/${jar.fileName}/" + className.replace('.', '/') + ".class")

    private fun syntheticUri(jar: Path, className: String): String =
        "jar:${jar.toUri()}!/${className.replace('.', '/')}.class"

    private fun runtimePath(originPath: Path, className: String): Path =
        originPath.resolve(className.replace('.', '/') + ".class")

    private fun runtimeUri(runtimeClass: Class<*>): String =
        "jrt:/${runtimeClass.module?.name ?: "java.base"}/${runtimeClass.name.replace('.', '/')}.class"

    private fun indexableClassName(className: String): Boolean =
        className != "module-info" &&
            className != "package-info" &&
            !className.contains("\$DefaultImpls") &&
            !Regex("""\$[0-9]+""").containsMatchIn(className)

    private fun String.normalizeBinaryType(): String =
        replace('$', '.').substringBefore('<').removeSuffix("?")

    private companion object {
        val ZERO_RANGE = Range(Position(0, 0), Position(0, 0))
    }
}
