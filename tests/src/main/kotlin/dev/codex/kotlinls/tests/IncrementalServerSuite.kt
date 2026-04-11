package dev.codex.kotlinls.tests

import com.fasterxml.jackson.databind.JsonNode
import dev.codex.kotlinls.protocol.Json
import dev.codex.kotlinls.protocol.JsonRpcInboundMessage
import dev.codex.kotlinls.protocol.JsonRpcTransport
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

fun incrementalServerSuite(): TestSuite = TestSuite(
    name = "incremental-server",
    cases = listOf(
        TestCase("publishes fast open-file diagnostics before semantic analysis completes") {
            val projectRoot = createFastDiagnosticsFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(server, projectRoot)
                openDocument(server, appFile)
                var sawSemanticAnalysis = false
                val firstDiagnostic = readUntil(server.reader, maxMessages = 80) { message ->
                    if (progressTitle(message) == "Semantic Analysis") {
                        sawSemanticAnalysis = true
                    }
                    diagnosticUri(message) == appFile.toUri().toString()
                }
                assertTrue(firstDiagnostic != null) { "Expected fast diagnostics for open file" }
                assertTrue(!sawSemanticAnalysis) { "Expected fast diagnostics before semantic analysis progress started" }
                val codes = firstDiagnostic?.params?.get("diagnostics")?.mapNotNull { it.get("code")?.asText() }.orEmpty()
                assertTrue("package-mismatch" in codes) { "Expected package mismatch diagnostic, got $codes" }
            }
        },
        TestCase("updates fast index from unsaved document changes") {
            val projectRoot = createLiveIndexFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(server, projectRoot)
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { diagnosticUri(it) == appFile.toUri().toString() }

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "method" to "textDocument/didChange",
                        "params" to mapOf(
                            "textDocument" to mapOf(
                                "uri" to appFile.toUri().toString(),
                                "version" to 2,
                            ),
                            "contentChanges" to listOf(
                                mapOf(
                                    "text" to """
                                        package demo.app

                                        fun newName(): String = "ok"
                                    """.trimIndent() + "\n",
                                ),
                            ),
                        ),
                    ),
                )

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 2,
                        "method" to "workspace/symbol",
                        "params" to mapOf("query" to "newName"),
                    ),
                )

                val response = readUntil(server.reader, maxMessages = 80) { it.id?.asInt() == 2 }
                val result = response?.result
                assertTrue(result != null && result.isArray && result.any { symbol ->
                    symbol.get("name")?.asText() == "newName"
                }) {
                    "Expected workspace symbol lookup to see unsaved rename, got $result"
                }
            }
        },
        TestCase("does not trigger semantic analysis on unsaved changes") {
            val projectRoot = createLiveIndexFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(server, projectRoot)
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { diagnosticUri(it) == appFile.toUri().toString() }
                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 2,
                        "method" to "textDocument/rename",
                        "params" to mapOf(
                            "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                            "position" to mapOf("line" to 2, "character" to 6),
                            "newName" to "oldNameRenamed",
                        ),
                    ),
                )
                readUntil(server.reader, maxMessages = 160) { message ->
                    message.id?.asInt() == 2
                }

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "method" to "textDocument/didChange",
                        "params" to mapOf(
                            "textDocument" to mapOf(
                                "uri" to appFile.toUri().toString(),
                                "version" to 2,
                            ),
                            "contentChanges" to listOf(
                                mapOf(
                                    "text" to """
                                        package demo.app

                                        fun newerName(): String = "ok"
                                    """.trimIndent() + "\n",
                                ),
                            ),
                        ),
                    ),
                )

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 3,
                        "method" to "workspace/symbol",
                        "params" to mapOf("query" to "newerName"),
                    ),
                )

                var sawSemanticAnalysis = false
                readUntil(server.reader, maxMessages = 30) { message ->
                    if (progressTitle(message) == "Semantic Analysis") {
                        sawSemanticAnalysis = true
                    }
                    message.id?.asInt() == 3
                }
                assertTrue(!sawSemanticAnalysis) { "Did not expect semantic analysis to start on unsaved edit" }
            }
        },
        TestCase("publishes diagnostics only for open files") {
            val projectRoot = createOpenFileDiagnosticsFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            val libFile = projectRoot.resolve("lib/src/main/kotlin/demo/lib/Broken.kt")
            withRunningServer { server ->
                initializeServer(server, projectRoot)
                openDocument(server, appFile)
                val diagnosticsUris = mutableListOf<String>()
                readUntil(server.reader, maxMessages = 60, predicate = { message ->
                    val uri = diagnosticUri(message)
                    if (uri != null) {
                        diagnosticsUris += uri
                    }
                    uri == appFile.toUri().toString()
                })
                assertTrue(diagnosticsUris.contains(appFile.toUri().toString())) {
                    "Expected diagnostics publish for open app file, got $diagnosticsUris"
                }
                assertTrue(libFile.toUri().toString() !in diagnosticsUris) {
                    "Expected unopened lib file diagnostics to be suppressed, got $diagnosticsUris"
                }
            }
        },
        TestCase("eventually serves semantic hover and definition for local variables when backend is enabled") {
            val projectRoot = createSemanticRequestFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(
                    server,
                    projectRoot,
                    initializationOptions = mapOf(
                        "progress" to mapOf("mode" to "off"),
                    ),
                )
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { diagnosticUri(it) == appFile.toUri().toString() }

                var hoverText = ""
                var definitionCount = 0
                repeat(10) { attempt ->
                    val requestId = 200 + attempt
                    writePayload(
                        server.clientOut,
                        mapOf(
                            "jsonrpc" to "2.0",
                            "id" to requestId,
                            "method" to "textDocument/hover",
                            "params" to mapOf(
                                "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                                "position" to mapOf("line" to 4, "character" to 11),
                            ),
                        ),
                    )
                    val hoverResponse = readUntil(server.reader, maxMessages = 120) { message ->
                        message.id?.asInt() == requestId
                    }
                    hoverText = hoverResponse?.result?.toString().orEmpty()
                    if ("Int" in hoverText) {
                        return@repeat
                    }
                    Thread.sleep(150)
                }
                assertContains(hoverText, "Int")

                repeat(10) { attempt ->
                    val requestId = 300 + attempt
                    writePayload(
                        server.clientOut,
                        mapOf(
                            "jsonrpc" to "2.0",
                            "id" to requestId,
                            "method" to "textDocument/definition",
                            "params" to mapOf(
                                "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                                "position" to mapOf("line" to 4, "character" to 11),
                            ),
                        ),
                    )
                    val definitionResponse = readUntil(server.reader, maxMessages = 120) { message ->
                        message.id?.asInt() == requestId
                    }
                    definitionCount = definitionResponse?.result?.size() ?: 0
                    if (definitionCount > 0) {
                        return@repeat
                    }
                    Thread.sleep(150)
                }
                assertTrue(definitionCount > 0) { "Expected semantic definition for local variable once snapshot was ready" }
            }
        },
        TestCase("serves first semantic hover and definition on cold request when focused analysis fits timeout") {
            val projectRoot = createSemanticRequestFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(
                    server,
                    projectRoot,
                    initializationOptions = mapOf(
                        "progress" to mapOf("mode" to "off"),
                        "semantic" to mapOf(
                            "request_timeout_ms" to 600,
                        ),
                    ),
                )
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { diagnosticUri(it) == appFile.toUri().toString() }

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 600,
                        "method" to "textDocument/hover",
                        "params" to mapOf(
                            "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                            "position" to mapOf("line" to 4, "character" to 11),
                        ),
                    ),
                )
                val hoverResponse = readUntil(server.reader, maxMessages = 160) { message ->
                    message.id?.asInt() == 600
                }
                assertContains(hoverResponse?.result?.toString().orEmpty(), "Int")

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 601,
                        "method" to "textDocument/definition",
                        "params" to mapOf(
                            "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                            "position" to mapOf("line" to 4, "character" to 11),
                        ),
                    ),
                )
                val definitionResponse = readUntil(server.reader, maxMessages = 160) { message ->
                    message.id?.asInt() == 601
                }
                assertTrue((definitionResponse?.result?.size() ?: 0) > 0) {
                    "Expected first cold definition request to succeed once focused analysis completed inside timeout"
                }
            }
        },
        TestCase("reuses current semantic state for repeated hover requests after edits") {
            val projectRoot = createSemanticRequestFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(
                    server,
                    projectRoot,
                    initializationOptions = mapOf(
                        "progress" to mapOf("mode" to "minimal"),
                    ),
                )
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { diagnosticUri(it) == appFile.toUri().toString() }

                val editedText = """
                    package demo.app

                    fun app(): Int {
                        val count = 42
                        return count + 0
                    }
                """.trimIndent() + "\n"
                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "method" to "textDocument/didChange",
                        "params" to mapOf(
                            "textDocument" to mapOf(
                                "uri" to appFile.toUri().toString(),
                                "version" to 2,
                            ),
                            "contentChanges" to listOf(
                                mapOf("text" to editedText),
                            ),
                        ),
                    ),
                )

                var primedHoverText = ""
                repeat(12) { attempt ->
                    val requestId = 400 + attempt
                    writePayload(
                        server.clientOut,
                        mapOf(
                            "jsonrpc" to "2.0",
                            "id" to requestId,
                            "method" to "textDocument/hover",
                            "params" to mapOf(
                                "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                                "position" to mapOf("line" to 4, "character" to 11),
                            ),
                        ),
                    )
                    val hoverResponse = readUntil(server.reader, maxMessages = 160) { message ->
                        message.id?.asInt() == requestId
                    }
                    primedHoverText = hoverResponse?.result?.toString().orEmpty()
                    if ("Int" in primedHoverText) {
                        return@repeat
                    }
                    Thread.sleep(150)
                }
                assertContains(primedHoverText, "Int")

                val secondRequestId = 500
                var sawSecondSemanticAnalysis = false
                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to secondRequestId,
                        "method" to "textDocument/hover",
                        "params" to mapOf(
                            "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                            "position" to mapOf("line" to 4, "character" to 11),
                        ),
                    ),
                )
                val secondHoverResponse = readUntil(server.reader, maxMessages = 80) { message ->
                    if (progressTitle(message) == "Semantic Analysis") {
                        sawSecondSemanticAnalysis = true
                    }
                    message.id?.asInt() == secondRequestId
                }
                val secondHoverText = secondHoverResponse?.result?.toString().orEmpty()
                assertContains(secondHoverText, "Int")
                assertTrue(!sawSecondSemanticAnalysis) {
                    "Expected repeated hover on unchanged version to reuse semantic state instead of re-running analysis"
                }
            }
        },
        TestCase("waits for current semantic state after edits instead of returning stale hover") {
            val projectRoot = createSemanticRequestFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(
                    server,
                    projectRoot,
                    initializationOptions = mapOf(
                        "progress" to mapOf("mode" to "off"),
                        "semantic" to mapOf(
                            "request_timeout_ms" to 1200,
                        ),
                    ),
                )
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { diagnosticUri(it) == appFile.toUri().toString() }

                repeat(12) { attempt ->
                    val requestId = 700 + attempt
                    writePayload(
                        server.clientOut,
                        mapOf(
                            "jsonrpc" to "2.0",
                            "id" to requestId,
                            "method" to "textDocument/hover",
                            "params" to mapOf(
                                "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                                "position" to mapOf("line" to 4, "character" to 11),
                            ),
                        ),
                    )
                    val hoverResponse = readUntil(server.reader, maxMessages = 160) { message ->
                        message.id?.asInt() == requestId
                    }
                    if ("Int" in hoverResponse?.result?.toString().orEmpty()) {
                        return@repeat
                    }
                    Thread.sleep(150)
                }

                val editedText = """
                    package demo.app

                    fun app(): String {
                        val count = "forty-two"
                        return count
                    }
                """.trimIndent() + "\n"
                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "method" to "textDocument/didChange",
                        "params" to mapOf(
                            "textDocument" to mapOf(
                                "uri" to appFile.toUri().toString(),
                                "version" to 2,
                            ),
                            "contentChanges" to listOf(
                                mapOf("text" to editedText),
                            ),
                        ),
                    ),
                )

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 800,
                        "method" to "textDocument/hover",
                        "params" to mapOf(
                            "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                            "position" to mapOf("line" to 4, "character" to 11),
                        ),
                    ),
                )
                val hoverResponse = readUntil(server.reader, maxMessages = 200) { message ->
                    message.id?.asInt() == 800
                }
                val hoverText = hoverResponse?.result?.toString().orEmpty()
                assertContains(hoverText, "String")
                assertTrue("Int" !in hoverText) {
                    "Expected post-edit hover to wait for current semantic state, got stale response: $hoverText"
                }
            }
        },
        TestCase("returns disabled semantic responses without custom progress spam") {
            val projectRoot = FixtureSupport.fixture("multi-module")
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/Main.kt")
            val libFile = projectRoot.resolve("lib/src/main/kotlin/demo/lib/Greeting.kt")
            withRunningServer { server ->
                initializeServer(server, projectRoot)
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { message ->
                    diagnosticUri(message) == appFile.toUri().toString()
                }
                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 2,
                        "method" to "textDocument/rename",
                        "params" to mapOf(
                            "textDocument" to mapOf("uri" to libFile.toUri().toString()),
                            "position" to mapOf("line" to 3, "character" to 8),
                            "newName" to "speak",
                        ),
                    ),
                )
                val observedMethods = mutableListOf<String>()
                val renameOutcome = readUntil(server.reader, maxMessages = 120, predicate = { message ->
                    message.method?.let(observedMethods::add)
                    message.id?.asInt() == 2
                })
                val resultNode = renameOutcome?.result
                assertTrue(renameOutcome != null && (resultNode == null || resultNode.isNull)) {
                    "Expected disabled semantic rename to return null, got $resultNode"
                }
                assertTrue("kotlinls/progress" !in observedMethods) {
                    "Expected custom progress notifications to stay disabled, got $observedMethods"
                }
            }
        },
    ),
)

private data class RunningServer(
    val clientOut: PipedOutputStream,
    val reader: JsonRpcTransport,
    val thread: Thread,
)

private fun withRunningServer(block: (RunningServer) -> Unit) {
    val server = startServer()
    try {
        block(server)
    } finally {
        runCatching { writePayload(server.clientOut, mapOf("jsonrpc" to "2.0", "id" to 999, "method" to "shutdown")) }
        runCatching { readUntil(server.reader, maxMessages = 10) { it.id?.asInt() == 999 } }
        runCatching { writePayload(server.clientOut, mapOf("jsonrpc" to "2.0", "method" to "exit")) }
        server.thread.join(2000)
    }
}

private fun startServer(): RunningServer {
    val serverIn = PipedInputStream()
    val clientOut = PipedOutputStream(serverIn)
    val clientIn = PipedInputStream()
    val serverOut = PipedOutputStream(clientIn)
    val transport = JsonRpcTransport(
        BufferedInputStream(serverIn),
        BufferedOutputStream(serverOut),
    )
    val server = KotlinLanguageServer(
        transport = transport,
        refreshDebounceMillis = 0L,
        warmupStartDelayMillis = 1_000L,
    )
    val thread = thread(start = true, isDaemon = true, name = "kotlinls-incremental-test") {
        server.run()
    }
    return RunningServer(
        clientOut = clientOut,
        reader = JsonRpcTransport(BufferedInputStream(clientIn), BufferedOutputStream(PipedOutputStream())),
        thread = thread,
    )
}

private fun initializeServer(
    server: RunningServer,
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

private fun openDocument(server: RunningServer, path: Path) {
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

private fun createOpenFileDiagnosticsFixture(): Path {
    val root = Files.createTempDirectory("kotlinls-open-file-diags")
    root.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "open-file-diags"
        include(":app", ":lib")
        """.trimIndent() + "\n",
    )
    root.resolve("app/src/main/kotlin/demo/app").createDirectories()
    root.resolve("lib/src/main/kotlin/demo/lib").createDirectories()
    root.resolve("app/build.gradle.kts").writeText(
        """
        plugins {
            kotlin("jvm")
        }

        repositories {
            mavenCentral()
        }

        dependencies {
            implementation(project(":lib"))
        }

        kotlin {
            jvmToolchain(21)
        }
        """.trimIndent() + "\n",
    )
    root.resolve("lib/build.gradle.kts").writeText(
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
    root.resolve("app/src/main/kotlin/demo/app/App.kt").writeText(
        """
        package demo.app

        fun app(): String = "ok"
        """.trimIndent() + "\n",
    )
    root.resolve("lib/src/main/kotlin/demo/lib/Broken.kt").writeText(
        """
        package demo.lib

        fun broken() = nope
        """.trimIndent() + "\n",
    )
    return root
}

private fun createFastDiagnosticsFixture(): Path {
    val root = Files.createTempDirectory("kotlinls-fast-diags")
    root.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "fast-diags"
        include(":app")
        """.trimIndent() + "\n",
    )
    root.resolve("app/src/main/kotlin/demo/app").createDirectories()
    root.resolve("app/build.gradle.kts").writeText(
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
    root.resolve("app/src/main/kotlin/demo/app/App.kt").writeText(
        """
        package wrong.name

        fun app(): String = "ok"
        """.trimIndent() + "\n",
    )
    return root
}

private fun createLiveIndexFixture(): Path {
    val root = Files.createTempDirectory("kotlinls-live-index")
    root.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "live-index"
        include(":app")
        """.trimIndent() + "\n",
    )
    root.resolve("app/src/main/kotlin/demo/app").createDirectories()
    root.resolve("app/build.gradle.kts").writeText(
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
    root.resolve("app/src/main/kotlin/demo/app/App.kt").writeText(
        """
        package demo.app

        fun oldName(): String = "ok"
        """.trimIndent() + "\n",
    )
    return root
}

private fun createSemanticRequestFixture(): Path {
    val root = Files.createTempDirectory("kotlinls-semantic-request")
    root.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "semantic-request"
        include(":app")
        """.trimIndent() + "\n",
    )
    root.resolve("app/src/main/kotlin/demo/app").createDirectories()
    root.resolve("app/build.gradle.kts").writeText(
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
    root.resolve("app/src/main/kotlin/demo/app/App.kt").writeText(
        """
        package demo.app

        fun app(): Int {
            val count = 42
            return count
        }
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

private fun progressSubtitle(message: JsonRpcInboundMessage): String? =
    message.takeIf { it.method == "kotlinls/progress" }
        ?.params
        ?.get("subtitle")
        ?.asText()

private fun progressTitle(message: JsonRpcInboundMessage): String? =
    message.takeIf { it.method == "kotlinls/progress" }
        ?.params
        ?.get("title")
        ?.asText()
