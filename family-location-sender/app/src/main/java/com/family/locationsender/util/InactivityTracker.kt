package com.family.locationsender.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock

/**
 * Tracks foreground inactivity. When the app has not seen any user interaction
 * or activity resume for [timeoutMs], [onTimeout] is invoked. Used to force-lock
 * the UI after 1 minute of idle time.
 *
 * Also locks immediately when all activities go to background (screen off,
 * another app, recents).
 */
class InactivityTracker(
    private val timeoutMs: Long,
    private val onTimeout: () -> Unit,
    private val onBackgrounded: () -> Unit
) : Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0
    private var lastInteractionAt = SystemClock.elapsedRealtime()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (startedActivities > 0) {
                val idle = SystemClock.elapsedRealtime() - lastInteractionAt
                if (idle >= timeoutMs) {
                    onTimeout()
                } else {
                    handler.postDelayed(this, 5_000)
                }
            }
        }
    }

    fun touch() {
        lastInteractionAt = SystemClock.elapsedRealtime()
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (startedActivities == 1) {
            // Just came to foreground
            touch()
            handler.removeCallbacks(tickRunnable)
            handler.postDelayed(tickRunnable, 5_000)
        }
    }

    override fun onActivityResumed(activity: Activity) { touch() }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {
        startedActivities--
        if (startedActivities <= 0) {
            startedActivities = 0
            handler.removeCallbacks(tickRunnable)
            onBackgrounded()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
