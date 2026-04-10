package dev.codex.kotlinls.index

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream

class ExternalSourceMirror(
    private val baseDir: Path = Path.of(System.getProperty("java.io.tmpdir"), "kotlin-neovim-lsp-external-sources"),
) {
    fun materialize(sourceJar: Path): Path {
        baseDir.createDirectories()
        val cacheKey = cacheKey(sourceJar)
        val targetDir = baseDir.resolve(cacheKey)
        val completeMarker = targetDir.resolve(".complete")
        if (completeMarker.exists()) return targetDir
        targetDir.createDirectories()
        JarFile(sourceJar.toFile()).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.substringAfterLast('.', "") in setOf("kt", "kts", "java") }
                .forEach { entry ->
                    val target = targetDir.resolve(entry.name)
                    target.parent?.createDirectories()
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
        }
        Files.writeString(completeMarker, sourceJar.toAbsolutePath().toString())
        return targetDir
    }

    private fun cacheKey(sourceJar: Path): String {
        val attributes = buildString {
            append(sourceJar.toAbsolutePath())
            append(':')
            append(runCatching { Files.size(sourceJar) }.getOrDefault(0L))
            append(':')
            append(runCatching { Files.getLastModifiedTime(sourceJar).toMillis() }.getOrDefault(0L))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(attributes.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16) + "-" + sourceJar.name
    }
}
