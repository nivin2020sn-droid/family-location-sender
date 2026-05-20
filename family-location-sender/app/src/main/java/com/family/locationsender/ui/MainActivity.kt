package com.family.locationsender.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.family.locationsender.R
import com.family.locationsender.data.OfflineQueue
import com.family.locationsender.data.Prefs
import com.family.locationsender.databinding.ActivityMainBinding
import com.family.locationsender.receiver.AppDeviceAdminReceiver
import com.family.locationsender.service.LocationForegroundService
import com.family.locationsender.util.DeviceUtils
import com.family.locationsender.util.LocaleHelper
import com.family.locationsender.util.SessionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main dashboard. Shows live status (GPS, network, battery, send counters)
 * and exposes Start/Stop/Test/Settings/Lock-Now controls. Stop and Settings
 * require re-entering the password (or being inside the current session).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var queue: OfflineQueue
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() { renderStatus(); handler.postDelayed(this, 5_000) }
    }

    /** Receives Test Send result broadcasts from the foreground service. */
    private val testResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val status = intent.getIntExtra(LocationForegroundService.EXTRA_HTTP_STATUS, 0)
            val body = intent.getStringExtra(LocationForegroundService.EXTRA_BODY) ?: ""
            val err = intent.getStringExtra(LocationForegroundService.EXTRA_ERROR) ?: ""
            showTestResultDialog(status, body, err)
            renderStatus()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applySaved(newBase))
    }

    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fine = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            askBackgroundIfNeeded()
            startTrackingService()
        } else {
            promptOpenSettings(R.string.permission_location_denied)
        }
    }

    private val askBackground = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Not mandatory for foreground, but warn.
            Toast.makeText(this, R.string.background_location_recommended, Toast.LENGTH_LONG).show()
        }
    }

    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user choice */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to inflate main layout", t)
            val tv = android.widget.TextView(this)
            tv.text = "Startup error — see logcat"
            setContentView(tv)
            return
        }

        try {
            prefs = Prefs.get(this)
            queue = OfflineQueue.get(this)

            binding.btnStart.setOnClickListener { onStartClicked() }
            binding.btnStop.setOnClickListener { promptPassword { stopTracking() } }
            binding.btnSettings.setOnClickListener { promptPassword { openSettings() } }
            binding.btnTest.setOnClickListener { onTestClicked() }
            binding.btnRefresh.setOnClickListener { renderStatus() }
            binding.btnLockNow.setOnClickListener { lockNow() }

            // Ask notification permission (Android 13+) for foreground notif
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            maybeShowDeviceAdminTip()
            maybeShowBatteryTip()
        } catch (t: Throwable) {
            Log.e(TAG, "MainActivity init failed", t)
            Toast.makeText(this, "Startup error — see logcat", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (!SessionState.authenticated) {
                startActivity(Intent(this, LockActivity::class.java))
                finish(); return
            }
            // Register Test Send broadcast receiver
            val filter = IntentFilter(LocationForegroundService.ACTION_TEST_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(testResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(testResultReceiver, filter)
            }
            renderStatus()
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, 5_000)
        } catch (t: Throwable) {
            Log.e(TAG, "onResume failed", t)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
        try { unregisterReceiver(testResultReceiver) } catch (_: Throwable) {}
    }

    private fun renderStatus() {
        binding.tvName.text = prefs.memberName.ifBlank { getString(R.string.unknown) }

        // Profile image
        val raw = prefs.profileImage.substringAfter(",", prefs.profileImage)
        if (raw.isNotBlank()) {
            try {
                val bytes = Base64.decode(raw, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) binding.ivAvatar.setImageBitmap(bmp)
            } catch (_: Exception) {}
        }

        val gps = DeviceUtils.isGpsEnabled(this)
        binding.tvGps.text = getString(
            R.string.fmt_gps, getString(if (gps) R.string.on else R.string.off)
        )

        val net = DeviceUtils.networkInfo(this)
        binding.tvNet.text = getString(
            R.string.fmt_net,
            getString(if (net.online) R.string.online else R.string.offline)
        )
        binding.tvConn.text = getString(
            R.string.fmt_conn, getString(when (net.type) {
                "wifi" -> R.string.wifi
                "mobile" -> R.string.mobile
                else -> R.string.unknown_short
            })
        )

        binding.tvBattery.text = getString(R.string.fmt_battery, DeviceUtils.batteryPercent(this))

        val last = prefs.lastSendTimestamp
        binding.tvLastSent.text = getString(
            R.string.fmt_last_sent,
            if (last > 0)
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(last))
            else "—"
        )

        binding.tvSuccess.text = getString(R.string.fmt_success, prefs.successCount)
        binding.tvFailure.text = getString(R.string.fmt_failure, prefs.failureCount)
        binding.tvQueue.text = getString(R.string.fmt_queue, queue.size())

        // Diagnostic info on the last send attempt — visible in status panel.
        val errLine = buildLastErrorLine()
        if (errLine.isNotBlank()) {
            binding.tvLastError.visibility = View.VISIBLE
            binding.tvLastError.text = errLine
        } else {
            binding.tvLastError.visibility = View.GONE
        }

        binding.tvServiceStatus.text = getString(
            R.string.fmt_service,
            getString(if (prefs.trackingEnabled) R.string.running else R.string.stopped)
        )

        val apiShort = if (prefs.apiEndpoint.isBlank()) {
            getString(R.string.api_not_set)
        } else prefs.apiEndpoint.let {
            if (it.length > 48) it.take(28) + "…" + it.takeLast(16) else it
        }
        binding.tvApi.text = getString(R.string.fmt_api, apiShort)

        // Re-evaluate battery warning visibility each refresh
        maybeShowBatteryTip()
    }

    private fun onStartClicked() {
        if (prefs.familyCode.isBlank() || prefs.memberName.isBlank()) {
            Toast.makeText(this, R.string.complete_setup_first, Toast.LENGTH_LONG).show()
            return
        }
        // Ask runtime permissions
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (needed.isNotEmpty()) {
            askLocation.launch(needed.toTypedArray())
        } else {
            askBackgroundIfNeeded()
            startTrackingService()
        }
    }

    private fun askBackgroundIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle(R.string.background_location_title)
                .setMessage(R.string.background_location_message)
                .setPositiveButton(R.string.ok) { _, _ ->
                    askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton(R.string.later, null)
                .show()
        }
    }

    private fun startTrackingService() {
        prefs.trackingEnabled = true
        LocationForegroundService.start(this)
        renderStatus()
        // Suggest disabling battery optimization for reliable background work
        requestIgnoreBatteryOptimizations()
    }

    private fun stopTracking() {
        prefs.trackingEnabled = false
        LocationForegroundService.stop(this)
        renderStatus()
    }

    private fun onTestClicked() {
        if (prefs.apiEndpoint.isBlank()) {
            Toast.makeText(this, R.string.api_required, Toast.LENGTH_LONG).show()
            return
        }
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isNotEmpty()) { askLocation.launch(needed.toTypedArray()); return }
        LocationForegroundService.testSend(this)
        Toast.makeText(this, R.string.test_send_queued, Toast.LENGTH_SHORT).show()
        handler.postDelayed({ renderStatus() }, 3_000)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun lockNow() {
        SessionState.lock()
        startActivity(Intent(this, LockActivity::class.java))
        finish()
    }

    /** Confirms session is still authenticated. Else returns to LockActivity. */
    private fun promptPassword(onOk: () -> Unit) {
        if (SessionState.authenticated) { onOk(); return }
        startActivity(Intent(this, LockActivity::class.java))
        finish()
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    private fun maybeShowDeviceAdminTip() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val comp = AppDeviceAdminReceiver.componentName(this)
        if (!dpm.isAdminActive(comp)) {
            binding.cardDeviceAdminWarning.visibility = View.VISIBLE
            binding.btnEnableAdmin.setOnClickListener {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.device_admin_explanation)
                    )
                startActivity(intent)
            }
        } else {
            binding.cardDeviceAdminWarning.visibility = View.GONE
        }
    }

    private fun buildLastErrorLine(): String {
        val status = prefs.lastHttpStatus
        val err = prefs.lastErrorMessage
        return when {
            status == 0 && err.isBlank() -> ""
            status in 200..299 -> ""
            status > 0 && err.isBlank() -> "Last: HTTP $status"
            status > 0 -> "Last: HTTP $status — $err"
            else -> "Last: $err"
        }
    }

    private fun showTestResultDialog(httpStatus: Int, body: String, error: String) {
        val ok = httpStatus in 200..299
        val titleRes = if (ok) R.string.test_result_success_title else R.string.test_result_fail_title
        val statusLabel = if (httpStatus == 0) "—" else httpStatus.toString()
        val message = buildString {
            append("HTTP status: ").append(statusLabel).append("\n\n")
            if (error.isNotBlank()) {
                append("Error:\n").append(error).append("\n\n")
            }
            if (body.isNotBlank()) {
                append("Response body:\n")
                append(if (body.length > 2000) body.take(2000) + "…" else body)
            } else if (error.isBlank()) {
                append("(empty response body)")
            }
        }
        try {
            AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show()
        } catch (t: Throwable) {
            Log.e(TAG, "showTestResultDialog failed", t)
        }
    }

    private fun maybeShowBatteryTip() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                binding.cardBatteryWarning.visibility = View.GONE
            } else {
                binding.cardBatteryWarning.visibility = View.VISIBLE
                binding.btnDisableBattery.setOnClickListener {
                    requestIgnoreBatteryOptimizations()
                }
            }
        } catch (_: Exception) {
            binding.cardBatteryWarning.visibility = View.GONE
        }
    }

    private fun promptOpenSettings(messageRes: Int) {
        AlertDialog.Builder(this)
            .setMessage(messageRes)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
                startActivity(i)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val TAG = "FLS-MainActivity"
    }
}

