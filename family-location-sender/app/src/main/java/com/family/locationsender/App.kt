package com.family.locationsender

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.family.locationsender.data.Prefs
import com.family.locationsender.receiver.KeepaliveAlarm
import com.family.locationsender.service.LocationForegroundService
import com.family.locationsender.ui.LockActivity
import com.family.locationsender.util.InactivityTracker
import com.family.locationsender.util.LocaleHelper
import com.family.locationsender.util.SessionState

class App : Application() {

    lateinit var inactivityTracker: InactivityTracker
        private set

    override fun attachBaseContext(base: Context) {
        // Locale application can throw on some devices if encrypted prefs
        // fail to initialise. Fall back to the original base context so the
        // app does NOT crash at startup.
        val ctx = try {
            LocaleHelper.applySaved(base)
        } catch (t: Throwable) {
            Log.e(TAG, "applySaved locale failed; using base context", t)
            base
        }
        super.attachBaseContext(ctx)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            createNotificationChannel()
        } catch (t: Throwable) {
            Log.e(TAG, "createNotificationChannel failed", t)
        }

        try {
            inactivityTracker = InactivityTracker(
                timeoutMs = 3 * 60_000L, // 3 minutes idle -> lock
                onTimeout = { safeForceLock() }
            )
            registerActivityLifecycleCallbacks(inactivityTracker)
        } catch (t: Throwable) {
            Log.e(TAG, "InactivityTracker init failed", t)
        }

        // ============================================================
        // AUTO BOOT CHECK
        // ------------------------------------------------------------
        // App.onCreate() runs on every process start. On car head units
        // that DO NOT deliver BOOT_COMPLETED to third-party apps, this
        // is in practice the only entry-point that fires after the
        // device powers on. If the user's last state was tracking ON,
        // we MUST execute the exact same logic as pressing the Start
        // Tracking button — without any user interaction.
        // ============================================================
        try {
            autoResumeTrackingIfNeeded(reason = "App.onCreate")
        } catch (t: Throwable) {
            Log.e(TAG, "autoResumeTrackingIfNeeded failed", t)
        }
    }

    /**
     * The single source of truth for "make the location pipeline run".
     * Called from:
     *   - App.onCreate()  (every process launch — works on devices that
     *     never deliver BOOT_COMPLETED to user apps)
     *   - BootReceiver    (when BOOT_COMPLETED *is* delivered)
     *   - KeepaliveAlarm  (every 60 s as long as tracking is enabled)
     *   - LockActivity    (so opening the app post-boot revives it too)
     *   - Service watchdog (self-heal from inside the service)
     *
     * It is **idempotent**: calling it many times in a row only ever
     * results in one Fused subscription + one keepalive chain.
     */
    fun autoResumeTrackingIfNeeded(reason: String) {
        val prefs = Prefs.get(this)
        Log.i(TAG, "AUTO BOOT CHECK [$reason] — " +
                "trackingEnabled=${prefs.trackingEnabled} " +
                "firstRunDone=${prefs.firstRunDone}")
        if (!prefs.firstRunDone) {
            Log.i(TAG, "AUTO BOOT CHECK [$reason] — setup not finished, skipping")
            return
        }
        if (!prefs.trackingEnabled) {
            Log.i(TAG, "AUTO BOOT CHECK [$reason] — trackingEnabled=false, NOT auto-starting")
            return
        }
        Log.i(TAG, "AUTO BOOT CHECK [$reason] — calling StartTracking logic")
        // Identical to what the Start Tracking button does in MainActivity.
        LocationForegroundService.start(this)
        KeepaliveAlarm.schedule(this)
    }

    private fun safeForceLock() {
        try {
            if (!SessionState.authenticated) return
            SessionState.lock()
            val intent = Intent(this, LockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "forceLock failed", t)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification for background location updates"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "FLS-App"
        const val CHANNEL_ID = "fls_location_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile lateinit var instance: App
            private set
    }
}
