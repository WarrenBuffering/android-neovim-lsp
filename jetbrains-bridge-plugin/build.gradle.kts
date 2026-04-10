plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val configuredIdeaHome = providers.systemProperty("kotlinls.intellijHome")
    .orElse(providers.environmentVariable("KOTLINLS_INTELLIJ_HOME"))
    .orNull

val ideaHome = listOfNotNull(
    configuredIdeaHome?.let(::file),
    file("/Applications/Android Studio.app/Contents").takeIf { it.isDirectory },
    file("/Applications/IntelliJ IDEA.app/Contents").takeIf { it.isDirectory },
    file("/Applications/IntelliJ IDEA CE.app/Contents").takeIf { it.isDirectory },
).firstOrNull()

check(ideaHome != null) {
    "JetBrains bridge plugin requires a local JetBrains IDE. Set kotlinls.intellijHome or KOTLINLS_INTELLIJ_HOME."
}

dependencies {
    compileOnly(files(fileTree(ideaHome!!.resolve("lib")) { include("*.jar") }))
    compileOnly(files(fileTree(ideaHome.resolve("plugins/Kotlin/lib")) { include("*.jar") }))
    compileOnly(files(fileTree(ideaHome.resolve("plugins/Kotlin/kotlinc/lib")) { include("*.jar") }))
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.13.4.2")
}

tasks.jar {
    archiveBaseName.set("kotlinls-jetbrains-bridge-plugin")
}
