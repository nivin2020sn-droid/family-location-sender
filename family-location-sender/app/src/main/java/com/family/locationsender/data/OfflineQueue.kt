package com.family.locationsender.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/**
 * FIFO queue of failed/offline location payloads, persisted encrypted.
 * Holds raw JSON strings only; entries are removed once delivered.
 *
 * This is **not** a history log: payloads sit here only between the moment
 * they could not be sent and the moment they are finally delivered.
 */
class OfflineQueue private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val sp: SharedPreferences by lazy {
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
        private const val KEY = "queue"
        private const val MAX_SIZE = 500

        @Volatile private var INSTANCE: OfflineQueue? = null
        fun get(context: Context): OfflineQueue =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: OfflineQueue(context.applicationContext).also { INSTANCE = it }
            }
    }
}
