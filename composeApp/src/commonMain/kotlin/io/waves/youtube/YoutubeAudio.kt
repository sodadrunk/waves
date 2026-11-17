package io.waves.youtube

import io.waves.net.ResponseCache
import io.waves.player.LocatedMedia
import io.ktor.client.HttpClient
import io.waves.currentTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Resolves a YouTube watch URL to a direct audio-only stream.
 *
 * The file is AAC in an MP4 container (the desktop player plays that, and so does ExoPlayer). Video
 * and WebM/Opus are skipped. Stream URLs expire, so this runs at play and download time; the library
 * keeps the watch URL.
 *
 * A stream on air right now has no file: it resolves to an HLS playlist of its audio ([LocatedMedia.live]), which
 * plays but can't be downloaded.
 *
 * YouTube sometimes answers the player call with a sign-in check instead of a stream (common on
 * datacenter and corporate networks). Then each of [fallbacks] is tried in order; when none can help it
 * becomes a normal playback error — the episode stays in the list like any other show.
 */
class YoutubeMediaLocator(
    private val client: HttpClient,
    private val fallbacks: List<YoutubeAudioSource> = emptyList(),
    private val clock: () -> Long = ::currentTimeMillis,
) {
    /** The audio to download or cache for [url]: a recording's. A stream still on air has none to keep, and throws. */
    suspend fun locate(url: String): String {
        val media = locateMedia(url)
        if (media.live) throw YoutubeException("A live stream can't be downloaded while it's on air.", live = true)
        return media.url
    }

    /** The audio to play for [url], a live stream's included. */
    suspend fun locateMedia(url: String): LocatedMedia {
        val videoId = YoutubeUrls.videoId(url) ?: return LocatedMedia(url)
        val builtIn = try {
            return YoutubeAudio.media(client, videoId, clock)
        } catch (e: YoutubeException) {
            e
        }
        var lastFailure: Exception? = null
        for (source in fallbacks) {
            try {
                source.streamUrl(videoId)?.let { return LocatedMedia(it, live = builtIn.live || isOnAir(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        val message = listOfNotNull(builtIn.message, lastFailure?.message).filter { it.isNotBlank() }.joinToString(" ")
        throw YoutubeException(message, lastFailure ?: builtIn, live = builtIn.live)
    }

    /**
     * Whether the address a fallback found is a stream on air, when YouTube itself never said (every built-in client
     * was turned away): an HLS playlist whose newest piece is from just now. A recording's, or an ended stream's, isn't.
     */
    private suspend fun isOnAir(url: String): Boolean {
        if (!url.substringBefore('?').endsWith(".m3u8")) return false
        val text = try {
            client.getText(url, WEB_USER_AGENT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        return HlsMaster.isOnAir(text, clock())
    }
}

/** Another way to get a playable URL for a video. Returns null when it isn't set up; throws when it fails. */
fun interface YoutubeAudioSource {
    suspend fun streamUrl(videoId: String): String?
}

object YoutubeAudio {
    /** [media]'s address. */
    suspend fun streamUrl(client: HttpClient, videoId: String): String = media(client, videoId).url

    /**
     * The video's audio stream address, kept for an hour ([ResponseCache]; YouTube's expire after about six), and never
     * an expired one in place of a failure. A live stream's is kept for [LIVE_KEPT_MS] only: it ends without notice,
     * and from then on the video is a recording, with an address of its own.
     */
    suspend fun media(client: HttpClient, videoId: String, clock: () -> Long = ::currentTimeMillis): LocatedMedia {
        if (!YoutubeUrls.isVideoId(videoId)) throw YoutubeException("That isn't a YouTube video")
        val key = "youtube stream $videoId"
        val find: suspend () -> String = {
            findStream(client, videoId).let { if (it.live) "$LIVE_MARK${clock()} ${it.url}" else it.url }
        }
        var kept = ResponseCache.text(key, 1.hours, staleOnError = false, fetch = find)
        if (kept.startsWith(LIVE_MARK)) {
            val foundAt = kept.removePrefix(LIVE_MARK).substringBefore(' ').toLongOrNull() ?: 0
            if (clock() - foundAt !in 0 until LIVE_KEPT_MS) kept = ResponseCache.text(key, Duration.ZERO, staleOnError = false, fetch = find)
        }
        return if (kept.startsWith(LIVE_MARK)) LocatedMedia(kept.removePrefix(LIVE_MARK).substringAfter(' '), live = true) else LocatedMedia(kept)
    }

    private suspend fun findStream(client: HttpClient, videoId: String): LocatedMedia {
        var failure: AudioChoice.Unavailable? = null
        var live = false
        var signInWall = false
        for (spec in clients) {
            // An IP-level sign-in wall rejects every client that comes as a stranger; don't wait on the rest of those.
            if (signInWall && !spec.asVisitor) continue
            val visitor = if (spec.asVisitor) visitorData(client, spec) ?: continue else null
            val text = try {
                client.player(spec, videoId, visitor)
            } catch (e: YoutubeException) {
                if (failure?.status == null) failure = AudioChoice.Unavailable(null, e.message)
                continue
            }
            when (val choice = interpret(text)) {
                is AudioChoice.Stream -> return LocatedMedia(choice.url)
                is AudioChoice.Live -> return LocatedMedia(liveAudio(client, videoId, spec, choice.manifestUrl), live = true)
                is AudioChoice.Unavailable -> {
                    failure = choice
                    live = live || choice.live
                    if (choice.status == "LOGIN_REQUIRED") signInWall = true
                }
            }
        }
        throw YoutubeException(audioFailureMessage(failure), live = live)
    }

    private suspend fun HttpClient.player(spec: Client, videoId: String, visitor: String?): String = postJson(
        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        spec.body(videoId, visitor),
        spec.userAgent,
        buildMap {
            put("X-YouTube-Client-Name", spec.clientId)
            put("X-YouTube-Client-Version", spec.version)
            if (visitor != null) put("X-Goog-Visitor-Id", visitor)
        },
    )

    /**
     * The id YouTube hands a new visitor, which a client can then call the player with: that passes where a stranger
     * is asked to sign in. Kept for six hours; null when YouTube won't give one.
     */
    private suspend fun visitorData(client: HttpClient, spec: Client): String? = try {
        ResponseCache.text("youtube visitor ${spec.name}", 6.hours) {
            val text = client.postJson(
                "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false",
                spec.body(videoId = null, visitor = null),
                spec.userAgent,
                mapOf("X-YouTube-Client-Name" to spec.clientId, "X-YouTube-Client-Version" to spec.version),
            )
            jsonObject(text)?.obj("responseContext")?.string("visitorData") ?: throw YoutubeException("YouTube gave no visitor id")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * The playlist to play of a live stream whose renditions [manifestUrl] (from [spec]'s answer) lists: its audio
     * alone when it's offered, which only the visitor client's list does; otherwise the smallest video, whose sound
     * plays just the same at a few times the data. The list itself when it can't be read (a player picks from it).
     */
    private suspend fun liveAudio(client: HttpClient, videoId: String, spec: Client, manifestUrl: String): String {
        val visitorMaster = clients.firstOrNull { it.asVisitor }?.takeIf { it != spec }?.let { other ->
            val visitor = visitorData(client, other) ?: return@let null
            val answer = quietly { client.player(other, videoId, visitor) } ?: return@let null
            (interpret(answer) as? AudioChoice.Live)?.let { it.manifestUrl to other }
        }
        // Read one at a time: the visitor client's list has the audio, and then the other is never needed.
        val read = ArrayList<Pair<String, String>>()
        for ((url, from) in listOfNotNull(visitorMaster, manifestUrl to spec)) {
            val text = quietly { client.getText(url, from.userAgent) } ?: continue
            HlsMaster.audioOnly(text, url)?.let { return it }
            read += url to text
        }
        return read.firstNotNullOfOrNull { (url, text) -> HlsMaster.lowestVariant(text, url) } ?: manifestUrl
    }

    /** [block]'s answer, or null when the request fails: there's another way to try. */
    private suspend fun quietly(block: suspend () -> String): String? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    internal fun interpret(json: String): AudioChoice {
        val root = jsonObject(json) ?: return AudioChoice.Unavailable(null, null)
        val playability = root.obj("playabilityStatus")
        val status = playability?.string("status")
        val reason = playability?.string("reason")
        val streaming = root.obj("streamingData")
        // On air: its audio formats are pieces to stitch (their addresses aren't files), so only the HLS list plays.
        if (root.obj("videoDetails")?.boolean("isLive") == true) {
            streaming?.string("hlsManifestUrl")?.takeIf { it.startsWith("https://") }?.let { return AudioChoice.Live(it) }
            val why = reason.takeIf { status != null && status != "OK" } ?: "This live stream can't be played as audio right now."
            return AudioChoice.Unavailable(status, why, live = true)
        }
        val formats = buildList {
            (streaming?.get("adaptiveFormats") as? JsonArray)?.let { addAll(it) }
            (streaming?.get("formats") as? JsonArray)?.let { addAll(it) }
        }.mapNotNull { it as? JsonObject }
        val audio = formats.mapNotNull { format ->
            val mime = format.string("mimeType") ?: return@mapNotNull null
            if (!mime.startsWith("audio/mp4")) return@mapNotNull null
            val url = format.string("url")?.takeIf { it.startsWith("https://") && YoutubeUrls.videoId(it) == null }
                ?: return@mapNotNull null
            url to (format.long("bitrate") ?: 0L)
        }
        if (audio.isNotEmpty()) return AudioChoice.Stream(audio.maxBy { it.second }.first)
        if (status != null && status != "OK") return AudioChoice.Unavailable(status, reason)
        return AudioChoice.Unavailable(status, "This video has no audio stream that can be played here.")
    }

    internal fun audioFailureMessage(failure: AudioChoice.Unavailable?): String = when (failure?.status) {
        "LOGIN_REQUIRED" -> "YouTube asked for a sign-in check, so this video can't be played as audio right now. " +
            "YouTube flags some VPN and proxy servers: if you use one, try a different server or region."
        else -> failure?.reason?.trim()?.takeIf { it.isNotEmpty() }
            ?: "This video can't be played as audio right now."
    }

    private data class Client(
        val name: String,
        val version: String,
        val clientId: String,
        val userAgent: String,
        val extra: JsonObject,
        /** Calls with a visitor id fetched first ([visitorData]): one more request, but it isn't asked to sign in. */
        val asVisitor: Boolean = false,
    ) {
        /** The player call's body for [videoId]; without one, just the client (what the visitor id call sends). */
        fun body(videoId: String?, visitor: String?) = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", name)
                    put("clientVersion", version)
                    put("userAgent", userAgent)
                    put("hl", "en")
                    put("gl", "US")
                    extra.forEach { (k, v) -> put(k, v) }
                    if (visitor != null) put("visitorData", visitor)
                }
            }
            if (videoId != null) {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
        }.toString()
    }

    private val clients = listOf(
        Client(
            name = "ANDROID_VR",
            version = "1.65.10",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            extra = buildJsonObject {
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
                put("androidSdkVersion", 32)
                put("osName", "Android")
                put("osVersion", "12L")
            },
        ),
        Client(
            name = "ANDROID",
            version = "21.26.364",
            clientId = "3",
            userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
            extra = buildJsonObject {
                put("androidSdkVersion", 30)
                put("osName", "Android")
                put("osVersion", "11")
            },
        ),
        // Last, as it takes a request more: it gets through the sign-in wall the others hit on some networks, and its
        // live streams come with their audio on its own.
        Client(
            name = "VISIONOS",
            version = "1.02",
            clientId = "101",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            extra = buildJsonObject {
                put("deviceMake", "Apple")
                put("deviceModel", "RealityDevice17,1")
                put("osName", "visionOS")
                put("osVersion", "26.5.23O471")
            },
            asVisitor = true,
        ),
    )

    /** Marks a kept address as a live stream's; the time it was found (epoch ms) and a space follow, then the address. */
    private const val LIVE_MARK = "live "

    /** How long a live stream's address is reused before YouTube is asked whether it's still on air. */
    private const val LIVE_KEPT_MS = 2 * 60_000L
}

internal sealed interface AudioChoice {
    data class Stream(val url: String) : AudioChoice

    /** A stream on air: [manifestUrl] is the HLS master playlist of its renditions. */
    data class Live(val manifestUrl: String) : AudioChoice

    data class Unavailable(val status: String?, val reason: String?, val live: Boolean = false) : AudioChoice
}
