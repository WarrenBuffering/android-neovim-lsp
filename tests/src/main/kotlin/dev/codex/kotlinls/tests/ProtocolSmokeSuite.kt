package dev.codex.kotlinls.tests

import com.fasterxml.jackson.databind.JsonNode
import dev.codex.kotlinls.protocol.Json
import dev.codex.kotlinls.protocol.JsonRpcTransport
import dev.codex.kotlinls.standalone.runStdioKotlinLanguageServer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

fun protocolSmokeSuite(): TestSuite = TestSuite(
    name = "protocol-smoke",
    cases = listOf(
        TestCase("initializes, opens a document, and answers workspace symbol") {
            val fixtureRoot = FixtureSupport.fixture("simple-jvm-app")
            val serverIn = PipedInputStream()
            val clientOut = PipedOutputStream(serverIn)
            val clientIn = PipedInputStream()
            val serverOut = PipedOutputStream(clientIn)
            val serverThread = thread(start = true, isDaemon = true, name = "kotlinls-smoke") {
                runStdioKotlinLanguageServer(serverIn, serverOut)
            }
            val reader = JsonRpcTransport(BufferedInputStream(clientIn), BufferedOutputStream(PipedOutputStream()))
            val appFile = fixtureRoot.resolve("src/main/kotlin/demo/App.kt")
            writePayload(
                clientOut,
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 1,
                    "method" to "initialize",
                    "params" to mapOf(
                        "rootUri" to fixtureRoot.toUri().toString(),
                        "initializationOptions" to mapOf(
                            "semantic" to mapOf(
                                "backend" to "disabled",
                            ),
                        ),
                    ),
                ),
            )
            val initResponse = readUntil(reader) { message -> message.id?.asInt() == 1 }
            assertTrue(initResponse?.result != null) { "Expected initialize response" }
            writePayload(
                clientOut,
                mapOf(
                    "jsonrpc" to "2.0",
                    "method" to "initialized",
                    "params" to emptyMap<String, Any>(),
                ),
            )

            writePayload(
                clientOut,
                mapOf(
                    "jsonrpc" to "2.0",
                    "method" to "textDocument/didOpen",
                    "params" to mapOf(
                        "textDocument" to mapOf(
                            "uri" to appFile.toUri().toString(),
                            "languageId" to "kotlin",
                            "version" to 1,
                            "text" to appFile.toFile().readText(),
                        ),
                    ),
                ),
            )
            val maybeDiagnostics = readUntil(reader, maxMessages = 80) { message -> message.method == "textDocument/publishDiagnostics" }
            assertTrue(maybeDiagnostics?.method == "textDocument/publishDiagnostics") {
                "Expected publishDiagnostics notification, got $maybeDiagnostics"
            }

            writePayload(
                clientOut,
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to 2,
                    "method" to "workspace/symbol",
                    "params" to mapOf("query" to "greet"),
                ),
            )
            val symbolResponse = readUntil(reader, maxMessages = 80) { message -> message.id?.asInt() == 2 }
            val symbolText = symbolResponse?.result?.toString().orEmpty()
            assertContains(symbolText, "greet")

            writePayload(clientOut, mapOf("jsonrpc" to "2.0", "id" to 3, "method" to "shutdown"))
            readUntil(reader, maxMessages = 20) { message -> message.id?.asInt() == 3 }
            writePayload(clientOut, mapOf("jsonrpc" to "2.0", "method" to "exit"))
            serverThread.join(2000)
        },
    ),
)

private fun writePayload(output: PipedOutputStream, payload: Any) {
    val bytes = Json.mapper.writeValueAsBytes(payload)
    val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
    output.write(header)
    output.write(bytes)
    output.flush()
}

private fun readUntil(
    reader: JsonRpcTransport,
    maxMessages: Int = 10,
    predicate: (dev.codex.kotlinls.protocol.JsonRpcInboundMessage) -> Boolean,
): dev.codex.kotlinls.protocol.JsonRpcInboundMessage? {
    repeat(maxMessages) {
        val message = reader.readMessage() ?: return null
        if (predicate(message)) return message
    }
    return null
}
