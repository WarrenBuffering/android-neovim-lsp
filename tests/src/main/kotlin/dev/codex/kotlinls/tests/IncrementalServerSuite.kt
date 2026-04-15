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
import java.lang.reflect.Field
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
        TestCase("publishes semantic diagnostics before semantic index starts") {
            val projectRoot = createSemanticDiagnosticsFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(
                    server,
                    projectRoot,
                    initializationOptions = mapOf(
                        "progress" to mapOf("mode" to "off"),
                        "semantic" to emptyMap<String, Any>(),
                    ),
                )
                openDocument(server, appFile)
                var sawSemanticIndex = false
                val semanticDiagnostic = readUntil(server.reader, maxMessages = 160, timeoutMillis = 15_000L) { message ->
                    if (progressTitle(message) == "Semantic Index") {
                        sawSemanticIndex = true
                    }
                    diagnosticUri(message) == appFile.toUri().toString() &&
                        ((message.params?.get("diagnostics")?.takeIf(JsonNode::isArray)?.size() ?: 0) > 0)
                }
                assertTrue(semanticDiagnostic != null) { "Expected semantic diagnostic for open file" }
                assertTrue(!sawSemanticIndex) {
                    "Expected semantic diagnostic to publish before semantic index progress started"
                }
                val messages = semanticDiagnostic?.params
                    ?.get("diagnostics")
                    ?.mapNotNull { it.get("message")?.asText() }
                    .orEmpty()
                assertTrue(messages.any {
                    val normalized = it.lowercase()
                    "type mismatch" in normalized ||
                        "initializer type mismatch" in normalized ||
                        "does not conform to the expected type" in normalized
                }) {
                    "Expected compiler type mismatch diagnostic, got $messages"
                }
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
        TestCase("debounces bursty change diagnostics down to the latest document state") {
            val projectRoot = createDiagnosticsDebounceFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(
                    server,
                    projectRoot,
                    initializationOptions = mapOf(
                        "semantic" to mapOf(
                            "backend" to "disabled",
                        ),
                        "progress" to mapOf(
                            "mode" to "off",
                        ),
                        "diagnostics" to mapOf(
                            "fast_debounce_ms" to 250,
                        ),
                    ),
                )
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { diagnosticUri(it) == appFile.toUri().toString() }

                val texts = listOf(
                    """
                    package demo.app

                    import demo.app.l

                    fun app(): String = "ok"
                    """.trimIndent() + "\n",
                    """
                    package demo.app

                    import demo.app.li

                    fun app(): String = "ok"
                    """.trimIndent() + "\n",
                    """
                    package demo.app

                    import demo.app.lis

                    fun app(): String = "ok"
                    """.trimIndent() + "\n",
                    """
                    package demo.app

                    import demo.app.list

                    fun app(): String = "ok"
                    """.trimIndent() + "\n",
                    """
                    package demo.app

                    import demo.app.listO

                    fun app(): String = "ok"
                    """.trimIndent() + "\n",
                    """
                    package demo.app

                    import demo.app.listOf

                    fun app(): String = "ok"
                    """.trimIndent() + "\n",
                )

                texts.forEachIndexed { index, text ->
                    writePayload(
                        server.clientOut,
                        mapOf(
                            "jsonrpc" to "2.0",
                            "method" to "textDocument/didChange",
                            "params" to mapOf(
                                "textDocument" to mapOf(
                                    "uri" to appFile.toUri().toString(),
                                    "version" to index + 2,
                                ),
                                "contentChanges" to listOf(
                                    mapOf("text" to text),
                                ),
                            ),
                        ),
                    )
                }

                Thread.sleep(800)

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 77,
                        "method" to "workspace/symbol",
                        "params" to mapOf("query" to "listOf"),
                    ),
                )

                var postBurstDiagnostics = 0
                readUntil(server.reader, maxMessages = 80) { message ->
                    if (diagnosticUri(message) == appFile.toUri().toString()) {
                        postBurstDiagnostics += 1
                    }
                    message.id?.asInt() == 77
                }

                assertEquals(0, postBurstDiagnostics) {
                    "Expected burst changes ending in a clean import state to suppress intermediate diagnostics, got $postBurstDiagnostics publishes"
                }
            }
        },
        TestCase("defers diagnostics until the insert-leave flush arrives") {
            val projectRoot = createDiagnosticsDebounceFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(
                    server,
                    projectRoot,
                    initializationOptions = mapOf(
                        "semantic" to mapOf(
                            "backend" to "disabled",
                        ),
                        "progress" to mapOf(
                            "mode" to "off",
                        ),
                        "diagnostics" to mapOf(
                            "fast_debounce_ms" to 0,
                        ),
                    ),
                )
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

                                        import demo.app.listO

                                        fun app(): String = "ok"
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
                        "id" to 88,
                        "method" to "workspace/symbol",
                        "params" to mapOf("query" to "listOf"),
                    ),
                )

                var diagnosticsBeforeFlush = 0
                val symbolResponse = readUntil(server.reader, maxMessages = 80) { message ->
                    if (diagnosticUri(message) == appFile.toUri().toString()) {
                        diagnosticsBeforeFlush += 1
                    }
                    message.id?.asInt() == 88
                }
                assertTrue(symbolResponse?.result?.isArray == true) { "Expected workspace/symbol response after didChange" }
                assertEquals(0, diagnosticsBeforeFlush) {
                    "Expected didChange diagnostics to stay deferred until flush, got $diagnosticsBeforeFlush publishes"
                }

                flushDiagnostics(
                    server,
                    appFile,
                    changedLines = listOf(
                        mapOf(
                            "start_line" to 2,
                            "end_line" to 3,
                        ),
                    ),
                )

                val flushedDiagnostics = readUntil(server.reader, maxMessages = 80) {
                    diagnosticUri(it) == appFile.toUri().toString()
                }
                val diagnosticCodes = flushedDiagnostics?.params
                    ?.get("diagnostics")
                    ?.mapNotNull { it.get("code")?.asText() }
                    .orEmpty()
                assertTrue("unresolved-import-symbol" in diagnosticCodes) {
                    "Expected explicit diagnostics flush to publish unresolved import diagnostics, got $diagnosticCodes"
                }
            }
        },
        TestCase("suppresses semantic progress notifications until the insert-leave flush arrives") {
            val projectRoot = createSemanticRequestFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(
                    server,
                    projectRoot,
                    initializationOptions = mapOf(
                        "semantic" to mapOf(
                            "backend" to "k2_bridge",
                        ),
                    ),
                )
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

                                        fun app(): Int {
                                            val total = 41
                                            return total + 2
                                        }
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
                        "id" to 189,
                        "method" to "workspace/symbol",
                        "params" to mapOf("query" to "app"),
                    ),
                )

                var sawSemanticProgressBeforeFlush = false
                val symbolResponse = readUntil(server.reader, maxMessages = 120) { message ->
                    if (progressTitle(message) == "Semantic Analysis" || progressTitle(message) == "Semantic Index") {
                        sawSemanticProgressBeforeFlush = true
                    }
                    message.id?.asInt() == 189
                }
                assertTrue(symbolResponse?.result?.isArray == true) { "Expected workspace/symbol response after didChange" }
                assertTrue(!sawSemanticProgressBeforeFlush) {
                    "Expected semantic progress to stay deferred until flush"
                }
            }
        },
        TestCase("serves named argument completions from the local index when semantic is disabled") {
            val projectRoot = createNamedArgumentCompletionFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(
                    server,
                    projectRoot,
                    initializationOptions = mapOf(
                        "semantic" to mapOf(
                            "backend" to "disabled",
                        ),
                        "progress" to mapOf(
                            "mode" to "off",
                        ),
                    ),
                )
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { diagnosticUri(it) == appFile.toUri().toString() }

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 140,
                        "method" to "textDocument/completion",
                        "params" to mapOf(
                            "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                            "position" to mapOf("line" to 10, "character" to 13),
                        ),
                    ),
                )

                val completionResponse = readUntil(server.reader, maxMessages = 120) { message ->
                    message.id?.asInt() == 140
                }
                val subtitle = completionResponse?.result
                    ?.get("items")
                    ?.firstOrNull { item -> item.get("label")?.asText() == "subtitle" }
                assertTrue(subtitle != null) {
                    "Expected named argument completion for subtitle from indexed fallback, got ${completionResponse?.result}"
                }
                assertEquals("subtitle = ", subtitle?.get("insertText")?.asText()) {
                    "Expected indexed fallback to insert the parameter assignment, got ${subtitle?.get("insertText")?.asText()}"
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
        TestCase("requests inlay hint refresh after semantic analysis for open files") {
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
                val refreshRequest = readUntil(server.reader, maxMessages = 160, timeoutMillis = 15_000L) { message ->
                    message.method == "workspace/inlayHint/refresh"
                }
                assertTrue(refreshRequest != null) {
                    "Expected workspace/inlayHint/refresh request after semantic state install"
                }
            }
        },
        TestCase("serves default-import Kotlin stdlib hover and definition from the support index") {
            val projectRoot = createDefaultImportSupportFixture()
            val appFile = projectRoot.resolve("src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(server, projectRoot)
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { diagnosticUri(it) == appFile.toUri().toString() }

                var hoverText = ""
                var definitionUri = ""
                repeat(30) { attempt ->
                    val hoverRequestId = 700 + attempt
                    writePayload(
                        server.clientOut,
                        mapOf(
                            "jsonrpc" to "2.0",
                            "id" to hoverRequestId,
                            "method" to "textDocument/hover",
                            "params" to mapOf(
                                "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                                "position" to mapOf("line" to 2, "character" to 16),
                            ),
                        ),
                    )
                    val hoverResponse = readUntil(server.reader, maxMessages = 160) { message ->
                        message.id?.asInt() == hoverRequestId
                    }
                    hoverText = hoverResponse?.result?.toString().orEmpty()

                    val definitionRequestId = 800 + attempt
                    writePayload(
                        server.clientOut,
                        mapOf(
                            "jsonrpc" to "2.0",
                            "id" to definitionRequestId,
                            "method" to "textDocument/definition",
                            "params" to mapOf(
                                "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                                "position" to mapOf("line" to 2, "character" to 16),
                            ),
                        ),
                    )
                    val definitionResponse = readUntil(server.reader, maxMessages = 160) { message ->
                        message.id?.asInt() == definitionRequestId
                    }
                    definitionUri = definitionResponse?.result
                        ?.firstOrNull()
                        ?.get("uri")
                        ?.asText()
                        .orEmpty()
                    if ("String" in hoverText && definitionUri.contains("/kotlin/String.kt")) {
                        return@repeat
                    }
                    Thread.sleep(150)
                }

                assertContains(hoverText, "String")
                assertContains(definitionUri, "/kotlin/String.kt")
            }
        },
        TestCase("serves support-index import completions for non-project symbols") {
            val projectRoot = createDefaultImportSupportFixture()
            val appFile = projectRoot.resolve("src/main/kotlin/demo/app/App.kt")
            appFile.writeText(
                """
                package demo.app

                import kotlin.collections.lis

                fun app(): Int = 1
                """.trimIndent() + "\n",
            )
            withRunningServer { server ->
                initializeServer(server, projectRoot)
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { diagnosticUri(it) == appFile.toUri().toString() }

                var labels = emptyList<String>()
                repeat(30) { attempt ->
                    val requestId = 900 + attempt
                    writePayload(
                        server.clientOut,
                        mapOf(
                            "jsonrpc" to "2.0",
                            "id" to requestId,
                            "method" to "textDocument/completion",
                            "params" to mapOf(
                                "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                                "position" to mapOf("line" to 2, "character" to 29),
                            ),
                        ),
                    )
                    val completionResponse = readUntil(server.reader, maxMessages = 160) { message ->
                        message.id?.asInt() == requestId
                    }
                    labels = completionResponse?.result
                        ?.get("items")
                        ?.mapNotNull { item -> item.get("label")?.asText() }
                        .orEmpty()
                    if ("listOf" in labels) {
                        return@repeat
                    }
                    Thread.sleep(150)
                }

                assertTrue("listOf" in labels) {
                    "Expected support-index import completion to include listOf, got ${labels.take(10)}"
                }
            }
        },
        TestCase("hydrates import completion for new package files added after startup") {
            val projectRoot = createImportHydrationFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(server, projectRoot)
                openDocument(server, appFile)
                readUntil(server.reader, maxMessages = 80) { diagnosticUri(it) == appFile.toUri().toString() }

                val packageDir = projectRoot.resolve("app/src/main/kotlin/demo/app/sections")
                packageDir.createDirectories()
                packageDir.resolve("DockChecklist.kt").writeText(
                    """
                    package demo.app.sections

                    class DockChecklist
                    """.trimIndent() + "\n",
                )

                var labels = emptyList<String>()
                repeat(20) { attempt ->
                    val requestId = 950 + attempt
                    writePayload(
                        server.clientOut,
                        mapOf(
                            "jsonrpc" to "2.0",
                            "id" to requestId,
                            "method" to "textDocument/completion",
                            "params" to mapOf(
                                "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                                "position" to mapOf("line" to 2, "character" to 29),
                            ),
                        ),
                    )
                    val completionResponse = readUntil(server.reader, maxMessages = 160) { message ->
                        message.id?.asInt() == requestId
                    }
                    labels = completionResponse?.result
                        ?.get("items")
                        ?.mapNotNull { item -> item.get("label")?.asText() }
                        .orEmpty()
                    if ("DockChecklist" in labels) {
                        return@repeat
                    }
                    Thread.sleep(100)
                }

                assertTrue("DockChecklist" in labels) {
                    "Expected import completion to hydrate new package files, got ${labels.take(10)}"
                }
            }
        },
        TestCase("hydrates import diagnostics and definition when target file appears after startup") {
            val projectRoot = createImportResolutionFixture()
            val appFile = projectRoot.resolve("app/src/main/kotlin/demo/app/App.kt")
            withRunningServer { server ->
                initializeServer(server, projectRoot)
                openDocument(server, appFile)

                val initialDiagnostics = readUntil(server.reader, maxMessages = 80) {
                    diagnosticUri(it) == appFile.toUri().toString()
                }
                val initialCodes = initialDiagnostics?.params
                    ?.get("diagnostics")
                    ?.mapNotNull { diagnostic -> diagnostic.get("code")?.asText() }
                    .orEmpty()
                assertTrue("unresolved-import-symbol" in initialCodes || "unresolved-import-package" in initialCodes) {
                    "Expected missing import to fail before target file exists, got $initialCodes"
                }

                val packageDir = projectRoot.resolve("app/src/main/kotlin/demo/app/sections")
                packageDir.createDirectories()
                val targetFile = packageDir.resolve("DockChecklist.kt")
                targetFile.writeText(
                    """
                    package demo.app.sections

                    class DockChecklist
                    """.trimIndent() + "\n",
                )

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
                                mapOf("text" to appFile.readText()),
                            ),
                        ),
                    ),
                )

                val recoveredDiagnostics = readUntil(server.reader, maxMessages = 120) {
                    diagnosticUri(it) == appFile.toUri().toString()
                }
                val recoveredCodes = recoveredDiagnostics?.params
                    ?.get("diagnostics")
                    ?.mapNotNull { diagnostic -> diagnostic.get("code")?.asText() }
                    .orEmpty()
                assertTrue("unresolved-import-symbol" !in recoveredCodes && "unresolved-import-package" !in recoveredCodes) {
                    "Expected diagnostics to recover once import target exists, got $recoveredCodes"
                }

                writePayload(
                    server.clientOut,
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to 991,
                        "method" to "textDocument/definition",
                        "params" to mapOf(
                            "textDocument" to mapOf("uri" to appFile.toUri().toString()),
                            "position" to mapOf("line" to 2, "character" to 31),
                        ),
                    ),
                )
                val definitionResponse = readUntil(server.reader, maxMessages = 120) { message ->
                    message.id?.asInt() == 991
                }
                val definitionUri = definitionResponse?.result
                    ?.firstOrNull()
                    ?.get("uri")
                    ?.asText()
                    .orEmpty()
                assertTrue(definitionUri.endsWith("/DockChecklist.kt")) {
                    "Expected import definition to resolve to DockChecklist.kt, got $definitionUri"
                }
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
                            "request_timeout_ms" to 2500,
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

                var hoverText = ""
                var sawStaleHover = false
                repeat(8) { attempt ->
                    val requestId = 800 + attempt
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
                    val hoverResponse = readUntil(server.reader, maxMessages = 200, timeoutMillis = 10_000L) { message ->
                        message.id?.asInt() == requestId
                    }
                    hoverText = hoverResponse?.result?.toString().orEmpty()
                    if ("Int" in hoverText) {
                        sawStaleHover = true
                    }
                    if ("String" in hoverText) {
                        return@repeat
                    }
                    Thread.sleep(150)
                }
                assertContains(hoverText, "String")
                assertTrue(!sawStaleHover) {
                    "Expected post-edit hover to avoid stale Int responses, got: $hoverText"
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

private fun flushDiagnostics(
    server: RunningServer,
    path: Path,
    changedLines: List<Map<String, Int>>,
) {
    writePayload(
        server.clientOut,
        mapOf(
            "jsonrpc" to "2.0",
            "method" to "\$/android-neovim/flushDiagnostics",
            "params" to mapOf(
                "textDocument" to mapOf(
                    "uri" to path.toUri().toString(),
                ),
                "changed_lines" to changedLines,
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

private fun createDiagnosticsDebounceFixture(): Path {
    val root = Files.createTempDirectory("kotlinls-diagnostics-debounce")
    root.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "diagnostics-debounce"
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

        fun app(): String = "ok"
        """.trimIndent() + "\n",
    )
    root.resolve("app/src/main/kotlin/demo/app/ImportTargets.kt").writeText(
        """
        package demo.app

        fun listOf(): String = "ok"
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

private fun createSemanticDiagnosticsFixture(): Path {
    val root = Files.createTempDirectory("kotlinls-semantic-diagnostics")
    root.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "semantic-diagnostics"
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

        fun app(): String {
            val label: String = 42
            return label
        }
        """.trimIndent() + "\n",
    )
    return root
}

private fun createNamedArgumentCompletionFixture(): Path {
    val root = Files.createTempDirectory("kotlinls-named-arg-completion")
    root.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "named-arg-completion"
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

        fun renderCard(
            title: String,
            subtitle: String,
        ) {}

        fun demo() {
            renderCard(
                title = "Dockside Notes",
                subti
            )
        }
        """.trimIndent() + "\n",
    )
    return root
}

private fun createDefaultImportSupportFixture(): Path {
    val root = FixtureSupport.fixtureCopy("simple-jvm-app")
    root.resolve("src/main/kotlin/demo/app").createDirectories()
    root.resolve("src/main/kotlin/demo/app/App.kt").writeText(
        """
        package demo.app

        fun app(label: String): String = label.trim()
        """.trimIndent() + "\n",
    )
    return root
}

private fun createImportHydrationFixture(): Path {
    val root = Files.createTempDirectory("kotlinls-import-hydration")
    root.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "import-hydration"
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

        import demo.app.sections.Dock

        fun app(): String = "ok"
        """.trimIndent() + "\n",
    )
    return root
}

private fun createImportResolutionFixture(): Path {
    val root = Files.createTempDirectory("kotlinls-import-resolution")
    root.resolve("settings.gradle.kts").writeText(
        """
        rootProject.name = "import-resolution"
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

        import demo.app.sections.DockChecklist

        fun app(): String = "ok"
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
    timeoutMillis: Long = 5_000L,
    predicate: (JsonRpcInboundMessage) -> Boolean,
): JsonRpcInboundMessage? {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    repeat(maxMessages) {
        while (readerAvailable(reader) <= 0) {
            if (System.nanoTime() >= deadline) {
                return null
            }
            Thread.sleep(10)
        }
        val message = reader.readMessage() ?: return null
        if (predicate(message)) return message
    }
    return null
}

private val jsonRpcSourceField: Field by lazy {
    JsonRpcTransport::class.java.getDeclaredField("source").apply {
        isAccessible = true
    }
}

private fun readerAvailable(reader: JsonRpcTransport): Int =
    runCatching {
        (jsonRpcSourceField.get(reader) as? BufferedInputStream)?.available() ?: 0
    }.getOrDefault(0)

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
