package com.apexfission.android.auth.onboarding

import org.json.JSONObject

/** Paths are relative to an HTTPS API base URL that includes /v1. Keys identify one mutation. */
interface OnboardingApi {
    suspend fun start(email: String, consentVersion: String, key: String): StartedSession
    suspend fun status(capability: Capability, sessionId: String): OnboardingSession
    suspend fun profile(capability: Capability, sessionId: String, profile: Profile, email: String, version: Long, key: String): OnboardingSession
    suspend fun createSetup(capability: Capability, method: EnrollmentMethod, key: String): CredentialSetup
    suspend fun setupStatus(capability: Capability, setupId: String): CredentialSetup
    suspend fun setupAction(capability: Capability, setupId: String, action: String, body: JSONObject, key: String): CredentialSetup
    suspend fun review(capability: Capability, sessionId: String, version: Long, consentVersion: String, key: String): OnboardingSession
    suspend fun sendEmail(capability: Capability, sessionId: String, confirmationId: String?, key: String): OnboardingSession
    suspend fun confirm(capability: Capability, sessionId: String, confirmationId: String, code: String, key: String): OnboardingSession
}
