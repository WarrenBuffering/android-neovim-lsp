package demo.video.app

import demo.video.domain.HighlightTone
import demo.video.domain.VideoCard
import demo.video.interop.LegacyHandleFormatter

object FormattingPlayground {
    fun loudCaption(card: VideoCard, tone: HighlightTone) = buildString {
        append(LegacyHandleFormatter.displayHandle(card.presenter))
        append(" :: ")
        append(card.title)
        append(" :: ")
        append(tone.presenterLabel.uppercase())
    }
}
