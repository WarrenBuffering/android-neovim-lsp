package dev.codex.kotlinls.server

import com.fasterxml.jackson.annotation.JsonProperty
import dev.codex.kotlinls.protocol.Json

internal data class KotlinLsConfig(
    val semantic: SemanticConfig = SemanticConfig(),
    val progress: ProgressConfig = ProgressConfig(),
    val diagnostics: DiagnosticsConfig = DiagnosticsConfig(),
) {
    companion object {
        fun fromInitializationOptions(initializationOptions: Map<String, Any?>?): KotlinLsConfig {
            val raw = initializationOptions ?: emptyMap()
            val parsed = runCatching {
                Json.mapper.convertValue(raw, RawKotlinLsConfig::class.java)
            }.getOrDefault(RawKotlinLsConfig())
            return KotlinLsConfig(
                semantic = SemanticConfig(
                    backend = SemanticBackend.from(parsed.semantic.backend),
                    prefetch = SemanticPrefetchMode.from(parsed.semantic.prefetch),
                    requestTimeoutMillis = parsed.semantic.requestTimeoutMillis
                        ?.coerceAtLeast(100L)
                        ?: SemanticConfig().requestTimeoutMillis,
                ),
                progress = ProgressConfig(
                    mode = ProgressMode.from(parsed.progress.mode),
                ),
                diagnostics = DiagnosticsConfig(
                    fastDebounceMillis = parsed.diagnostics.fastDebounceMillis
                        ?.coerceAtLeast(0L)
                        ?: DiagnosticsConfig().fastDebounceMillis,
                ),
            )
        }
    }
}

internal data class SemanticConfig(
    val backend: SemanticBackend = SemanticBackend.K2_BRIDGE,
    val prefetch: SemanticPrefetchMode = SemanticPrefetchMode.ACTIVE_FILE,
    val requestTimeoutMillis: Long = 1_200L,
)

internal data class ProgressConfig(
    val mode: ProgressMode = ProgressMode.MINIMAL,
)

internal data class DiagnosticsConfig(
    val fastDebounceMillis: Long = 0L,
)

internal enum class SemanticBackend {
    K2_BRIDGE,
    DISABLED;

    companion object {
        fun from(value: String?): SemanticBackend = when (value?.trim()?.lowercase()) {
            "disabled", "off", "none" -> DISABLED
            else -> K2_BRIDGE
        }
    }
}

internal enum class SemanticPrefetchMode {
    ACTIVE_FILE,
    VISIBLE_FILES,
    MODULE;

    companion object {
        fun from(value: String?): SemanticPrefetchMode = when (value?.trim()?.lowercase()) {
            "visible_files", "visible-files", "visiblefiles" -> VISIBLE_FILES
            "module" -> MODULE
            else -> ACTIVE_FILE
        }
    }
}

internal enum class ProgressMode {
    OFF,
    MINIMAL,
    VERBOSE;

    companion object {
        fun from(value: String?): ProgressMode = when (value?.trim()?.lowercase()) {
            "off", "none", "false" -> OFF
            "verbose", "debug" -> VERBOSE
            else -> MINIMAL
        }
    }
}

private data class RawKotlinLsConfig(
    val semantic: RawSemanticConfig = RawSemanticConfig(),
    val progress: RawProgressConfig = RawProgressConfig(),
    val diagnostics: RawDiagnosticsConfig = RawDiagnosticsConfig(),
)

private data class RawSemanticConfig(
    val backend: String? = null,
    val prefetch: String? = null,
    @param:JsonProperty("request_timeout_ms")
    val requestTimeoutMillis: Long? = null,
)

private data class RawProgressConfig(
    val mode: String? = null,
)

private data class RawDiagnosticsConfig(
    @param:JsonProperty("fast_debounce_ms")
    val fastDebounceMillis: Long? = null,
)
