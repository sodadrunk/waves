package io.waves.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PollOnlineTest {
    @Test
    fun followsTheCheckAndRetriesSoonerWhileOffline() = runTest {
        var reachable = true
        var checks = 0
        val online = pollOnline(backgroundScope, onlineEvery = 60.seconds, offlineEvery = 10.seconds) {
            checks++
            reachable
        }
        assertEquals(true, online.value)
        runCurrent()
        assertEquals(1, checks)

        reachable = false
        advanceTimeBy(59.seconds)
        runCurrent()
        assertEquals(true, online.value)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(false, online.value)

        reachable = true
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(true, online.value)
        assertEquals(3, checks)
    }

    @Test
    fun checksAtOnceWhenTheNetworkChanges() = runTest {
        var reachable = true
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val online = pollOnline(backgroundScope, changes = changes) { reachable }
        runCurrent()

        reachable = false
        changes.tryEmit(Unit)
        runCurrent()
        assertEquals(false, online.value)

        reachable = true
        advanceTimeBy(2.seconds)
        changes.tryEmit(Unit)
        runCurrent()
        assertEquals(true, online.value)
    }
}
