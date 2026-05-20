package com.family.locationsender.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/**
 * FIFO queue of failed/offline location payloads, persisted encrypted (with a
 * safe fallback to plain SharedPreferences if the Android Keystore is broken
 * on the device). Holds raw JSON strings only; entries are removed once
 * delivered.
 *
 * This is **not** a history log: payloads sit here only between the moment
 * they could not be sent and the moment they are finally delivered.
 */
class OfflineQueue private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val sp: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "fls_queue",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Log.e(TAG, "EncryptedSharedPreferences init failed for queue, falling back to plain prefs", t)
            try { appContext.deleteSharedPreferences("fls_queue") } catch (_: Throwable) {}
            appContext.getSharedPreferences("fls_queue_plain", Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun enqueue(json: String) {
        val arr = readArray()
        arr.put(json)
        // Cap to prevent unbounded growth (oldest dropped first).
        while (arr.length() > MAX_SIZE) {
            arr.remove(0)
        }
        write(arr)
    }

    @Synchronized
    fun peekAll(): List<String> {
        val arr = readArray()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out += arr.optString(i, "")
        return out.filter { it.isNotEmpty() }
    }

    @Synchronized
    fun removeFirst(n: Int) {
        if (n <= 0) return
        val arr = readArray()
        val toRemove = minOf(n, arr.length())
        repeat(toRemove) { arr.remove(0) }
        write(arr)
    }

    @Synchronized
    fun size(): Int = readArray().length()

    @Synchronized
    fun clear() {
        sp.edit().remove(KEY).apply()
    }

    private fun readArray(): JSONArray {
        val raw = sp.getString(KEY, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun write(arr: JSONArray) {
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "FLS-OfflineQueue"
        private const val KEY = "queue"
        private const val MAX_SIZE = 500

        @Volatile private var INSTANCE: OfflineQueue? = null
        fun get(context: Context): OfflineQueue =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: OfflineQueue(context.applicationContext).also { INSTANCE = it }
            }
    }
}
