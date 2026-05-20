package com.family.locationsender.network

import com.family.locationsender.data.LocationPayload
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ApiClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Send a payload. Returns true if HTTP 2xx, false otherwise.
     * Errors are returned as false (no exception leaks).
     */
    fun send(endpoint: String, payload: LocationPayload): Boolean {
        if (endpoint.isBlank()) return false
        return try {
            val body = payload.toJson().toRequestBody(JSON)
            val req = Request.Builder()
                .url(endpoint)
                .post(body)
                .header("Content-Type", "application/json")
                .header("User-Agent", "FamilyLocationSender/1.0 Android")
                .build()
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }
}
