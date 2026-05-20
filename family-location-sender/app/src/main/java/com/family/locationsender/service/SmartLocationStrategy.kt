package com.family.locationsender.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import com.family.locationsender.data.Prefs
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

/**
 * Decides the desired update interval based on the user's chosen mode.
 * Returns a [LocationRequest] suitable for FusedLocationProviderClient.
 *
 * Smart Mode rules:
 *  - Moving (speed > 1 m/s or recent significant displacement): 1 min
 *  - Stationary: 15 min
 *  - Hard movement > 100m since last fix: immediate (handled by service)
 */
class SmartLocationStrategy(private val prefs: Prefs) {

    private var lastLocation: Location? = null
    private var lastMovementAt: Long = 0L

    fun onNewLocation(loc: Location) {
        if (lastLocation == null) {
            lastLocation = loc
            lastMovementAt = SystemClock.elapsedRealtime()
            return
        }
        val dist = lastLocation!!.distanceTo(loc)
        if (dist >= 25f || loc.speed > 1.0f) {
            lastMovementAt = SystemClock.elapsedRealtime()
        }
        lastLocation = loc
    }

    /** Returns true if a hard movement (> 100m) occurred and we should send immediately. */
    fun shouldForceSend(newLoc: Location): Boolean {
        val prev = lastLocation ?: return true
        return prev.distanceTo(newLoc) >= 100f
    }

    fun buildRequest(): LocationRequest {
        val mode = prefs.updateInterval
        val intervalMs = when (mode) {
            Prefs.INTERVAL_1MIN -> 60_000L
            Prefs.INTERVAL_3MIN -> 3 * 60_000L
            Prefs.INTERVAL_5MIN -> 5 * 60_000L
            Prefs.INTERVAL_15MIN -> 15 * 60_000L
            Prefs.INTERVAL_SMART -> smartInterval()
            else -> smartInterval()
        }
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()
    }

    private fun smartInterval(): Long {
        val movingRecently = SystemClock.elapsedRealtime() - lastMovementAt < 5 * 60_000L
        return if (movingRecently) 60_000L else 15 * 60_000L
    }
}
