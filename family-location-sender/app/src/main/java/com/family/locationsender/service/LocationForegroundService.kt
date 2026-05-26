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
import android.util.Log
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

    /** Main-thread handler for the self-heal watchdog. */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Self-heal watchdog. Runs every 60 s. If tracking is supposed to be
     * active but no LocationCallback is registered (or nothing has been
     * attempted for > 2 minutes), it re-runs the same logic as pressing
     * the Start Tracking button — without any user interaction.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                if (prefs.trackingEnabled) {
                    val now = System.currentTimeMillis()
                    val sinceLast = if (prefs.lastAttemptAt > 0)
                        now - prefs.lastAttemptAt else Long.MAX_VALUE
                    val unhealthy = callback == null || sinceLast > 2 * 60_000L
                    if (unhealthy) {
                        Log.w(TAG, "Watchdog: tracking unhealthy (callback=${callback != null}, " +
                                "sinceLastMs=$sinceLast) — restarting updates")
                        startUpdates()
                        // Force a one-off send so we don't wait for the next
                        // location callback to know we're healthy again.
                        scope.launch { sendOnce() }
                        // Also try to drain any queued offline payloads.
                        scope.launch { flushQueue() }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Watchdog tick failed", t)
            }
            mainHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service onCreate — initialising prefs / fused / strategy / watchdog")
        prefs = Prefs.get(this)
        queue = OfflineQueue.get(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        strategy = SmartLocationStrategy(prefs)
        registerConnectivityListener()
        // Start the self-heal watchdog. It is cheap (one tick / minute) and
        // only takes action when tracking is actually meant to be running.
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val act = intent?.action
        Log.i(TAG, "onStartCommand action='$act' trackingEnabled=${prefs.trackingEnabled}")
        startInForeground()
        when (act) {
            ACTION_TEST_SEND -> scope.launch { sendOnce(isTest = true) }
            ACTION_FLUSH_QUEUE -> scope.launch { flushQueue() }
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested — disabling tracking + cancelling alarms")
                prefs.trackingEnabled = false
                stopUpdates()
                mainHandler.removeCallbacks(watchdog)
                com.family.locationsender.receiver.KeepaliveAlarm.cancel(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.i(TAG, "Start requested — running same logic as Start Tracking button")
                startUpdates()
                // Seed: try to grab the cached last-known location immediately so
                // we don't have to wait for the first Fused callback after boot.
                scope.launch { sendOnce() }
                scope.launch { flushQueue() }
                // Make sure the external keepalive keeps the service alive even
                // if the OEM kill-policy nukes it.
                com.family.locationsender.receiver.KeepaliveAlarm.schedule(this)
            }
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
        if (!hasLocationPermission()) {
            Log.e(TAG, "startUpdates: ACCESS_FINE/COARSE_LOCATION permission missing — cannot subscribe")
            return
        }
        prefs.trackingEnabled = true
        stopUpdates()
        val request = strategy.buildRequest()
        Log.i(TAG, "Location tracking started — interval=${request.intervalMillis}ms " +
                "minUpdate=${request.minUpdateIntervalMillis}ms")
        val cb = object : LocationCallback() {
            private var firstFix = true
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (firstFix) {
                    Log.i(TAG, "First location fix after subscribe: ${loc.latitude},${loc.longitude} " +
                            "acc=${loc.accuracy}m")
                    firstFix = false
                }
                strategy.onNewLocation(loc)
                scope.launch { sendLocation(loc) }
            }
        }
        callback = cb
        try {
            fused.requestLocationUpdates(request, cb, Looper.getMainLooper())
            Log.i(TAG, "Send loop started (Fused subscription registered)")
        } catch (se: SecurityException) {
            Log.e(TAG, "requestLocationUpdates threw SecurityException", se)
        } catch (t: Throwable) {
            Log.e(TAG, "requestLocationUpdates failed", t)
        }
    }

    private fun stopUpdates() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }

    private suspend fun sendOnce(isTest: Boolean = false) {
        if (!hasLocationPermission()) {
            if (isTest) broadcastTestResult(0, "", "Location permission not granted")
            return
        }
        try {
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    scope.launch { sendLocation(loc, isTest) }
                } else if (isTest) {
                    scope.launch { broadcastTestResult(0, "", "No last known location yet — wait for first GPS fix") }
                }
            }
        } catch (_: SecurityException) {
            if (isTest) broadcastTestResult(0, "", "SecurityException reading last location")
        }
    }

    private suspend fun sendLocation(loc: Location, isTest: Boolean = false) {
        if (prefs.apiEndpoint.isBlank()) {
            if (isTest) broadcastTestResult(0, "", "API endpoint is empty — set it in Settings")
            return
        }
        if (prefs.familyCode.isBlank() || prefs.memberName.isBlank()) {
            if (isTest) broadcastTestResult(0, "", "Family code or member name missing — open Settings")
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
            recordResult(0, "", "Offline — queued for later")
            if (isTest) broadcastTestResult(0, "", "Offline — queued for later", payload.toPrettyJson())
            return
        }

        val jsonBody = payload.toJson()
        Log.i(TAG, "POST ${prefs.apiEndpoint}\nPayload: $jsonBody")
        val result = ApiClient.sendRaw(prefs.apiEndpoint, jsonBody)
        Log.i(TAG, "Response: HTTP ${result.httpStatus} body=${result.body} err=${result.errorMessage ?: "-"}")
        recordResult(result.httpStatus, result.body, result.errorMessage)

        if (result.success) {
            Log.i(TAG, "First location sent successfully — HTTP ${result.httpStatus}")
            prefs.incrementSuccess()
            prefs.lastSendTimestamp = payload.timestamp
            flushQueue()
        } else {
            queue.enqueue(jsonBody)
            prefs.incrementFailure()
        }
        if (isTest) {
            broadcastTestResult(result.httpStatus, result.body, result.errorMessage, payload.toPrettyJson())
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
                val result = ApiClient.sendRaw(prefs.apiEndpoint, json)
                recordResult(result.httpStatus, result.body, result.errorMessage)
                if (!result.success) break
                sent++
                prefs.incrementSuccess()
                prefs.lastSendTimestamp = System.currentTimeMillis()
            }
            if (sent > 0) queue.removeFirst(sent)
        }
    }

    /** Persist the most recent attempt's diagnostic info so the UI can show it. */
    private fun recordResult(httpStatus: Int, body: String, errorMessage: String?) {
        prefs.lastHttpStatus = httpStatus
        prefs.lastResponseBody = body
        prefs.lastErrorMessage = errorMessage ?: ""
        prefs.lastAttemptAt = System.currentTimeMillis()
    }

    /** Broadcast Test Send result so MainActivity can pop a dialog. */
    private fun broadcastTestResult(
        httpStatus: Int,
        body: String,
        errorMessage: String?,
        sentPayload: String = ""
    ) {
        val intent = Intent(ACTION_TEST_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_HTTP_STATUS, httpStatus)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_ERROR, errorMessage ?: "")
            putExtra(EXTRA_PAYLOAD, sentPayload)
        }
        sendBroadcast(intent)
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
        mainHandler.removeCallbacks(watchdog)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.family.locationsender.action.START"
        const val ACTION_STOP = "com.family.locationsender.action.STOP"
        const val ACTION_TEST_SEND = "com.family.locationsender.action.TEST_SEND"
        const val ACTION_FLUSH_QUEUE = "com.family.locationsender.action.FLUSH_QUEUE"

        // Broadcast emitted after a Test Send so the UI can show a dialog.
        const val ACTION_TEST_RESULT = "com.family.locationsender.action.TEST_RESULT"
        const val EXTRA_HTTP_STATUS = "http_status"
        const val EXTRA_BODY = "body"
        const val EXTRA_ERROR = "error"
        const val EXTRA_PAYLOAD = "payload"

        private const val TAG = "FLS-Service"

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
