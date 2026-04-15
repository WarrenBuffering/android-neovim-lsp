package demo.video.domain

import java.util.UUID

/**
 * A single card in the demo feed.
 */
data class VideoCard(
    val id: UUID,
    val title: String,
    val presenter: String,
    val tone: HighlightTone,
    val tags: List<String>,
    val liveViewerCount: Int,
    val spotlightSummary: String,
)
