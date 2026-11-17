package io.waves.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * Plays a live stream's HLS playlist through 127.0.0.1 so it can be rewound. JavaFX plays a live playlist from its
 * end and won't seek in it, so to play from further back it is handed a playlist that ends there: the relay serves
 * the last few pieces up to a point that stays as far behind what's on air as it was when asked for ([Session.urlAt]).
 * The pieces themselves are fetched straight from the stream's server.
 *
 * It also keeps the playlist small. YouTube's lists an hour of pieces, most of a megabyte, every few seconds; the
 * relay fetches that compressed and passes on a handful of lines.
 */
internal object LiveRelay {
    /** The stream times (ms, see [Playlist.timeOf]) that can be played: from the oldest piece kept to what's on air. */
    data class Window(val startMs: Long, val endMs: Long)

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val views = HashMap<String, View>()

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            executor = Executors.newCachedThreadPool { r -> Thread(r, "waves-live-relay").apply { isDaemon = true } }
            start()
        }
    }

    /** Reads the live playlist at [playlistUrl] (blocking); throws [IOException] when it can't be read or is empty. */
    fun open(playlistUrl: String, fetch: (String) -> String = ::fetch, clock: () -> Long = System::currentTimeMillis): Session =
        Session(playlistUrl, fetch, clock).also { it.refresh(force = true) }

    class Session internal constructor(
        private val playlistUrl: String,
        private val fetch: (String) -> String,
        private val clock: () -> Long,
    ) {
        private var playlist: Playlist? = null
        private var fetchedAt = 0L
        private val tokens = ArrayList<String>()
        private val reading = Any()

        /**
         * A loopback playlist that starts playing at stream time [startMs] (as near as a piece's start), or at what's
         * on air for null or a time too close to it. Each call makes a new address, as each is for a new player.
         */
        fun urlAt(startMs: Long?): String {
            val list = refresh()
            // The player starts a few pieces before the end of the list it's given.
            val end = if (startMs == null) list.last else (list.sequenceAt(startMs).coerceAtLeast(list.first) + START_BEFORE_END).coerceAtMost(list.last)
            val token = UUID.randomUUID().toString()
            synchronized(views) {
                views[token] = View(this, behind = list.last - end)
                tokens += token
            }
            return "http://127.0.0.1:${server.address.port}/$token.m3u8"
        }

        /**
         * What can be played now: the playlist last read (it is read again as the player asks for it), moved on by the
         * time since, as the stream has. Without that it would stand still and then jump a piece at each reading.
         */
        fun window(): Window = synchronized(this) {
            val list = playlist!!
            val since = if (list.ended) 0 else (clock() - fetchedAt).coerceIn(0, 2 * list.pieceMs)
            Window(list.timeOf(list.first) + since, list.timeOf(list.last + 1) + since)
        }

        /** The stream time the player of [url] is at when its own clock reads [playerMs]; null before it has started. */
        fun timeOf(url: String, playerMs: Long): Long? {
            val view = synchronized(views) { views[url.substringAfterLast('/').substringBefore('.')] } ?: return null
            val base = view.base ?: return null
            return synchronized(this) { playlist!! }.timeOf(base) + playerMs
        }

        internal fun now(): Long = clock()

        fun close() = synchronized(views) {
            tokens.forEach(views::remove)
            tokens.clear()
        }

        /**
         * The playlist, read again when the one held is older than a piece is long. One reading at a time, and not
         * under the session's own lock: [window] and [timeOf] are asked from the UI's threads, which a slow network
         * mustn't hold up.
         */
        internal fun refresh(force: Boolean = false): Playlist {
            fun fresh(): Playlist? = synchronized(this) {
                playlist?.takeIf { it.ended || clock() - fetchedAt < it.pieceMs }
            }
            if (!force) fresh()?.let { return it }
            synchronized(reading) {
                if (!force) fresh()?.let { return it } // read meanwhile by the one waited for
                val read = try {
                    Playlist.parse(fetch(playlistUrl)) ?: throw IOException("The live stream has nothing to play")
                } catch (e: IOException) {
                    // A failed re-read: the player asks again in a moment.
                    return synchronized(this) { playlist } ?: throw e
                }
                synchronized(this) {
                    playlist = read
                    fetchedAt = clock()
                }
                return read
            }
        }
    }

    /** One player's playlist: the pieces up to [behind] before the newest. */
    private class View(val session: Session, val behind: Long) {
        /** The first piece of the first playlist served: where the player's clock reads zero. */
        @Volatile var base: Long? = null
        private var endedAt: Long? = null

        fun render(): String {
            val list = session.refresh()
            // Once the stream has ended nothing newer comes to push this view on: it catches up on the end by itself.
            val caughtUp = if (list.ended) (session.now() - (endedAt ?: session.now().also { endedAt = it })) / list.pieceMs else 0
            val end = (list.last - (behind - caughtUp).coerceAtLeast(0)).coerceAtLeast(list.first)
            val start = (end - SERVED_PIECES + 1).coerceAtLeast(list.first)
            if (base == null) base = start
            return list.render(start, end)
        }
    }

    /** A media playlist's pieces: [first]..[last] by sequence number, each [pieceMs] long (YouTube's all are). */
    internal class Playlist(
        val first: Long,
        private val header: List<String>,
        /** Each piece's lines: its tags (length, a break in the stream, ...) and then its address. */
        private val pieces: List<List<String>>,
        val pieceMs: Long,
        val ended: Boolean,
    ) {
        val last: Long get() = first + pieces.size - 1

        /** The stream time a piece starts at: pieces are numbered from the stream's start, so it's their count before. */
        fun timeOf(sequence: Long): Long = sequence * pieceMs

        fun sequenceAt(timeMs: Long): Long = timeMs / pieceMs

        fun render(start: Long, end: Long): String = buildString {
            header.forEach { appendLine(it) }
            appendLine("#EXT-X-MEDIA-SEQUENCE:$start")
            for (sequence in start..end) pieces[(sequence - first).toInt()].forEach { appendLine(it) }
            if (ended && end == last) appendLine("#EXT-X-ENDLIST")
        }

        companion object {
            fun parse(text: String): Playlist? {
                val header = ArrayList<String>()
                val pieces = ArrayList<List<String>>()
                var pending = ArrayList<String>()
                var first = 0L
                var ended = false
                var pieceMs = 0L
                for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                    when {
                        line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> first = line.substringAfter(':').toLongOrNull() ?: 0
                        line == "#EXT-X-ENDLIST" -> ended = true
                        line.startsWith("#EXTM3U") || line.startsWith("#EXT-X-VERSION") ||
                            line.startsWith("#EXT-X-TARGETDURATION") || line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") -> header += line
                        // The times of ad breaks and the like, by date: nothing a player of sound needs, and a lot of lines.
                        line.startsWith("#EXT-X-DATERANGE") || line.startsWith("#EXT-X-PROGRAM-DATE-TIME") -> Unit
                        line.startsWith("#") -> {
                            if (line.startsWith("#EXTINF:") && pieceMs == 0L) {
                                pieceMs = ((line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0) * 1000).toLong()
                            }
                            pending += line
                        }
                        else -> {
                            pieces += pending + line
                            pending = ArrayList()
                        }
                    }
                }
                if (pieces.isEmpty() || pieceMs <= 0) return null
                return Playlist(first, header, pieces, pieceMs, ended)
            }
        }
    }

    private fun handle(exchange: HttpExchange) = exchange.use {
        val view = synchronized(views) { views[exchange.requestURI.path.trim('/').substringBefore('.')] }
        if (view == null) {
            exchange.sendResponseHeaders(404, -1)
            return@use
        }
        try {
            val body = view.render().toByteArray()
            exchange.responseHeaders["Content-Type"] = "application/vnd.apple.mpegurl"
            exchange.responseHeaders["Cache-Control"] = "no-cache"
            if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
                exchange.sendResponseHeaders(200, -1)
                return@use
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        } catch (_: IOException) {
            runCatching { exchange.sendResponseHeaders(502, -1) }
        }
    }

    /** The playlist's text, asked for compressed: an hour of YouTube's is 900 KB plain and 10 KB gzipped. */
    private fun fetch(url: String): String {
        val request = HttpRequest.newBuilder(URI(url)).header("Accept-Encoding", "gzip").timeout(Duration.ofSeconds(20)).build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: InterruptedException) {
            throw IOException(e)
        }
        response.body().use { body ->
            if (response.statusCode() !in 200..299) throw IOException("The live stream answered HTTP ${response.statusCode()}")
            val gzip = response.headers().firstValue("Content-Encoding").orElse("").equals("gzip", ignoreCase = true)
            return (if (gzip) GZIPInputStream(body) else body).readBytes().decodeToString()
        }
    }

    /** How many pieces a player is given at a time. */
    private const val SERVED_PIECES = 6

    /** JavaFX starts a live playlist this many pieces before its end. */
    private const val START_BEFORE_END = 2L
}
