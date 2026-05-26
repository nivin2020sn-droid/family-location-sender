package com.family.locationsender.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.family.locationsender.data.Prefs
import com.family.locationsender.service.LocationForegroundService

/**
 * Wakes up every 60 s thanks to [KeepaliveAlarm]. If tracking is supposed
 * to be on, it (a) kicks the foreground service (idempotent, the service
 * uses START_STICKY anyway) and (b) re-arms the next alarm tick. If the
 * user has explicitly stopped tracking, neither happens — the alarm chain
 * dies cleanly.
 *
 * This is the only mechanism that guarantees the service comes back if
 * the OEM kill-policy on a head unit has killed it (very common on cheap
 * Android Auto devices).
 */
class KeepaliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val prefs = Prefs.get(context)
            Log.i(
                TAG,
                "tick: trackingEnabled=${prefs.trackingEnabled} " +
                    "firstRunDone=${prefs.firstRunDone}"
            )
            if (prefs.firstRunDone && prefs.trackingEnabled) {
                Log.i(TAG, "tick: re-arming LocationForegroundService")
                LocationForegroundService.start(context)
                // Always schedule the next tick while we're meant to be
                // running. setAndAllowWhileIdle is one-shot.
                KeepaliveAlarm.schedule(context)
            } else {
                Log.i(TAG, "tick: tracking disabled, NOT rescheduling")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onReceive failed", t)
        }
    }

    companion object {
        private const val TAG = "FLS-KeepaliveReceiver"
    }
}
