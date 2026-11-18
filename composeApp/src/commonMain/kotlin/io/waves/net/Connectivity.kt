package io.waves.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the device can reach the Internet, for platforms without a system signal: runs [check] now and then
 * every [onlineEvery] while it passes, every [offlineEvery] while it fails (to notice the network coming back
 * quickly), and at once whenever [changes] says the network may have changed (an interface went up or down).
 * Starts out online so nothing is hidden before the first check finishes.
 */
fun pollOnline(
    scope: CoroutineScope,
    onlineEvery: Duration = 60.seconds,
    offlineEvery: Duration = 10.seconds,
    changes: Flow<Unit> = emptyFlow(),
    check: suspend () -> Boolean,
): StateFlow<Boolean> = channelFlow {
    // A change during a check isn't lost: the check runs again right after.
    val changed = Channel<Unit>(Channel.CONFLATED)
    launch { changes.collect { changed.trySend(Unit) } }
    while (true) {
        val online = check()
        send(online)
        withTimeoutOrNull(if (online) onlineEvery else offlineEvery) { changed.receive() }
    }
}.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, true)
