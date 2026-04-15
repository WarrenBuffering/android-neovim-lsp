package demo.video.domain

/**
 * Loud accent colors meant to read well in a thumbnail or lower-third.
 */
enum class HighlightTone(
    val accentHex: String,
    val presenterLabel: String,
) {
    NEON_PINK("#ff38c7", "Neon Pink"),
    ACID_GREEN("#a7ff1a", "Acid Green"),
    LASER_BLUE("#1cf6ff", "Laser Blue"),
}
