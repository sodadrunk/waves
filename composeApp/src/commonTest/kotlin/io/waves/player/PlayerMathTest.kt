package io.waves.player

import io.waves.model.Episode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class PlayerMathTest {
    private val episode = Episode(guid = "g", podcastFeedUrl = "https://www.example.com:443/feed.xml", title = "t", audioUrl = "u", durationSeconds = 60)

    @Test
    fun aSeekGoesOnAirOnlyWhenItLandsAtTheLiveEdgeWithoutGoingBack() {
        val hour = 3_600_000L
        val onAir = hour - 15_000 // players keep some seconds behind the newest audio
        assertTrue(PlayerMath.seeksOnAir(Long.MAX_VALUE.coerceAtMost(hour), onAir, hour), "the LIVE mark, a drag to the end")
        assertTrue(PlayerMath.seeksOnAir(onAir + 30_000, onAir, hour), "a skip forward on air")
        assertFalse(PlayerMath.seeksOnAir(onAir - 15_000, onAir, hour), "a skip back on air is a rewind, though it lands near the end")
        assertFalse(PlayerMath.seeksOnAir(hour / 2, hour / 4, hour))
        assertTrue(PlayerMath.seeksOnAir(hour - 10_000, hour / 2, hour), "forward to the end from far back")
    }

    @Test
    fun durationPrefersEngineThenFeedHint() {
        assertEquals(1234, PlayerMath.durationMs(1234, episode))
        assertEquals(60_000, PlayerMath.durationMs(null, episode))
        assertEquals(60_000, PlayerMath.durationMs(-1, episode))
        assertEquals(0, PlayerMath.durationMs(null, episode.copy(durationSeconds = null)))
        assertEquals(0, PlayerMath.durationMs(0, null))
    }

    @Test
    fun clampSeek() {
        assertEquals(0, PlayerMath.clampSeek(-5, 1000))
        assertEquals(1000, PlayerMath.clampSeek(5000, 1000))
        assertEquals(500, PlayerMath.clampSeek(500, 1000))
        assertEquals(5000, PlayerMath.clampSeek(5000, 0)) // unknown duration: no upper bound
    }

    @Test
    fun clampSpeed() {
        assertEquals(1f, PlayerMath.clampSpeed(Float.NaN))
        assertEquals(AudioPlayer.MIN_SPEED, PlayerMath.clampSpeed(0.1f))
        assertEquals(AudioPlayer.MAX_SPEED, PlayerMath.clampSpeed(10f))
        assertEquals(1.5f, PlayerMath.clampSpeed(1.5f))
    }

    @Test
    fun fallbackArtistIsFeedHost() {
        assertEquals("example.com", PlayerMath.fallbackArtist(episode.podcastFeedUrl))
        assertEquals("feeds.example.org", PlayerMath.fallbackArtist("https://feeds.example.org/123456/podcast.xml"))
    }
}
