package demo.video.app

import demo.video.domain.HighlightTone
import demo.video.domain.PlaybackState

class WorkspaceSymbolsPlayground {
    sealed interface Cue {
        data object Intro : Cue

        data class Detail(val query: String) : Cue

        data class Outro(val tone: HighlightTone) : Cue
    }

    fun cueLabel(cue: Cue): String = when (cue) {
        Cue.Intro -> "Intro"
        is Cue.Detail -> "Detail: ${cue.query}"
        is Cue.Outro -> "Outro: ${cue.tone.presenterLabel}"
    }

    fun playbackSummary(state: PlaybackState): String = when (state) {
        PlaybackState.Idle -> "Idle"
        is PlaybackState.Countdown -> "Countdown"
        is PlaybackState.Live -> "Live"
    }
}
