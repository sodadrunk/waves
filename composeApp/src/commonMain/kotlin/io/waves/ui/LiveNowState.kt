package io.waves.ui

import io.waves.model.Episode
import io.waves.model.Podcast
import io.waves.youtube.YoutubeUrls
import io.waves.youtube.liveArchiveOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A stream on air from a followed show. */
data class LiveNowItem(val episode: Episode, val podcast: Podcast)

/**
 * The streams on air right now from the YouTube channels the user follows, for Home's "Live now" rows. A channel
 * counts as followed through its own show or its live archive; either way its streams go under the archive ("Show ·
 * Lived"), where each turns up as an episode once it has ended.
 *
 * Looked up when the followed shows change and on [refresh] (Home coming into view, and its pull), not on a timer:
 * each look fetches a page per channel. Nothing is looked up or listed while [offline].
 *
 * @param liveNow the streams on air of a channel, as episodes of its live archive.
 */
class LiveNowState(
    subscriptions: Flow<List<Podcast>>,
    private val liveNow: suspend (channelId: String) -> List<Episode>,
    private val scope: CoroutineScope,
    private val offline: StateFlow<Boolean> = MutableStateFlow(false),
    private val parallelism: Int = 4,
) {
    private val _items = MutableStateFlow<List<LiveNowItem>>(emptyList())
    val items: StateFlow<List<LiveNowItem>> = _items.asStateFlow()

    private val asked = MutableStateFlow(0)

    /** The live archives of the followed YouTube channels, by channel: as followed, or else made up from the channel. */
    private val channels: Flow<Map<String, Podcast>> = subscriptions.map { shows ->
        val archives = shows.mapNotNull { show -> YoutubeUrls.channelIdOfLiveArchive(show.feedUrl)?.let { it to show } }
        val own = shows.mapNotNull { show -> YoutubeUrls.channelIdOfFeed(show.feedUrl)?.let { it to liveArchiveOf(show, it) } }
        (own + archives).toMap()
    }.distinctUntilChanged()

    init {
        scope.launch {
            combine(channels, offline, asked) { followed, off, _ -> followed.takeIf { !off } }.collectLatest { followed ->
                if (followed == null) {
                    _items.value = emptyList()
                    return@collectLatest
                }
                // A show no longer followed takes its streams with it at once.
                _items.value = _items.value.filter { item -> followed.values.any { it.feedUrl == item.podcast.feedUrl } }
                _items.value = lookUp(followed)
            }
        }
    }

    /** Looks again now. */
    fun refresh() {
        asked.value++
    }

    private suspend fun lookUp(followed: Map<String, Podcast>): List<LiveNowItem> = coroutineScope {
        val gate = Semaphore(parallelism.coerceAtLeast(1))
        followed.entries.sortedBy { it.value.title.lowercase() }.map { (channelId, show) ->
            async {
                gate.withPermit {
                    try {
                        liveNow(channelId).map { LiveNowItem(it, show) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Keep what was on air from this channel: one failed look doesn't mean the stream ended.
                        _items.value.filter { it.podcast.feedUrl == show.feedUrl }
                    }
                }
            }
        }.awaitAll().flatten()
    }
}
