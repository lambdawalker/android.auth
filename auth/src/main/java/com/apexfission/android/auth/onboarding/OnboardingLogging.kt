package com.apexfission.android.auth.onboarding

import android.util.Log

/** Opt-in diagnostics. Enable only for development; never logs request/response bodies. */
object OnboardingLogging {
    const val TAG = "onboarding"
    @Volatile var enabled: Boolean = false
}

internal object OnboardingLog {
    fun debug(message: String) {
        if (OnboardingLogging.enabled) Log.d(OnboardingLogging.TAG, message)
    }
    fun warning(message: String) {
        if (OnboardingLogging.enabled) Log.w(OnboardingLogging.TAG, message)
    }
    // Identifiers in routes are never emitted, even when they resemble ordinary path names.
    fun route(path: List<String>): String = "/" + path.mapIndexed { index, segment ->
        when {
            index == 2 -> "{id}"
            index == 4 && path.getOrNull(3) == "email-confirmations" -> "{confirmationId}"
            else -> label(segment)
        }
    }.joinToString("/")
    fun label(value: String?): String = value?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: "unknown"
}
