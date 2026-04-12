package dev.codex.kotlinls.tests

import dev.codex.kotlinls.analysis.KotlinWorkspaceAnalyzer
import dev.codex.kotlinls.completion.CompletionService
import dev.codex.kotlinls.hover.HoverAndSignatureService
import dev.codex.kotlinls.index.WorkspaceIndexBuilder
import dev.codex.kotlinls.navigation.NavigationService
import dev.codex.kotlinls.projectimport.GradleProjectImporter
import dev.codex.kotlinls.protocol.CompletionParams
import dev.codex.kotlinls.protocol.CompletionItem
import dev.codex.kotlinls.protocol.CompletionItemKind
import dev.codex.kotlinls.protocol.CompletionList
import dev.codex.kotlinls.protocol.InlayHintParams
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.TextDocumentIdentifier
import dev.codex.kotlinls.workspace.TextDocumentStore
import kotlin.io.path.readText

fun featureSuite(): TestSuite {
    val importer = GradleProjectImporter()
    val analyzer = KotlinWorkspaceAnalyzer()
    val indexBuilder = WorkspaceIndexBuilder()
    val completionService = CompletionService()
    val hoverService = HoverAndSignatureService()
    val navigationService = NavigationService()
    return TestSuite(
        name = "features",
        cases = listOf(
            TestCase("offers cross-module completion candidates") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = """
                    package demo.app

                    fun demo(): String {
                        return Gre
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 2,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("Gre") }
                val column = content.lines()[line].indexOf("Gre") + 3
                val completions = completionService.complete(
                    snapshot,
                    index,
                    CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(completions.items.any { it.label == "Greeting" }) {
                    "Expected Greeting completion, got ${completions.items.take(10).map { it.label }}"
                }
            },
            TestCase("scopes import completion to matching package members") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = """
                    package demo.app

                    import demo.lib.Gr

                    class GroupThing
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 22,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("import demo.lib.Gr") }
                val column = content.lines()[line].indexOf("Gr") + 2
                val completions = completionService.complete(
                    snapshot,
                    index,
                    CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                val labels = completions.items.map { it.label }
                assertTrue(labels.contains("Greeting")) {
                    "Expected Greeting import completion, got ${labels.take(10)}"
                }
                assertTrue("GroupThing" !in labels) {
                    "Expected import completion to exclude unrelated workspace symbols, got ${labels.take(10)}"
                }
            },
            TestCase("offers package segment completion inside imports") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = """
                    package demo.app

                    import demo.li
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 23,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("import demo.li") }
                val column = content.lines()[line].indexOf("li") + 2
                val completions = completionService.complete(
                    snapshot,
                    index,
                    CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                val packageItem = completions.items.firstOrNull { it.label == "lib" }
                assertTrue(packageItem?.kind == dev.codex.kotlinls.protocol.CompletionItemKind.MODULE) {
                    "Expected import package completion for demo.lib, got ${completions.items.take(10).map { it.label to it.kind }}"
                }
            },
            TestCase("offers receiver members and extension functions in completion") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = """
                    package demo.app

                    import demo.lib.Greeting

                    fun demo(name: String): String {
                        return Greeting().sh
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 3,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("Greeting().sh") }
                val column = content.lines()[line].indexOf("sh") + 2
                val completions = completionService.complete(
                    snapshot,
                    index,
                    CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(completions.items.firstOrNull()?.label == "shout") {
                    "Expected shout to rank first for receiver completion, got ${completions.items.take(10).map { it.label }}"
                }
                val shout = completions.items.firstOrNull { it.label == "shout" }
                assertTrue(shout?.additionalTextEdits?.any { it.newText.contains("import demo.lib.shout") } == true) {
                    "Expected shout completion to add import, got ${shout?.additionalTextEdits}"
                }
            },
            TestCase("boosts imported extension completions in index-only mode") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = """
                    package demo.app

                    import demo.lib.Greeting
                    import demo.lib.shout

                    fun demo(name: String): String {
                        return Greeting().sh
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 31,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("Greeting().sh") }
                val column = content.lines()[line].indexOf("sh") + 2
                val completions = completionService.completeFromIndex(
                    index = index,
                    path = appFile,
                    text = content,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                val topLabels = completions.items.take(10).map { it.label }
                assertTrue(topLabels.contains("shout")) {
                    "Expected imported extension completion to stay near the top, got $topLabels"
                }
            },
            TestCase("merges weak semantic completions with indexed fallback candidates") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = """
                    package demo.app

                    import demo.lib.Greeting
                    import demo.lib.shout

                    fun demo(name: String): String {
                        return Greeting().sh
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 32,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("Greeting().sh") }
                val column = content.lines()[line].indexOf("sh") + 2
                val merged = completionService.mergeSemanticAndIndexCompletions(
                    index = index,
                    path = appFile,
                    text = content,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                    semantic = CompletionList(
                        isIncomplete = false,
                        items = listOf(
                            CompletionItem(
                                label = "shape",
                                kind = CompletionItemKind.FUNCTION,
                                filterText = "shape",
                                detail = "noise",
                                data = mapOf("provider" to "jetbrains"),
                            ),
                            CompletionItem(
                                label = "showcase",
                                kind = CompletionItemKind.FUNCTION,
                                filterText = "showcase",
                                detail = "noise",
                                data = mapOf("provider" to "jetbrains"),
                            ),
                        ),
                    ),
                )
                assertTrue(merged.items.firstOrNull()?.label == "shout") {
                    "Expected indexed fallback to outrank noisy semantic candidates, got ${merged.items.take(10).map { it.label }}"
                }
            },
            TestCase("offers primitive conversion completions for local numeric chains in index-only mode") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun demo(): Float {
                        val count = 1
                        return count.to
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 42,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("count.to") }
                val column = content.lines()[line].indexOf("to") + 2
                val completions = completionService.completeFromIndex(
                    index = index,
                    path = appFile,
                    text = content,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(completions.items.any { it.label == "toFloat" }) {
                    "Expected primitive conversion completion, got ${completions.items.take(10).map { it.label }}"
                }
            },
            TestCase("ranks expected-type-matching completions above unrelated ones") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = """
                    package demo.app

                    import demo.lib.Greeting

                    fun demo(): Greeting {
                        return pick
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 4,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("return pick") }
                val column = content.lines()[line].indexOf("pick") + 4
                val completions = completionService.complete(
                    snapshot,
                    index,
                    CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                val topLabels = completions.items.take(5).map { it.label }
                assertTrue(topLabels.firstOrNull() == "pickGreeting") {
                    "Expected pickGreeting to rank first with expected type Greeting, got $topLabels"
                }
                assertTrue(topLabels.contains("pickAnyText")) {
                    "Expected unrelated completion to remain available, got $topLabels"
                }
            },
            TestCase("offers JDK member completions from inferred receiver types") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun demo(): Int {
                        val value = "team"
                        return value.len
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 40,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("value.len") }
                val column = content.lines()[line].indexOf("len") + 3
                val completions = completionService.complete(
                    snapshot,
                    index,
                    CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(completions.items.firstOrNull()?.label == "length") {
                    "Expected String member completion, got ${completions.items.take(10).map { it.label }}"
                }
            },
            TestCase("offers stdlib smart completions through K2 scope analysis") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun demo(): List<String> {
                        return listO
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 41,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("return listO") }
                val column = content.lines()[line].indexOf("listO") + 5
                val completions = completionService.complete(
                    snapshot,
                    index,
                    CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(completions.items.firstOrNull()?.label == "listOf") {
                    "Expected stdlib listOf completion first, got ${completions.items.take(10).map { it.label }}"
                }
            },
            TestCase("uses resolved overloads for signature help and inlay hints") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun consume(count: Int) {}

                    fun consume(name: String, times: Int) {}

                    fun demo() {
                        consume("team", 2)
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 5,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("consume(\"team\", 2)") }
                val signature = hoverService.signatureHelp(
                    snapshot,
                    index,
                    dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, content.lines()[line].indexOf("\"team\"") + 2),
                    ),
                )
                assertTrue(signature?.signatures?.firstOrNull()?.label?.contains("consume(name: String, times: Int)") == true) {
                    "Expected resolved two-parameter overload in signature help, got $signature"
                }
                val parameterLabels = signature?.signatures?.firstOrNull()?.parameters?.map { it.label }.orEmpty()
                assertTrue(parameterLabels == listOf("name: String", "times: Int")) {
                    "Expected parameter labels from resolved overload, got $parameterLabels"
                }
                val hints = hoverService.inlayHints(
                    snapshot,
                    index,
                    InlayHintParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        range = dev.codex.kotlinls.protocol.Range(
                            start = Position(0, 0),
                            end = Position(Int.MAX_VALUE / 4, 0),
                        ),
                    ),
                )
                val hintLabels = hints.map { it.label }
                assertTrue(hintLabels.containsAll(listOf("name:", "times:"))) {
                    "Expected inlay hints from resolved overload, got $hintLabels"
                }
            },
            TestCase("renders structured KDoc in hover") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    /**
                     * Greets [name] nicely.
                     *
                     * Adds excitement.
                     *
                     * @param name person to greet
                     * @return greeting text
                     */
                    fun greet(name: String): String = "Hello, ${'$'}name"

                    fun demo() = greet("team")
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 15,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("greet(\"team\")") }
                val hover = hoverService.hover(
                    snapshot,
                    index,
                    dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, content.lines()[line].indexOf("greet") + 2),
                    ),
                )
                val hoverText = hover?.contents?.value ?: error("Expected hover text")
                assertContains(hoverText, "Greets `name` nicely.")
                assertContains(hoverText, "Adds excitement.")
                assertContains(hoverText, "### Parameters")
                assertContains(hoverText, "- `name`: person to greet")
                assertContains(hoverText, "### Returns")
                assertContains(hoverText, "greeting text")
            },
            TestCase("resolves hover and definition for local variables") {
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
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 16,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("println(count)") }
                val column = content.lines()[line].indexOf("count") + 2
                val hover = hoverService.hover(
                    snapshot,
                    index,
                    dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertContains(hover?.contents?.value ?: "", "count")
                val locations = navigationService.definition(
                    snapshot,
                    index,
                    dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(locations.any { it.uri == appFile.toUri().toString() && it.range.start.line == 3 }) {
                    "Expected local variable definition, got $locations"
                }
            },
            TestCase("navigates to inferred expression types") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = """
                    package demo.app

                    import demo.lib.Greeting

                    fun demo() {
                        val greeting = Greeting()
                        println(greeting)
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 6,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("println(greeting)") }
                val column = content.lines()[line].indexOf("greeting") + 2
                val locations = navigationService.typeDefinition(
                    snapshot,
                    index,
                    dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(locations.any { it.uri.endsWith("/fixtures/multi-module/lib/src/main/kotlin/demo/lib/Greeting.kt") }) {
                    "Expected inferred type definition to navigate to Greeting, got $locations"
                }
            },
            TestCase("finds overriding method implementations") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    interface Greeter {
                        fun greet(name: String): String
                    }

                    class FriendlyGreeter : Greeter {
                        override fun greet(name: String): String = "Hello, ${'$'}name"
                    }

                    fun demo(greeter: Greeter) = greeter
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 7,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("fun greet(name: String): String") }
                val column = content.lines()[line].indexOf("greet") + 2
                val locations = navigationService.implementations(
                    snapshot,
                    index,
                    dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(locations.any { it.range.start.line > line }) {
                    "Expected overriding greet implementation, got $locations"
                }
            },
            TestCase("navigates to cross-module definitions") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val appFile = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = appFile.readText()
                val targetLine = content.lines().indexOfFirst { it.contains("Greeting().say") }
                val targetColumn = content.lines()[targetLine].lastIndexOf("Greeting") + 2
                val locations = navigationService.definition(
                    snapshot,
                    index,
                    dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(targetLine, targetColumn),
                    ),
                )
                assertTrue(locations.any { it.uri.endsWith("/fixtures/multi-module/lib/src/main/kotlin/demo/lib/Greeting.kt") }) {
                    "Expected definition in lib module, got $locations"
                }
            },
        ),
    )
}
