package io.waves.offline

import io.waves.download.DownloadInfo
import io.waves.download.DownloadManager
import io.waves.download.DownloadStatus
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.sync.HistoryRemoval
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryMediaPurgeTest {
    private fun id(guid: String) = EpisodeId("https://f", guid)
    private fun entry(guid: String) = HistoryEntry(id(guid), guid, "Show", 1, "d")
    private fun done(guid: String, at: Long) = DownloadInfo(id(guid), DownloadStatus.Done, 1, 1, "/d/$guid.mp3", null, completedAtEpochMs = at)

    private class Downloads : DownloadManager {
        val rows = MutableStateFlow<List<DownloadInfo>>(emptyList())
        val cached = MutableStateFlow<Set<EpisodeId>>(emptySet())
        val deleted = mutableListOf<EpisodeId>()
        var prunes = 0
        override val downloads: Flow<List<DownloadInfo>> = rows
        override val cachedEpisodeIds: Flow<Set<EpisodeId>> = cached
        override fun state(episodeId: EpisodeId): Flow<DownloadInfo?> = emptyFlow()
        override suspend fun enqueue(episode: Episode) {}
        override suspend fun cancel(episodeId: EpisodeId) {}
        override suspend fun delete(episodeId: EpisodeId) {
            deleted += episodeId
            rows.update { list -> list.filter { it.episodeId != episodeId } }
            cached.update { it - episodeId }
        }
        override fun localUri(episodeId: EpisodeId): String? = null
        override suspend fun deleteCompleted(olderThan: Duration) = 0
        override suspend fun deleteDownloads(episodeIds: Collection<EpisodeId>) = 0
        override suspend fun pruneStrayFiles(): Int = 0.also { prunes++ }
    }

    private class Cache : PlaybackCache {
        override val entries = MutableStateFlow<List<PlaybackCacheEntry>>(emptyList())
        val removed = mutableListOf<EpisodeId>()
        override suspend fun upsert(entry: PlaybackCacheEntry) = entries.update { it + entry }
        override suspend fun remove(episodeId: EpisodeId) {
            removed += episodeId
            entries.update { list -> list.filter { it.episodeId != episodeId } }
        }
    }

    private val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val removals = MutableStateFlow<List<HistoryRemoval>>(emptyList())
    private val active = MutableStateFlow<EpisodeId?>(null)
    private val offline = MutableStateFlow(false)
    private val downloads = Downloads()
    private val cache = Cache()

    private fun TestScope.purge() = HistoryMediaPurge(
        history, removals, downloads, cache, active, offline, backgroundScope, clock = { testScheduler.currentTime },
        firstScanAfter = 2.minutes, scanEvery = 6.hours, scanGrace = 1.hours,
    ).start()

    @Test
    fun aRemovalTakesTheEpisodesAudioHereOrFromAnotherDevice() = runTest {
        history.value = listOf(entry("a"), entry("b"))
        downloads.rows.value = listOf(done("a", 0), done("b", 0))
        cache.entries.value = listOf(PlaybackCacheEntry(id("b"), "u", 1, 1, 0))
        purge()
        runCurrent()
        assertEquals(emptyList(), downloads.deleted)

        // Removed here, or synced in from another device: the same flows change.
        history.value = listOf(entry("b"))
        removals.value = listOf(HistoryRemoval(id("a"), 10))
        runCurrent()
        assertEquals(listOf(id("a")), downloads.deleted)

        // The one playing keeps its audio until it's let go.
        active.value = id("b")
        history.value = emptyList()
        removals.value = removals.value + HistoryRemoval(id("b"), 20)
        runCurrent()
        assertEquals(listOf(id("a")), downloads.deleted)
        active.value = null
        runCurrent()
        assertEquals(listOf(id("a"), id("b")), downloads.deleted)
        assertEquals(listOf(id("a"), id("b")), cache.removed)
    }

    @Test
    fun aDownloadAskedForAfterTheRemovalStays() = runTest {
        removals.value = listOf(HistoryRemoval(id("a"), 10))
        purge()
        // Downloaded again: queued, under way, then done, all after the removal.
        downloads.rows.value = listOf(DownloadInfo(id("a"), DownloadStatus.Queued, 0, null, "/d/a.mp3", null, createdAtEpochMs = 20))
        runCurrent()
        downloads.rows.value = listOf(done("a", 30).copy(createdAtEpochMs = 20))
        runCurrent()
        assertEquals(emptyList(), downloads.deleted)
    }

    @Test
    fun nothingGoesWhileOffline() = runTest {
        offline.value = true
        downloads.rows.value = listOf(done("a", 0))
        downloads.cached.value = setOf(id("b"))
        removals.value = listOf(HistoryRemoval(id("a"), 10))
        purge()
        advanceTimeBy(7.hours)
        assertEquals(emptyList(), downloads.deleted)
        assertEquals(0, downloads.prunes)
        // Back online, the removal is carried out.
        offline.value = false
        runCurrent()
        assertEquals(listOf(id("a")), downloads.deleted)
    }

    @Test
    fun theScanSweepsWhatTheHistoryDoesNotAccountFor() = runTest {
        advanceTimeBy(5.hours)
        val now = testScheduler.currentTime
        history.value = listOf(entry("kept"))
        downloads.rows.value = listOf(
            done("kept", 0), done("orphan", 0),
            done("fresh", now), // just completed: its history entry may not be in yet
            DownloadInfo(id("loading"), DownloadStatus.Downloading, 1, 2, "/d/loading.mp3", null),
        )
        downloads.cached.value = setOf(id("cachedOrphan"), id("loading"))
        cache.entries.value = listOf(PlaybackCacheEntry(id("partial"), "u", 1, 10, 0))
        purge()
        advanceTimeBy(1.minutes)
        assertEquals(emptyList(), downloads.deleted)
        advanceTimeBy(2.minutes)
        // Not the downloads: the history entry of one is written as it completes, and only a removal takes it.
        assertEquals(setOf(id("cachedOrphan"), id("partial")), downloads.deleted.toSet())
        assertEquals(listOf(id("partial")), cache.removed.filter { it == id("partial") })
        assertEquals(1, downloads.prunes)
    }
}
