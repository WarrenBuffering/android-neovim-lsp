package dev.codex.kotlinls.tests

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
}
