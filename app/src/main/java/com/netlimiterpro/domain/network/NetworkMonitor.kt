package com.netlimiterpro.domain.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class ActiveNetworkKind {
    WIFI,
    CELLULAR,
    OTHER,
    OFFLINE
}

/**
 * Observes the default network and excludes VPN transports when determining
 * the underlying transport. The callback is registered only while collected.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun observe(): Flow<ActiveNetworkKind> = callbackFlow {
        fun resolveUnderlying(): ActiveNetworkKind {
            val networks = connectivity.allNetworks
            var hasWifi = false
            var hasCellular = false
            for (network in networks) {
                val caps = connectivity.getNetworkCapabilities(network) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) hasWifi = true
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) hasCellular = true
            }
            return when {
                hasWifi -> ActiveNetworkKind.WIFI
                hasCellular -> ActiveNetworkKind.CELLULAR
                else -> {
                    val active = connectivity.activeNetwork
                    val caps = active?.let(connectivity::getNetworkCapabilities)
                    if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        ActiveNetworkKind.OFFLINE
                    } else ActiveNetworkKind.OTHER
                }
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(resolveUnderlying())
            }

            override fun onLost(network: Network) {
                trySend(resolveUnderlying())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(resolveUnderlying())
            }
        }

        trySend(resolveUnderlying())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivity.registerDefaultNetworkCallback(callback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.registerNetworkCallback(request, callback)
        }
        awaitClose {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }
}
