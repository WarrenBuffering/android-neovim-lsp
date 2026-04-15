package demo.video.domain

/**
 * Formats the high-contrast banner shown at the top of the fake home screen.
 */
fun bannerLine(
    title: String,
    subtitle: String,
    tone: HighlightTone,
    emphasized: Boolean = false,
): String {
    val prefix = if (emphasized) "LIVE" else "QUEUE"
    return "$prefix • ${tone.presenterLabel} • $title • $subtitle"
}

/**
 * Small helper that is useful for hover, definition, and workspace symbol demos.
 */
fun watchPartyUrl(card: VideoCard): String =
    "https://demo.video/watch/${card.id}?tone=${card.tone.name.lowercase()}"

sealed interface PlaybackState {
    data object Idle : PlaybackState

    data class Countdown(val secondsRemaining: Int) : PlaybackState

    data class Live(val viewerCount: Int) : PlaybackState
}
