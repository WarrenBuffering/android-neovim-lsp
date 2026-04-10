package dev.codex.kotlinls.benchmarks

import dev.codex.kotlinls.analysis.KotlinWorkspaceAnalyzer
import dev.codex.kotlinls.completion.CompletionService
import dev.codex.kotlinls.index.WorkspaceIndexBuilder
import dev.codex.kotlinls.projectimport.GradleProjectImporter
import dev.codex.kotlinls.protocol.CompletionParams
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.TextDocumentIdentifier
import dev.codex.kotlinls.workspace.TextDocumentStore
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.measureTimeMillis

fun main() {
    val root = benchmarkRoot()
    val importer = GradleProjectImporter()
    val analyzer = KotlinWorkspaceAnalyzer()
    val indexBuilder = WorkspaceIndexBuilder()
    val completionService = CompletionService()

    val importMs = measureTimeMillis {
        importer.importProject(root)
    }
    val project = importer.importProject(root)
    val documents = TextDocumentStore()
    val analyzeMs = measureTimeMillis {
        analyzer.analyze(project, documents)
    }
    val snapshot = analyzer.analyze(project, documents)
    val indexMs = measureTimeMillis {
        indexBuilder.build(snapshot)
    }
    val index = indexBuilder.build(snapshot)
    val target = root.resolve("feature/src/main/kotlin/demo/feature/Dashboard.kt")
    val source = target.readText()
    val line = source.lines().indexOfFirst { it.contains("repository.load") }
    val completionMs = measureTimeMillis {
        completionService.complete(
            snapshot,
            index,
            CompletionParams(
                textDocument = TextDocumentIdentifier(target.toUri().toString()),
                position = Position(line, source.lines()[line].indexOf("load") + 2),
            ),
        )
    }

    println("Benchmark root: $root")
    println("Import ms: $importMs")
    println("Analyze ms: $analyzeMs")
    println("Index ms: $indexMs")
    println("Completion ms: $completionMs")
    println("Symbol count: ${index.symbols.size}")
    println("Reference count: ${index.references.size}")
}

private fun benchmarkRoot(): Path {
    val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    return when {
        cwd.resolve("fixtures/large-workspace").toFile().exists() -> cwd.resolve("fixtures/large-workspace")
        cwd.fileName.toString() == "benchmarks" -> cwd.parent.resolve("fixtures/large-workspace")
        else -> cwd.parent.resolve("fixtures/large-workspace")
    }
}
