package dev.codex.kotlinls.tests

import dev.codex.kotlinls.index.CallEdge
import dev.codex.kotlinls.index.IndexedReference
import dev.codex.kotlinls.index.IndexedSymbol
import dev.codex.kotlinls.index.PersistentSemanticIndexCache
import dev.codex.kotlinls.index.WorkspaceIndex
import dev.codex.kotlinls.protocol.Json
import dev.codex.kotlinls.protocol.JsonRpcInboundMessage
import dev.codex.kotlinls.protocol.JsonRpcTransport
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.SymbolKind
import dev.codex.kotlinls.server.KotlinLanguageServer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun semanticPersistenceSuite(): TestSuite = TestSuite(
    name = "semantic-persistence",
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

            cache.save(projectRoot, ":app", "fingerprint-1", index)
            val loaded = cache.load(projectRoot, ":app", "fingerprint-1")

            assertEquals(index, loaded) { "Expected semantic index cache roundtrip to preserve data" }
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
        TestCase("reuses persisted semantic index on restart") {
            val projectRoot = createSemanticPersistenceFixture()
            val sourceFile = projectRoot.resolve("app/src/main/kotlin/demo/app/Caller.kt")
            val cacheRoot = Files.createTempDirectory("kotlinls-semantic-restart")

            withRunningServer(
                serverFactory = { transport ->
                    KotlinLanguageServer(
                        transport = transport,
                        semanticIndexCache = PersistentSemanticIndexCache(cacheRoot),
                        refreshDebounceMillis = 0L,
                        warmupStartDelayMillis = 1_000L,
                    )
                },
            ) { server ->
                initializeServer(server, projectRoot)
                openDocument(server, sourceFile)
                val warmupComplete = readUntil(server.reader, maxMessages = 160) { message ->
                    progressTitle(message) == "Semantic Warmup Index" &&
                        progressEvent(message) == "end" &&
                        progressSubtitle(message)?.contains("semantic warm index ready", ignoreCase = true) == true
                }
                assertTrue(warmupComplete != null) { "Expected a persisted full-module semantic warmup before restart" }
            }

            val cacheFileCount = Files.walk(cacheRoot).use { stream ->
                stream.filter { it.fileName?.toString() == "semantic-index.json" }.count()
            }
            assertTrue(cacheFileCount > 0) { "Expected semantic cache files to be written under $cacheRoot" }

            withRunningServer(
                serverFactory = { transport ->
                    KotlinLanguageServer(
                        transport = transport,
                        semanticIndexCache = PersistentSemanticIndexCache(cacheRoot),
                        refreshDebounceMillis = 0L,
                        warmupStartDelayMillis = 1_000L,
                    )
                },
            ) { server ->
                initializeServer(server, projectRoot)
                openDocument(server, sourceFile)

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 2,
                        "method" to "textDocument/definition",
                        "params" to mapOf(
                            "textDocument" to mapOf("uri" to sourceFile.toUri().toString()),
                            "position" to mapOf("line" to 2, "character" to 27),
                        ),
                    ),
                )

                val progressTitles = mutableListOf<String>()
                val progressEvents = mutableListOf<String>()
                val definitionResponse = readUntil(server.reader, maxMessages = 60) { message ->
                    progressTitle(message)?.let(progressTitles::add)
                    progressEvent(message)?.let(progressEvents::add)
                    message.id?.asInt() == 2
                }
                val result = definitionResponse?.result
                assertTrue(result != null && result.isArray && result.size() > 0) {
                    "Expected definition data from persisted caches, got $result"
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

private fun initializeServer(server: PersistenceRunningServer, root: Path) {
    writePayload(
        server.clientOut,
        mapOf(
            "jsonrpc" to "2.0",
            "id" to 1,
            "method" to "initialize",
            "params" to mapOf("rootUri" to root.toUri().toString()),
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

private fun progressTitle(message: JsonRpcInboundMessage): String? =
    message.takeIf { it.method == "kotlinls/progress" }
        ?.params
        ?.get("title")
        ?.asText()

private fun progressSubtitle(message: JsonRpcInboundMessage): String? =
    message.takeIf { it.method == "kotlinls/progress" }
        ?.params
        ?.get("subtitle")
        ?.asText()

private fun progressEvent(message: JsonRpcInboundMessage): String? =
    message.takeIf { it.method == "kotlinls/progress" }
        ?.params
        ?.get("event")
        ?.asText()
