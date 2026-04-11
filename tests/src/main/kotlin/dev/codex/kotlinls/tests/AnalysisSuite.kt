package dev.codex.kotlinls.tests

import dev.codex.kotlinls.analysis.KotlinWorkspaceAnalyzer
import dev.codex.kotlinls.projectimport.GradleProjectImporter
import dev.codex.kotlinls.protocol.TextDocumentItem
import dev.codex.kotlinls.workspace.TextDocumentStore
import java.nio.file.Files
import kotlin.io.path.relativeTo

fun analysisSuite(): TestSuite {
    val importer = GradleProjectImporter()
    val analyzer = KotlinWorkspaceAnalyzer()
    return TestSuite(
        name = "analysis",
        cases = listOf(
            TestCase("reports unresolved references from live buffer content") {
                val projectRoot = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(projectRoot)
                val appFile = projectRoot.resolve("src/main/kotlin/demo/App.kt")
                val store = TextDocumentStore()
                val brokenText = """
                    package demo

                    fun greet(name: String): String = "Hello, ${'$'}name"

                    fun main() {
                        println(greet(missingName))
                    }
                """.trimIndent()
                store.open(
                    TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 2,
                        text = brokenText,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                assertTrue(snapshot.files.isNotEmpty()) { "Expected analyzed files" }
                assertTrue(snapshot.diagnostics.any { it.message.contains("Unresolved reference", ignoreCase = true) }) {
                    "Expected unresolved reference diagnostics, got ${snapshot.diagnostics}"
                }
            },
            TestCase("focused analysis keeps previously mirrored workspace files intact") {
                val projectRoot = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(projectRoot)
                val mirrorBase = Files.createTempDirectory("kotlinls-analysis-mirror")
                val focusedAnalyzer = KotlinWorkspaceAnalyzer(mirrorBase)
                val store = TextDocumentStore()
                val fullSnapshot = focusedAnalyzer.analyze(project, store)
                try {
                    val cacheKey = project.root.normalize().toString().hashCode().toUInt().toString(16)
                    val snapshotRoot = mirrorBase.resolve("snapshot-$cacheKey")
                    val preservedFile = projectRoot.resolve("lib/src/main/kotlin/demo/lib/Greeting.kt").normalize()
                    val preservedMirror = snapshotRoot.resolve(preservedFile.relativeTo(project.root).toString()).normalize()
                    assertTrue(Files.exists(preservedMirror)) {
                        "Expected full analysis to mirror ${preservedFile.fileName}"
                    }

                    val focusedPath = projectRoot.resolve("app/src/main/kotlin/demo/app/Main.kt").normalize()
                    focusedAnalyzer.analyze(project, store, includedPaths = setOf(focusedPath)).close()

                    assertTrue(Files.exists(preservedMirror)) {
                        "Expected focused analysis to preserve previously mirrored files, but ${preservedMirror} was deleted"
                    }
                } finally {
                    fullSnapshot.close()
                }
            },
        ),
    )
}
