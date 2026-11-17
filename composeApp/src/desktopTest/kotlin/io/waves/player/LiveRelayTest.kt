package io.waves.player

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A live stream's playlist through the relay: a few pieces ending where the player should play, on air or rewound. */
class LiveRelayTest {
    private var now = 0L
    /** The newest piece on air: the stream's playlist holds the 720 (an hour) up to it. */
    private var newest = 1_000L
    private var ended = false
    private var fetches = 0

    private fun upstream(): String = buildString {
        fetches++
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:5")
        appendLine("#EXT-X-MEDIA-SEQUENCE:${newest - 719}")
        appendLine("#EXT-X-PROGRAM-DATE-TIME:2026-11-16T14:27:20.018+00:00")
        for (n in newest - 719..newest) {
            if (n == 990L) appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXTINF:5.0,")
            appendLine("https://cdn.example/piece/$n")
        }
        if (ended) appendLine("#EXT-X-ENDLIST")
    }

    private fun session() = LiveRelay.open("https://cdn.example/live.m3u8", fetch = { upstream() }, clock = { now })

    private fun get(url: String): String =
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofString()).body()

    private fun pieces(playlist: String): List<Long> =
        playlist.lines().filter { it.startsWith("https://") }.map { it.substringAfterLast('/').toLong() }

    @Test
    fun onAirThePlayerGetsTheNewestFewPiecesAndFollowsTheStream() {
        val session = session()
        val url = session.urlAt(null)
        val first = get(url)
        assertEquals((995L..1000L).toList(), pieces(first))
        assertTrue("#EXT-X-MEDIA-SEQUENCE:995" in first, first)
        assertTrue("#EXT-X-PROGRAM-DATE-TIME" !in first)

        // Asked again before a piece's time has passed, the stream isn't read again.
        val before = fetches
        now += 2_000
        get(url)
        assertEquals(before, fetches)

        now += 5_000
        newest = 1_001
        assertEquals((996L..1001L).toList(), pieces(get(url)))
    }

    @Test
    fun rewoundThePlayerGetsThePiecesEndingJustPastThatPointAndStaysAsFarBehind() {
        val session = session()
        val window = session.window()
        assertEquals(LiveRelay.Window(281 * 5_000L, 1_001 * 5_000L), window)

        // Ten minutes before the end: piece 881. The player starts two pieces before the end of its list.
        val url = session.urlAt(window.endMs - 600_000)
        assertEquals((878L..883L).toList(), pieces(get(url)))
        // Its clock reads zero at the first piece it was given.
        assertEquals(878 * 5_000L + 12_000, session.timeOf(url, 12_000))

        now += 10_000
        newest = 1_002
        assertEquals((880L..885L).toList(), pieces(get(url)))
        assertEquals(878 * 5_000L + 22_000, session.timeOf(url, 22_000), "still counted from the first list")
    }

    @Test
    fun aBreakInTheStreamIsPassedOnWithItsPiece() {
        val url = session().urlAt(990 * 5_000L)
        val playlist = get(url)
        assertEquals((987L..992L).toList(), pieces(playlist))
        assertEquals("#EXT-X-DISCONTINUITY", playlist.lines().let { it[it.indexOf("https://cdn.example/piece/990") - 2] })
    }

    @Test
    fun aTimeBeforeWhatTheStreamHoldsStartsAtItsOldest() {
        val url = session().urlAt(0)
        assertEquals((281L..283L).toList(), pieces(get(url)))
    }

    @Test
    fun onceTheStreamEndsARewoundPlayerCatchesUpAndIsToldItsOver() {
        val session = session()
        val url = session.urlAt(session.window().endMs - 60_000) // piece 989 → list ends at 991
        assertEquals(991L, pieces(get(url)).last())
        ended = true
        now += 6_000
        val after = get(url)
        assertEquals(991L, pieces(after).last())
        assertTrue("#EXT-X-ENDLIST" !in after)
        now += 45_000
        val caughtUp = get(url)
        assertEquals(1_000L, pieces(caughtUp).last())
        assertTrue("#EXT-X-ENDLIST" in caughtUp)
    }

    @Test
    fun aSlowReadingOfTheStreamDoesntHoldUpWhereItPlays() {
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var slow = false
        val session = LiveRelay.open(
            "https://cdn.example/live.m3u8",
            fetch = {
                if (slow) {
                    reading.countDown()
                    release.await()
                }
                upstream()
            },
            clock = { now },
        )
        val url = session.urlAt(null)
        get(url)
        slow = true
        now += 6_000
        val asking = Thread { get(url) }.apply { start() }
        assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // The player's request is waiting on the stream; the UI's questions are answered from what's held.
        val answered = java.util.concurrent.CompletableFuture.supplyAsync { session.window() to session.timeOf(url, 1_000) }
        val (window, time) = answered.get(2, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(1_001 * 5_000L + 6_000, window.endMs)
        assertEquals(995 * 5_000L + 1_000, time)
        release.countDown()
        asking.join(5_000)
    }

    @Test
    fun aClosedSessionsAddressesAreGone() {
        val session = session()
        val url = session.urlAt(null)
        session.close()
        assertNull(session.timeOf(url, 0))
        val status = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.discarding()).statusCode()
        assertEquals(404, status)
    }
}
