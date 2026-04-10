package dev.codex.kotlinls.tests

import dev.codex.kotlinls.analysis.KotlinWorkspaceAnalyzer
import dev.codex.kotlinls.projectimport.GradleProjectImporter
import dev.codex.kotlinls.protocol.TextDocumentItem
import dev.codex.kotlinls.workspace.TextDocumentStore

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
        ),
    )
}
