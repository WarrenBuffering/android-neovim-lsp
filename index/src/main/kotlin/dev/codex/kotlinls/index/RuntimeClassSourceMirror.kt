package dev.codex.kotlinls.index

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class RuntimeClassSourceMirror(
    private val baseDir: Path = IndexCachePaths.root().resolve("runtime-sources"),
    private val javaVersion: String = System.getProperty("java.version"),
) {
    fun materialize(runtimeClass: Class<*>): Path {
        val moduleName = runtimeClass.module?.name ?: "java.base"
        val target = baseDir
            .resolve(javaVersion)
            .resolve(moduleName)
            .resolve(runtimeClass.name.replace('.', '/') + ".java")
        if (target.exists()) return target
        target.parent?.createDirectories()
        target.writeText(render(runtimeClass))
        return target
    }

    private fun render(runtimeClass: Class<*>): String = buildString {
        append("// Generated from runtime reflection because JDK sources were unavailable.\n")
        append("// Module: ").append(runtimeClass.module?.name ?: "java.base").append("\n\n")
        if (runtimeClass.packageName.isNotBlank()) {
            append("package ").append(runtimeClass.packageName).append(";\n\n")
        }
        append(typeDeclaration(runtimeClass)).append(" {\n")
        publicFields(runtimeClass).forEach { field ->
            append("    ").append(fieldSignature(field)).append(";\n")
        }
        if (publicFields(runtimeClass).isNotEmpty()) {
            append('\n')
        }
        publicConstructors(runtimeClass).forEach { constructor ->
            append("    ").append(constructorSignature(runtimeClass, constructor)).append(" {}\n")
        }
        if (publicConstructors(runtimeClass).isNotEmpty() && publicMethods(runtimeClass).isNotEmpty()) {
            append('\n')
        }
        publicMethods(runtimeClass).forEach { method ->
            append("    ").append(methodSignature(method))
            if (runtimeClass.isInterface || Modifier.isAbstract(method.modifiers)) {
                append(";\n")
            } else {
                append(" {}\n")
            }
        }
        append("}\n")
    }

    private fun typeDeclaration(runtimeClass: Class<*>): String {
        val modifiers = buildList {
            add("public")
            if (Modifier.isStatic(runtimeClass.modifiers) && runtimeClass.enclosingClass != null) add("static")
            if (Modifier.isAbstract(runtimeClass.modifiers) && !runtimeClass.isInterface) add("abstract")
            if (Modifier.isFinal(runtimeClass.modifiers) && !runtimeClass.isEnum) add("final")
        }
        val keyword = when {
            runtimeClass.isAnnotation -> "@interface"
            runtimeClass.isInterface -> "interface"
            runtimeClass.isEnum -> "enum"
            else -> "class"
        }
        val supertypes = buildList {
            runtimeClass.superclass
                ?.takeUnless { it == Any::class.java || it.name == "java.lang.Object" || runtimeClass.isEnum || runtimeClass.isInterface }
                ?.name
                ?.normalizeRuntimeType()
                ?.let { superClass -> add("extends $superClass") }
            runtimeClass.interfaces
                .map { it.name.normalizeRuntimeType() }
                .takeIf { it.isNotEmpty() }
                ?.let { interfaces ->
                    add(if (runtimeClass.isInterface) "extends ${interfaces.joinToString(", ")}" else "implements ${interfaces.joinToString(", ")}")
                }
        }
        return (modifiers + keyword + runtimeClass.simpleName.ifBlank { runtimeClass.name.substringAfterLast('.') } + supertypes)
            .joinToString(" ")
    }

    private fun fieldSignature(field: Field): String = buildString {
        append("public")
        if (Modifier.isStatic(field.modifiers)) append(" static")
        if (Modifier.isFinal(field.modifiers)) append(" final")
        append(' ')
        append(field.genericType.typeName.normalizeRuntimeType())
        append(' ')
        append(field.name)
    }

    private fun constructorSignature(runtimeClass: Class<*>, constructor: Constructor<*>): String = buildString {
        append("public ")
        append(runtimeClass.simpleName.ifBlank { runtimeClass.name.substringAfterLast('.') })
        append('(')
        append(constructor.parameters.joinToString(", ") { parameter ->
            "${parameter.parameterizedType.typeName.normalizeRuntimeType()} ${parameter.name.takeUnless { it.isNullOrBlank() } ?: "arg"}"
        })
        append(')')
    }

    private fun methodSignature(method: Method): String = buildString {
        append("public")
        if (Modifier.isStatic(method.modifiers)) append(" static")
        if (Modifier.isFinal(method.modifiers)) append(" final")
        append(' ')
        append(method.genericReturnType.typeName.normalizeRuntimeType())
        append(' ')
        append(method.name)
        append('(')
        append(method.parameters.mapIndexed { index, parameter ->
            val suffix = if (method.isVarArgs && index == method.parameterCount - 1) "..." else ""
            "${parameter.parameterizedType.typeName.normalizeRuntimeType().removeSuffix("[]")}$suffix ${parameter.name.takeUnless { it.isNullOrBlank() } ?: "arg${index + 1}"}"
        }.joinToString(", "))
        append(')')
    }

    private fun publicFields(runtimeClass: Class<*>): List<Field> =
        runtimeClass.fields
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    private fun publicConstructors(runtimeClass: Class<*>): List<Constructor<*>> =
        runtimeClass.constructors
            .filter { Modifier.isPublic(it.modifiers) }

    private fun publicMethods(runtimeClass: Class<*>): List<Method> =
        runtimeClass.methods
            .filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    !method.isSynthetic &&
                    method.declaringClass != Any::class.java
            }
            .sortedWith(compareBy(Method::getName, Method::getParameterCount))

    private fun String.normalizeRuntimeType(): String =
        replace('$', '.').substringBefore('<').removeSuffix("?")
}
