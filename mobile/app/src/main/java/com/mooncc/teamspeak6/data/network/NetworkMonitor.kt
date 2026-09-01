package com.mooncc.teamspeak6.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * Tracks whether a network with validated internet access is available.
 *
 * Reconnect logic waits on this instead of blindly retrying: a retry issued while
 * the radio is down burns an attempt and a backoff step for nothing, and the
 * callback also lets a reconnect fire the moment connectivity returns rather than
 * at the end of the current backoff.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /**
     * Cold flow of connectivity changes. Collecting it keeps [isOnline] fresh; the
     * callback is unregistered as soon as collection stops.
     */
    val updates: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publish(currentlyOnline())
            }

            override fun onLost(network: Network) {
                publish(currentlyOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                publish(currentlyOnline())
            }

            private fun publish(online: Boolean) {
                _isOnline.value = online
                trySend(online)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        if (manager == null) {
            // No connectivity service: assume online so we never block forever.
            _isOnline.value = true
            trySend(true)
        } else {
            manager.registerNetworkCallback(request, callback)
            val initial = currentlyOnline()
            _isOnline.value = initial
            trySend(initial)
        }

        awaitClose {
            if (manager != null) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()

    /** Suspends until a usable network is available, returning immediately if it already is. */
    suspend fun awaitOnline() {
        if (currentlyOnline()) return
        updates.first { it }
    }

    private fun currentlyOnline(): Boolean {
        val connectivity = manager ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = connectivity.activeNetwork ?: return false
            val capabilities = connectivity.getNetworkCapabilities(active) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        return connectivity.activeNetworkInfo?.isConnected == true
    }
}
