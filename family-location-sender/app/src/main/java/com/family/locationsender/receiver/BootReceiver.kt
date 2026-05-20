package com.family.locationsender.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.family.locationsender.data.Prefs
import com.family.locationsender.service.LocationForegroundService

/**
 * Re-starts the location service after device boot or app upgrade
 * when tracking was previously enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            val prefs = Prefs.get(context)
            if (prefs.firstRunDone && prefs.trackingEnabled) {
                LocationForegroundService.start(context)
            }
        }
    }
}
