package dev.codex.kotlinls.index

import java.nio.file.Path

internal object IndexCachePaths {
    fun root(): Path {
        val userHome = Path.of(System.getProperty("user.home"))
        val osName = System.getProperty("os.name").lowercase()
        return when {
            "mac" in osName -> userHome.resolve("Library/Caches/android-neovim-lsp")
            else -> userHome.resolve(".cache/android-neovim-lsp")
        }
    }
}
