plugins {
    application
}

dependencies {
    implementation(project(":analysis"))
    implementation(project(":index"))
    implementation(project(":completion"))
    implementation(project(":project-import"))
    implementation(project(":protocol"))
    implementation(project(":workspace"))
}

application {
    mainClass.set("dev.codex.kotlinls.benchmarks.BenchmarkMainKt")
}

tasks.register<JavaExec>("runBenchmarks") {
    group = "verification"
    description = "Runs lightweight indexing and request-latency benchmarks."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.codex.kotlinls.benchmarks.BenchmarkMainKt")
}
