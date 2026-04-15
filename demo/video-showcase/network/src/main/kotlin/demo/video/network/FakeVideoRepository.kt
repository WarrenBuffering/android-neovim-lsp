package demo.video.network

import demo.video.domain.HighlightTone
import demo.video.domain.VideoCard
import demo.video.domain.VideoRepository
import demo.video.interop.LegacyHandleFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Fast in-memory repository that still touches coroutines, UUID, and Java helpers.
 */
class FakeVideoRepository : VideoRepository {
    private val cards = listOf(
        VideoCard(
            id = UUID.fromString("b9f2a0f0-12d1-4d49-8b26-3e86c31a5001"),
            title = "Fast local completion that still feels smart",
            presenter = "Andrew Neon",
            tone = HighlightTone.NEON_PINK,
            tags = listOf("completion", "ranking", "local-first"),
            liveViewerCount = 14_280,
            spotlightSummary = "Local-first completion with a semantic fallback for harder cases.",
        ),
        VideoCard(
            id = UUID.fromString("b9f2a0f0-12d1-4d49-8b26-3e86c31a5002"),
            title = "Kotlin to Java navigation without leaving Neovim",
            presenter = "Legacy Bridge",
            tone = HighlightTone.LASER_BLUE,
            tags = listOf("navigation", "java", "definition"),
            liveViewerCount = 9_450,
            spotlightSummary = "Jump straight from Kotlin call sites into Java helpers and library code.",
        ),
        VideoCard(
            id = UUID.fromString("b9f2a0f0-12d1-4d49-8b26-3e86c31a5003"),
            title = "Formatting, imports, and code actions in one pass",
            presenter = "Refactor Rider",
            tone = HighlightTone.ACID_GREEN,
            tags = listOf("formatting", "imports", "actions"),
            liveViewerCount = 11_120,
            spotlightSummary = "Quick fixes, organize imports, and formatting stay close to the cursor.",
        ),
    )

    override suspend fun featuredCards(): List<VideoCard> = withContext(Dispatchers.Default) {
        delay(25)
        cards
    }

    override suspend fun cardById(id: UUID): VideoCard? =
        featuredCards().firstOrNull { it.id == id }

    fun feed(): Flow<List<VideoCard>> = flow {
        emit(featuredCards())
    }

    /**
     * Handy hover target that also makes rename and references look good in a demo.
     */
    fun buildPresenterTag(card: VideoCard): String =
        LegacyHandleFormatter.displayHandle(card.presenter) + " • " + card.tone.presenterLabel

    fun pickSpotlight(cards: List<VideoCard>): VideoCard =
        cards.maxByOrNull(VideoCard::liveViewerCount) ?: cards.first()

    fun cacheKeyFor(rawTopic: String) =
        LegacyHandleFormatter.slugFor(rawTopic)
}
