package com.apexfission.android.auth.onboarding

import org.junit.Assert.*
import org.junit.Test

class OnboardingLoggingTest {
    @Test fun `diagnostic routes hide session setup and confirmation identifiers`() {
        assertEquals("/onboarding/sessions/{id}/email-confirmations/{confirmationId}/confirm",
            OnboardingLog.route(listOf("onboarding", "sessions", "secret-session", "email-confirmations", "secret-proof", "confirm")))
        assertEquals("/auth/credential-setups/{id}/passkey-registration/complete",
            OnboardingLog.route(listOf("auth", "credential-setups", "private-id", "passkey-registration", "complete")))
    }
    @Test fun `metadata rejects multiline values payloads and personal data`() {
        for (value in listOf("email@example.com", "error\nforged line", "{\"token\":\"secret\"}", "x".repeat(81))) {
            assertEquals("unknown", OnboardingLog.label(value))
        }
        assertEquals("credential_operation_failed", OnboardingLog.label("credential_operation_failed"))
    }
    @Test fun `logging is opt in`() { assertFalse(OnboardingLogging.enabled) }
}
