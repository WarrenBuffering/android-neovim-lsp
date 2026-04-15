package demo.video.domain

import java.util.UUID

/**
 * Read-only feed API used for completion, navigation, rename, and implementation demos.
 */
interface VideoRepository {
    suspend fun featuredCards(): List<VideoCard>

    suspend fun cardById(id: UUID): VideoCard?
}
