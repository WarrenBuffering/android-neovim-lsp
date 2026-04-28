package dev.codex.kotlinls.index

import dev.codex.kotlinls.projectimport.StableArtifactFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.walk

class ExternalSourceMirror(
    private val baseDir: Path = IndexCachePaths.root().resolve("external-sources"),
) {
    fun materialize(sourceJar: Path): Path {
        baseDir.createDirectories()
        val cacheKey = cacheKey(sourceJar)
        val targetDir = baseDir.resolve(cacheKey)
        val completeMarker = targetDir.resolve(".complete")
        if (completeMarker.exists() && hasExtractedSourceFiles(targetDir)) return targetDir
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

    private fun hasExtractedSourceFiles(targetDir: Path): Boolean =
        targetDir.exists() &&
            targetDir.walk().any { path ->
                path.isRegularFile() &&
                    path.name.substringAfterLast('.', "") in setOf("kt", "kts", "java")
            }

    private fun cacheKey(sourceJar: Path): String {
        val attributes = buildString {
            append(sourceJar.toAbsolutePath())
            append(':')
            append(StableArtifactFingerprint.fingerprint(sourceJar))
        }
        return attributes.hashCode().toUInt().toString(16) + "-" + sourceJar.name
    }
}
