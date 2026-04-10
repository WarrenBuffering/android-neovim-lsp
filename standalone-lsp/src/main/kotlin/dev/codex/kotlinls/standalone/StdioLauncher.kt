package dev.codex.kotlinls.standalone

import dev.codex.kotlinls.protocol.JsonRpcTransport
import dev.codex.kotlinls.server.KotlinLanguageServer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream

object KotlinLanguageServerLibrary {
    fun create(transport: JsonRpcTransport): KotlinLanguageServer = KotlinLanguageServer(transport)

    fun createStdioTransport(
        input: InputStream = System.`in`,
        output: OutputStream = System.out,
    ): JsonRpcTransport = JsonRpcTransport(
        input = BufferedInputStream(input),
        output = BufferedOutputStream(output),
    )

    fun runStdio(
        input: InputStream = System.`in`,
        output: OutputStream = System.out,
    ) {
        create(createStdioTransport(input, output)).run()
    }
}

fun runStdioKotlinLanguageServer(
    input: InputStream = System.`in`,
    output: OutputStream = System.out,
) {
    KotlinLanguageServerLibrary.runStdio(input, output)
}
