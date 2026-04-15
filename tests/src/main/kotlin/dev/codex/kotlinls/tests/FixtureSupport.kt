package dev.codex.kotlinls.tests

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

object FixtureSupport {
    val repoRoot: Path by lazy {
        val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        when {
            cwd.fileName.toString() == "tests" -> cwd.parent
            cwd.resolve("fixtures").toFile().exists() -> cwd
            else -> cwd.parent
        }
    }

    fun fixture(name: String): Path = repoRoot.resolve("fixtures").resolve(name)

    fun fixtureCopy(name: String): Path {
        val source = fixture(name)
        val target = Files.createTempDirectory("kotlinls-fixture-$name")
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val relative = source.relativize(path)
                val destination = target.resolve(relative.toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    destination.parent?.let(Files::createDirectories)
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return target
    }

    fun seedLocalProperties(targetRoot: Path) {
        val entries = linkedMapOf<String, String>()
        repoRoot.resolve("local.properties")
            .takeIf { it.exists() }
            ?.readLines()
            ?.map(String::trim)
            ?.filter { it.isNotBlank() && !it.startsWith('#') && '=' in it }
            ?.forEach { line ->
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim()
                if (key in setOf("sdk.dir", "kotlinls.intellijHome", "androidStudio.dir") && value.isNotBlank()) {
                    entries[key] = value
                }
            }
        System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { entries.putIfAbsent("sdk.dir", it) }
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { entries.putIfAbsent("sdk.dir", it) }
        System.getProperty("kotlinls.intellijHome")?.takeIf { it.isNotBlank() }?.let { entries.putIfAbsent("kotlinls.intellijHome", it) }
        System.getenv("KOTLINLS_INTELLIJ_HOME")?.takeIf { it.isNotBlank() }?.let { entries.putIfAbsent("kotlinls.intellijHome", it) }
        if (entries.isEmpty()) return
        targetRoot.resolve("local.properties").writeText(
            entries.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" },
        )
    }
}
