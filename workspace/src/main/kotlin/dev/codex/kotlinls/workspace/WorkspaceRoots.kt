package dev.codex.kotlinls.workspace

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class ProjectRootDetector {
    fun detect(start: Path): Path {
        val normalized = if (start.isDirectory()) start else start.parent ?: start
        var current: Path? = normalized
        var buildRoot: Path? = null
        while (current != null) {
            if (current.resolve("settings.gradle.kts").exists() || current.resolve("settings.gradle").exists()) {
                return current
            }
            if (buildRoot == null && (current.resolve("build.gradle.kts").exists() || current.resolve("build.gradle").exists())) {
                buildRoot = current
            }
            if (current.resolve(".git").exists()) {
                return buildRoot ?: current
            }
            current = current.parent
        }
        return buildRoot ?: normalized
    }
}

