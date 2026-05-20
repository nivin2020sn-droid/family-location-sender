package com.family.locationsender.util

/**
 * Session state for the password lock. Once the user enters the correct
 * password we set [authenticated]=true. On background or 1-minute idle we
 * reset it. All sensitive screens must check [authenticated] in onResume.
 */
object SessionState {
    @Volatile var authenticated: Boolean = false

    fun lock() { authenticated = false }
    fun unlock() { authenticated = true }
}
