package com.family.locationsender.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.family.locationsender.App
import com.family.locationsender.R
import com.family.locationsender.data.LocationPayload
import com.family.locationsender.data.OfflineQueue
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service:
 *  - Holds a persistent notification (`foregroundServiceType=location`).
 *  - Pushes location updates via [ApiClient] using the user-chosen interval
 *    or Smart Mode.
 *  - Queues payloads to [OfflineQueue] when offline / on failure, and flushes
 *    them on next success or when connectivity returns.
 */
class LocationForegroundService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var queue: OfflineQueue
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var strategy: SmartLocationStrategy
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()
    private var callback: LocationCallback? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        queue = OfflineQueue.get(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        strategy = SmartLocationStrategy(prefs)
        registerConnectivityListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_TEST_SEND -> scope.launch { sendOnce() }
            ACTION_FLUSH_QUEUE -> scope.launch { flushQueue() }
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
                App.NOTIFICATION_ID, notification,
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
                strategy.onNewLocation(loc)
                scope.launch { sendLocation(loc) }
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

    private suspend fun sendOnce() {
        if (!hasLocationPermission()) return
        try {
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) scope.launch { sendLocation(loc) }
            }
        } catch (_: SecurityException) {}
    }

    private suspend fun sendLocation(loc: Location) {
        if (prefs.apiEndpoint.isBlank()) return // nothing to send to
        if (prefs.familyCode.isBlank() || prefs.memberName.isBlank()) {
            // Setup not completed yet — do not contact the server with empty
            // identity, to avoid filling the offline queue with useless rows.
            return
        }

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

        if (!net.online) {
            queue.enqueue(payload.toJson())
            return
        }

        val ok = ApiClient.send(prefs.apiEndpoint, payload)
        if (ok) {
            prefs.incrementSuccess()
            prefs.lastSendTimestamp = payload.timestamp
            // Try to drain anything queued from previous offline periods.
            flushQueue()
        } else {
            queue.enqueue(payload.toJson())
            prefs.incrementFailure()
        }
    }

    /** Sends queued payloads in FIFO order until one fails or the queue is empty. */
    private suspend fun flushQueue() {
        flushMutex.withLock {
            if (prefs.apiEndpoint.isBlank()) return
            val items = queue.peekAll()
            if (items.isEmpty()) return
            val net = DeviceUtils.networkInfo(this)
            if (!net.online) return

            var sent = 0
            for (json in items) {
                val ok = ApiClient.sendRaw(prefs.apiEndpoint, json)
                if (!ok) break
                sent++
                prefs.incrementSuccess()
                prefs.lastSendTimestamp = System.currentTimeMillis()
            }
            if (sent > 0) queue.removeFirst(sent)
        }
    }

    private fun registerConnectivityListener() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Drain queue when connectivity returns
                scope.launch { flushQueue() }
            }
        }
        networkCallback = cb
        try { cm.registerNetworkCallback(request, cb) } catch (_: Exception) {}
    }

    private fun unregisterConnectivityListener() {
        val cm = connectivityManager
        val cb = networkCallback
        if (cm != null && cb != null) {
            try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        networkCallback = null
        connectivityManager = null
    }

    override fun onDestroy() {
        stopUpdates()
        unregisterConnectivityListener()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.family.locationsender.action.START"
        const val ACTION_STOP = "com.family.locationsender.action.STOP"
        const val ACTION_TEST_SEND = "com.family.locationsender.action.TEST_SEND"
        const val ACTION_FLUSH_QUEUE = "com.family.locationsender.action.FLUSH_QUEUE"

        fun start(ctx: Context) {
            val i = Intent(ctx, LocationForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, LocationForegroundService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun testSend(ctx: Context) {
            val i = Intent(ctx, LocationForegroundService::class.java).setAction(ACTION_TEST_SEND)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun flushQueue(ctx: Context) {
            val i = Intent(ctx, LocationForegroundService::class.java).setAction(ACTION_FLUSH_QUEUE)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
