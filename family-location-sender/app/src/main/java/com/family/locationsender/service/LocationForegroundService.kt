package com.family.locationsender.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.family.locationsender.App
import com.family.locationsender.R
import com.family.locationsender.data.LocationPayload
import com.family.locationsender.data.Prefs
import com.family.locationsender.network.ApiClient
import com.family.locationsender.ui.LockActivity
import com.family.locationsender.util.DeviceUtils
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps a persistent notification and pushes location
 * updates to the configured endpoint based on the user's interval / smart mode.
 */
class LocationForegroundService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var strategy: SmartLocationStrategy
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var callback: LocationCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        strategy = SmartLocationStrategy(prefs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_TEST_SEND -> scope.launch { sendOnce(force = true) }
            ACTION_STOP -> {
                prefs.trackingEnabled = false
                stopUpdates()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startUpdates()
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val openIntent = Intent(this, LockActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                App.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(App.NOTIFICATION_ID, notification)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun startUpdates() {
        if (!hasLocationPermission()) return
        prefs.trackingEnabled = true
        stopUpdates()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val forceSend = strategy.shouldForceSend(loc)
                strategy.onNewLocation(loc)
                scope.launch { sendLocation(loc, force = forceSend) }
            }
        }
        callback = cb
        try {
            fused.requestLocationUpdates(strategy.buildRequest(), cb, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // permission revoked
        }
    }

    private fun stopUpdates() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }

    private suspend fun sendOnce(force: Boolean) {
        if (!hasLocationPermission()) return
        try {
            val task = fused.lastLocation
            task.addOnSuccessListener { loc ->
                if (loc != null) scope.launch { sendLocation(loc, force) }
            }
        } catch (_: SecurityException) {}
    }

    private fun sendLocation(loc: Location, force: Boolean) {
        val net = DeviceUtils.networkInfo(this)
        val payload = LocationPayload(
            familyCode = prefs.familyCode,
            memberId = prefs.deviceId,
            memberName = prefs.memberName,
            profileImage = prefs.profileImage,
            deviceId = prefs.deviceId,
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy = loc.accuracy,
            speed = if (loc.hasSpeed()) loc.speed else 0f,
            battery = DeviceUtils.batteryPercent(this),
            timestamp = System.currentTimeMillis(),
            trackingStatus = if (prefs.trackingEnabled) "active" else "stopped",
            networkStatus = if (net.online) "online" else "offline",
            connectionType = net.type
        )
        val ok = ApiClient.send(prefs.apiEndpoint, payload)
        if (ok) {
            prefs.incrementSuccess()
            prefs.lastSendTimestamp = payload.timestamp
        } else {
            prefs.incrementFailure()
        }
    }

    override fun onDestroy() {
        stopUpdates()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.family.locationsender.action.START"
        const val ACTION_STOP = "com.family.locationsender.action.STOP"
        const val ACTION_TEST_SEND = "com.family.locationsender.action.TEST_SEND"

        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, LocationForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: android.content.Context) {
            val i = Intent(ctx, LocationForegroundService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun testSend(ctx: android.content.Context) {
            val i = Intent(ctx, LocationForegroundService::class.java).setAction(ACTION_TEST_SEND)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
