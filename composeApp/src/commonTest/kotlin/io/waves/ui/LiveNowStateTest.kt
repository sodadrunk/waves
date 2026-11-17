package io.waves.ui

import io.waves.model.Episode
import io.waves.model.Podcast
import io.waves.youtube.YoutubeUrls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveNowStateTest {
    private val news = "UCnewsnewsnewsnewsnews00"
    private val music = "UCmusicmusicmusicmusic00"
    private val newsChannel = Podcast(YoutubeUrls.feedUrl(news), "News")
    private val newsArchive = Podcast(YoutubeUrls.liveArchiveFeedUrl(news), "News · Lived")
    private val musicChannel = Podcast(YoutubeUrls.feedUrl(music), "Music")
    private val rss = Podcast("https://example.com/feed.xml", "A podcast")

    /** Streams on air by channel, and how often each channel was asked about. */
    private val onAir = mutableMapOf(news to listOf("liveNews001"), music to emptyList())
    private val asked = mutableListOf<String>()
    private var failing = false

    private fun stream(id: String, channel: String) =
        Episode(guid = id, podcastFeedUrl = YoutubeUrls.liveArchiveFeedUrl(channel), title = id, audioUrl = YoutubeUrls.watchUrl(id))

    private val look: suspend (String) -> List<Episode> = { channel ->
        asked += channel
        if (failing) error("YouTube is down")
        onAir[channel].orEmpty().map { stream(it, channel) }
    }

    @Test
    fun listsStreamsOnAirFromFollowedChannelsUnderTheirLiveArchives() = runTest {
        val subscriptions = MutableStateFlow(listOf(rss, musicChannel, newsChannel))
        val state = LiveNowState(subscriptions, look, backgroundScope)
        runCurrent()
        // Followed as the channel only, its stream still goes under the channel's live archive, named after it.
        val item = state.items.value.single()
        assertEquals(stream("liveNews001", news), item.episode)
        assertEquals(newsArchive.feedUrl, item.podcast.feedUrl)
        assertEquals("News · Lived", item.podcast.title)
        assertEquals(setOf(news, music), asked.toSet(), "only YouTube channels are asked about")

        // Followed as its live archive too, a channel is asked about once, and its streams go under the one followed.
        asked.clear()
        subscriptions.value = listOf(newsArchive, newsChannel)
        runCurrent()
        assertEquals(listOf(news), asked)
        assertEquals(listOf(newsArchive), state.items.value.map { it.podcast })
        assertEquals(newsArchive.feedUrl, state.items.value.single().episode.podcastFeedUrl)

        subscriptions.value = emptyList()
        runCurrent()
        assertTrue(state.items.value.isEmpty())
    }

    @Test
    fun looksAgainWhenAskedAndKeepsAStreamThroughAFailedLook() = runTest {
        val state = LiveNowState(MutableStateFlow(listOf(newsChannel, musicChannel)), look, backgroundScope)
        runCurrent()
        assertEquals(listOf("liveNews001"), state.items.value.map { it.episode.guid })

        onAir[music] = listOf("liveMusic01")
        runCurrent()
        assertEquals(1, state.items.value.size, "not looked at again by itself")
        state.refresh()
        runCurrent()
        assertEquals(listOf("liveMusic01", "liveNews001"), state.items.value.map { it.episode.guid }, "by show title")

        failing = true
        state.refresh()
        runCurrent()
        assertEquals(2, state.items.value.size)

        failing = false
        onAir[news] = emptyList()
        state.refresh()
        runCurrent()
        assertEquals(listOf("liveMusic01"), state.items.value.map { it.episode.guid })
    }

    @Test
    fun offlineNothingIsAskedOrListed() = runTest {
        val offline = MutableStateFlow(true)
        val state = LiveNowState(MutableStateFlow(listOf(newsChannel)), look, backgroundScope, offline)
        runCurrent()
        assertTrue(asked.isEmpty())
        assertTrue(state.items.value.isEmpty())
        offline.value = false
        runCurrent()
        assertEquals(1, state.items.value.size)
        offline.value = true
        runCurrent()
        assertTrue(state.items.value.isEmpty())
    }
}
