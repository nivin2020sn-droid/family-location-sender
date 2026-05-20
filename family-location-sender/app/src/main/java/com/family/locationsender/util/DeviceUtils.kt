package com.family.locationsender.util

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build

object DeviceUtils {

    fun batteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    fun isGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    data class NetInfo(val online: Boolean, val type: String)

    fun networkInfo(context: Context): NetInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetInfo(false, "unknown")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return NetInfo(false, "unknown")
            val caps = cm.getNetworkCapabilities(net) ?: return NetInfo(false, "unknown")
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "unknown"
            }
            return NetInfo(online, type)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            val online = info?.isConnected == true
            @Suppress("DEPRECATION")
            val type = when (info?.type) {
                ConnectivityManager.TYPE_WIFI -> "wifi"
                ConnectivityManager.TYPE_MOBILE -> "mobile"
                else -> "unknown"
            }
            return NetInfo(online, type)
        }
    }
}
