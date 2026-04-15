package demo.video.app

import demo.video.domain.VideoCard
import demo.video.interop.LegacyHandleFormatter
import demo.video.network.FakeVideoRepository
import java.util.UUID

object CodeActionPlayground {
    fun explicitTypeDemo(repository: FakeVideoRepository, card: VideoCard) {
        val presenterTag = repository.buildPresenterTag(card)
        val slug = repository.cacheKeyFor(card.title)
        println(presenterTag + " / " + slug)
    }

    fun explicitReturnDemo(card: VideoCard) =
        LegacyHandleFormatter.displayHandle(card.presenter)

    fun importFixDemo() {
        // Delete the UUID import above, then trigger the missing-import code action.
        val scratchId = UUID.fromString("00000000-0000-0000-0000-000000000000")
        println(scratchId)
    }
}
