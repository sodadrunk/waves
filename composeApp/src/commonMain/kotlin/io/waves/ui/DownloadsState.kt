package io.waves.ui

import io.waves.currentTimeMillis
import io.waves.data.LibraryRepository
import io.waves.data.SettingsStore
import io.waves.download.DownloadInfo
import io.waves.download.DownloadManager
import io.waves.download.DownloadStatus
import io.waves.model.Episode
import io.waves.model.EpisodeId
import io.waves.model.HistoryEntry
import io.waves.model.PlaybackProgress
import io.waves.offline.PlaybackCache
import io.waves.player.PlaybackState
import io.waves.ui.fake.InMemorySettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.waves.data.UserSettings
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** What the per-episode download control shows. */
sealed interface DownloadControl {
    /** Not downloaded: download icon. */
    data object NotDownloaded : DownloadControl
    /** Queued or downloading: circular progress ([progress] null = indeterminate); tap cancels. */
    data class InProgress(val progress: Float?) : DownloadControl
    /** Downloaded: tap turns it into a delete button for a moment; long-press offers "Remove download". */
    data object Downloaded : DownloadControl
    /** Fully cached by the player, not an explicit download. Tap downloads it; long-press offers "Remove cached episode". */
    data object Cached : DownloadControl
    /** Failed: error icon; tap retries. */
    data class Failed(val error: String?) : DownloadControl
}

fun downloadControlFor(info: DownloadInfo?, fullyCached: Boolean = false): DownloadControl {
    if (info == null && fullyCached) return DownloadControl.Cached
    return when (info?.status) {
        null -> DownloadControl.NotDownloaded
        DownloadStatus.Queued -> DownloadControl.InProgress(null)
        DownloadStatus.Downloading -> DownloadControl.InProgress(info.progress)
        DownloadStatus.Done -> DownloadControl.Downloaded
        DownloadStatus.Failed -> DownloadControl.Failed(info.error)
    }
}

data class DownloadsUiState(
    val items: List<DownloadInfo> = emptyList(),
    val autoDeleteAfterFinishing: Boolean = false,
)

/** How many downloads there are here, and how many bytes they take. */
data class DownloadUsage(val count: Int, val bytes: Long)

/**
 * App-wide download actions and state.
 *
 * Delete after playing ([deleteAfterDays]): a download is removed once its episode was played (to the end, or marked
 * played) after the download was made: right away, as that is seen, or so many days after it was played, checked
 * every [laterDeleteEvery]. Never while it is the episode loaded in the player (until it has ended or the player
 * moves on), nor while offline, where those deletions wait for the connection. A download made of an episode played
 * before is the user's to keep. Removing a download by hand always deletes it, and also drops a playback-cache entry
 * for that episode.
 *
 * A cached episode (kept by the player, not downloaded) is removed [cacheKeptAfterPlayed] after it was played, checked
 * every [cacheSweepEvery], unless it's the episode in the player; not while offline, where cached audio is all there is.
 *
 * Removing the local copy of the episode in the player hands it to [onLocalCopyRemoved] to carry on from the stream;
 * while offline, where there's no stream, the removal waits until that episode stops (it shows as removed meanwhile).
 *
 * With a [deviceId], each finished download is added to History at the time it finished, including downloads
 * that finished in the background while the app was closed. A never-played episode also gets progress at 0:00.
 */
class DownloadsState(
    private val downloads: DownloadManager,
    private val library: LibraryRepository,
    private val playback: StateFlow<PlaybackState>,
    private val scope: CoroutineScope,
    private val settings: SettingsStore = InMemorySettingsStore(),
    private val playbackCache: PlaybackCache? = null,
    /** True while offline (no Internet). */
    private val offline: StateFlow<Boolean> = MutableStateFlow(false),
    private val deviceId: String? = null,
    private val onLocalChange: () -> Unit = {},
    private val onLocalCopyRemoved: (EpisodeId) -> Unit = {},
    private val cacheKeptAfterPlayed: Duration = 24.hours,
    private val cacheSweepEvery: Duration = 30.minutes,
    /** How often downloads played some days ago are looked for. */
    private val laterDeleteEvery: Duration = 1.hours,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    /** How many days after playing a download is deleted; [UserSettings.NEVER] for never. */
    val deleteAfterDays: StateFlow<Int> = settings.settings.map { it.deleteAfterDays }
        .stateIn(scope, SharingStarted.Eagerly, settings.settings.value.deleteAfterDays)

    /** Whether downloads are deleted at all once played. */
    val autoDeleteAfterFinishing: StateFlow<Boolean> = deleteAfterDays.map { it >= 0 }
        .stateIn(scope, SharingStarted.Eagerly, deleteAfterDays.value >= 0)

    private val fullyCachedIds: StateFlow<Set<EpisodeId>> =
        combine(
            (playbackCache?.entries ?: flowOf(emptyList()))
                .map { list -> list.filter { it.isFullyCached }.map { it.episodeId }.toSet() },
            downloads.cachedEpisodeIds,
        ) { streamed, files -> streamed + files }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val state: StateFlow<DownloadsUiState> =
        combine(downloads.downloads, autoDeleteAfterFinishing) { items, auto -> DownloadsUiState(items, auto) }
            .stateIn(scope, SharingStarted.Eagerly, DownloadsUiState(autoDeleteAfterFinishing = autoDeleteAfterFinishing.value))

    /** Completed downloads here, and the space their files take (bytes). */
    val usage: StateFlow<DownloadUsage> = downloads.downloads
        .map { list -> list.filter { it.status == DownloadStatus.Done }.let { done -> DownloadUsage(done.size, done.sumOf { it.bytesDownloaded }) } }
        .stateIn(scope, SharingStarted.Eagerly, DownloadUsage(0, 0))

    /** Removed while playing offline: deleted once the episode stops. */
    private val deferredRemovals = MutableStateFlow<Set<EpisodeId>>(emptySet())

    fun control(episodeId: EpisodeId): Flow<DownloadControl> =
        combine(downloads.state(episodeId), fullyCachedIds, deferredRemovals) { info, cached, deferred ->
            if (episodeId in deferred) DownloadControl.NotDownloaded else downloadControlFor(info, episodeId in cached)
        }

    private fun activeEpisode(): EpisodeId? = playback.value.let { s -> s.episode?.id?.takeIf { !s.isEnded } }

    init {
        // Episode ids are the only stable key: the player may report a file:// audioUrl for downloaded episodes.
        val activeEpisode = playback.map { s -> s.episode?.id?.takeIf { !s.isEnded } }.distinctUntilChanged()
        val completed = library.allProgress.map { list -> list.filter { it.completed }.map { it.episodeId }.toSet() }
            .distinctUntilChanged()
        scope.launch {
            var baseline: Set<EpisodeId>? = null
            val pending = mutableSetOf<EpisodeId>()
            // Right after playing: as an episode is seen to be played.
            combine(completed, activeEpisode, deleteAfterDays.map { it == 0 }, offline) { done, active, auto, off ->
                AutoDeleteTick(done, active, auto, off)
            }.collect { (done, active, auto, off) ->
                val previous = baseline
                baseline = done
                if (previous != null && auto) pending += done - previous
                pending.retainAll(done) // marked unplayed again: keep the download
                if (!auto) pending.clear()
                if (off) return@collect // keep pending; delete once back online
                val ready = pending.filter { it != active }
                if (ready.isNotEmpty()) {
                    pending -= ready.toSet()
                    guarded { downloads.deleteDownloads(ready) }
                }
            }
        }
        // Those already played: once on switching to right away, and some days after playing, checked now and then;
        // offline, as soon as the connection is back.
        scope.launch {
            combine(deleteAfterDays, offline) { days, off -> days.takeUnless { off } }.collectLatest { days ->
                when {
                    days == null || days < 0 -> Unit
                    days == 0 -> guarded { deletePlayedBefore(Long.MAX_VALUE) }
                    else -> while (true) {
                        guarded { deletePlayedBefore(clock() - days.days.inWholeMilliseconds) }
                        delay(laterDeleteEvery)
                    }
                }
            }
        }
        if (deviceId != null) scope.launch { recordFinishedDownloads(deviceId) }
        scope.launch {
            val active = playback.map { s -> s.episode?.id?.takeIf { !s.isEnded } }.distinctUntilChanged()
            combine(deferredRemovals, active) { deferred, playing -> deferred - setOfNotNull(playing) }.collect { due ->
                for (id in due) {
                    deferredRemovals.value -= id
                    guarded { deleteLocalCopy(id) }
                }
            }
        }
        scope.launch {
            while (true) {
                guarded { sweepPlayedCache() }
                delay(cacheSweepEvery)
            }
        }
    }

    /**
     * Deletes the downloads of episodes played (marked played) before [cutoff], but only where that was after the
     * download was made: one made again of an episode played before is wanted. Not the one loaded.
     */
    private suspend fun deletePlayedBefore(cutoff: Long) {
        val made = downloads.downloads.first().filter { it.status == DownloadStatus.Done }
            .associate { it.episodeId to it.createdAtEpochMs }
        if (made.isEmpty()) return
        val active = activeEpisode()
        val due = library.allProgress.first()
            .filter { p ->
                val since = made[p.episodeId] ?: return@filter false
                p.completed && p.updatedAtEpochMs < cutoff && p.updatedAtEpochMs >= since && p.episodeId != active
            }
            .map { it.episodeId }
        if (due.isNotEmpty()) downloads.deleteDownloads(due)
    }

    /** Removes cached episodes played at least [cacheKeptAfterPlayed] ago. */
    private suspend fun sweepPlayedCache() {
        if (offline.value) return
        val cached = fullyCachedIds.value
        if (cached.isEmpty()) return
        val now = clock()
        val played = library.allProgress.first().filter { it.completed }.associateBy { it.episodeId }
        val active = activeEpisode()
        for (id in cached) {
            val progress = played[id] ?: continue
            if (id == active || now - progress.updatedAtEpochMs < cacheKeptAfterPlayed.inWholeMilliseconds) continue
            if (downloads.state(id).first() != null) continue // a download of it, in any state, is the user's
            downloads.delete(id)
            playbackCache?.remove(id)
        }
    }

    private suspend fun recordFinishedDownloads(deviceId: String) {
        val recorded = mutableSetOf<Pair<EpisodeId, Long>>()
        var loaded = false
        downloads.downloads
            .map { list -> list.mapNotNull { d -> d.completedAtEpochMs?.takeIf { d.status == DownloadStatus.Done }?.let { d to it } } }
            .collect { done ->
                guarded {
                    if (!loaded) {
                        library.history.first().filter { it.deviceId == deviceId }
                            .forEach { recorded += it.episodeId to it.playedAtEpochMs }
                        loaded = true
                    }
                    // Not one taken out of the history since: that removal stands.
                    val removedAt = library.historyRemovals.first().associate { it.episodeId to it.removedAtEpochMs }
                    val fresh = done.filter { (d, at) -> (d.episodeId to at) !in recorded && (removedAt[d.episodeId] ?: Long.MIN_VALUE) < at }
                    for ((d, at) in fresh) {
                        library.addHistory(
                            HistoryEntry(
                                episodeId = d.episodeId,
                                episodeTitle = d.episodeTitle.ifBlank { library.episode(d.episodeId)?.title.orEmpty() },
                                podcastTitle = library.podcast(d.episodeId.feedUrl)?.title.orEmpty(),
                                playedAtEpochMs = at,
                                deviceId = deviceId,
                            ),
                        )
                        recorded += d.episodeId to at
                        if (library.progress(d.episodeId).first() == null) {
                            val durationMs = (library.episode(d.episodeId)?.durationSeconds ?: 0L) * 1000
                            // Timestamp 0: any real progress, from this or another device, wins over this one.
                            library.saveProgress(PlaybackProgress(d.episodeId, 0, durationMs, completed = false, updatedAtEpochMs = 0))
                        }
                    }
                    if (fresh.isNotEmpty()) onLocalChange()
                }
            }
    }

    /** Download [episode], or retry it when it failed. */
    fun download(episode: Episode) {
        deferredRemovals.value -= episode.id
        launch { downloads.enqueue(canonical(episode)) }
    }

    fun cancel(episodeId: EpisodeId) = launch { downloads.cancel(episodeId) }

    fun remove(episodeId: EpisodeId) {
        val playing = episodeId == activeEpisode()
        if (playing && offline.value) {
            deferredRemovals.value += episodeId
            return
        }
        launch {
            deleteLocalCopy(episodeId)
            if (playing) onLocalCopyRemoved(episodeId)
        }
    }

    private suspend fun deleteLocalCopy(episodeId: EpisodeId) {
        downloads.delete(episodeId)
        playbackCache?.remove(episodeId)
    }

    /** The tap action of the per-episode control for its current [control] state. */
    fun onControlClick(episode: Episode, control: DownloadControl) {
        when (control) {
            DownloadControl.NotDownloaded, DownloadControl.Cached, is DownloadControl.Failed -> download(episode)
            is DownloadControl.InProgress -> cancel(episode.id)
            DownloadControl.Downloaded -> Unit // removal goes through the menu
        }
    }

    fun setAutoDeleteAfterFinishing(enabled: Boolean) = setDeleteAfterDays(if (enabled) 0 else UserSettings.NEVER)

    /** Deletes downloads [days] after playing: 0 right away, [UserSettings.NEVER] not at all. */
    fun setDeleteAfterDays(days: Int) = launch {
        settings.update { it.withDeleteAfterDays(days) }
    }

    /** Episodes from the player may carry a file:// audioUrl; prefer the stored episode with its remote URL. */
    private suspend fun canonical(episode: Episode): Episode =
        if (episode.audioUrl.startsWith("file:")) library.episode(episode.id) ?: episode else episode

    private fun launch(block: suspend () -> Unit) {
        scope.launch { guarded(block) }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Transfer failures surface through the episode's Failed download control.
        }
    }
}

private data class AutoDeleteTick(
    val done: Set<EpisodeId>,
    val active: EpisodeId?,
    val auto: Boolean,
    val offline: Boolean,
)
