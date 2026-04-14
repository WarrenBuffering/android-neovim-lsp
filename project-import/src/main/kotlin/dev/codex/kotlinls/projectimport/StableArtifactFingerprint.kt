package dev.codex.kotlinls.projectimport

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

object StableArtifactFingerprint {
    private const val fullHashLimitBytes = 2L * 1024L * 1024L
    private const val sampleWindowBytes = 64 * 1024

    fun fingerprint(path: Path): String {
        val normalized = path.normalize()
        if (!Files.exists(normalized)) return "missing"
        val size = runCatching { Files.size(normalized) }.getOrDefault(0L)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(size.toString().toByteArray())
        digest.update(0)
        if (size <= fullHashLimitBytes) {
            Files.newInputStream(normalized).use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } else {
            Files.newByteChannel(normalized, StandardOpenOption.READ).use { channel ->
                updateWindowDigest(channel, digest, start = 0L, byteCount = sampleWindowBytes)
                updateWindowDigest(
                    channel,
                    digest,
                    start = (size - sampleWindowBytes).coerceAtLeast(0L),
                    byteCount = sampleWindowBytes,
                )
            }
        }
        return buildString {
            append(size)
            append(':')
            append(digest.digest().joinToString("") { byte -> "%02x".format(byte) })
        }
    }

    private fun updateWindowDigest(
        channel: SeekableByteChannel,
        digest: MessageDigest,
        start: Long,
        byteCount: Int,
    ) {
        if (byteCount <= 0) return
        channel.position(start)
        val buffer = ByteBuffer.allocate(minOf(byteCount, 8 * 1024))
        var remaining = byteCount.toLong()
        while (remaining > 0) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
            val read = channel.read(buffer)
            if (read <= 0) break
            digest.update(buffer.array(), 0, read)
            remaining -= read.toLong()
        }
    }
}
