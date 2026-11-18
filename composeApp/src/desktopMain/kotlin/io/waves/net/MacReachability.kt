package io.waves.net

import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * macOS telling that the network changed (Wi-Fi joined or left, a cable, a VPN), through SystemConfiguration's
 * SCNetworkReachability. It says a route exists, not that the Internet answers through it (a captive portal has a
 * route), so it only prompts the check that does ([desktopOnline]) instead of waiting for its next round.
 */
internal object MacReachability {
    /** Emits at each change; nothing on other systems, or if the framework can't be reached. */
    val changes: Flow<Unit> by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return@lazy emptyFlow()
        runCatching { Native.changes }.getOrElse { emptyFlow() }
    }

    /** `void callback(SCNetworkReachabilityRef target, SCNetworkReachabilityFlags flags, void *info)` */
    private fun interface ReachabilityCallback : Callback {
        fun invoke(target: Pointer?, flags: Int, info: Pointer?)
    }

    /** Process-wide: the reachability object and its callback are never released. */
    private object Native {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        private val callback = ReachabilityCallback { _, _, _ -> changes.tryEmit(Unit) }
        private val target: Pointer

        init {
            val framework = NativeLibrary.getInstance("SystemConfiguration")
            // The address 0.0.0.0: "is there a route to anywhere", which changes as networks come and go.
            val anywhere = Memory(SOCKADDR_IN_SIZE).apply {
                clear()
                setByte(0, SOCKADDR_IN_SIZE.toByte()) // sin_len
                setByte(1, AF_INET)
            }
            target = framework.getFunction("SCNetworkReachabilityCreateWithAddress").invokePointer(arrayOf(null, anywhere))
                ?: error("No reachability target")
            val queue = NativeLibrary.getInstance("System").getFunction("dispatch_get_global_queue").invokePointer(arrayOf(0L, 0L))
            val set = framework.getFunction("SCNetworkReachabilitySetCallback").invokeInt(arrayOf(target, callback, null)) and 0xff
            val queued = framework.getFunction("SCNetworkReachabilitySetDispatchQueue").invokeInt(arrayOf(target, queue)) and 0xff
            check(set != 0 && queued != 0) { "Reachability changes can't be watched" }
        }

        private const val SOCKADDR_IN_SIZE = 16L
        private const val AF_INET: Byte = 2
    }
}
