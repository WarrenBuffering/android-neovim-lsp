package demo.video.app

import demo.video.domain.HighlightTone
import demo.video.domain.PlaybackState
import demo.video.domain.VideoCard
import demo.video.domain.bannerLine
import demo.video.domain.watchPartyUrl
import demo.video.network.FakeVideoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val repository = FakeVideoRepository()

fun main() = runBlocking {
    val cards = repository.feed().first()
    val spotlight = repository.pickSpotlight(cards)
    val toneBuckets = cards.groupBy { it.tone }
    val summaryByTone = toneBuckets.mapValues { (_, toneCards) -> toneCards.sumOf { it.liveViewerCount } }
    val state = PlaybackState.Live(viewerCount = spotlight.liveViewerCount)

    println(renderShowcase(spotlight, state))
    println(repository.buildPresenterTag(spotlight))
    println(watchPartyUrl(spotlight))
    println(summaryByTone)
}

fun renderShowcase(card: VideoCard, state: PlaybackState) = bannerLine(
    title = card.title,
    subtitle = repository.buildPresenterTag(card),
    tone = card.tone,
    emphasized = state is PlaybackState.Live && state.viewerCount > 10_000,
)

fun completionHotspot(card: VideoCard): String {
    val tone = card.tone
    return tone.presenterLabel + " • " + tone.accentHex
}

fun stateLabel(state: PlaybackState): String = when (state) {
    PlaybackState.Idle -> "Idle"
    is PlaybackState.Countdown -> "Starts in ${state.secondsRemaining}s"
    is PlaybackState.Live -> "Live for ${state.viewerCount} viewers"
}

c
