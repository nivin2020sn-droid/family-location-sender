package com.family.locationsender

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.family.locationsender.ui.LockActivity
import com.family.locationsender.util.InactivityTracker
import com.family.locationsender.util.LocaleHelper
import com.family.locationsender.util.SessionState

class App : Application() {

    lateinit var inactivityTracker: InactivityTracker
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applySaved(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        inactivityTracker = InactivityTracker(
            timeoutMs = 60_000L, // 1 minute idle -> lock
            onTimeout = { forceLock() },
            onBackgrounded = { forceLock() }
        )
        registerActivityLifecycleCallbacks(inactivityTracker)
    }

    private fun forceLock() {
        if (!SessionState.authenticated) return
        SessionState.lock()
        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
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
        const val CHANNEL_ID = "fls_location_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile lateinit var instance: App
            private set
    }
}
