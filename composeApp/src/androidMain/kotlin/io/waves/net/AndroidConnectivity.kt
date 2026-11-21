package io.waves.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a physical network (Wi-Fi, cellular, ethernet) reaches the Internet, as the system validated it.
 * VPNs are ignored: a VPN stays up, and keeps its "validated" flag, after Wi-Fi and mobile data go away.
 */
fun androidOnline(context: Context, scope: CoroutineScope): StateFlow<Boolean> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
    @Suppress("DEPRECATION")
    val initially = manager.allNetworks.any { manager.getNetworkCapabilities(it)?.reachesInternet() == true }
    return callbackFlow {
        val validated = ConcurrentHashMap.newKeySet<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.reachesInternet()) validated.add(network) else validated.remove(network)
                trySend(validated.isNotEmpty())
            }

            override fun onLost(network: Network) {
                validated.remove(network)
                trySend(validated.isNotEmpty())
            }
        }
        manager.registerNetworkCallback(request, callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, initially)
}

private fun NetworkCapabilities.reachesInternet() =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

/**
 * Whether the device is on Wi-Fi or a cable that isn't metered, rather than mobile data or a phone's hotspot. Judged
 * by the physical networks, as [androidOnline] is: a VPN on top is the default network, and most VPNs count as metered
 * whatever carries them, so home Wi-Fi under a VPN would read as mobile data. Android sends traffic over such a Wi-Fi
 * when it's there. True until told otherwise.
 */
fun androidUnmetered(context: Context, scope: CoroutineScope): StateFlow<Boolean> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()
    @Suppress("DEPRECATION")
    val initially = manager.allNetworks.mapNotNull(manager::getNetworkCapabilities)
        .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
        .let { physical -> physical.isEmpty() || physical.any { it.isUnmetered() } }
    return callbackFlow {
        val unmetered = ConcurrentHashMap<Network, Boolean>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                unmetered[network] = capabilities.isUnmetered()
                trySend(unmetered.values.any { it })
            }

            override fun onLost(network: Network) {
                unmetered.remove(network)
                trySend(unmetered.values.any { it })
            }
        }
        manager.registerNetworkCallback(request, callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, initially)
}

/** Wi-Fi or a cable that isn't metered: not a phone's hotspot, which the system marks metered, nor mobile data. */
private fun NetworkCapabilities.isUnmetered() =
    (hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
