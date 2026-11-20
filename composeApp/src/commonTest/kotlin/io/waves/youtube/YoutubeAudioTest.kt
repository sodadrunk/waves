package io.waves.youtube

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.waves.player.LocatedMedia
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class YoutubeAudioTest {
    @Test
    fun picksTheHighestBitrateAacAndSkipsVideoAndOpus() {
        val choice = YoutubeAudio.interpret(
            """
            {
              "playabilityStatus": {"status": "OK"},
              "streamingData": {
                "formats": [{"itag": 18, "mimeType": "video/mp4", "bitrate": 500000, "url": "https://rr.googlevideo.com/videoplayback?id=v"}],
                "adaptiveFormats": [
                  {"itag": 251, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000, "url": "https://rr.googlevideo.com/videoplayback?id=opus"},
                  {"itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "https://rr.googlevideo.com/videoplayback?id=aac"},
                  {"itag": 139, "mimeType": "audio/mp4; codecs=\"mp4a.40.5\"", "bitrate": 48000, "url": "https://rr.googlevideo.com/videoplayback?id=low"},
                  {"itag": 141, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 256000, "signatureCipher": "s=abc"}
                ]
              }
            }
            """.trimIndent(),
        )
        val stream = assertIs<AudioChoice.Stream>(choice)
        assertEquals("https://rr.googlevideo.com/videoplayback?id=aac", stream.url)
    }

    @Test
    fun signInWallIsAPlaybackError() {
        val choice = YoutubeAudio.interpret("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}""")
        val unavailable = assertIs<AudioChoice.Unavailable>(choice)
        assertEquals("LOGIN_REQUIRED", unavailable.status)
        assertTrue(YoutubeAudio.audioFailureMessage(unavailable).contains("sign-in"))
        assertTrue(YoutubeAudio.audioFailureMessage(unavailable).contains("VPN"))
    }

    @Test
    fun okWithoutAacExplainsThat() {
        val choice = YoutubeAudio.interpret(
            """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/webm","bitrate":1,"url":"https://rr.googlevideo.com/a"}]}}""",
        )
        val unavailable = assertIs<AudioChoice.Unavailable>(choice)
        assertEquals("This video has no audio stream that can be played here.", YoutubeAudio.audioFailureMessage(unavailable))
    }

    @Test
    fun locatorFallsBackInOrderWhenYouTubeAsksForSignIn() = runTest {
        val tried = mutableListOf<String>()
        val locator = YoutubeMediaLocator(
            signInWall(),
            fallbacks = listOf(
                YoutubeAudioSource { tried += "absent"; null },
                YoutubeAudioSource { tried += "broken"; throw YoutubeException("yt-dlp: nope.") },
                YoutubeAudioSource { tried += "works"; "https://stream.example/a.m4a" },
                YoutubeAudioSource { tried += "never"; "https://unused" },
            ),
        )
        assertEquals("https://stream.example/a.m4a", locator.locate(YoutubeUrls.watchUrl(VIDEO)))
        assertEquals(listOf("absent", "broken", "works"), tried)
        assertEquals("https://feeds.example/ep.mp3", locator.locate("https://feeds.example/ep.mp3"))
    }

    @Test
    fun failureCombinesTheBuiltInAndFallbackMessages() = runTest {
        val unset = YoutubeMediaLocator(signInWall(), listOf(YoutubeAudioSource { null }))
        val e1 = assertFailsWith<YoutubeException> { unset.locate(YoutubeUrls.watchUrl(VIDEO)) }
        assertTrue(e1.message!!.contains("sign-in check"), e1.message)

        val failing = YoutubeMediaLocator(
            signInWall(),
            listOf(YoutubeAudioSource { throw YoutubeException("yt-dlp: Sign in to confirm you’re not a bot.") }),
        )
        val e2 = assertFailsWith<YoutubeException> { failing.locate(YoutubeUrls.watchUrl(VIDEO)) }
        assertTrue(e2.message!!.endsWith("yt-dlp: Sign in to confirm you’re not a bot."), e2.message)
    }

    @Test
    fun aStreamOnAirIsItsHlsListNotItsAudioFormats() {
        // A live stream's audio formats carry addresses too, but of pieces, not of a file.
        val choice = YoutubeAudio.interpret(
            """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{"isLive":true},"streamingData":{
              "hlsManifestUrl":"https://manifest.googlevideo.com/master.m3u8",
              "adaptiveFormats":[{"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":144000,"url":"https://rr.googlevideo.com/videoplayback?id=pieces"}]}}
            """.trimIndent(),
        )
        assertEquals("https://manifest.googlevideo.com/master.m3u8", assertIs<AudioChoice.Live>(choice).manifestUrl)

        val noList = assertIs<AudioChoice.Unavailable>(
            YoutubeAudio.interpret("""{"playabilityStatus":{"status":"OK"},"videoDetails":{"isLive":true},"streamingData":{}}"""),
        )
        assertTrue(noList.live)
        assertEquals("This live stream can't be played as audio right now.", YoutubeAudio.audioFailureMessage(noList))
    }

    @Test
    fun aLiveStreamPlaysItsAudioAloneFromTheVisitorClientsList() = runTest {
        val locator = YoutubeMediaLocator(youtube(live = true))
        val media = locator.locateMedia(YoutubeUrls.watchUrl(VIDEO))
        assertTrue(media.live)
        assertEquals("https://manifest.googlevideo.com/audio-234.m3u8", media.url)
        assertEquals(listOf("/master-audio.m3u8"), mastersRead, "the list with the audio is all that's read")
        // Nothing of it to keep as a file.
        val e = assertFailsWith<YoutubeException> { locator.locate(YoutubeUrls.watchUrl(VIDEO)) }
        assertTrue(e.live)
        assertTrue(e.message!!.contains("live stream"), e.message)
    }

    @Test
    fun aFallbacksStreamOnAirIsLiveThoughYoutubeNeverSaidSo() = runTest {
        // Every built-in client is asked to sign in, so nothing says the video is live; the fallback's playlist does.
        val now = kotlinx.datetime.Instant.parse("2026-11-16T15:00:00Z").toEpochMilliseconds()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val path = request.url.encodedPath
                    val body = when {
                        path.endsWith("/onair/index.m3u8") -> "#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:2026-11-16T14:59:30.000+00:00\n#EXTINF:5.0,\nhttps://cdn.example/1\n#EXTINF:5.0,\nhttps://cdn.example/2\n"
                        path.endsWith("/ended/index.m3u8") -> "#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:2026-11-16T12:00:00.000+00:00\n#EXTINF:5.0,\nhttps://cdn.example/1\n"
                        else -> """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}"""
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        fun locator(found: String) = YoutubeMediaLocator(client, listOf(YoutubeAudioSource { found }), clock = { now })
        val watch = YoutubeUrls.watchUrl(VIDEO)
        assertTrue(locator("https://manifest.example/onair/index.m3u8").locateMedia(watch).live)
        assertTrue(!locator("https://manifest.example/ended/index.m3u8").locateMedia(watch).live)
        assertTrue(!locator("https://rr.example/videoplayback?id=aac").locateMedia(watch).live)
    }

    @Test
    fun aLiveStreamWithoutAudioOnItsOwnPlaysItsSmallestVideo() = runTest {
        val media = YoutubeMediaLocator(youtube(live = true, visitorId = false)).locateMedia(YoutubeUrls.watchUrl(VIDEO))
        assertTrue(media.live)
        assertEquals("https://manifest.googlevideo.com/video-91.m3u8", media.url)
    }

    @Test
    fun theVisitorClientGetsPastTheSignInWall() = runTest {
        val media = YoutubeMediaLocator(youtube(live = false)).locateMedia(YoutubeUrls.watchUrl(VIDEO))
        assertEquals(LocatedMedia("https://rr.googlevideo.com/videoplayback?id=aac"), media)
    }

    /**
     * YouTube as it answers on a walled network: strangers are asked to sign in (for a live stream, the first gets a
     * list of video only), a client with a visitor id gets the audio.
     */
    private val mastersRead = mutableListOf<String>()

    private fun youtube(live: Boolean, visitorId: Boolean = true) = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val body = (request.body as? io.ktor.http.content.TextContent)?.text
                val path = request.url.encodedPath
                if (path.endsWith(".m3u8")) mastersRead += path
                val json = when {
                    path.endsWith("/visitor_id") -> if (visitorId) """{"responseContext":{"visitorData":"VISITOR"}}""" else "{}"
                    path.endsWith("/player") && live && "VISITOR" in body.orEmpty() ->
                        """{"playabilityStatus":{"status":"OK"},"videoDetails":{"isLive":true},"streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/master-audio.m3u8"}}"""
                    path.endsWith("/player") && live && "ANDROID_VR" in body.orEmpty() ->
                        """{"playabilityStatus":{"status":"OK"},"videoDetails":{"isLive":true},"streamingData":{"hlsManifestUrl":"https://manifest.googlevideo.com/master-video.m3u8"}}"""
                    path.endsWith("/player") && "VISITOR" in body.orEmpty() ->
                        """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/mp4","bitrate":1,"url":"https://rr.googlevideo.com/videoplayback?id=aac"}]}}"""
                    path.endsWith("/player") -> """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}"""
                    path.endsWith("/master-audio.m3u8") -> """
                        #EXTM3U
                        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/audio-233.m3u8",TYPE=AUDIO,GROUP-ID="233"
                        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/audio-234.m3u8",TYPE=AUDIO,GROUP-ID="234"
                        #EXT-X-STREAM-INF:BANDWIDTH=269034,CODECS="avc1.42C00B,mp4a.40.5",AUDIO="233"
                        https://manifest.googlevideo.com/video-269.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=962461,CODECS="avc1.4D401E,mp4a.40.2",AUDIO="234"
                        https://manifest.googlevideo.com/video-230.m3u8
                    """.trimIndent()
                    path.endsWith("/master-video.m3u8") -> """
                        #EXTM3U
                        #EXT-X-STREAM-INF:BANDWIDTH=962461,CODECS="mp4a.40.2,avc1.4D401E"
                        https://manifest.googlevideo.com/video-93.m3u8
                        #EXT-X-STREAM-INF:BANDWIDTH=269034,CODECS="mp4a.40.5,avc1.42C00B"
                        https://manifest.googlevideo.com/video-91.m3u8
                    """.trimIndent()
                    else -> error("unexpected request $path")
                }
                respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
    }

    private fun signInWall() = HttpClient(MockEngine) {
        engine {
            addHandler {
                respond(
                    """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you’re not a bot"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
    }

    private companion object {
        const val VIDEO = "sampleVid01"
    }

    @Test
    fun playsTheOriginalAudioNotADub() {
        val json = """
            {
              "playabilityStatus": {"status": "OK"},
              "streamingData": {
                "adaptiveFormats": [
                  {"itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 130000,
                   "url": "https://rr.googlevideo.com/videoplayback?id=fr&xtags=acont%3Ddubbed-auto",
                   "audioTrack": {"displayName": "French (France)", "id": "fr.3", "audioIsDefault": true}},
                  {"itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 120000,
                   "url": "https://rr.googlevideo.com/videoplayback?id=en&xtags=acont%3Doriginal",
                   "audioTrack": {"displayName": "English (United States) original", "id": "en-US.4", "audioIsDefault": false}}
                ]
              }
            }
        """.trimIndent()
        assertEquals(
            "https://rr.googlevideo.com/videoplayback?id=en&xtags=acont%3Doriginal",
            assertIs<AudioChoice.Stream>(YoutubeAudio.interpret(json)).url,
        )
    }
}
