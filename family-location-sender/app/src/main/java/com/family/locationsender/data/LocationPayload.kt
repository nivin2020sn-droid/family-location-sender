package com.family.locationsender.data

import org.json.JSONObject

/**
 * Payload object sent to the server. Mirrors the JSON contract required by
 * `apiEndpoint`. No history is ever stored locally; only the latest values
 * are transmitted.
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
    val timestamp: Long,
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
        obj.put("battery", battery)
        obj.put("timestamp", timestamp)
        obj.put("trackingStatus", trackingStatus)
        obj.put("networkStatus", networkStatus)
        obj.put("connectionType", connectionType)
        return obj.toString()
    }
}
