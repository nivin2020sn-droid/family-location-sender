package com.family.locationsender.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.family.locationsender.data.Prefs
import com.family.locationsender.service.LocationForegroundService

/**
 * Re-starts the location service after device boot, app upgrade, OR any of
 * the OEM "quick boot" power-on broadcasts that cheap car head units use
 * instead of the standard one. Also re-arms the [KeepaliveAlarm] so the
 * service survives subsequent kills.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Device boot detected — action='$action'")

        val isBootish =
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_REBOOT

        if (!isBootish) {
            Log.w(TAG, "Ignoring unrelated action: $action")
            return
        }

        try {
            val prefs = Prefs.get(context)
            Log.i(
                TAG,
                "Boot state: firstRunDone=${prefs.firstRunDone} " +
                    "trackingEnabled=${prefs.trackingEnabled}"
            )
            if (prefs.firstRunDone && prefs.trackingEnabled) {
                Log.i(TAG, "Tracking state restored — starting service + keepalive alarm")
                LocationForegroundService.start(context)
                KeepaliveAlarm.schedule(context)
            } else {
                Log.i(TAG, "Tracking not enabled, nothing to start")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "BootReceiver failed", t)
        }
    }

    companion object {
        private const val TAG = "FLS-BootReceiver"
    }
}
