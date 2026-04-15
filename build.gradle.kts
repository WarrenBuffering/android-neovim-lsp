import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    base
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
}

group = "dev.codex.kotlinls"
version = "0.1.2"

val enableJetBrainsBridge = providers.gradleProperty("kotlinls.enableJetBrainsBridge")
    .map(String::toBoolean)
    .orElse(false)

subprojects {
    if (name != "jetbrains-bridge-plugin") {
        apply(plugin = "org.jetbrains.kotlin.jvm")
    }

    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    if (plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                freeCompilerArgs.addAll(
                    "-Xjsr305=strict",
                    "-Xconsistent-data-class-copy-visibility",
                    "-Xcontext-sensitive-resolution",
                )
            }
        }

        dependencies {
            "implementation"("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
        }
    }
}

tasks.register("testAll") {
    group = "verification"
    description = "Runs the custom fixture and protocol test suites."
    dependsOn(":tests:runAllTests")
}

tasks.register("benchmarkAll") {
    group = "verification"
    description = "Runs lightweight workspace and request latency benchmarks."
    dependsOn(":benchmarks:runBenchmarks")
}

tasks.register("smoke") {
    group = "verification"
    description = "Builds the server distribution and runs the smoke suite."
    dependsOn(":server:installDist", ":tests:runSmokeTests")
}

tasks.register("resolveAndLockAll") {
    group = "build setup"
    description = "Resolves the bundled build graph and writes dependency lockfiles."
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "Run with --write-locks to refresh lockfiles."
        }
    }
    dependsOn(":server:installDist", ":tests:compileKotlin", ":benchmarks:compileKotlin")
    if (enableJetBrainsBridge.get()) {
        dependsOn(":jetbrains-bridge-plugin:jar")
    }
}
