package dev.codex.kotlinls.tests

import dev.codex.kotlinls.analysis.KotlinWorkspaceAnalyzer
import dev.codex.kotlinls.diagnostics.DiagnosticsService
import dev.codex.kotlinls.diagnostics.DiagnosticsService.FastDiagnosticLookup
import dev.codex.kotlinls.projectimport.GradleProjectImporter
import dev.codex.kotlinls.protocol.TextDocumentItem
import dev.codex.kotlinls.workspace.TextDocumentStore
import java.nio.file.Files
import java.nio.file.Path
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
                try {
                    assertTrue(snapshot.files.isNotEmpty()) { "Expected analyzed files" }
                    assertTrue(snapshot.diagnostics.any { it.message.contains("Unresolved reference", ignoreCase = true) }) {
                        "Expected unresolved reference diagnostics, got ${snapshot.diagnostics}"
                    }
                } finally {
                    snapshot.close()
                }
            },
            TestCase("publishes immutable reassignment before downstream type mismatch noise") {
                val projectRoot = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(projectRoot)
                val appFile = projectRoot.resolve("src/main/kotlin/demo/App.kt")
                val store = TextDocumentStore()
                val brokenText = """
                    package demo

                    fun main() {
                        val recordingPuck = 1
                        recordingPuck = "sticks"
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
                try {
                    assertTrue(snapshot.diagnostics.any { it.message.contains("Val cannot be reassigned") }) {
                        "Expected immutable reassignment diagnostic, got ${snapshot.diagnostics}"
                    }

                    val published = DiagnosticsService().publishable(snapshot, setOf(appFile.toUri().toString()))
                        .singleOrNull()
                        ?.diagnostics
                        .orEmpty()
                    val assignmentLine = published.filter { it.range.start.line == 4 }
                    assertTrue(assignmentLine.isNotEmpty()) {
                        "Expected published diagnostics on the reassignment line, got $published"
                    }
                    assertTrue(assignmentLine.first().message.contains("Val cannot be reassigned")) {
                        "Expected immutable reassignment to be the primary diagnostic, got $assignmentLine"
                    }
                } finally {
                    snapshot.close()
                }
            },
            TestCase("preserves syntax diagnostics for malformed named-argument calls") {
                val projectRoot = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(projectRoot)
                val appFile = projectRoot.resolve("src/main/kotlin/demo/App.kt")
                val store = TextDocumentStore()
                val brokenText = """
                    package demo

                    fun navigate(routeKey: String, initialRoute: String) {}

                    fun initialRoute(route: String, params: String): String = route + params

                    fun openIncidentDetails() {
                        navigate(
                            val fish = "sticks"
                            fish = "turn"
                            routeKey = "route",
                            initialRoute = initialRoute(
                                "detail",
                                "standard",
                            ),
                        )
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
                try {
                    assertTrue(snapshot.diagnostics.any { it.message.contains("Expecting an element") }) {
                        "Expected syntax diagnostics, got ${snapshot.diagnostics}"
                    }
                    assertTrue(snapshot.diagnostics.any { it.message.contains("Unexpected tokens") }) {
                        "Expected trailing syntax diagnostics, got ${snapshot.diagnostics}"
                    }
                } finally {
                    snapshot.close()
                }
            },
            TestCase("fast diagnostics validate imports from the local index") {
                val diagnostics = DiagnosticsService().fastDiagnostics(
                    project = null,
                    path = Path.of("/tmp/FastImports.kt"),
                    text = """
                        package demo

                        import androidx.compose.runtime.Composable
                        import androidx.compose.runtime.Composable
                        import androidx.compose.runtime.MissingThing
                        import androidz.compose.runtime.Composable
                        import androidx.compose.runtime.
                    """.trimIndent(),
                    lookup = importValidationLookup(),
                )

                assertTrue(diagnostics.any { it.code == "duplicate-import" && it.message.contains("Composable") }) {
                    "Expected duplicate import diagnostic, got $diagnostics"
                }
                assertTrue(diagnostics.any { it.code == "unresolved-import-symbol" && it.message.contains("MissingThing") }) {
                    "Expected unresolved import symbol diagnostic, got $diagnostics"
                }
                assertTrue(diagnostics.any { it.code == "unresolved-import-package" && it.message.contains("androidz.compose.runtime") }) {
                    "Expected unresolved import package diagnostic, got $diagnostics"
                }
                assertTrue(diagnostics.any { it.code == "malformed-import" }) {
                    "Expected malformed import diagnostic, got $diagnostics"
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

private fun importValidationLookup(): FastDiagnosticLookup =
    FastDiagnosticLookup(
        importableFqNames = setOf("androidx.compose.runtime.Composable"),
        packagePrefixes = setOf(
            "androidx",
            "androidx.compose",
            "androidx.compose.runtime",
        ),
    )
