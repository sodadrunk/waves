package io.waves.ui

import io.waves.player.PlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReconnectTest {
    private val episode = episode(podcastA, 1)
    private val playing = PlaybackState(episode, isPlaying = true, positionMs = 5_000, durationMs = 600_000)
    private val failed = playing.copy(isPlaying = false, error = "Source error", connectionLost = true)
    private val state = MutableStateFlow(playing)
    private val offline = MutableStateFlow(false)
    private var retries = 0

    /** [onRetry] stands in for the player: what trying again leads to. */
    private fun TestScope.reconnect(onRetry: () -> Unit = {}) = PlaybackReconnect(
        state, offline, backgroundScope,
        retry = {
            retries++
            onRetry()
        },
        clock = { testScheduler.currentTime },
    ).start()

    @Test
    fun triesAgainOnceTheConnectionIsBack() = runTest {
        reconnect { state.value = playing }
        runCurrent()
        offline.value = true
        state.value = failed
        advanceTimeBy(3.minutes)
        assertEquals(0, retries)

        offline.value = false
        advanceTimeBy(2.seconds)
        assertEquals(0, retries) // a moment for the connection to settle
        advanceTimeBy(2.seconds)
        assertEquals(1, retries)
        advanceTimeBy(10.minutes)
        assertEquals(1, retries)
    }

    @Test
    fun triesAFailureTheDetectorHasNotSeenLaterEachTime() = runTest {
        reconnect { state.update { it.copy(isPlaying = true, isBuffering = true, error = null) } }
        runCurrent()
        state.value = failed
        advanceTimeBy(4.seconds)
        assertEquals(1, retries)
        state.value = failed // and it fails again
        advanceTimeBy(4.seconds)
        assertEquals(1, retries)
        advanceTimeBy(3.seconds)
        assertEquals(2, retries)
    }

    @Test
    fun givesUpAfterAWhileAndAfterSomeTries() = runTest {
        reconnect() // trying again changes nothing
        runCurrent()
        offline.value = true
        state.value = failed
        advanceTimeBy(11.minutes)
        offline.value = false
        advanceTimeBy(5.minutes)
        assertEquals(0, retries)

        // Something else failing while online: tried a few times, not for ever.
        state.value = failed.copy(episode = episode(podcastA, 2))
        advanceTimeBy(9.minutes)
        val tried = retries
        assertEquals(true, tried in 1..8)
        advanceTimeBy(30.minutes)
        assertEquals(tried, retries)
    }

    @Test
    fun leavesAloneAFailureThatWasNotTheConnection() = runTest {
        reconnect()
        runCurrent()
        state.value = failed.copy(error = "This video is private", connectionLost = false)
        advanceTimeBy(10.minutes)
        assertEquals(0, retries)
    }

    @Test
    fun leavesAloneWhatTheUserMovedOnFrom() = runTest {
        reconnect()
        runCurrent()
        offline.value = true
        state.value = failed
        runCurrent()
        state.value = PlaybackState(episode(podcastA, 2), isPlaying = false) // picked something else, paused
        offline.value = false
        advanceTimeBy(5.minutes)
        assertEquals(0, retries)
    }
}
