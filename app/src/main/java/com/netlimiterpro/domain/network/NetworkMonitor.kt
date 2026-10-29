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
 * Observes the underlying network transport (Wi-Fi, Cellular, Other, or Offline)
 * excluding VPN transports. Fully compatible with Android 8.0+ (API 26) and EMUI 8.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun observe(): Flow<ActiveNetworkKind> = callbackFlow {
        fun resolveUnderlying(): ActiveNetworkKind {
            var hasWifi = false
            var hasCellular = false

            // 1. Inspect all active networks via NetworkCapabilities
            runCatching {
                val networks = connectivity.allNetworks
                for (network in networks) {
                    val caps = connectivity.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                    // Do NOT require NET_CAPABILITY_VALIDATED: EMUI 8/Android 8 often omits it
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) hasWifi = true
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) hasCellular = true
                }
            }

            if (hasWifi) return ActiveNetworkKind.WIFI
            if (hasCellular) return ActiveNetworkKind.CELLULAR

            // 2. Active network capabilities fallback (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = runCatching { connectivity.activeNetwork }.getOrNull()
                val caps = activeNetwork?.let { runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull() }
                if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return ActiveNetworkKind.WIFI
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return ActiveNetworkKind.CELLULAR
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return ActiveNetworkKind.OTHER
                }
            }

            // 3. Robust fallback using activeNetworkInfo (vital for Huawei EMUI 8 / Android 8.0)
            @Suppress("DEPRECATION")
            val activeInfo = runCatching { connectivity.activeNetworkInfo }.getOrNull()
            if (activeInfo != null && activeInfo.isConnected) {
                return when (activeInfo.type) {
                    ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_WIMAX -> ActiveNetworkKind.WIFI
                    ConnectivityManager.TYPE_MOBILE,
                    ConnectivityManager.TYPE_MOBILE_DUN,
                    ConnectivityManager.TYPE_MOBILE_HIPRI -> ActiveNetworkKind.CELLULAR
                    ConnectivityManager.TYPE_ETHERNET -> ActiveNetworkKind.OTHER
                    else -> ActiveNetworkKind.OTHER
                }
            }

            return ActiveNetworkKind.OFFLINE
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
            runCatching { connectivity.registerDefaultNetworkCallback(callback) }
                .onFailure {
                    val request = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    connectivity.registerNetworkCallback(request, callback)
                }
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
