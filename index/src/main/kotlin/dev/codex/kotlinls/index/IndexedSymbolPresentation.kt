package dev.codex.kotlinls.index

import dev.codex.kotlinls.protocol.MarkupContent

fun indexedSymbolDocumentation(symbol: IndexedSymbol): MarkupContent =
    MarkupContent(kind = "markdown", value = indexedSymbolMarkdown(symbol))

fun indexedSymbolMarkdown(symbol: IndexedSymbol): String =
    buildString {
        appendLine("```kotlin")
        appendLine(symbol.signature)
        appendLine("```")

        symbol.documentation
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                appendLine()
                appendLine(it)
            }

        if (symbol.parameters.isNotEmpty()) {
            appendLine()
            appendLine("### Parameters")
            appendLine("```kotlin")
            symbol.parameters.forEach { parameter ->
                appendLine(renderParameter(parameter))
            }
            appendLine("```")
        }

        symbol.resultType
            ?.takeIf { it.isNotBlank() && symbol.kind !in setOf(dev.codex.kotlinls.protocol.SymbolKind.CLASS, dev.codex.kotlinls.protocol.SymbolKind.ENUM, dev.codex.kotlinls.protocol.SymbolKind.OBJECT, dev.codex.kotlinls.protocol.SymbolKind.INTERFACE) }
            ?.let {
                appendLine()
                appendLine("### Returns")
                appendLine("```kotlin")
                appendLine(it)
                appendLine("```")
            }

        if (symbol.enumEntries.isNotEmpty()) {
            appendLine()
            appendLine("### Enum Values")
            appendLine("```kotlin")
            symbol.enumEntries.forEach { entry ->
                appendLine(renderEnumEntry(entry))
            }
            appendLine("```")
        } else {
            symbol.enumValue?.let { entry ->
                appendLine()
                appendLine("### Enum Value")
                appendLine("```kotlin")
                appendLine(renderEnumEntry(entry))
                appendLine("```")
            }
        }

        if (symbol.supertypes.isNotEmpty()) {
            appendLine()
            appendLine("### Supertypes")
            appendLine("```kotlin")
            symbol.supertypes.forEach { supertype ->
                appendLine(supertype)
            }
            appendLine("```")
        }
    }.trim()

fun indexedSymbolCompletionDetail(symbol: IndexedSymbol): String {
    val summary = symbol.documentation
        ?.lineSequence()
        ?.map(String::trim)
        ?.firstOrNull { it.isNotBlank() && it != "/**" }
    val enumSummary = symbol.enumEntries
        .takeIf { it.isNotEmpty() }
        ?.take(4)
        ?.joinToString(", ") { it.name }
        ?.let { values ->
            if (symbol.enumEntries.size > 4) "$values, ..." else values
        }
    return when {
        summary != null -> "${symbol.signature} - $summary"
        enumSummary != null -> "${symbol.signature} - Values: $enumSummary"
        else -> symbol.signature
    }
}

private fun renderEnumEntry(entry: IndexedEnumEntry): String =
    buildString {
        append(entry.name)
        if (entry.arguments.isNotEmpty()) {
            append('(')
            append(entry.arguments.joinToString(", "))
            append(')')
        }
        entry.stringValue
            ?.takeIf { it.isNotBlank() && it != entry.name }
            ?.let {
                append(" // ")
                append(it)
            }
    }

private fun renderParameter(parameter: IndexedParameter): String =
    buildString {
        if (parameter.isVararg) {
            append("vararg ")
        }
        append(parameter.name)
        parameter.type?.takeIf { it.isNotBlank() }?.let {
            append(": ")
            append(it)
        }
        parameter.defaultValue?.takeIf { it.isNotBlank() }?.let {
            append(" = ")
            append(it)
        }
    }
