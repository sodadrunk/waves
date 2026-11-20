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
 * Keeps this device's audio to the episodes in the history, while online (offline, audio isn't cleaned up
 * automatically, as [LocalMediaCleaner] has it).
 *
 * - An episode removed from the history, here or on another device (its [HistoryRemoval] synced in), loses the
 *   audio it had then: a download made before the removal, its cached file and the player's cache. One downloaded
 *   or played after the removal is the user's again and stays. The one loaded in the player keeps its audio until
 *   it's let go.
 * - A scan, [firstScanAfter] the start and then every [scanEvery], removes cached audio no history entry accounts
 *   for (caching starts a minute into playing, after the entry is made), and files in the downloads folder no
 *   download is for ([DownloadManager.pruneStrayFiles]). Downloads aren't judged by the history: their entries are
 *   written once they complete, so only a removal takes them. Only what has been there longer than [scanGrace]
 *   counts, and nothing being downloaded.
 */
class HistoryMediaPurge(
    private val history: Flow<List<HistoryEntry>>,
    private val removals: Flow<List<HistoryRemoval>>,
    private val downloads: DownloadManager?,
    private val cache: PlaybackCache,
    private val activeEpisode: StateFlow<EpisodeId?>,
    private val offline: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::currentTimeMillis,
    private val firstScanAfter: Duration = 2.minutes,
    private val scanEvery: Duration = 6.hours,
    private val scanGrace: Duration = 1.hours,
) {
    private val lock = Mutex()

    fun start(): Job = scope.launch {
        launch {
            combine(history, removals, activeEpisode, offline, localMedia()) { entries, removed, active, off, local ->
                if (off) return@combine emptySet()
                val listed = entries.mapTo(HashSet()) { it.episodeId }
                removed.mapNotNullTo(HashSet()) { r ->
                    val id = r.episodeId
                    id.takeIf { it !in listed && it != active && local[it]?.let { since -> since <= r.removedAtEpochMs } == true }
                }
            }.distinctUntilChanged().collect { ids -> if (ids.isNotEmpty()) purge(ids) }
        }
        launch {
            delay(firstScanAfter)
            while (true) {
                if (!offline.value) scan()
                delay(scanEvery)
            }
        }
    }

    /** Removes what nothing in the history accounts for (see the class). */
    suspend fun scan() {
        if (offline.value) return
        val listed = history.first().mapTo(HashSet()) { it.episodeId }
        val active = activeEpisode.value
        val settled = clock() - scanGrace.inWholeMilliseconds
        val orphans = HashSet<EpisodeId>()
        cache.entries.first().filter { it.updatedAtEpochMs < settled }.mapTo(orphans) { it.episodeId }
        // A cached file says nothing of its age, but it was cached a minute into playing, which made the entry.
        // One with a download of its own, in any state, is that download's.
        val rows = downloads?.downloads?.first().orEmpty().mapTo(HashSet()) { it.episodeId }
        downloads?.cachedEpisodeIds?.first()?.filterTo(orphans) { it !in rows }
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

    /**
     * Episodes with audio here, and since when: a completed download (from when it was started), a cached file or
     * the player's cache (from the first time; either was made from playing, which a removal covers when earlier).
     * A download still under way isn't counted: it was asked for, and a removal before it doesn't cover it.
     */
    private fun localMedia(): Flow<Map<EpisodeId, Long>> {
        val manager = downloads
        val stored: Flow<Map<EpisodeId, Long>> = manager?.let { m ->
            combine(m.downloads, m.cachedEpisodeIds) { rows, cached ->
                val out = HashMap<EpisodeId, Long>()
                cached.forEach { out[it] = Long.MIN_VALUE }
                val transferring = rows.filter { it.status != DownloadStatus.Done }.mapTo(HashSet()) { it.episodeId }
                transferring.forEach(out::remove)
                rows.filter { it.status == DownloadStatus.Done }.forEach { out[it.episodeId] = it.createdAtEpochMs }
                out as Map<EpisodeId, Long>
            }
        } ?: flowOf(emptyMap())
        return combine(stored, cache.entries) { a, entries ->
            val out = HashMap<EpisodeId, Long>(a)
            entries.forEach { e -> out.putIfAbsent(e.episodeId, Long.MIN_VALUE) }
            out as Map<EpisodeId, Long>
        }.distinctUntilChanged()
    }
}
