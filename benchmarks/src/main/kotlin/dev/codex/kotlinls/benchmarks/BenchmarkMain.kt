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
    val target = benchmarkTarget(root)
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
    val focusedTarget = setOf(target.normalize())
    val focusedAnalyzeMs = measureTimeMillis {
        analyzer.analyze(project, documents, includedPaths = focusedTarget).close()
    }
    val focusedWarmAnalyzeMs = measureTimeMillis {
        analyzer.analyze(project, documents, includedPaths = focusedTarget).close()
    }
    val indexMs = measureTimeMillis {
        indexBuilder.build(snapshot)
    }
    val index = indexBuilder.build(snapshot)
    val focusedIndexMs = measureTimeMillis {
        indexBuilder.build(snapshot, targetPaths = focusedTarget)
    }
    val focusedWarmIndexMs = measureTimeMillis {
        indexBuilder.build(snapshot, targetPaths = focusedTarget)
    }
    val source = target.readText()
    val query = benchmarkQuery()
    val line = source.lines().indexOfFirst { it.contains(query) }
    require(line >= 0) { "Could not find benchmark query `$query` in $target" }
    val completionMs = measureTimeMillis {
        completionService.complete(
            snapshot,
            index,
            CompletionParams(
                textDocument = TextDocumentIdentifier(target.toUri().toString()),
                position = Position(line, source.lines()[line].indexOf(query) + minOf(query.length, 2)),
            ),
        )
    }

    println("Benchmark root: $root")
    println("Benchmark target: $target")
    println("Benchmark query: $query")
    println("Import ms: $importMs")
    println("Analyze ms: $analyzeMs")
    println("Focused analyze ms: $focusedAnalyzeMs")
    println("Focused warm analyze ms: $focusedWarmAnalyzeMs")
    println("Index ms: $indexMs")
    println("Focused index ms: $focusedIndexMs")
    println("Focused warm index ms: $focusedWarmIndexMs")
    println("Completion ms: $completionMs")
    println("Symbol count: ${index.symbols.size}")
    println("Reference count: ${index.references.size}")
}

private fun benchmarkRoot(): Path {
    val configured = System.getProperty("kotlinls.benchmarkRoot")
        ?: System.getenv("KOTLINLS_BENCHMARK_ROOT")
    if (!configured.isNullOrBlank()) {
        return Path.of(configured).toAbsolutePath().normalize()
    }
    val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    return when {
        cwd.resolve("fixtures/large-workspace").toFile().exists() -> cwd.resolve("fixtures/large-workspace")
        cwd.fileName.toString() == "benchmarks" -> cwd.parent.resolve("fixtures/large-workspace")
        else -> cwd.parent.resolve("fixtures/large-workspace")
    }
}

private fun benchmarkTarget(root: Path): Path {
    val configured = System.getProperty("kotlinls.benchmarkTarget")
        ?: System.getenv("KOTLINLS_BENCHMARK_TARGET")
    if (!configured.isNullOrBlank()) {
        return Path.of(configured).toAbsolutePath().normalize()
    }
    return root.resolve("feature/src/main/kotlin/demo/feature/Dashboard.kt")
}

private fun benchmarkQuery(): String =
    System.getProperty("kotlinls.benchmarkQuery")
        ?: System.getenv("KOTLINLS_BENCHMARK_QUERY")
        ?: "repository.load"
