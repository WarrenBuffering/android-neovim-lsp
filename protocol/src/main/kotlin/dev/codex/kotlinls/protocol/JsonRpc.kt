package dev.codex.kotlinls.protocol

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class JsonRpcInboundMessage(
    val id: JsonNode?,
    val method: String?,
    val params: JsonNode?,
    val result: JsonNode?,
    val error: JsonRpcError?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null,
)

object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val REQUEST_CANCELLED = -32800
}

class JsonRpcTransport(
    input: BufferedInputStream,
    output: BufferedOutputStream,
) {
    private val source = input
    private val sink = output
    private val writeLock = ReentrantLock()
    private val outboundRequestIds = AtomicLong(1)

    fun readMessage(): JsonRpcInboundMessage? {
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readHeaderLine() ?: return null
            if (line.isBlank()) {
                break
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: return null
        val bytes = source.readNBytes(contentLength)
        if (bytes.size != contentLength) {
            return null
        }
        val root = Json.mapper.readTree(bytes)
        return JsonRpcInboundMessage(
            id = root.get("id"),
            method = root.get("method")?.asText(),
            params = root.get("params"),
            result = root.get("result"),
            error = root.get("error")?.let { Json.mapper.treeToValue(it, JsonRpcError::class.java) },
        )
    }

    fun sendResponse(id: JsonNode, result: Any?) {
        write(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to result,
            ),
        )
    }

    fun sendError(id: JsonNode?, code: Int, message: String, data: Any? = null) {
        write(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "error" to JsonRpcError(code = code, message = message, data = data),
            ),
        )
    }

    fun sendNotification(method: String, params: Any?) {
        write(
            mapOf(
                "jsonrpc" to "2.0",
                "method" to method,
                "params" to params,
            ),
        )
    }

    fun sendRequest(method: String, params: Any? = null): Long {
        val id = outboundRequestIds.getAndIncrement()
        write(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "method" to method,
                "params" to params,
            ),
        )
        return id
    }

    private fun write(payload: Any) {
        val bytes = Json.mapper.writeValueAsBytes(payload)
        val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
        writeLock.withLock {
            sink.write(header)
            sink.write(bytes)
            sink.flush()
        }
    }

    private fun readHeaderLine(): String? {
        val bytes = ArrayList<Byte>(128)
        while (true) {
            val next = source.read()
            if (next == -1) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.UTF_8)
            }
            if (next == '\n'.code) {
                val line = bytes.toByteArray().toString(StandardCharsets.UTF_8)
                return line.removeSuffix("\r")
            }
            bytes += next.toByte()
        }
    }
}
