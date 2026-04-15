package dev.codex.kotlinls.tests

import dev.codex.kotlinls.index.CallEdge
import dev.codex.kotlinls.index.BinaryClasspathSymbolIndexer
import dev.codex.kotlinls.index.IndexedReference
import dev.codex.kotlinls.index.IndexedSymbol
import dev.codex.kotlinls.index.LightweightWorkspaceIndexBuilder
import dev.codex.kotlinls.index.PersistentSemanticIndexCache
import dev.codex.kotlinls.index.PersistentSupportSymbolCache
import dev.codex.kotlinls.index.RuntimeClassSourceMirror
import dev.codex.kotlinls.index.SupportSymbolIndexBuilder
import dev.codex.kotlinls.index.SupportSymbolLayer
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.projectimport.CompilerOptions
import dev.codex.kotlinls.projectimport.ImportedModule
import dev.codex.kotlinls.projectimport.ImportedProject
import dev.codex.kotlinls.protocol.Json
import dev.codex.kotlinls.protocol.JsonRpcInboundMessage
import dev.codex.kotlinls.protocol.JsonRpcTransport
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.SymbolKind
import dev.codex.kotlinls.server.KotlinLanguageServer
import dev.codex.kotlinls.workspace.TextDocumentStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun semanticPersistenceSuite(): TestSuite = TestSuite(
    name = "runtime-caches",
    cases = listOf(
        TestCase("roundtrips semantic index cache") {
            val cacheRoot = Files.createTempDirectory("kotlinls-semantic-cache")
            val cache = PersistentSemanticIndexCache(cacheRoot)
            val projectRoot = Files.createTempDirectory("kotlinls-semantic-project")
            val sourceFile = projectRoot.resolve("app/src/main/kotlin/demo/app/Foo.kt").also {
                it.parent.createDirectories()
                it.writeText("package demo.app\n\nclass Foo\n")
            }
            val index = WorkspaceIndex(
                symbols = listOf(
                    IndexedSymbol(
                        id = "demo.app.Foo",
                        name = "Foo",
                        fqName = "demo.app.Foo",
                        kind = SymbolKind.CLASS,
                        path = sourceFile,
                        uri = sourceFile.toUri().toString(),
                        range = Range(Position(2, 0), Position(2, 9)),
                        selectionRange = Range(Position(2, 6), Position(2, 9)),
                        signature = "class Foo",
                        packageName = "demo.app",
                        moduleName = "app",
                        importable = true,
                    ),
                ),
                references = listOf(
                    IndexedReference(
                        symbolId = "demo.app.Foo",
                        path = sourceFile,
                        uri = sourceFile.toUri().toString(),
                        range = Range(Position(2, 6), Position(2, 9)),
                    ),
                ),
                callEdges = listOf(
                    CallEdge(
                        callerSymbolId = "caller",
                        calleeSymbolId = "demo.app.Foo",
                        path = sourceFile,
                        range = Range(Position(2, 6), Position(2, 9)),
                    ),
                ),
            )
            val fileContentHashes = mapOf(sourceFile.toUri().toString() to "hash-foo")

            cache.save(projectRoot, ":app", "fingerprint-1", index, fileContentHashes = fileContentHashes)
            val loaded = cache.loadEntry(projectRoot, ":app", "fingerprint-1")

            assertEquals(index, loaded?.index) { "Expected semantic index cache roundtrip to preserve data" }
            assertEquals(fileContentHashes, loaded?.fileContentHashes) {
                "Expected semantic index cache roundtrip to preserve file content hashes"
            }
        },
        TestCase("loads all module semantic indexes from cache") {
            val cacheRoot = Files.createTempDirectory("kotlinls-semantic-cache-all")
            val cache = PersistentSemanticIndexCache(cacheRoot)
            val projectRoot = Files.createTempDirectory("kotlinls-semantic-project-all")
            val sourceFile = projectRoot.resolve("app/src/main/kotlin/demo/app/Foo.kt").also {
                it.parent.createDirectories()
                it.writeText("package demo.app\n\nclass Foo\n")
            }
            val index = WorkspaceIndex(
                symbols = listOf(
                    IndexedSymbol(
                        id = "demo.app.Foo",
                        name = "Foo",
                        fqName = "demo.app.Foo",
                        kind = SymbolKind.CLASS,
                        path = sourceFile,
                        uri = sourceFile.toUri().toString(),
                        range = Range(Position(2, 0), Position(2, 9)),
                        selectionRange = Range(Position(2, 6), Position(2, 9)),
                        signature = "class Foo",
                        packageName = "demo.app",
                        moduleName = "app",
                        importable = true,
                    ),
                ),
                references = emptyList(),
                callEdges = emptyList(),
            )

            cache.save(projectRoot, ":app", "fingerprint-1", index)
            val loaded = cache.loadAll(projectRoot)

            assertEquals(index, loaded[":app"]) { "Expected loadAll to recover module semantic index" }
        },
        TestCase("roundtrips support symbol cache and invalidates on fingerprint change") {
            val cacheRoot = Files.createTempDirectory("kotlinls-support-cache")
            val cache = PersistentSupportSymbolCache(cacheRoot)
            val projectRoot = Files.createTempDirectory("kotlinls-support-project")
            val sourceFile = projectRoot.resolve("deps/demo/lib/SampleLib.kt").also {
                it.parent.createDirectories()
                it.writeText(
                    """
                    package demo.lib

                    /** Greets from cached docs. */
                    class SampleLib
                    """.trimIndent() + "\n",
                )
            }
            val layer = SupportSymbolLayer(
                fingerprint = "fingerprint-1",
                symbols = listOf(
                    IndexedSymbol(
                        id = "demo.lib.SampleLib",
                        name = "SampleLib",
                        fqName = "demo.lib.SampleLib",
                        kind = SymbolKind.CLASS,
                        path = sourceFile,
                        uri = sourceFile.toUri().toString(),
                        range = Range(Position(3, 0), Position(3, 15)),
                        selectionRange = Range(Position(3, 6), Position(3, 15)),
                        signature = "class SampleLib",
                        documentation = "Greets from cached docs.",
                        packageName = "demo.lib",
                        moduleName = "deps",
                        importable = true,
                    ),
                ),
            )

            cache.save(projectRoot, layer)

            val loaded = cache.load(projectRoot, "fingerprint-1")
            assertEquals(layer, loaded) { "Expected support symbol cache roundtrip to preserve dependency docs and symbols" }

            val changed = cache.load(projectRoot, "fingerprint-2")
            assertEquals(null, changed) { "Expected support symbol cache to invalidate when artifact fingerprint changes" }
        },
        TestCase("keeps support cache hot across java timestamp churn but invalidates content edits") {
            val cacheRoot = Files.createTempDirectory("kotlinls-support-fingerprint-cache")
            val cache = PersistentSupportSymbolCache(cacheRoot)
            val builder = SupportSymbolIndexBuilder(persistentSupportCache = cache)
            val projectRoot = Files.createTempDirectory("kotlinls-support-fingerprint-project")
            val javaRoot = projectRoot.resolve("app/src/main/java/demo").also { it.createDirectories() }
            val javaFile = javaRoot.resolve("Sample.java").also {
                it.writeText(
                    """
                    package demo;

                    public final class Sample {
                        public static String value() {
                            return "one";
                        }
                    }
                    """.trimIndent() + "\n",
                )
            }
            val project = ImportedProject(
                root = projectRoot,
                modules = listOf(
                    ImportedModule(
                        name = "app",
                        gradlePath = ":app",
                        dir = projectRoot.resolve("app"),
                        buildFile = null,
                        sourceRoots = emptyList(),
                        javaSourceRoots = listOf(projectRoot.resolve("app/src/main/java")),
                        testRoots = emptyList(),
                        compilerOptions = CompilerOptions(),
                        externalDependencies = emptyList(),
                        projectDependencies = emptyList(),
                        classpathJars = emptyList(),
                    ),
                ),
            )
            val layer = SupportSymbolLayer(
                fingerprint = supportLayerFingerprint(builder, project),
                symbols = emptyList(),
            )
            cache.save(projectRoot, layer)

            val touched = FileTime.fromMillis(Files.getLastModifiedTime(javaFile).toMillis() + 60_000)
            Files.setLastModifiedTime(javaFile, touched)

            assertEquals(layer, builder.load(project)) {
                "Expected support fingerprint to ignore Java timestamp-only changes"
            }

            javaFile.writeText(
                """
                package demo;

                public final class Sample {
                    public static String value() {
                        return "two";
                    }
                }
                """.trimIndent() + "\n",
            )

            val changed = builder.load(project)
            assertEquals(null, changed) {
                "Expected support fingerprint to invalidate when Java source contents change"
            }
        },
        TestCase("indexes Java constructors from source roots") {
            val cacheRoot = Files.createTempDirectory("kotlinls-java-constructors-cache")
            val builder = LightweightWorkspaceIndexBuilder(cacheRoot = cacheRoot)
            val projectRoot = Files.createTempDirectory("kotlinls-java-constructors-project")
            val javaRoot = projectRoot.resolve("src/main/java/demo/app").also { it.createDirectories() }
            javaRoot.resolve("Greeter.java").writeText(
                """
                package demo.app;

                public final class Greeter {
                    public Greeter(String name) {}

                    public String greet() {
                        return name();
                    }

                    private String name() {
                        return "hi";
                    }
                }
                """.trimIndent() + "\n",
            )
            val project = ImportedProject(
                root = projectRoot,
                modules = listOf(
                    ImportedModule(
                        name = "app",
                        gradlePath = ":app",
                        dir = projectRoot,
                        buildFile = null,
                        sourceRoots = emptyList(),
                        javaSourceRoots = listOf(projectRoot.resolve("src/main/java")),
                        testRoots = emptyList(),
                        compilerOptions = CompilerOptions(),
                        externalDependencies = emptyList(),
                        projectDependencies = emptyList(),
                        classpathJars = emptyList(),
                    ),
                ),
            )

            val index = builder.build(project, TextDocumentStore())
            val constructor = index.symbols.firstOrNull { symbol ->
                symbol.kind == SymbolKind.CONSTRUCTOR && symbol.fqName == "demo.app.Greeter"
            }

            assertTrue(constructor != null) { "Expected Java constructor symbol to be indexed" }
            assertEquals(1, constructor?.parameterCount) { "Expected Java constructor parameter count to be preserved" }
        },
        TestCase("materializes runtime JDK symbols as Java stubs") {
            val mirrorRoot = Files.createTempDirectory("kotlinls-runtime-stubs")
            val indexer = BinaryClasspathSymbolIndexer(
                runtimeSourceMirror = RuntimeClassSourceMirror(
                    baseDir = mirrorRoot,
                    javaVersion = "test-jdk",
                ),
            )

            val symbols = indexer.indexRuntimeClasses(
                originPath = Path.of("/jdk-runtime/java.base"),
                moduleName = "jdk",
                classNames = sequenceOf("java.util.UUID"),
            )
            val uuid = symbols.firstOrNull { symbol ->
                symbol.kind == SymbolKind.CLASS && symbol.fqName == "java.util.UUID"
            }

            assertTrue(uuid != null) { "Expected runtime UUID class to be indexed" }
            assertTrue(uuid!!.uri.endsWith("/java/util/UUID.java")) {
                "Expected runtime UUID definition to point at a Java stub, got ${uuid.uri}"
            }
            val stubPath = Path.of(URI(uuid.uri))
            assertTrue(Files.exists(stubPath)) { "Expected runtime UUID stub to exist at $stubPath" }
            assertTrue(stubPath.readText().contains("class UUID")) {
                "Expected runtime UUID stub to contain a class declaration"
            }
        },
        TestCase("loads lightweight workspace index from root manifests without a recrawl") {
            val cacheRoot = Files.createTempDirectory("kotlinls-lightweight-cache")
            val builder = LightweightWorkspaceIndexBuilder(cacheRoot = cacheRoot)
            val projectRoot = Files.createTempDirectory("kotlinls-lightweight-project")
            val sourceRoot = projectRoot.resolve("src/main/kotlin/demo/app").also { it.createDirectories() }
            val sourceFile = sourceRoot.resolve("Foo.kt").also {
                it.writeText("package demo.app\n\nclass Foo\n")
            }
            val project = importedProject(projectRoot, sourceRoot.parent.parent)

            val built = builder.build(project, TextDocumentStore())
            assertTrue(built.symbols.any { it.name == "Foo" }) { "Expected initial build to index Foo" }
            assertTrue(!builder.requiresBackgroundRefresh(project)) {
                "Expected root manifests to make the cached workspace view immediately reusable"
            }

            val reloaded = LightweightWorkspaceIndexBuilder(cacheRoot = cacheRoot).load(project)
            assertTrue(reloaded?.symbols?.any { it.name == "Foo" } == true) {
                "Expected manifest-backed cache load to recover Foo without rescanning roots"
            }

            Files.delete(sourceFile)
        },
        TestCase("marks lightweight workspace cache stale when existing roots gain new files") {
            val cacheRoot = Files.createTempDirectory("kotlinls-lightweight-new-file")
            val builder = LightweightWorkspaceIndexBuilder(cacheRoot = cacheRoot)
            val projectRoot = Files.createTempDirectory("kotlinls-lightweight-project-files")
            val sourceRoot = projectRoot.resolve("src/main/kotlin/demo/app").also { it.createDirectories() }
            sourceRoot.resolve("Foo.kt").writeText("package demo.app\n\nclass Foo\n")
            val project = importedProject(projectRoot, sourceRoot.parent.parent)

            builder.build(project, TextDocumentStore())

            val newFile = sourceRoot.resolve("DockChecklist.kt")
            newFile.writeText("package demo.app\n\nclass DockChecklist\n")

            assertTrue(builder.requiresBackgroundRefresh(project)) {
                "Expected a new source file under an existing root to invalidate the manifest-backed cache"
            }

            val rebuilt = builder.build(project, TextDocumentStore())
            assertTrue(rebuilt.symbols.any { it.name == "DockChecklist" }) {
                "Expected a rebuild to pick up new files added before startup"
            }
        },
        TestCase("marks lightweight workspace cache stale when project roots change") {
            val cacheRoot = Files.createTempDirectory("kotlinls-lightweight-root-change")
            val builder = LightweightWorkspaceIndexBuilder(cacheRoot = cacheRoot)
            val projectRoot = Files.createTempDirectory("kotlinls-lightweight-project-roots")
            val mainRoot = projectRoot.resolve("src/main/kotlin/demo/app").also { it.createDirectories() }
            mainRoot.resolve("Foo.kt").writeText("package demo.app\n\nclass Foo\n")
            val baseProject = importedProject(projectRoot, mainRoot.parent.parent)
            builder.build(baseProject, TextDocumentStore())

            val debugRoot = projectRoot.resolve("src/debug/kotlin/demo/app").also { it.createDirectories() }
            debugRoot.resolve("DebugOnly.kt").writeText("package demo.app\n\nclass DebugOnly\n")
            val expandedProject = importedProject(
                projectRoot = projectRoot,
                sourceRoots = listOf(
                    mainRoot.parent.parent,
                    debugRoot.parent.parent,
                ),
            )

            assertTrue(builder.requiresBackgroundRefresh(expandedProject)) {
                "Expected a new source root to invalidate the manifest-backed workspace cache"
            }
        },
        TestCase("persists saved documents into the lightweight workspace cache") {
            val cacheRoot = Files.createTempDirectory("kotlinls-lightweight-persist")
            val builder = LightweightWorkspaceIndexBuilder(cacheRoot = cacheRoot)
            val projectRoot = Files.createTempDirectory("kotlinls-lightweight-project-persist")
            val sourceRoot = projectRoot.resolve("src/main/kotlin/demo/app").also { it.createDirectories() }
            val project = importedProject(projectRoot, sourceRoot.parent.parent)

            val savedFile = sourceRoot.resolve("SavedLater.kt")
            val savedText = "package demo.app\n\nclass SavedLater\n"
            savedFile.writeText(savedText)
            builder.persistDocument(project, savedFile, savedText)

            val reloaded = LightweightWorkspaceIndexBuilder(cacheRoot = cacheRoot).load(project)
            assertTrue(reloaded?.symbols?.any { it.name == "SavedLater" } == true) {
                "Expected save-time persistence to surface new files from the lightweight cache"
            }
        },
        TestCase("initializes with bridge-first capability gating and no custom progress channel") {
            val projectRoot = createSemanticPersistenceFixture()
            val sourceFile = projectRoot.resolve("app/src/main/kotlin/demo/app/Caller.kt")
            withRunningServer(
                serverFactory = { transport ->
                    KotlinLanguageServer(
                        transport = transport,
                        refreshDebounceMillis = 0L,
                        warmupStartDelayMillis = 1_000L,
                    )
                },
            ) { server ->
                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 1,
                        "method" to "initialize",
                        "params" to mapOf(
                            "rootUri" to projectRoot.toUri().toString(),
                            "initializationOptions" to mapOf(
                                "semantic" to mapOf(
                                    "backend" to "disabled",
                                ),
                            ),
                        ),
                    ),
                )
                val initResponse = readUntil(server.reader, maxMessages = 20) { it.id?.asInt() == 1 }
                val capabilities = initResponse?.result?.get("capabilities")
                assertTrue(capabilities != null) { "Expected initialize capabilities" }
                assertEquals(false, capabilities?.get("selectionRangeProvider")?.asBoolean())
                assertEquals(false, capabilities?.get("documentHighlightProvider")?.asBoolean())
                assertEquals(false, capabilities?.get("inlayHintProvider")?.asBoolean())
                assertEquals(false, capabilities?.get("callHierarchyProvider")?.asBoolean())
                assertEquals(false, capabilities?.get("typeHierarchyProvider")?.asBoolean())

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "method" to "initialized",
                        "params" to emptyMap<String, Any>(),
                    ),
                )

                openDocument(server, sourceFile)
                val observedMethods = mutableListOf<String>()
                readUntil(server.reader, maxMessages = 20) { message ->
                    message.method?.let(observedMethods::add)
                    diagnosticUri(message) == sourceFile.toUri().toString()
                }
                assertTrue("kotlinls/progress" !in observedMethods) {
                    "Expected server to stop using custom kotlinls/progress notifications, got $observedMethods"
                }
            }
        },
        TestCase("skips foreground semantic analysis for unchanged files when warm semantic cache exists") {
            val projectRoot = createSemanticPersistenceFixture()
            val callerFile = projectRoot.resolve("app/src/main/kotlin/demo/app/Caller.kt")
            val calleeFile = projectRoot.resolve("app/src/main/kotlin/demo/app/Callee.kt")
            val cacheRoot = Files.createTempDirectory("kotlinls-semantic-open-cache")

            withRunningServer(
                serverFactory = { transport ->
                    KotlinLanguageServer(
                        transport = transport,
                        semanticIndexCache = PersistentSemanticIndexCache(cacheRoot),
                        refreshDebounceMillis = 0L,
                        warmupStartDelayMillis = 0L,
                    )
                },
            ) { server ->
                initializeServer(server, projectRoot, initializationOptions = emptyMap())
                openDocument(server, callerFile)
                readUntil(server.reader, maxMessages = 120) { diagnosticUri(it) == callerFile.toUri().toString() }

                val cached = waitForSemanticCacheEntry(
                    cache = PersistentSemanticIndexCache(cacheRoot),
                    projectRoot = projectRoot,
                    moduleGradlePath = ":app",
                )
                assertTrue(cached != null) { "Expected background warmup to persist a semantic cache entry" }
                val cachedUris = cached?.fileContentHashes?.keys.orEmpty()
                assertTrue(callerFile.toUri().toString() in cachedUris && calleeFile.toUri().toString() in cachedUris) {
                    "Expected warm semantic cache to capture both module files, got $cachedUris"
                }
            }

            withRunningServer(
                serverFactory = { transport ->
                    KotlinLanguageServer(
                        transport = transport,
                        semanticIndexCache = PersistentSemanticIndexCache(cacheRoot),
                        refreshDebounceMillis = 0L,
                        warmupStartDelayMillis = 10_000L,
                    )
                },
            ) { server ->
                initializeServer(server, projectRoot, initializationOptions = emptyMap())
                openDocument(server, calleeFile)
                var sawSemanticAnalysis = false
                readUntil(server.reader, maxMessages = 120) { message ->
                    if (progressTitle(message) == "Semantic Analysis") {
                        sawSemanticAnalysis = true
                    }
                    diagnosticUri(message) == calleeFile.toUri().toString()
                }
                assertTrue(!sawSemanticAnalysis) {
                    "Expected unchanged open file to reuse warm semantic cache without foreground semantic analysis"
                }
            }
        },
    ),
)

private data class PersistenceRunningServer(
    val clientOut: PipedOutputStream,
    val reader: JsonRpcTransport,
    val thread: Thread,
)

private fun withRunningServer(
    serverFactory: (JsonRpcTransport) -> KotlinLanguageServer = { KotlinLanguageServer(it) },
    block: (PersistenceRunningServer) -> Unit,
) {
    val server = startServer(serverFactory)
    try {
        block(server)
    } finally {
        runCatching { writePayload(server.clientOut, mapOf("jsonrpc" to "2.0", "id" to 999, "method" to "shutdown")) }
        runCatching { readUntil(server.reader, maxMessages = 10) { it.id?.asInt() == 999 } }
        runCatching { writePayload(server.clientOut, mapOf("jsonrpc" to "2.0", "method" to "exit")) }
        server.thread.join(2_000)
    }
}

private fun startServer(
    serverFactory: (JsonRpcTransport) -> KotlinLanguageServer,
): PersistenceRunningServer {
    val serverIn = PipedInputStream()
    val clientOut = PipedOutputStream(serverIn)
    val clientIn = PipedInputStream()
    val serverOut = PipedOutputStream(clientIn)
    val transport = JsonRpcTransport(
        BufferedInputStream(serverIn),
        BufferedOutputStream(serverOut),
    )
    val server = serverFactory(transport)
    val thread = thread(start = true, isDaemon = true, name = "kotlinls-semantic-persistence-test") {
        server.run()
    }
    return PersistenceRunningServer(
        clientOut = clientOut,
        reader = JsonRpcTransport(BufferedInputStream(clientIn), BufferedOutputStream(PipedOutputStream())),
        thread = thread,
    )
}

private fun initializeServer(
    server: PersistenceRunningServer,
    root: Path,
    initializationOptions: Map<String, Any?> = mapOf(
        "semantic" to mapOf(
            "backend" to "disabled",
        ),
    ),
) {
    writePayload(
        server.clientOut,
        mapOf(
            "jsonrpc" to "2.0",
            "id" to 1,
            "method" to "initialize",
            "params" to mapOf(
                "rootUri" to root.toUri().toString(),
                "initializationOptions" to initializationOptions,
            ),
        ),
    )
    val initResponse = readUntil(server.reader, maxMessages = 20) { it.id?.asInt() == 1 }
    assertTrue(initResponse?.result != null) { "Expected initialize response" }
    writePayload(
        server.clientOut,
        mapOf(
            "jsonrpc" to "2.0",
            "method" to "initialized",
            "params" to emptyMap<String, Any>(),
        ),
    )
}

private fun openDocument(server: PersistenceRunningServer, path: Path) {
    writePayload(
        server.clientOut,
        mapOf(
            "jsonrpc" to "2.0",
            "method" to "textDocument/didOpen",
            "params" to mapOf(
                "textDocument" to mapOf(
                    "uri" to path.toUri().toString(),
                    "languageId" to "kotlin",
                    "version" to 1,
                    "text" to path.readText(),
                ),
            ),
        ),
    )
}

private fun createSemanticPersistenceFixture(): Path {
    val root = Files.createTempDirectory("kotlinls-semantic-persist-fixture")
    root.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "semantic-persist"
        include(":app")
        """.trimIndent() + "\n",
    )
    root.resolve("app/build.gradle.kts").apply {
        parent.createDirectories()
        writeText(
            """
            plugins {
                kotlin("jvm")
            }

            repositories {
                mavenCentral()
            }

            kotlin {
                jvmToolchain(21)
            }
            """.trimIndent() + "\n",
        )
    }
    val sourceRoot = root.resolve("app/src/main/kotlin/demo/app").also { it.createDirectories() }
    sourceRoot.resolve("Callee.kt").writeText(
        """
        package demo.app

        fun callee(): String = "ok"
        """.trimIndent() + "\n",
    )
    sourceRoot.resolve("Caller.kt").writeText(
        """
        package demo.app

        fun caller(): String = callee()
        """.trimIndent() + "\n",
    )
    return root
}

private fun writePayload(output: PipedOutputStream, payload: Any) {
    val bytes = Json.mapper.writeValueAsBytes(payload)
    val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
    output.write(header)
    output.write(bytes)
    output.flush()
}

private fun readUntil(
    reader: JsonRpcTransport,
    maxMessages: Int,
    predicate: (JsonRpcInboundMessage) -> Boolean,
): JsonRpcInboundMessage? {
    repeat(maxMessages) {
        val message = reader.readMessage() ?: return null
        if (predicate(message)) return message
    }
    return null
}

private fun diagnosticUri(message: JsonRpcInboundMessage): String? =
    message.takeIf { it.method == "textDocument/publishDiagnostics" }
        ?.params
        ?.get("uri")
        ?.asText()

private fun progressTitle(message: JsonRpcInboundMessage): String? =
    message.takeIf { it.method == "\$/progress" }
        ?.params
        ?.get("value")
        ?.get("title")
        ?.asText()

private fun waitForSemanticCacheEntry(
    cache: PersistentSemanticIndexCache,
    projectRoot: Path,
    moduleGradlePath: String,
    timeoutMillis: Long = 5_000L,
): dev.codex.kotlinls.index.SemanticIndexCacheEntry? {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        cache.loadAllEntries(projectRoot)[moduleGradlePath]
            ?.takeIf { it.fileContentHashes.isNotEmpty() }
            ?.let { return it }
        Thread.sleep(50)
    }
    return null
}

private fun supportLayerFingerprint(
    builder: SupportSymbolIndexBuilder,
    project: ImportedProject,
): String {
    val method = SupportSymbolIndexBuilder::class.java.getDeclaredMethod(
        "supportLayerFingerprint",
        ImportedProject::class.java,
    )
    method.isAccessible = true
    return method.invoke(builder, project) as String
}

private fun importedProject(
    projectRoot: Path,
    sourceRoot: Path,
): ImportedProject = importedProject(projectRoot, listOf(sourceRoot))

private fun importedProject(
    projectRoot: Path,
    sourceRoots: List<Path>,
): ImportedProject =
    ImportedProject(
        root = projectRoot,
        modules = listOf(
            ImportedModule(
                name = "app",
                gradlePath = ":app",
                dir = projectRoot,
                buildFile = null,
                sourceRoots = sourceRoots,
                javaSourceRoots = emptyList(),
                testRoots = emptyList(),
                compilerOptions = CompilerOptions(),
                externalDependencies = emptyList(),
                projectDependencies = emptyList(),
                classpathJars = emptyList(),
            ),
        ),
    )
