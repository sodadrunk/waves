package io.waves.ui

import io.waves.currentTimeMillis
import io.waves.model.EpisodeId
import io.waves.player.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Plays on by itself after the connection was lost for a while. Playback that failed for want of a connection
 * ([PlaybackState.connectionLost]; the players only fail what was asked to play) is tried again ([retry]) once the device isn't [offline], a little later
 * each time it fails again, until sound comes out, it has been tried [tries] times, or [giveUpAfter] has passed since
 * it first failed: audio starting up long after that would come as a surprise. Playing something else, or playing again by hand, starts over.
 */
class PlaybackReconnect(
    private val playback: Flow<PlaybackState>,
    private val offline: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val retry: () -> Unit,
    private val giveUpAfter: Duration = 10.minutes,
    private val tries: Int = 8,
    private val firstWait: Duration = 3.seconds,
    private val longestWait: Duration = 1.minutes,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private data class Status(val episode: EpisodeId?, val failed: Boolean, val sounding: Boolean)

    fun start(): Job = scope.launch {
        var episode: EpisodeId? = null
        var failedAt: Long? = null
        var attempts = 0
        playback
            .map { Status(it.episode?.id, it.error != null && it.connectionLost, it.error == null && it.isPlaying && !it.isBuffering) }
            .distinctUntilChanged()
            .collectLatest { status ->
                if (status.episode != episode || status.sounding) {
                    episode = status.episode
                    failedAt = null
                    attempts = 0
                }
                if (!status.failed || attempts >= tries) return@collectLatest
                val since = failedAt ?: clock().also { failedAt = it }
                val left = giveUpAfter.inWholeMilliseconds - (clock() - since)
                if (left <= 0) return@collectLatest
                withTimeoutOrNull(left) {
                    // Each try that changes nothing seen here (it failed again at once) is followed by the next.
                    while (attempts < tries) {
                        // The wait also covers a connection the detector hasn't seen go yet, and one just back settling.
                        val wait = (firstWait * (1 shl attempts)).coerceAtMost(longestWait)
                        do {
                            offline.first { !it }
                            delay(wait)
                        } while (offline.value)
                        attempts++
                        retry()
                    }
                }
            }
    }
}
