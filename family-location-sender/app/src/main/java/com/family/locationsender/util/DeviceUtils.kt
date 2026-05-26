package com.family.locationsender.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build

object DeviceUtils {

    /**
     * Returns the battery percentage as a value clamped to 0..100.
     * Some Android head units (car stereos) return [Int.MIN_VALUE] or a
     * negative number when no battery is wired up — we treat that as 0
     * instead of leaking a junk value to the UI or the server.
     */
    fun batteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val raw = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return if (raw in 0..100) raw else 0
    }

    /**
     * Best-effort read of car / power-input voltage on Android head units.
     *
     * Strategy: register a null receiver on [Intent.ACTION_BATTERY_CHANGED]
     * to grab the current battery sticky intent. The extra
     * [BatteryManager.EXTRA_VOLTAGE] is reported in millivolts.
     *
     *  - Phone Li-Ion cells live around 3.0 – 4.4 V → ignore (not car voltage).
     *  - 12 V automotive systems read 10 – 15 V (≈ 10000 – 15000 mV) while
     *    running; 24 V trucks read 20 – 30 V. Anything in 6 – 30 V we treat
     *    as a car-side voltage and display.
     *  - Otherwise we return `"Good"` instead of showing a misleading number.
     */
    fun carVoltageLabel(context: Context): String {
        val v = readVoltageMillivolts(context)
        if (v != null && v in 6_000..30_000) {
            val volts = v / 1000.0
            return "%.1fV".format(volts)
        }
        return "Good"
    }

    private fun readVoltageMillivolts(context: Context): Int? {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return null
            val mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            if (mv <= 0 || mv == Int.MIN_VALUE) null else mv
        } catch (_: Throwable) {
            null
        }
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
