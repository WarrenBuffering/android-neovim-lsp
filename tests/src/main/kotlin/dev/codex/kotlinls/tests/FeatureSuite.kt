package dev.codex.kotlinls.tests

import dev.codex.kotlinls.analysis.KotlinWorkspaceAnalyzer
import dev.codex.kotlinls.completion.CompletionRoute
import dev.codex.kotlinls.completion.CompletionService
import dev.codex.kotlinls.hover.HoverAndSignatureService
import dev.codex.kotlinls.index.IndexedParameter
import dev.codex.kotlinls.index.IndexedSymbol
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.index.WorkspaceIndexBuilder
import dev.codex.kotlinls.navigation.NavigationService
import dev.codex.kotlinls.projectimport.GradleProjectImporter
import dev.codex.kotlinls.protocol.CompletionParams
import dev.codex.kotlinls.protocol.CompletionItem
import dev.codex.kotlinls.protocol.CompletionItemKind
import dev.codex.kotlinls.protocol.CompletionList
import dev.codex.kotlinls.protocol.InlayHintParams
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.SymbolKind
import dev.codex.kotlinls.protocol.TextDocumentIdentifier
import dev.codex.kotlinls.workspace.TextDocumentStore
import java.nio.file.Path
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
            TestCase("prefers source-backed import completion details over lowered binary symbols") {
                val index = WorkspaceIndex(
                    symbols = listOf(
                        indexedSymbol(
                            id = "source::androidx.compose.foundation.layout.Column",
                            name = "Column",
                            fqName = "androidx.compose.foundation.layout.Column",
                            signature = "Column(modifier: Modifier = ..., verticalArrangement: Arrangement.Vertical = ..., horizontalAlignment: Alignment.Horizontal = ..., content: @Composable ColumnScope.() -> Unit)",
                            documentation = "A layout composable that places its children in a vertical sequence.",
                            packageName = "androidx.compose.foundation.layout",
                            path = Path.of("/tmp/androidx/compose/foundation/layout/Column.kt"),
                            uri = "file:///tmp/androidx/compose/foundation/layout/Column.kt",
                        ),
                        indexedSymbol(
                            id = "binary::androidx.compose.foundation.layout.Column",
                            name = "Column",
                            fqName = "androidx.compose.foundation.layout.Column",
                            signature = "Column(Modifier, Vertical, Horizontal, Function3, Composer, int, int): void",
                            documentation = null,
                            packageName = "androidx.compose.foundation.layout",
                            path = Path.of("/binary-libraries/foundation-layout/ColumnKt.class"),
                            uri = "jar:file:///Users/test/.gradle/foundation-layout.jar!/androidx/compose/foundation/layout/ColumnKt.class",
                        ),
                    ),
                    references = emptyList(),
                    callEdges = emptyList(),
                )
                val text = """
                    package demo

                    import androidx.compose.foundation.layout.Col
                """.trimIndent()
                val completions = completionService.completeFromIndex(
                    index = index,
                    path = Path.of("/workspace/demo/App.kt"),
                    text = text,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier("file:///workspace/demo/App.kt"),
                        position = Position(2, text.lines()[2].length),
                    ),
                )
                val column = completions.items.firstOrNull { it.label == "Column" }
                assertTrue(column != null) { "Expected Column completion, got ${completions.items.map { it.label to it.detail }}" }
                assertContains(column?.detail.orEmpty(), "vertical sequence")
                assertTrue("Composer" !in column?.detail.orEmpty()) {
                    "Expected source-backed completion detail, got ${column?.detail}"
                }
            },
            TestCase("offers top-level library function completions inside imports") {
                val index = WorkspaceIndex(
                    symbols = listOf(
                        indexedSymbol(
                            id = "source::androidx.compose.animation.core.animateDpAsState",
                            name = "animateDpAsState",
                            fqName = "androidx.compose.animation.core.animateDpAsState",
                            signature = "animateDpAsState(targetValue: Dp, label: String = ...)",
                            documentation = "Fire-and-forget animation for Dp values.",
                            packageName = "androidx.compose.animation.core",
                            path = Path.of("/tmp/androidx/compose/animation/core/AnimateAsState.kt"),
                            uri = "file:///tmp/androidx/compose/animation/core/AnimateAsState.kt",
                        ),
                        indexedSymbol(
                            id = "binary::androidx.compose.animation.core.animateDpAsState-AjpBEmI",
                            name = "animateDpAsState-AjpBEmI",
                            fqName = "androidx.compose.animation.core.animateDpAsState-AjpBEmI",
                            signature = "animateDpAsState-AjpBEmI(...): State<Dp>",
                            documentation = null,
                            packageName = "androidx.compose.animation.core",
                            path = Path.of("/binary-libraries/animation-core/AnimateAsStateKt.class"),
                            uri = "jar:file:///Users/test/.gradle/animation-core.jar!/androidx/compose/animation/core/AnimateAsStateKt.class",
                        ),
                    ),
                    references = emptyList(),
                    callEdges = emptyList(),
                )
                val text = """
                    package demo

                    import androidx.compose.animation.core.animateDpAs
                """.trimIndent()
                val completions = completionService.completeFromIndex(
                    index = index,
                    path = Path.of("/workspace/demo/App.kt"),
                    text = text,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier("file:///workspace/demo/App.kt"),
                        position = Position(2, text.lines()[2].length),
                    ),
                )
                val labels = completions.items.map { it.label }
                assertTrue("animateDpAsState" in labels) {
                    "Expected animateDpAsState import completion, got ${labels.take(10)}"
                }
                val animate = completions.items.firstOrNull { it.label == "animateDpAsState" }
                assertEquals(CompletionItemKind.TEXT, animate?.kind) {
                    "Expected import completion to stay plain-text so confirm does not insert (), got ${animate?.kind}"
                }
                assertEquals("animateDpAsState", animate?.insertText) {
                    "Expected import completion to insert the symbol name only, got ${animate?.insertText}"
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
            TestCase("routes lexical call-argument completions through the bridge") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun renderCard(
                        title: String,
                        subtitle: String,
                    ) {}

                    fun demo() {
                        renderCard(
                            title = "Dockside Notes",
                            sub
                        )
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
                val line = content.lines().indexOfFirst { it.trim() == "sub" }
                val column = content.lines()[line].indexOf("sub") + 3
                val decision = completionService.classifyCompletionRoute(
                    index = index,
                    path = appFile,
                    text = content,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                    bridgeAvailable = true,
                )
                assertEquals(CompletionRoute.BRIDGE, decision.route) {
                    "Expected lexical call-argument completion to use the bridge, got $decision"
                }
                assertEquals("lexical-scope-sensitive-context", decision.reason) {
                    "Expected lexical bridge routing reason, got $decision"
                }
            },
            TestCase("offers resolved named argument completions from semantic call descriptors") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun renderCard(
                        title: String,
                        subtitle: String,
                    ) {}

                    fun demo() {
                        renderCard(
                            title = "Dockside Notes",
                            sub
                        )
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
                val line = content.lines().indexOfFirst { it.trim() == "sub" }
                val column = content.lines()[line].indexOf("sub") + 3
                val completions = completionService.complete(
                    snapshot,
                    index,
                    CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                    allowBridge = false,
                )
                val subtitle = completions.items.firstOrNull { it.label == "subtitle" }
                assertTrue(subtitle != null) {
                    "Expected resolved named argument completion for subtitle, got ${completions.items.take(10).map { it.label }}"
                }
                assertEquals("subtitle = ", subtitle?.insertText) {
                    "Expected named argument completion to insert the parameter assignment, got ${subtitle?.insertText}"
                }
                assertEquals(CompletionItemKind.PROPERTY, subtitle?.kind) {
                    "Expected named argument completion to avoid callable insertion behavior, got ${subtitle?.kind}"
                }
                assertContains(subtitle?.detail.orEmpty(), "String")
            },
            TestCase("offers named argument completions from indexed callable signatures") {
                val index = WorkspaceIndex(
                    symbols = listOf(
                        indexedSymbol(
                            id = "source::dev.castline.anglerswharf.sections.TackleBoxHeader",
                            name = "TackleBoxHeader",
                            fqName = "dev.castline.anglerswharf.sections.TackleBoxHeader",
                            signature = "TackleBoxHeader(title: String, subtitle: String)",
                            documentation = "Fishing demo header composable.",
                            packageName = "dev.castline.anglerswharf.sections",
                            path = Path.of("/workspace/app/src/main/kotlin/dev/castline/anglerswharf/sections/TackleBoxHeader.kt"),
                            uri = "file:///workspace/app/src/main/kotlin/dev/castline/anglerswharf/sections/TackleBoxHeader.kt",
                            parameters = listOf(
                                IndexedParameter(name = "title", type = "String"),
                                IndexedParameter(name = "subtitle", type = "String"),
                            ),
                        ),
                    ),
                    references = emptyList(),
                    callEdges = emptyList(),
                )
                val text = """
                    package dev.castline.anglerswharf

                    import dev.castline.anglerswharf.sections.TackleBoxHeader

                    fun demo() {
                        TackleBoxHeader(
                            title = "Dockside Notes",
                            subti
                        )
                    }
                """.trimIndent()
                val line = text.lines().indexOfFirst { it.trim() == "subti" }
                val column = text.lines()[line].indexOf("subti") + 5
                val completions = completionService.completeFromIndex(
                    index = index,
                    path = Path.of("/workspace/app/src/main/kotlin/dev/castline/anglerswharf/TackleBoxView.kt"),
                    text = text,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier("file:///workspace/app/src/main/kotlin/dev/castline/anglerswharf/TackleBoxView.kt"),
                        position = Position(line, column),
                    ),
                )
                val subtitle = completions.items.firstOrNull { it.label == "subtitle" }
                assertTrue(subtitle != null) {
                    "Expected indexed named argument completion for subtitle, got ${completions.items.take(10).map { it.label }}"
                }
                assertEquals("subtitle = ", subtitle?.insertText) {
                    "Expected indexed named argument completion to insert the parameter assignment, got ${subtitle?.insertText}"
                }
                assertEquals(CompletionItemKind.PROPERTY, subtitle?.kind) {
                    "Expected indexed named argument completion to stay non-callable, got ${subtitle?.kind}"
                }
                assertContains(subtitle?.detail.orEmpty(), "String")
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
            TestCase("scopes index-only member completion through local parameter and inferred property types") {
                val root = FixtureSupport.fixture("multi-module")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("app/src/main/kotlin/demo/app/Main.kt")
                val content = """
                    package demo.app

                    class StateHolder {
                        fun collectAsState() = 1
                        fun collectLatest() = 2
                    }

                    class Vm {
                        val mapState = StateHolder()
                    }

                    fun collectNoise() = 0
                    class CollectionIndex

                    fun demo(vm: Vm) {
                        vm.mapState.colle
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
                val line = content.lines().indexOfFirst { it.contains("vm.mapState.colle") }
                val column = content.lines()[line].indexOf("colle") + 5
                val completions = completionService.completeFromIndex(
                    index = index,
                    path = appFile,
                    text = content,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                val labels = completions.items.take(10).map { it.label }
                assertTrue("collectAsState" in labels) {
                    "Expected receiver-scoped member completion, got $labels"
                }
                assertTrue("CollectionIndex" !in labels && "collectNoise" !in labels) {
                    "Expected member completion to exclude unrelated globals, got $labels"
                }
            },
            TestCase("infers implicit it members inside comparator lambdas from the sorted receiver") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
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
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 38,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("it.incide") }
                val column = content.lines()[line].indexOf("incide") + "incide".length
                val params = CompletionParams(
                    textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                    position = Position(line, column),
                )
                val decision = completionService.classifyCompletionRoute(
                    index = index,
                    path = appFile,
                    text = content,
                    params = params,
                    bridgeAvailable = true,
                )
                assertEquals(CompletionRoute.INDEX, decision.route) {
                    "Expected implicit it comparator completion to stay on the fast index, got $decision"
                }
                val completions = completionService.completeFromIndex(
                    index = index,
                    path = appFile,
                    text = content,
                    params = params,
                )
                val labels = completions.items.take(10).map { it.label }
                assertTrue("incident" in labels) {
                    "Expected implicit it member completion for incident, got $labels"
                }
            },
            TestCase("routes simple member access to the local index when indexed members exist") {
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
                val decision = completionService.classifyCompletionRoute(
                    index = index,
                    path = appFile,
                    text = content,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                    bridgeAvailable = true,
                )
                assertEquals(CompletionRoute.INDEX, decision.route) {
                    "Expected simple member access to stay local, got $decision"
                }
                val completions = completionService.completeFromIndex(
                    index = index,
                    path = appFile,
                    text = content,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(completions.items.firstOrNull()?.label == "shout") {
                    "Expected indexed member completion to prioritize `shout`, got ${completions.items.take(10).map { it.label }}"
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
            TestCase("offers primitive conversion completions from semantic receiver types") {
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
                        version = 43,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("count.to") }
                val column = content.lines()[line].indexOf("to") + 2
                val completions = completionService.complete(
                    snapshot,
                    index,
                    CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                assertTrue(completions.items.any { it.label == "toFloat" }) {
                    "Expected semantic primitive conversion completion, got ${completions.items.take(10).map { it.label }}"
                }
            },
            TestCase("offers enum entry completions for project-owned member access") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    enum class SheetVisibilityState {
                        Peek,
                        Fit,
                        Expanded,
                    }

                    fun demo() {
                        val state = SheetVisibilityState.Fi
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 44,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("SheetVisibilityState.Fi") }
                val column = content.lines()[line].indexOf("Fi") + 2
                val completions = completionService.complete(
                    snapshot,
                    index,
                    CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                    allowBridge = false,
                )
                assertTrue(completions.items.any { it.label == "Fit" }) {
                    "Expected enum entry completion, got ${completions.items.take(10).map { it.label }}"
                }
            },
            TestCase("keeps simple member access on the local route without semantic merging") {
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
                        version = 44,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("Greeting().sh") }
                val column = content.lines()[line].indexOf("sh") + 2
                val decision = completionService.classifyCompletionRoute(
                    index = index,
                    path = appFile,
                    text = content,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                    bridgeAvailable = true,
                )
                assertEquals(CompletionRoute.INDEX, decision.route) {
                    "Expected simple member access to remain local, got $decision"
                }
                val completions = completionService.completeFromIndex(
                    index = index,
                    path = appFile,
                    text = content,
                    params = CompletionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                val labels = completions.items.take(10).map { item -> item.label }
                assertTrue("shout" in labels) {
                    "Expected local member completions to include `shout`, got $labels"
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
            TestCase("prefers source-backed definitions over binary jar entries") {
                val index = WorkspaceIndex(
                    symbols = listOf(
                        indexedSymbol(
                            id = "source::androidx.compose.foundation.layout.Column",
                            name = "Column",
                            fqName = "androidx.compose.foundation.layout.Column",
                            signature = "Column(modifier: Modifier = ..., content: @Composable ColumnScope.() -> Unit)",
                            documentation = "A layout composable that places its children in a vertical sequence.",
                            packageName = "androidx.compose.foundation.layout",
                            path = Path.of("/tmp/androidx/compose/foundation/layout/Column.kt"),
                            uri = "file:///tmp/androidx/compose/foundation/layout/Column.kt",
                        ),
                        indexedSymbol(
                            id = "binary::androidx.compose.foundation.layout.Column",
                            name = "Column",
                            fqName = "androidx.compose.foundation.layout.Column",
                            signature = "Column(Modifier, Vertical, Horizontal, Function3, Composer, int, int): void",
                            documentation = null,
                            packageName = "androidx.compose.foundation.layout",
                            path = Path.of("/binary-libraries/foundation-layout/ColumnKt.class"),
                            uri = "jar:file:///Users/test/.gradle/foundation-layout.jar!/androidx/compose/foundation/layout/ColumnKt.class",
                        ),
                    ),
                    references = emptyList(),
                    callEdges = emptyList(),
                )
                val text = """
                    package demo

                    import androidx.compose.foundation.layout.Column
                """.trimIndent()
                val locations = navigationService.definitionFromIndex(
                    index = index,
                    path = Path.of("/workspace/demo/App.kt"),
                    text = text,
                    params = dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier("file:///workspace/demo/App.kt"),
                        position = Position(2, text.lines()[2].lastIndexOf("Column") + 2),
                    ),
                )
                assertTrue(locations.singleOrNull()?.uri == "file:///tmp/androidx/compose/foundation/layout/Column.kt") {
                    "Expected source-backed definition target, got $locations"
                }
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
            TestCase("shows hover for primitive conversion calls resolved from semantic calls") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun demo(): Float {
                        val count = 42
                        return count.toFloat()
                    }
                """.trimIndent()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 17,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val index = indexBuilder.build(snapshot)
                val line = content.lines().indexOfFirst { it.contains("count.toFloat()") }
                val column = content.lines()[line].indexOf("toFloat") + 3
                val hover = hoverService.hover(
                    snapshot,
                    index,
                    dev.codex.kotlinls.protocol.TextDocumentPositionParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        position = Position(line, column),
                    ),
                )
                val hoverText = hover?.contents?.value ?: error("Expected primitive conversion hover")
                assertContains(hoverText, "toFloat")
                assertContains(hoverText, "Float")
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
                val targetLine = content.lines().indexOfFirst { it.contains("fun demo(): Greeting") }
                val targetColumn = content.lines()[targetLine].indexOf("Greeting") + 2
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

private fun indexedSymbol(
    id: String,
    name: String,
    fqName: String,
    signature: String,
    documentation: String?,
    packageName: String,
    path: Path,
    uri: String,
    parameters: List<IndexedParameter> = emptyList(),
): IndexedSymbol = IndexedSymbol(
    id = id,
    name = name,
    fqName = fqName,
    kind = SymbolKind.FUNCTION,
    path = path,
    uri = uri,
    range = Range(Position(1, 0), Position(1, 1)),
    selectionRange = Range(Position(1, 0), Position(1, 1)),
    signature = signature,
    documentation = documentation,
    packageName = packageName,
    moduleName = "support",
    importable = true,
    parameterCount = parameters.size,
    parameters = parameters,
)
