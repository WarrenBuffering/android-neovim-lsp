plugins {
    application
}

dependencies {
    implementation(project(":standalone-lsp"))
    implementation(project(":protocol"))
    implementation(project(":workspace"))
    implementation(project(":project-import"))
    implementation(project(":analysis"))
    implementation(project(":index"))
    implementation(project(":diagnostics"))
    implementation(project(":completion"))
    implementation(project(":hover"))
    implementation(project(":symbols"))
    implementation(project(":navigation"))
    implementation(project(":refactor"))
    implementation(project(":formatting"))
    implementation(project(":code-actions"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.13.4.2")
}

application {
    mainClass.set("dev.codex.kotlinls.tests.TestMainKt")
}

tasks.register<JavaExec>("runAllTests") {
    group = "verification"
    description = "Runs all deterministic protocol, fixture, and smoke tests."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.codex.kotlinls.tests.TestMainKt")
    args("all")
}

tasks.register<JavaExec>("runSmokeTests") {
    group = "verification"
    description = "Runs the smoke subset against the executable server pipeline."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.codex.kotlinls.tests.TestMainKt")
    args("smoke")
}
