package io.waves.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Keeps loading through a connection that is gone for a few minutes. ExoPlayer's own policy gives a load three more
 * tries, seconds apart, and then fails playback, which stays stopped when the network is back. With this one a load
 * that fails for want of a connection is tried every few seconds for about [PATIENT_TRIES] × 5 s: playback sits
 * buffering meanwhile and carries on by itself. Anything else (a server's refusal, bad data) fails as soon as before.
 */
@UnstableApi
internal class PatientLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    // The count is decided in getRetryDelayMsFor, which knows what failed.
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val delay = super.getRetryDelayMsFor(loadErrorInfo)
        if (delay == C.TIME_UNSET) return delay
        val tries = if (isConnectionLoss(loadErrorInfo.exception)) PATIENT_TRIES
        else super.getMinimumLoadableRetryCount(loadErrorInfo.mediaLoadData.dataType)
        return if (loadErrorInfo.errorCount > tries) C.TIME_UNSET else delay
    }

    internal companion object {
        /** About ten minutes of tries, 5 s apart. */
        const val PATIENT_TRIES = 120

        /** Whether [error] is the network failing to carry a request, rather than a server's answer to one. */
        fun isConnectionLoss(error: Throwable): Boolean {
            val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(10).toList()
            if (chain.any { it is HttpDataSource.InvalidResponseCodeException || it is HttpDataSource.InvalidContentTypeException }) return false
            return chain.any {
                it is UnknownHostException || it is SocketException || it is SocketTimeoutException || it is SSLException
            }
        }
    }
}
