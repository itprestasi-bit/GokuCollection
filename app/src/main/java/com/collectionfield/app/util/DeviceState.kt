package com.collectionfield.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

object DeviceState {
    fun batteryPercent(context: Context): Int {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return getBatteryPercentFromIntent(status)
    }

    private fun getBatteryPercentFromIntent(intent: Intent?): Int {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return -1
        return ((level * 100f) / scale).toInt().coerceIn(0, 100)
    }

    fun batteryFlow(context: Context): Flow<Int> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(getBatteryPercentFromIntent(intent))
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        awaitClose { context.unregisterReceiver(receiver) }
    }.onStart { emit(batteryPercent(context)) }.distinctUntilChanged()

    fun networkState(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "OFFLINE"
        return getNetworkLabel(cm, network)
    }

    private fun getNetworkLabel(cm: ConnectivityManager, network: Network?): String {
        val caps = cm.getNetworkCapabilities(network) ?: return "OFFLINE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "ONLINE"
            else -> "OFFLINE"
        }
    }

    fun networkFlow(context: Context): Flow<String> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getNetworkLabel(cm, network))
            }

            override fun onLost(network: Network) {
                trySend("OFFLINE")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(getNetworkLabel(cm, network))
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback)
        } else {
            val request = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.onStart { emit(networkState(context)) }.distinctUntilChanged()
}

