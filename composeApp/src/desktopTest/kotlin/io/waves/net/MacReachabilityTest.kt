package io.waves.net

import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertNotSame

class MacReachabilityTest {
    @Test
    fun watchesTheSystemsNetworkChangesOnAMac() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return
        assertNotSame(emptyFlow<Unit>(), MacReachability.changes)
    }
}
