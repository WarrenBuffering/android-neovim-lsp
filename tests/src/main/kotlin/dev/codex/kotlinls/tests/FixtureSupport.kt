package dev.codex.kotlinls.tests

import java.nio.file.Path

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
}

