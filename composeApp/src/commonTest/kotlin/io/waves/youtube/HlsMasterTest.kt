package io.waves.youtube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HlsMasterTest {
    private val url = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/x/file/index.m3u8"

    /** What YouTube's visitor client lists for a live stream: audio on its own, twice, and video that points at it. */
    private val withAudio = """
        #EXTM3U
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/playlist/itag/233/index.m3u8",TYPE=AUDIO,GROUP-ID="233",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/playlist/itag/234/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/playlist/itag/387/index.m3u8",TYPE=SUBTITLES,GROUP-ID="vtt",NAME="English"
        #EXT-X-STREAM-INF:BANDWIDTH=507418,CODECS="avc1.4D4015,mp4a.40.5",RESOLUTION=426x240,AUDIO="233",SUBTITLES="vtt"
        https://manifest.googlevideo.com/playlist/itag/229/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=962461,CODECS="avc1.4D401E,mp4a.40.2",RESOLUTION=640x360,AUDIO="234",SUBTITLES="vtt"
        https://manifest.googlevideo.com/playlist/itag/230/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=269034,CODECS="avc1.42C00B,mp4a.40.5",RESOLUTION=256x144,AUDIO="233",SUBTITLES="vtt"
        https://manifest.googlevideo.com/playlist/itag/269/index.m3u8
    """.trimIndent()

    /** What the other clients list: only video with the sound in it. */
    private val videoOnly = """
        #EXTM3U
        #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/playlist/itag/387/index.m3u8",TYPE=SUBTITLES,GROUP-ID="vtt",NAME="English"
        #EXT-X-STREAM-INF:BANDWIDTH=962461,CODECS="mp4a.40.2,avc1.4D401E",RESOLUTION=640x360,SUBTITLES="vtt"
        https://manifest.googlevideo.com/playlist/itag/93/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=269034,CODECS="mp4a.40.5,avc1.42C00B",RESOLUTION=256x144,SUBTITLES="vtt"
        https://manifest.googlevideo.com/playlist/itag/91/index.m3u8
    """.trimIndent()

    @Test
    fun theBetterAudioOnlyRenditionIsPicked() {
        assertEquals("https://manifest.googlevideo.com/playlist/itag/234/index.m3u8", HlsMaster.audioOnly(withAudio, url))
    }

    @Test
    fun aListWithoutAudioOnItsOwnHasNoneToPick() {
        assertNull(HlsMaster.audioOnly(videoOnly, url))
    }

    @Test
    fun theSmallestVideoIsTheOneThatCostsLeast() {
        assertEquals("https://manifest.googlevideo.com/playlist/itag/91/index.m3u8", HlsMaster.lowestVariant(videoOnly, url))
        assertNull(HlsMaster.lowestVariant("#EXTM3U\n", url))
    }

    @Test
    fun aPlaylistWhoseNewestPieceIsFromJustNowIsOnAir() {
        val start = kotlinx.datetime.Instant.parse("2026-11-16T14:27:20.018+00:00").toEpochMilliseconds()
        fun playlist(closed: Boolean = false, dated: Boolean = true) = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:5")
            if (dated) appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-16T14:27:20.018+00:00")
            repeat(720) { appendLine("#EXTINF:5.0,"); appendLine("https://cdn.example/piece/$it") }
            if (closed) appendLine("#EXT-X-ENDLIST")
        }
        val end = start + 3_600_000
        assertEquals(true, HlsMaster.isOnAir(playlist(), end + 10_000))
        assertEquals(false, HlsMaster.isOnAir(playlist(), end + 20 * 60_000), "its pieces stopped a while ago: it has ended")
        assertEquals(false, HlsMaster.isOnAir(playlist(closed = true), end + 10_000))
        assertEquals(false, HlsMaster.isOnAir(playlist(dated = false), end + 10_000))
    }

    @Test
    fun addressesWrittenRelativeToTheListAreCompleted() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:URI="audio/index.m3u8",TYPE=AUDIO,GROUP-ID="a"
            #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="a"
            /video/index.m3u8
        """.trimIndent()
        assertEquals("https://manifest.googlevideo.com/api/manifest/hls_variant/id/x/file/audio/index.m3u8", HlsMaster.audioOnly(master, "$url?x=1"))
        assertEquals("https://manifest.googlevideo.com/video/index.m3u8", HlsMaster.lowestVariant(master, url))
    }

    @Test
    fun picksTheOriginalAudioOfAStreamWithADub() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Español",LANGUAGE="es",DEFAULT=YES,URI="https://m.example/es.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="English original",LANGUAGE="en",DEFAULT=NO,URI="https://m.example/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=200000,CODECS="mp4a.40.2",AUDIO="a"
            https://m.example/v.m3u8
        """.trimIndent()
        assertEquals("https://m.example/en.m3u8", HlsMaster.audioOnly(master, "https://m.example/master.m3u8"))
    }

    @Test
    fun aacLcBeatsALowerTrackMarkedDefault() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="233",NAME="Default",DEFAULT=YES,URI="https://m.example/233.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="234",NAME="Default",DEFAULT=NO,URI="https://m.example/234.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=100000,CODECS="mp4a.40.5",AUDIO="233"
            https://m.example/v1.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=200000,CODECS="mp4a.40.2",AUDIO="234"
            https://m.example/v2.m3u8
        """.trimIndent()
        assertEquals("https://m.example/234.m3u8", HlsMaster.audioOnly(master, "https://m.example/master.m3u8"))
    }
}
