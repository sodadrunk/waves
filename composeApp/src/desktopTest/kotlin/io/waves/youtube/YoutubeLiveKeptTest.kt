package io.waves.youtube

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.waves.net.ResponseCache
import io.waves.player.LocatedMedia
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** What's kept of YouTube's answers about live streams ([ResponseCache]), which end without notice. */
class YoutubeLiveKeptTest {
    private lateinit var dir: File
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val channel = "UCexampleChannel00000000"

    @BeforeTest fun setUp() = runBlocking<Unit> {
        dir = Files.createTempDirectory("responses").toFile()
        ResponseCache.install(dir.path)
    }

    @AfterTest fun tearDown() {
        ResponseCache.uninstall()
        dir.deleteRecursively()
    }

    @Test fun aLiveStreamsAddressIsAskedForAgainAfterAFewMinutesAndARecordingsIsNot() = runBlocking<Unit> {
        var live = true
        var playerCalls = 0
        val client = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val body = when {
                path.endsWith("/player") -> {
                    playerCalls++
                    if (live) {
                        """{"playabilityStatus":{"status":"OK"},"videoDetails":{"isLive":true},"streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/master.m3u8"}}"""
                    } else {
                        """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","bitrate":1,"url":"https://rr.googlevideo.com/videoplayback?id=recording"}]}}"""
                    }
                }
                path.endsWith("/visitor_id") -> "{}"
                else -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://manifest.googlevideo.com/video.m3u8\n"
            }
            respond(body, HttpStatusCode.OK, json)
        })
        var now = 1_000_000L
        val onAir = LocatedMedia("https://manifest.googlevideo.com/video.m3u8", live = true)
        assertEquals(onAir, YoutubeAudio.media(client, "sampleVid01") { now })
        now += 60_000
        assertEquals(onAir, YoutubeAudio.media(client, "sampleVid01") { now })
        assertEquals(1, playerCalls, "reused within a couple of minutes")

        // The stream has ended: asked again, the video is a recording, and that is kept as any recording's is.
        live = false
        now += 2 * 60_000
        val recording = LocatedMedia("https://rr.googlevideo.com/videoplayback?id=recording")
        assertEquals(recording, YoutubeAudio.media(client, "sampleVid01") { now })
        assertEquals(2, playerCalls)
        now += 30 * 60_000
        assertEquals(recording, YoutubeAudio.media(client, "sampleVid01") { now })
        assertEquals(2, playerCalls)
    }

    @Test fun aFailedLookAtWhatsOnAirIsAFailureNotTheLastLooksAnswer() = runBlocking<Unit> {
        var down = false
        val tab = """
            {"contents":[{"lockupViewModel":{"contentId":"liveNow0001","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"News, live"}}},
              "badge":{"thumbnailBadgeViewModel":{"text":"LIVE","badgeStyle":"THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE"}}}}]}
        """.trimIndent()
        val channels = YoutubeChannels(HttpClient(MockEngine {
            if (down) respond("down", HttpStatusCode.InternalServerError) else respond(tab, HttpStatusCode.OK, json)
        }))
        assertEquals(listOf("liveNow0001"), channels.liveNow(channel).map { it.guid })
        // Kept for a couple of minutes, so still answered while YouTube is down...
        down = true
        assertEquals(listOf("liveNow0001"), channels.liveNow(channel).map { it.guid })
        // ...but once that's stale (a new start drops it from memory; on disk it is made old), not from the kept answer.
        ResponseCache.uninstall()
        dir.listFiles()!!.forEach { file ->
            val lines = file.readLines().toMutableList()
            lines[1] = (lines[1].toLong() - 10 * 60_000).toString()
            file.writeText(lines.joinToString("\n"))
        }
        ResponseCache.install(dir.path)
        assertFailsWith<YoutubeException> { channels.liveNow(channel) }
    }
}
