package io.waves.player

import io.waves.model.Episode
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Manual smoke test against real podcast CDNs; skipped unless WAVES_PLAYER_SMOKE=1:
 *
 *   WAVES_PLAYER_SMOKE=1 ./gradlew :composeApp:desktopTest --tests '*DesktopPlayerSmokeTest*' --rerun -i
 *
 * Optional: WAVES_PLAYER_SMOKE_URL=<enclosure url> to try a specific episode,
 * WAVES_PLAYER_SMOKE_AWT=1 to boot AWT first (like the Compose app does) before JavaFX.
 *
 * A YouTube stream that is on air, found the way the app finds it and played:
 *
 *   WAVES_LIVE_SMOKE_URL=<watch url> ./gradlew :composeApp:desktopTest --tests '*DesktopPlayerSmokeTest*' --rerun -i
 */
class DesktopPlayerSmokeTest {
    private val urls = System.getenv("WAVES_PLAYER_SMOKE_URL")?.let { listOf(it) } ?: listOf(
        // MP3 behind a swap.fm -> spotify prefix -> podtrac -> simplecast redirect chain (NPR Planet Money).
        "https://tracking.swap.fm/track/XvDEoI11TR00olTUO8US/prfx.byspotify.com/e/play.podtrac.com/npr-510289/npr.simplecastaudio.com/43b5acee-463e-4612-95ad-d2596d9dd337/episodes/080b75be-92f2-4af7-b874-89008175bcd2/audio/128/default.mp3",
        // AAC/M4A behind an anchor.fm redirect.
        "https://anchor.fm/s/8831168/podcast/play/127600778/https%3A%2F%2Fd3ctxlq1ktw2nl.cloudfront.net%2Fstaging%2F2026-9-17%2F434341789-44100-2-bc3bd56b2b4b5.m4a",
    )

    @Test
    fun streamsSeeksAndAdvances() {
        if (System.getenv("WAVES_PLAYER_SMOKE") != "1") return
        useEnvProxy()
        // Native-lib extraction dir; defaults to ~/.openjfx/cache, which may be read-only in sandboxes.
        System.getenv("WAVES_PLAYER_SMOKE_FXCACHE")?.let { System.setProperty("javafx.cachedir", it) }
        if (System.getenv("WAVES_PLAYER_SMOKE_AWT") == "1") {
            java.awt.EventQueue.invokeAndWait { java.awt.Toolkit.getDefaultToolkit() }
        }
        for (url in urls) smoke(url)
    }

    /** Honors HTTPS_PROXY (with credentials) in sandboxed/CI environments; the JDK ignores it by default. */
    private fun useEnvProxy() {
        val proxy = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: return
        val uri = java.net.URI(proxy)
        for (scheme in listOf("http", "https")) {
            System.setProperty("$scheme.proxyHost", uri.host)
            System.setProperty("$scheme.proxyPort", uri.port.toString())
        }
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1")
        val userInfo = uri.userInfo ?: return
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
        System.setProperty("jdk.http.auth.proxying.disabledSchemes", "")
        val (user, pass) = userInfo.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication() =
                if (requestorType == RequestorType.PROXY) java.net.PasswordAuthentication(user, pass.toCharArray()) else null
        })
    }

    private fun smoke(url: String) {
        println("=== $url")
        println("resolved -> ${RedirectResolver.resolve(url)}")
        val player = DesktopAudioPlayer()
        val episode = Episode(guid = "smoke", podcastFeedUrl = "https://example.com/feed", title = "Smoke", audioUrl = url)
        try {
            player.play(episode)
            val firstPos = awaitPosition(player, minMs = 500, timeoutMs = 20_000)
            sample(player, seconds = 5)
            val beforeSeek = player.state.value.positionMs
            assertTrue(beforeSeek > firstPos, "position should advance: $firstPos -> $beforeSeek")

            player.setSpeed(1.5f)
            player.seekTo(60_000)
            Thread.sleep(500)
            sample(player, seconds = 3)
            val afterSeek = player.state.value.positionMs
            assertTrue(afterSeek in 60_500..70_000, "after seek to 60s position should be ~61-65s, was $afterSeek")

            player.pause()
            Thread.sleep(700)
            val paused = player.state.value.positionMs
            Thread.sleep(1_000)
            println("paused at $paused -> ${player.state.value.positionMs}, state=${player.state.value}")
            assertTrue(player.state.value.positionMs - paused < 300, "position must not advance while paused")
            assertNull(player.state.value.error)
        } finally {
            player.release()
        }
    }

    @Test
    fun playsAYoutubeStreamOnAir() {
        val url = System.getenv("WAVES_LIVE_SMOKE_URL") ?: return
        useEnvProxy()
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.java.Java) { expectSuccess = false }
        val locator = io.waves.youtube.YoutubeMediaLocator(http)
        // Found the way a pasted link to it is: on its channel's Live tab.
        val found = kotlinx.coroutines.runBlocking { io.waves.youtube.YoutubeChannels(http).liveStream(url) }
        println("live stream -> ${found?.podcast?.title}: ${found?.episode?.title}")
        assertTrue(found != null, "the link's stream is listed as on air on its channel")
        val player = DesktopAudioPlayer { locator.locateMedia(it).also { media -> println("located -> $media") } }
        val episode = Episode(guid = "live", podcastFeedUrl = "https://example.com/feed", title = "Live", audioUrl = url)
        fun awaitSound(what: String) {
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                val s = player.state.value
                check(s.error == null) { "playback error: ${s.error}" }
                if (s.isPlaying && !s.isBuffering && s.isLive) return println("$what: $s")
                Thread.sleep(100)
            }
            error("$what: the stream did not start within 30s: ${player.state.value}")
        }
        try {
            player.play(episode, startPositionMs = 123_000) // a start position means nothing to it
            awaitSound("playing")
            sample(player, seconds = 8)
            val onAir = player.state.value
            assertTrue(PlayerMath.isAtLiveEdge(onAir.positionMs, onAir.durationMs), "starts on air: $onAir")

            // Rewound ten minutes, it plays from there on.
            val back = onAir.durationMs - 600_000
            player.seekTo(back)
            Thread.sleep(300)
            awaitSound("rewound")
            sample(player, seconds = 8)
            val rewound = player.state.value.positionMs
            assertTrue(rewound in back - 10_000..back + 20_000, "about ten minutes back ($back), was $rewound")

            // Paused, it resumes where it was, not on air.
            player.pause()
            Thread.sleep(8_000)
            assertTrue(!player.state.value.isPlaying)
            player.resume()
            Thread.sleep(300)
            awaitSound("playing again")
            sample(player, seconds = 5)
            val resumed = player.state.value
            assertTrue(resumed.durationMs - resumed.positionMs in 560_000..640_000, "still about ten minutes behind: $resumed")

            // Sought to the end, it's back on air.
            player.seekTo(Long.MAX_VALUE)
            Thread.sleep(300)
            awaitSound("on air again")
            sample(player, seconds = 5)
            assertTrue(player.state.value.let { PlayerMath.isAtLiveEdge(it.positionMs, it.durationMs) }, "${player.state.value}")

            // Three quick skips back from on air add up, each counted from the last.
            val before = player.state.value
            repeat(3) {
                player.seekTo(player.state.value.positionMs - 15_000)
                Thread.sleep(150)
            }
            Thread.sleep(300)
            awaitSound("skipped back three times")
            sample(player, seconds = 5)
            val skipped = player.state.value
            val behind = (before.durationMs - before.positionMs).let { skipped.durationMs - skipped.positionMs - it }
            assertTrue(behind in 35_000..60_000, "about 45 s further back, was $behind ms: $skipped")
            assertNull(player.state.value.error)
        } finally {
            player.release()
            http.close()
        }
    }

    private fun awaitPosition(player: AudioPlayer, minMs: Long, timeoutMs: Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = player.state.value
            check(s.error == null) { "playback error: ${s.error}" }
            if (s.positionMs >= minMs && !s.isBuffering) return s.positionMs
            Thread.sleep(100)
        }
        error("playback did not start within ${timeoutMs}ms: ${player.state.value}")
    }

    private fun sample(player: AudioPlayer, seconds: Int) = repeat(seconds * 2) {
        val s = player.state.value
        println("  pos=${s.positionMs}ms dur=${s.durationMs}ms playing=${s.isPlaying} buffering=${s.isBuffering} speed=${s.speed}")
        Thread.sleep(500)
    }
}
