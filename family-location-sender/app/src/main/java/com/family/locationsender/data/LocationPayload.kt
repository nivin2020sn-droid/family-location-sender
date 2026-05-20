package com.family.locationsender.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Payload object sent to the server. Mirrors the JSON contract required by
 * `apiEndpoint`. No history is ever stored locally; only the latest values
 * are transmitted.
 *
 * All required fields are guaranteed non-empty before send (caller validates
 * familyCode / memberName up-front in [LocationForegroundService]).
 *
 *  - `latitude` / `longitude` → Double
 *  - `accuracy` / `speed`     → Double (serialised from Float)
 *  - `battery`                → Int (0..100, never negative)
 *  - `timestamp`              → ISO-8601 UTC string ("2024-05-20T08:37:00.123Z")
 */
data class LocationPayload(
    val familyCode: String,
    val memberId: String,
    val memberName: String,
    val profileImage: String,
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val battery: Int,
    val timestamp: Long,        // epoch millis, converted to ISO-8601 in toJson()
    val trackingStatus: String, // "active" | "stopped"
    val networkStatus: String,  // "online" | "offline"
    val connectionType: String  // "wifi" | "mobile" | "unknown"
) {

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("familyCode", familyCode)
        obj.put("memberId", memberId)
        obj.put("memberName", memberName)
        obj.put("profileImage", profileImage)
        obj.put("deviceId", deviceId)
        obj.put("latitude", latitude)
        obj.put("longitude", longitude)
        obj.put("accuracy", accuracy.toDouble())
        obj.put("speed", speed.toDouble())
        // Clamp battery to non-negative; some devices return -1 if unknown.
        obj.put("battery", if (battery < 0) 0 else battery)
        // timestamp in ISO-8601 UTC as required by the server.
        obj.put("timestamp", isoUtc(timestamp))
        obj.put("trackingStatus", trackingStatus)
        obj.put("networkStatus", networkStatus)
        obj.put("connectionType", connectionType)
        return obj.toString()
    }

    /** Returns a human-readable, indented version for the Test Send dialog. */
    fun toPrettyJson(): String = try {
        JSONObject(toJson()).toString(2)
    } catch (_: Exception) {
        toJson()
    }

    companion object {
        private val ISO_FMT: SimpleDateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }

        fun isoUtc(epochMillis: Long): String = ISO_FMT.format(Date(epochMillis))
    }
}
