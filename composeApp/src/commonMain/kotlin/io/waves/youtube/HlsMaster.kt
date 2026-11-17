package io.waves.youtube

import kotlinx.datetime.Instant

/**
 * Picks what to play out of an HLS master playlist, the list of a stream's renditions. A YouTube live stream has no
 * audio file, only this: for some clients it lists audio on its own, for the others only video with the sound in it.
 */
internal object HlsMaster {
    /**
     * The playlist of the best rendition that is audio alone, or null when the stream offers none. AAC-LC (what
     * YouTube's higher one is) is preferred over HE-AAC, the low-bitrate one.
     */
    fun audioOnly(master: String, masterUrl: String): String? {
        val variants = variants(master)
        return tags(master, "#EXT-X-MEDIA:")
            .filter { it["TYPE"] == "AUDIO" && !it["URI"].isNullOrEmpty() }
            .maxByOrNull { audio -> if (variants.any { it.audioGroup == audio["GROUP-ID"] && "mp4a.40.2" in it.codecs }) 1 else 0 }
            ?.let { resolve(it.getValue("URI"), masterUrl) }
    }

    /** The playlist of the rendition that costs the least data: video with the sound in it, at its smallest. */
    fun lowestVariant(master: String, masterUrl: String): String? =
        variants(master).minByOrNull { it.bandwidth }?.let { resolve(it.uri, masterUrl) }

    /**
     * Whether a media playlist (a rendition's list of pieces) is of a stream on air at [now]: it isn't closed, and
     * its last piece ends within [ON_AIR_SLACK_MS] of now, going by the date its first piece carries. An ended
     * stream's list can stay unclosed, but its pieces stop there.
     */
    fun isOnAir(playlist: String, now: Long): Boolean {
        val lines = playlist.lines().map { it.trim() }
        if (lines.any { it == "#EXT-X-ENDLIST" }) return false
        val dated = lines.indexOfFirst { it.startsWith(DATE) }
        if (dated < 0) return false
        val start = runCatching { Instant.parse(lines[dated].removePrefix(DATE)).toEpochMilliseconds() }.getOrNull() ?: return false
        val length = lines.drop(dated).filter { it.startsWith("#EXTINF:") }
            .sumOf { it.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0 }
        return now - (start + (length * 1000).toLong()) in -ON_AIR_SLACK_MS..ON_AIR_SLACK_MS
    }

    private class Variant(val uri: String, val bandwidth: Long, val codecs: String, val audioGroup: String?)

    private fun variants(master: String): List<Variant> {
        val lines = master.lines().map { it.trim() }
        return lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith(STREAM)) return@mapIndexedNotNull null
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            val attributes = attributes(line.removePrefix(STREAM))
            Variant(uri, attributes["BANDWIDTH"]?.toLongOrNull() ?: Long.MAX_VALUE, attributes["CODECS"].orEmpty(), attributes["AUDIO"])
        }
    }

    private fun tags(master: String, tag: String): List<Map<String, String>> =
        master.lines().map { it.trim() }.filter { it.startsWith(tag) }.map { attributes(it.removePrefix(tag)) }

    /** `KEY=value,KEY="quoted, with commas"` as a map, quotes dropped. */
    private fun attributes(list: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < list.length) {
            val eq = list.indexOf('=', i)
            if (eq < 0) break
            val key = list.substring(i, eq).trim()
            val quoted = list.getOrNull(eq + 1) == '"'
            val end = if (quoted) list.indexOf('"', eq + 2).takeIf { it >= 0 } ?: list.length
            else list.indexOf(',', eq + 1).takeIf { it >= 0 } ?: list.length
            out[key] = list.substring(if (quoted) eq + 2 else eq + 1, end).trim()
            i = (if (quoted) list.indexOf(',', end).takeIf { it >= 0 } ?: list.length else end) + 1
        }
        return out
    }

    /** YouTube writes whole addresses; one written relative to the master playlist is completed from it. */
    private fun resolve(uri: String, masterUrl: String): String = when {
        "://" in uri -> uri
        uri.startsWith("/") -> masterUrl.substringBefore("://") + "://" + masterUrl.substringAfter("://").substringBefore('/') + uri
        else -> masterUrl.substringBefore('?').substringBeforeLast('/') + "/" + uri
    }

    private const val STREAM = "#EXT-X-STREAM-INF:"
    private const val DATE = "#EXT-X-PROGRAM-DATE-TIME:"
    private const val ON_AIR_SLACK_MS = 3 * 60_000L
}
