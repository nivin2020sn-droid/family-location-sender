package com.family.locationsender.network

import com.family.locationsender.data.LocationPayload
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Result of a single send attempt. Either a real HTTP response (code + body)
 * or a thrown exception (timeout, no DNS, no network, etc.).
 */
data class SendResult(
    val success: Boolean,
    val httpStatus: Int,         // 0 if no response received
    val body: String,            // server response body (truncated)
    val errorMessage: String?    // exception message when no HTTP response
) {
    fun summary(): String = buildString {
        append("HTTP ")
        append(if (httpStatus == 0) "-" else httpStatus.toString())
        if (!errorMessage.isNullOrBlank()) {
            append("  (")
            append(errorMessage)
            append(")")
        }
    }
}

object ApiClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val MAX_BODY_CHARS = 4000

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun send(endpoint: String, payload: LocationPayload): SendResult =
        sendRaw(endpoint, payload.toJson())

    /**
     * Send a raw JSON body. Always returns a [SendResult]; never throws.
     * Captures HTTP status, response body and any exception so the UI can
     * show the actual server error to the user.
     */
    fun sendRaw(endpoint: String, jsonBody: String): SendResult {
        if (endpoint.isBlank()) {
            return SendResult(
                success = false,
                httpStatus = 0,
                body = "",
                errorMessage = "API endpoint is empty"
            )
        }
        return try {
            val body = jsonBody.toRequestBody(JSON)
            val req = Request.Builder()
                .url(endpoint)
                .post(body)
                .header("Content-Type", "application/json")
                .header("User-Agent", "FamilyLocationSender/1.0 Android")
                .build()
            client.newCall(req).execute().use { resp ->
                val text = try {
                    resp.body?.string()?.take(MAX_BODY_CHARS) ?: ""
                } catch (e: Exception) {
                    "(failed to read body: ${e.message})"
                }
                SendResult(
                    success = resp.isSuccessful,
                    httpStatus = resp.code,
                    body = text,
                    errorMessage = if (resp.isSuccessful) null
                        else "HTTP ${resp.code} ${resp.message}"
                )
            }
        } catch (e: SocketTimeoutException) {
            SendResult(false, 0, "", "Timeout: ${e.message ?: "request timed out"}")
        } catch (e: UnknownHostException) {
            SendResult(false, 0, "", "Unknown host: ${e.message ?: endpoint}")
        } catch (e: IOException) {
            SendResult(false, 0, "", "IO error: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        } catch (e: Exception) {
            SendResult(false, 0, "", "${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }
}
