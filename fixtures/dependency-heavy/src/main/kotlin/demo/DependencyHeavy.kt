package demo

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.flowOf
import okio.Buffer

class DependencyHeavy {
    fun render(): String {
        val mapper = ObjectMapper()
        val payload = mapper.writeValueAsString(mapOf("status" to "ok"))
        val buffer = Buffer().writeUtf8(payload)
        return flowOf(buffer.readUtf8()).toString()
    }
}

