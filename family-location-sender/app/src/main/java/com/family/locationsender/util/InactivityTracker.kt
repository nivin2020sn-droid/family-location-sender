package com.family.locationsender.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock

/**
 * Tracks foreground inactivity. The session is considered "active" as long as
 * any user interaction has happened within [timeoutMs]. Going to background or
 * switching to another app does NOT lock immediately — only a real lack of
 * interaction for [timeoutMs] does.
 *
 * Behaviour:
 *  - Every call to [touch] (or `onUserInteraction`) refreshes `lastInteractionAt`.
 *  - While the app is in the foreground, a ticker checks every 5 s whether the
 *    timeout has elapsed and, if so, fires [onTimeout].
 *  - While the app is in the background the timer is paused; when the user
 *    re-enters the app, we compare the wall-clock against `lastInteractionAt`
 *    and fire [onTimeout] right away if more than [timeoutMs] elapsed.
 *  - The background location service is completely independent of this and
 *    keeps running.
 */
class InactivityTracker(
    private val timeoutMs: Long,
    private val onTimeout: () -> Unit
) : Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0
    @Volatile private var lastInteractionAt = SystemClock.elapsedRealtime()
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

    /** Refresh the last-interaction timestamp. */
    fun touch() {
        lastInteractionAt = SystemClock.elapsedRealtime()
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (startedActivities == 1) {
            // App just came back to the foreground. If we've been idle longer
            // than the timeout while in background, fire onTimeout NOW so the
            // password screen comes up. Otherwise resume the foreground ticker.
            val idle = SystemClock.elapsedRealtime() - lastInteractionAt
            if (idle >= timeoutMs) {
                onTimeout()
            } else {
                handler.removeCallbacks(tickRunnable)
                handler.postDelayed(tickRunnable, 5_000)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        // Treat a resume as user activity too so navigation between our own
        // screens doesn't accidentally trigger a lock.
        touch()
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        startedActivities--
        if (startedActivities <= 0) {
            startedActivities = 0
            // Stop the foreground ticker, but DO NOT lock here — we only lock
            // after [timeoutMs] of true inactivity (checked on re-entry).
            handler.removeCallbacks(tickRunnable)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
