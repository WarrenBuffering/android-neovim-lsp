package dev.codex.kotlinls.workspace

import dev.codex.kotlinls.protocol.DocumentUri
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.absolute

fun Path.toDocumentUri(): DocumentUri = absolute().toUri().toString()

fun documentUriToPath(uri: DocumentUri): Path {
    val parsed = URI(uri)
    if (parsed.scheme.isNullOrBlank() || parsed.scheme.equals("file", ignoreCase = true).not()) {
        return Path.of(parsed)
    }
    val authority = parsed.authority?.takeIf { it.isNotBlank() && it != "localhost" }.orEmpty()
    val normalizedPath = buildString {
        if (authority.isNotEmpty()) {
            append("//")
            append(authority)
        }
        append(parsed.path ?: "")
    }
    return Path.of(normalizedPath)
}
