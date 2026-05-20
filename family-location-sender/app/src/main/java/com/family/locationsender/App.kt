package com.family.locationsender

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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
