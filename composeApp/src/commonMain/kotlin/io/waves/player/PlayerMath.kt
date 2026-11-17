package io.waves.player

import io.waves.model.Episode

/** Small pure helpers shared by the platform players (kept here so they are unit-testable). */
internal object PlayerMath {

    /**
     * Picks a usable duration: the engine's value when known (> 0), otherwise the feed's
     * `itunes:duration` hint, otherwise 0 (unknown).
     */
    fun durationMs(engineDurationMs: Long?, episode: Episode?): Long = when {
        engineDurationMs != null && engineDurationMs > 0 -> engineDurationMs
        episode?.durationSeconds != null && episode.durationSeconds > 0 -> episode.durationSeconds * 1000
        else -> 0
    }

    /** Clamps a seek target into [0, duration] (or just >= 0 when the duration is unknown). */
    fun clampSeek(positionMs: Long, durationMs: Long): Long {
        val nonNegative = positionMs.coerceAtLeast(0)
        return if (durationMs > 0) nonNegative.coerceAtMost(durationMs) else nonNegative
    }

    /**
     * Whether a live stream at [positionMs] of the [durationMs] it can be rewound over plays what's on air: players
     * keep some seconds behind the newest audio, so anywhere in the last [LIVE_EDGE_SLACK_MS] counts. A seek there
     * goes to what's on air.
     */
    fun isAtLiveEdge(positionMs: Long, durationMs: Long): Boolean = durationMs <= 0 || durationMs - positionMs <= LIVE_EDGE_SLACK_MS

    /**
     * Whether a seek to [targetMs] in a live stream playing at [positionMs] means "go on air": one that lands at the
     * live edge without going back from where it plays. A skip back from on air lands there too (players keep some
     * seconds behind), and that one is a rewind.
     */
    fun seeksOnAir(targetMs: Long, positionMs: Long, durationMs: Long): Boolean =
        isAtLiveEdge(targetMs, durationMs) && targetMs >= positionMs

    const val LIVE_EDGE_SLACK_MS = 30_000L

    fun clampSpeed(speed: Float): Float =
        if (speed.isNaN()) 1f else speed.coerceIn(AudioPlayer.MIN_SPEED, AudioPlayer.MAX_SPEED)

    /** Artist line for media notifications when the podcast title wasn't supplied: the feed's host. */
    fun fallbackArtist(feedUrl: String): String =
        feedUrl.substringAfter("://", feedUrl).substringBefore('/').substringBefore(':').removePrefix("www.")
}
