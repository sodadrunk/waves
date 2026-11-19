package io.waves.offline

import io.waves.currentTimeMillis
import io.waves.download.DownloadManager
import io.waves.download.DownloadStatus
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.sync.HistoryRemoval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Keeps this device's audio to the episodes in the history.
 *
 * - An episode removed from the history, here or on another device (its [HistoryRemoval] synced in), loses its
 *   download, its cached file and the player's cache as soon as that is known. The one loaded in the player keeps
 *   them until it's let go.
 * - A scan, [firstScanAfter] the start and then every [scanEvery], removes what nothing in the history accounts for:
 *   downloads and cached audio of episodes with no history entry, and files in the downloads folder no download is
 *   for ([DownloadManager.pruneStrayFiles]). An episode gets its entry when it starts playing or its download
 *   completes, so only what has been there longer than [scanGrace] counts, and nothing being downloaded.
 */
class HistoryMediaPurge(
    private val history: Flow<List<HistoryEntry>>,
    private val removals: Flow<List<HistoryRemoval>>,
    private val downloads: DownloadManager?,
    private val cache: PlaybackCache,
    private val activeEpisode: StateFlow<EpisodeId?>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::currentTimeMillis,
    private val firstScanAfter: Duration = 2.minutes,
    private val scanEvery: Duration = 6.hours,
    private val scanGrace: Duration = 1.hours,
) {
    private val lock = Mutex()

    fun start(): Job = scope.launch {
        launch {
            combine(history, removals, activeEpisode, localMedia()) { entries, removed, active, local ->
                val listed = entries.mapTo(HashSet()) { it.episodeId }
                removed.mapNotNullTo(HashSet()) { r -> r.episodeId.takeIf { it !in listed && it in local && it != active } }
            }.distinctUntilChanged().collect { ids -> if (ids.isNotEmpty()) purge(ids) }
        }
        launch {
            delay(firstScanAfter)
            while (true) {
                scan()
                delay(scanEvery)
            }
        }
    }

    /** Removes what nothing in the history accounts for (see the class). */
    suspend fun scan() {
        val listed = history.first().mapTo(HashSet()) { it.episodeId }
        val active = activeEpisode.value
        val settled = clock() - scanGrace.inWholeMilliseconds
        val orphans = HashSet<EpisodeId>()
        val rows = downloads?.downloads?.first().orEmpty()
        rows.filter { it.status == DownloadStatus.Done && (it.completedAtEpochMs ?: it.createdAtEpochMs) < settled }
            .mapTo(orphans) { it.episodeId }
        cache.entries.first().filter { it.updatedAtEpochMs < settled }.mapTo(orphans) { it.episodeId }
        // A cached file says nothing of its age, but it was cached a minute into playing, which made the entry.
        val transferring = rows.filter { it.status != DownloadStatus.Done }.mapTo(HashSet()) { it.episodeId }
        downloads?.cachedEpisodeIds?.first()?.filterTo(orphans) { it !in transferring }
        orphans.removeAll(listed)
        active?.let(orphans::remove)
        if (orphans.isNotEmpty()) purge(orphans)
        downloads?.pruneStrayFiles()
    }

    private suspend fun purge(ids: Set<EpisodeId>) = lock.withLock {
        for (id in ids) {
            downloads?.delete(id)
            cache.remove(id)
        }
    }

    /** Episodes with any audio here: downloaded, cached whole, or in the player's cache. */
    private fun localMedia(): Flow<Set<EpisodeId>> {
        val manager = downloads
        val stored = manager?.let { m ->
            combine(m.downloads, m.cachedEpisodeIds) { rows, cached -> rows.mapTo(HashSet()) { it.episodeId } + cached }
        } ?: flowOf(emptySet())
        return combine(stored, cache.entries) { a, entries -> a + entries.map { it.episodeId } }.distinctUntilChanged()
    }
}
