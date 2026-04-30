package dev.codex.kotlinls.tests

import dev.codex.kotlinls.analysis.KotlinWorkspaceAnalyzer
import dev.codex.kotlinls.codeactions.CodeActionService
import dev.codex.kotlinls.completion.CompletionService
import dev.codex.kotlinls.hover.HoverAndSignatureService
import dev.codex.kotlinls.index.IndexedSymbol
import dev.codex.kotlinls.index.LightweightWorkspaceIndexBuilder
import dev.codex.kotlinls.index.SupportSymbolIndexBuilder
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.index.WorkspaceIndexBuilder
import dev.codex.kotlinls.navigation.NavigationService
import dev.codex.kotlinls.protocol.CompletionParams
import dev.codex.kotlinls.projectimport.GradleProjectImporter
import dev.codex.kotlinls.projectimport.LocalGradleCacheResolver
import dev.codex.kotlinls.protocol.CodeActionContext
import dev.codex.kotlinls.protocol.CodeActionParams
import dev.codex.kotlinls.protocol.InlayHintParams
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.SymbolKind
import dev.codex.kotlinls.protocol.TextDocumentIdentifier
import dev.codex.kotlinls.protocol.TextDocumentItem
import dev.codex.kotlinls.workspace.TextDocumentStore
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun editorParitySuite(): TestSuite {
    val importer = GradleProjectImporter()
    val analyzer = KotlinWorkspaceAnalyzer()
    val indexBuilder = WorkspaceIndexBuilder()
    val lightweightIndexBuilder = LightweightWorkspaceIndexBuilder()
    val supportIndexBuilder = SupportSymbolIndexBuilder()
    val navigationService = NavigationService()
    val hoverService = HoverAndSignatureService()
    val codeActionService = CodeActionService()
    val completionService = CompletionService()
    return TestSuite(
        name = "editor-parity",
        cases = listOf(
            TestCase("offers enum entry completions from the lightweight source index") {
                val root = FixtureSupport.fixtureCopy("simple-jvm-app")
                val project = importer.importProject(root)
                val file = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    enum class SheetVisibilityState {
                        Peek,
                        Fit,
                        Expanded,
                        Full,
                    }

                    fun demo() {
                        val state = SheetVisibilityState.Fi
                    }
                """.trimIndent() + "\n"
                file.writeText(content)
                val index = lightweightIndexBuilder.build(project, TextDocumentStore())
                val line = content.lines().indexOfFirst { it.contains("SheetVisibilityState.Fi") }
                val column = content.lines()[line].indexOf("Fi") + 2
                val completions = completionService.completeFromIndex(
                    index = index,
                    path = file,
                    text = content,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier(file.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                val labels = completions.items.map { it.label }
                assertTrue(labels.firstOrNull() == "Fit") {
                    "Expected lightweight enum entry completions, got ${labels.take(10)}"
                }
            },
            TestCase("offers implicit it member completions from the lightweight source index") {
                val root = FixtureSupport.fixtureCopy("simple-jvm-app")
                val project = importer.importProject(root)
                val file = root.resolve("src/main/kotlin/demo/ImplicitItCompletion.kt")
                val content = """
                    package demo

                    import java.util.UUID

                    data class IncidentShareItem(
                        val incident: String,
                        val distanceInMiles: Double?,
                    )

                    class ManageAccessViewModel(
                        private val incidentsById: Map<UUID, IncidentShareItem>,
                    ) {
                        private val sortedIncidents: List<IncidentShareItem>
                            get() = incidentsById.values.sortedWith(
                                compareBy(
                                    { it.distanceInMiles == null },
                                    { it.distanceInMiles },
                                    { it.incide }
                                )
                            )
                    }
                """.trimIndent() + "\n"
                file.writeText(content)
                val index = lightweightIndexBuilder.build(project, TextDocumentStore())
                val line = content.lines().indexOfFirst { it.contains("it.incide") }
                val column = content.lines()[line].indexOf("incide") + "incide".length
                val completions = completionService.completeFromIndex(
                    index = index,
                    path = file,
                    text = content,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier(file.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                val labels = completions.items.take(10).map { it.label }
                assertTrue("incident" in labels) {
                    "Expected lightweight implicit it member completion for incident, got $labels"
                }
            },
            TestCase("resolves workspace definitions from the lightweight source index") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val index = lightweightIndexBuilder.build(project, TextDocumentStore())
                val file = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = file.readText()
                val line = content.lines().indexOfFirst { it.contains("fun demo(): Greeting") }
                val column = content.lines()[line].indexOf("Greeting") + 2
                val locations = navigationService.definitionFromIndex(
                    index = index,
                    path = file,
                    text = content,
                    params = dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(file.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(locations.any { it.uri.endsWith("/fixtures/multi-module/lib/src/main/kotlin/demo/lib/Greeting.kt") }) {
                    "Expected lightweight definition target, got $locations"
                }
            },
            TestCase("renders hover from the lightweight source index") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val index = lightweightIndexBuilder.build(project, TextDocumentStore())
                val file = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = file.readText()
                val line = content.lines().indexOfFirst { it.contains("fun demo(): Greeting") }
                val column = content.lines()[line].indexOf("Greeting") + 2
                val hover = hoverService.hoverFromIndex(
                    index = index,
                    path = file,
                    text = content,
                    params = dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(file.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertContains(hover?.contents?.value ?: "", "class Greeting")
            },
            TestCase("uses the cursor token before trusting same-file declaration ranges") {
                val root = FixtureSupport.fixtureCopy("simple-jvm-app")
                val project = importer.importProject(root)
                val file = root.resolve("src/main/kotlin/demo/BroadRangeHover.kt")
                val content = """
                    package demo

                    import java.util.UUID

                    data class Content(
                        val id: UUID,
                    )
                """.trimIndent() + "\n"
                file.writeText(content)
                val projectIndex = lightweightIndexBuilder.build(project, TextDocumentStore())
                val contentLine = content.lines().indexOfFirst { it.contains("data class Content") }
                val contentDeclarationLine = content.lines()[contentLine]
                val broadContentRange = Range(
                    Position(contentLine, 0),
                    Position(contentLine, contentDeclarationLine.length),
                )
                val uuidSymbol = IndexedSymbol(
                    id = "java.util.UUID",
                    name = "UUID",
                    fqName = "java.util.UUID",
                    kind = SymbolKind.CLASS,
                    path = root.resolve("runtime/java/util/UUID.java"),
                    uri = root.resolve("runtime/java/util/UUID.java").toUri().toString(),
                    range = Range(Position(0, 0), Position(0, 10)),
                    selectionRange = Range(Position(0, 6), Position(0, 10)),
                    signature = "class UUID",
                    documentation = "UUID docs",
                    packageName = "java.util",
                    moduleName = "support",
                    importable = true,
                )
                val index = WorkspaceIndex(
                    symbols = projectIndex.symbols.map { symbol ->
                        if (symbol.name == "Content") {
                            symbol.copy(selectionRange = broadContentRange)
                        } else {
                            symbol
                        }
                    } + uuidSymbol,
                    references = emptyList(),
                    callEdges = emptyList(),
                )
                val uuidLine = content.lines().indexOfFirst { it.contains("val id: UUID") }
                val uuidColumn = content.lines()[uuidLine].indexOf("UUID") + 1
                val hover = hoverService.hoverFromIndex(
                    index = index,
                    path = file,
                    text = content,
                    params = dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(file.toUri().toString()),
                        position = Position(uuidLine, uuidColumn),
                    ),
                )
                val hoverText = hover?.contents?.value ?: error("Expected UUID hover from the index")
                assertContains(hoverText, "class UUID")
                assertTrue("data class Content" !in hoverText) {
                    "Expected UUID hover, got Content hover: $hoverText"
                }
            },
            TestCase("navigates from Kotlin to Java source definitions") {
                val root = FixtureSupport.fixture("mixed-kotlin-java")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val file = root.resolve("src/main/kotlin/demo/MixedUsage.kt")
                val content = file.readText()
                val line = content.lines().indexOfFirst { it.contains("JavaGreeter.greet") }
                val column = content.lines()[line].lastIndexOf("greet") + 2
                val locations = navigationService.definition(
                    snapshot,
                    index,
                    dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(file.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(locations.any { it.uri.endsWith("/fixtures/mixed-kotlin-java/src/main/java/demo/JavaGreeter.java") }) {
                    "Expected Java definition target, got $locations"
                }
            },
            TestCase("navigates to dependency sources when source jars are present") {
                val gradleHome = Files.createTempDirectory("kotlinls-gradle-home")
                val cacheDir = gradleHome.resolve("caches/modules-2/files-2.1/demo/sample/1.0/hash")
                cacheDir.createDirectories()
                val sourceRoot = Files.createTempDirectory("kotlinls-sample-src")
                val sourceFile = sourceRoot.resolve("demo/lib/SampleLib.java")
                sourceFile.parent.createDirectories()
                sourceFile.writeText(
                    """
                    package demo.lib;

                    /** Greets from source jar. */
                    public final class SampleLib {
                        public static String hello() {
                            return "hi";
                        }
                    }
                    """.trimIndent() + "\n",
                )
                val classesDir = Files.createTempDirectory("kotlinls-sample-classes")
                val compiler = ToolProvider.getSystemJavaCompiler() ?: error("JDK compiler not available")
                val compileResult = compiler.run(null, null, null, "-d", classesDir.toString(), sourceFile.toString())
                assertEquals(0, compileResult) { "Expected Java dependency to compile" }
                val binaryJar = cacheDir.resolve("sample-1.0.jar")
                createJar(binaryJar, classesDir)
                val sourceJar = cacheDir.resolve("sample-1.0-sources.jar")
                createJar(sourceJar, sourceRoot)

                val root = Files.createTempDirectory("kotlinls-dependency-sources")
                root.resolve("src/main/kotlin/demo").createDirectories()
                root.resolve("settings.gradle.kts").writeText("""rootProject.name = "dep-sources"""" + "\n")
                root.resolve("build.gradle.kts").writeText(
                    """
                    plugins {
                        kotlin("jvm") version "2.3.20"
                    }

                    repositories {
                        mavenCentral()
                    }

                    dependencies {
                        implementation("demo:sample:1.0")
                    }
                    """.trimIndent() + "\n",
                )
                val usageFile = root.resolve("src/main/kotlin/demo/App.kt")
                usageFile.writeText(
                    """
                    package demo

                    import demo.lib.SampleLib

                    fun demo(): String = SampleLib.hello()
                    """.trimIndent() + "\n",
                )

                val customImporter = GradleProjectImporter(cacheResolver = LocalGradleCacheResolver(gradleHome))
                val project = customImporter.importProject(root)
                val snapshot = analyzer.analyze(project, TextDocumentStore())
                val index = indexBuilder.build(snapshot)
                val content = usageFile.readText()
                val line = content.lines().indexOfFirst { it.contains("SampleLib.hello") }
                val column = content.lines()[line].indexOf("SampleLib") + 2
                val locations = navigationService.definition(
                    snapshot,
                    index,
                    dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(usageFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(locations.any { it.uri.endsWith("/SampleLib.java") }) {
                    "Expected definition in extracted dependency sources, got $locations"
                }
                val hover = hoverService.hover(
                    snapshot,
                    index,
                    dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(usageFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertContains(hover?.contents?.value ?: "", "Greets from source jar.")
            },
            TestCase("resolves default-import Kotlin stdlib symbols from the support index") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val supportLayer = supportIndexBuilder.build(project)
                val index = WorkspaceIndex(
                    symbols = supportLayer.symbols,
                    references = emptyList(),
                    callEdges = emptyList(),
                )
                assertTrue(index.symbolsByFqName["kotlin.String"] != null) {
                    "Expected support index to include kotlin.String"
                }
                val file = root.resolve("src/main/kotlin/demo/DefaultImportsProbe.kt")
                val content = """
                    package demo

                    fun probe(count: Int, label: String): String {
                        return count.toFloat().toString() + label
                    }
                """.trimIndent()
                val stringLine = content.lines().indexOfFirst { it.contains("label: String") }
                val stringColumn = content.lines()[stringLine].indexOf("String") + 2
                val stringDefinition = navigationService.definitionFromIndex(
                    index = index,
                    path = file,
                    text = content,
                    params = dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(file.toUri().toString()),
                        position = Position(stringLine, stringColumn),
                    ),
                )
                assertTrue(stringDefinition.any { it.uri.contains("/kotlin/String.kt") }) {
                    "Expected default-import String definition in extracted stdlib sources, got $stringDefinition"
                }
                val hoverLine = content.lines().indexOfFirst { it.contains("count.toFloat()") }
                val hoverColumn = content.lines()[hoverLine].indexOf("toFloat") + 3
                val hover = hoverService.hoverFromIndex(
                    index = index,
                    path = file,
                    text = content,
                    params = dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(file.toUri().toString()),
                        position = Position(hoverLine, hoverColumn),
                    ),
                )
                val hoverText = hover?.contents?.value ?: error("Expected stdlib hover from support index")
                assertContains(hoverText, "Parses the string as a `Float` number")
                assertContains(hoverText, "Float")
            },
            TestCase("offers explicit type and return type code actions") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun answer() = 42

                    fun demo() {
                        val name = "team"
                        println(name)
                    }
                """.trimIndent()
                store.open(
                    TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 21,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val functionLine = content.lines().indexOfFirst { it.contains("fun answer") }
                val functionActions = codeActionService.codeActions(
                    snapshot,
                    index,
                    CodeActionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        range = Range(Position(functionLine, 4), Position(functionLine, 10)),
                        context = CodeActionContext(only = listOf("refactor.rewrite")),
                    ),
                )
                assertTrue(functionActions.any { it.title.contains("Add explicit return type: Int") }) {
                    "Expected explicit return type action, got ${functionActions.map { it.title }}"
                }
                val propertyLine = content.lines().indexOfFirst { it.contains("val name") }
                val propertyActions = codeActionService.codeActions(
                    snapshot,
                    index,
                    CodeActionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        range = Range(Position(propertyLine, 8), Position(propertyLine, 12)),
                        context = CodeActionContext(only = listOf("refactor.rewrite")),
                    ),
                )
                assertTrue(propertyActions.any { it.title.contains("Add explicit type annotation: String") }) {
                    "Expected explicit type annotation action, got ${propertyActions.map { it.title }}"
                }
                val unfiltered = codeActionService.codeActions(
                    snapshot,
                    index,
                    CodeActionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        range = Range(Position(functionLine, 4), Position(functionLine, 10)),
                        context = CodeActionContext(),
                    ),
                )
                assertTrue(unfiltered.none { it.kind == "refactor.rewrite" }) {
                    "Expected unfiltered requests to omit rewrite intentions, got ${unfiltered.map { it.kind to it.title }}"
                }
            },
            TestCase("shows inferred local variable type inlay hints") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun demo() {
                        val count = 42
                        println(count)
                    }
                """.trimIndent()
                store.open(
                    TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 22,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val hints = hoverService.inlayHints(
                    snapshot,
                    index,
                    InlayHintParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        range = Range(Position(0, 0), Position(Int.MAX_VALUE / 4, 0)),
                    ),
                )
                assertTrue(hints.any { it.label == ": Int" }) {
                    "Expected inferred type hint, got ${hints.map { it.label }}"
                }
            },
        ),
    )
}

private fun createJar(jarPath: Path, root: Path) {
    JarOutputStream(FileOutputStream(jarPath.toFile())).use { output ->
        root.toFile().walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = root.relativize(file.toPath()).toString().replace('\\', '/')
                output.putNextEntry(JarEntry(relative))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
    }
}
