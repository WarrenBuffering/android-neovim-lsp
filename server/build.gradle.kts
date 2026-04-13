plugins {
    application
}

dependencies {
    implementation(project(":standalone-lsp"))
}

application {
    mainClass.set("dev.codex.kotlinls.server.MainKt")
}

tasks.named<CreateStartScripts>("startScripts") {
    applicationName = "android-neovim-lsp"
}
