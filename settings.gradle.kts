pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

val enableJetBrainsBridge = providers.gradleProperty("kotlinls.enableJetBrainsBridge")
    .map(String::toBoolean)
    .orElse(
        listOf(
            file("/Applications/Android Studio.app/Contents"),
            file("/Applications/IntelliJ IDEA.app/Contents"),
            file("/Applications/IntelliJ IDEA CE.app/Contents"),
        ).any { it.isDirectory },
    )
    .get()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
}

rootProject.name = "android-neovim-lsp"

include(
    ":protocol",
    ":workspace",
    ":project-import",
    ":analysis",
    ":index",
    ":diagnostics",
    ":completion",
    ":hover",
    ":symbols",
    ":navigation",
    ":refactor",
    ":formatting",
    ":code-actions",
    ":standalone-lsp",
    ":server",
    ":tests",
    ":benchmarks",
)

if (enableJetBrainsBridge) {
    include(":jetbrains-bridge-plugin")
}
