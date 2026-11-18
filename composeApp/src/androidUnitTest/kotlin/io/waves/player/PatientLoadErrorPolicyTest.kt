package io.waves.player

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class PatientLoadErrorPolicyTest {
    @Test
    fun aRequestTheNetworkDidNotCarryIsAConnectionLoss() {
        assertEquals(true, PatientLoadErrorPolicy.isConnectionLoss(UnknownHostException("rr1.googlevideo.com")))
        assertEquals(true, PatientLoadErrorPolicy.isConnectionLoss(IOException("open failed", ConnectException("Network is unreachable"))))
        assertEquals(true, PatientLoadErrorPolicy.isConnectionLoss(IOException(IOException(SocketTimeoutException()))))
    }

    @Test
    fun otherFailuresAreNot() {
        assertEquals(false, PatientLoadErrorPolicy.isConnectionLoss(EOFException("Empty range at 10 of 20")))
        assertEquals(false, PatientLoadErrorPolicy.isConnectionLoss(IOException("bad data", IllegalStateException())))
    }
}
