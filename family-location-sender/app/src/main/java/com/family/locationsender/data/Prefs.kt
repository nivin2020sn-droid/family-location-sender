package com.family.locationsender.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.util.UUID

/**
 * Wrapper around EncryptedSharedPreferences with a safe fallback to standard
 * SharedPreferences when the encryption layer fails to initialise (which is
 * a known issue with `security-crypto:1.1.0-alpha06` on some devices, e.g.
 * locked/broken Android Keystore, certain OEM ROMs, etc.).
 *
 * Stores app settings, hashed password, counters and the last-known status.
 * Never stores location history.
 */
class Prefs(context: Context) {

    private val appContext = context.applicationContext

    private val sp: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                "fls_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // EncryptedSharedPreferences can throw on some devices with a
            // broken/locked Android Keystore. Fall back to plain SharedPrefs
            // so the app remains usable.
            Log.e(TAG, "EncryptedSharedPreferences init failed, falling back to plain SharedPreferences", t)
            try {
                appContext.deleteSharedPreferences("fls_prefs")
            } catch (_: Throwable) {}
            appContext.getSharedPreferences("fls_prefs_plain", Context.MODE_PRIVATE)
        }
    }

    // ---------------- First run ----------------
    var firstRunDone: Boolean
        get() = sp.getBoolean(KEY_FIRST_RUN_DONE, false)
        set(v) = sp.edit().putBoolean(KEY_FIRST_RUN_DONE, v).apply()

    // ---------------- Identity ----------------
    var memberName: String
        get() = sp.getString(KEY_MEMBER_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_MEMBER_NAME, v).apply()

    /** Base64-encoded profile image (PNG/JPG). */
    var profileImage: String
        get() = sp.getString(KEY_PROFILE_IMAGE, "") ?: ""
        set(v) = sp.edit().putString(KEY_PROFILE_IMAGE, v).apply()

    var familyCode: String
        get() = sp.getString(KEY_FAMILY_CODE, DEFAULT_FAMILY_CODE) ?: DEFAULT_FAMILY_CODE
        set(v) = sp.edit().putString(KEY_FAMILY_CODE, v).apply()

    // ---------------- API ----------------
    var apiEndpoint: String
        get() = sp.getString(KEY_API_ENDPOINT, DEFAULT_API_ENDPOINT) ?: DEFAULT_API_ENDPOINT
        set(v) = sp.edit().putString(KEY_API_ENDPOINT, v).apply()

    // ---------------- Password ----------------
    /** Returns SHA-256 hash of stored password. Defaults to hash of `1001`. */
    var passwordHash: String
        get() = sp.getString(KEY_PASSWORD_HASH, sha256(DEFAULT_PASSWORD)) ?: sha256(DEFAULT_PASSWORD)
        set(v) = sp.edit().putString(KEY_PASSWORD_HASH, v).apply()

    fun checkPassword(input: String): Boolean = sha256(input) == passwordHash

    fun setPassword(plain: String) {
        passwordHash = sha256(plain)
    }

    // ---------------- Interval ----------------
    var updateInterval: String
        get() = sp.getString(KEY_UPDATE_INTERVAL, INTERVAL_SMART) ?: INTERVAL_SMART
        set(v) = sp.edit().putString(KEY_UPDATE_INTERVAL, v).apply()

    // ---------------- Device ----------------
    @get:SuppressLint("HardwareIds")
    val deviceId: String
        get() {
            val existing = sp.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrEmpty()) return existing
            val androidId = try {
                Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (e: Exception) { null }
            val id = if (!androidId.isNullOrEmpty()) "and_$androidId" else "uid_${UUID.randomUUID()}"
            sp.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }

    // ---------------- Tracking state ----------------
    var trackingEnabled: Boolean
        get() = sp.getBoolean(KEY_TRACKING_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_TRACKING_ENABLED, v).apply()

    var successCount: Long
        get() = sp.getLong(KEY_SUCCESS_COUNT, 0L)
        set(v) = sp.edit().putLong(KEY_SUCCESS_COUNT, v).apply()

    var failureCount: Long
        get() = sp.getLong(KEY_FAILURE_COUNT, 0L)
        set(v) = sp.edit().putLong(KEY_FAILURE_COUNT, v).apply()

    var lastSendTimestamp: Long
        get() = sp.getLong(KEY_LAST_SEND_TS, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_SEND_TS, v).apply()

    // ---------------- Diagnostic info on last send attempt ----------------
    var lastHttpStatus: Int
        get() = sp.getInt(KEY_LAST_HTTP, 0)
        set(v) = sp.edit().putInt(KEY_LAST_HTTP, v).apply()

    /** Last server response body (truncated) or last exception message. */
    var lastResponseBody: String
        get() = sp.getString(KEY_LAST_BODY, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_BODY, v).apply()

    var lastErrorMessage: String
        get() = sp.getString(KEY_LAST_ERR, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_ERR, v).apply()

    var lastAttemptAt: Long
        get() = sp.getLong(KEY_LAST_ATTEMPT_TS, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_ATTEMPT_TS, v).apply()

    fun incrementSuccess() {
        sp.edit().putLong(KEY_SUCCESS_COUNT, successCount + 1).apply()
    }

    fun incrementFailure() {
        sp.edit().putLong(KEY_FAILURE_COUNT, failureCount + 1).apply()
    }

    // ---------------- Language ----------------
    var language: String
        get() = sp.getString(KEY_LANGUAGE, LANG_EN) ?: LANG_EN
        set(v) = sp.edit().putString(KEY_LANGUAGE, v).apply()

    // ---------------- Manual Location Override ----------------
    /** When true, the service ignores real GPS and uses the manual coordinates. */
    var manualLocationMode: Boolean
        get() = sp.getBoolean(KEY_MANUAL_MODE, false)
        set(v) = sp.edit().putBoolean(KEY_MANUAL_MODE, v).apply()

    /** Manual latitude (-90..90). NaN if unset. */
    var manualLat: Double
        get() {
            val raw = sp.getString(KEY_MANUAL_LAT, null) ?: return Double.NaN
            return raw.toDoubleOrNull() ?: Double.NaN
        }
        set(v) = sp.edit().putString(KEY_MANUAL_LAT, v.toString()).apply()

    /** Manual longitude (-180..180). NaN if unset. */
    var manualLng: Double
        get() {
            val raw = sp.getString(KEY_MANUAL_LNG, null) ?: return Double.NaN
            return raw.toDoubleOrNull() ?: Double.NaN
        }
        set(v) = sp.edit().putString(KEY_MANUAL_LNG, v.toString()).apply()

    /** Manual accuracy in metres. 0 if unset. */
    var manualAccuracy: Float
        get() = sp.getFloat(KEY_MANUAL_ACC, 0f)
        set(v) = sp.edit().putFloat(KEY_MANUAL_ACC, v).apply()

    // ---------------- Helpers ----------------
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "FLS-Prefs"

        const val DEFAULT_API_ENDPOINT =
            "https://my-family-my-life-api.onrender.com/api/location/update"
        const val DEFAULT_PASSWORD = "1001"
        const val DEFAULT_FAMILY_CODE = "1001"

        const val INTERVAL_1SEC = "1s"
        const val INTERVAL_10SEC = "10s"
        const val INTERVAL_30SEC = "30s"
        const val INTERVAL_1MIN = "1m"
        const val INTERVAL_3MIN = "3m"
        const val INTERVAL_5MIN = "5m"
        const val INTERVAL_10MIN = "10m"
        const val INTERVAL_15MIN = "15m"
        const val INTERVAL_SMART = "smart"

        const val LANG_EN = "en"
        const val LANG_AR = "ar"

        private const val KEY_FIRST_RUN_DONE = "first_run_done"
        private const val KEY_MEMBER_NAME = "member_name"
        private const val KEY_PROFILE_IMAGE = "profile_image"
        private const val KEY_FAMILY_CODE = "family_code"
        private const val KEY_API_ENDPOINT = "api_endpoint"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_UPDATE_INTERVAL = "update_interval"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_SUCCESS_COUNT = "success_count"
        private const val KEY_FAILURE_COUNT = "failure_count"
        private const val KEY_LAST_SEND_TS = "last_send_ts"
        private const val KEY_LAST_HTTP = "last_http"
        private const val KEY_LAST_BODY = "last_body"
        private const val KEY_LAST_ERR = "last_err"
        private const val KEY_LAST_ATTEMPT_TS = "last_attempt_ts"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_MANUAL_MODE = "manual_mode"
        private const val KEY_MANUAL_LAT = "manual_lat"
        private const val KEY_MANUAL_LNG = "manual_lng"
        private const val KEY_MANUAL_ACC = "manual_acc"

        @Volatile private var INSTANCE: Prefs? = null
        fun get(context: Context): Prefs =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Prefs(context.applicationContext).also { INSTANCE = it }
            }
    }
}
