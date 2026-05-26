package com.family.locationsender.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * External keep-alive alarm that survives the foreground service being
 * killed by the OEM kill-policy on cheap head units. Every 60 s the alarm
 * fires [KeepaliveReceiver] which (a) re-arms itself and (b) re-starts the
 * [com.family.locationsender.service.LocationForegroundService] if tracking
 * is supposed to be on. Uses [AlarmManager.setAndAllowWhileIdle] which works
 * during Doze and does NOT need the `SCHEDULE_EXACT_ALARM` runtime
 * permission on Android 12+.
 */
object KeepaliveAlarm {
    private const val TAG = "FLS-KeepaliveAlarm"
    const val ACTION = "com.family.locationsender.action.KEEPALIVE"
    private const val REQUEST_CODE = 7301
    private const val INTERVAL_MS = 60_000L

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            Log.e(TAG, "AlarmManager not available")
            return
        }
        val pi = pendingIntent(context)
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.i(TAG, "scheduled next tick in ${INTERVAL_MS / 1000}s")
        } catch (t: Throwable) {
            Log.e(TAG, "schedule failed", t)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.cancel(pendingIntent(context))
            Log.i(TAG, "alarm cancelled")
        } catch (t: Throwable) {
            Log.e(TAG, "cancel failed", t)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, KeepaliveReceiver::class.java).apply {
            action = ACTION
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
