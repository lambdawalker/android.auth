package com.apexfission.android.auth.onboarding

import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/** An opaque, restricted onboarding capability. Never persist or log it. */
class Capability internal constructor(internal val value: String) {
    override fun toString() = "Capability([REDACTED])"
}

/** Sensitive values deliberately do not participate in data-class toString(). */
class EnrollmentSecret internal constructor(val value: String) {
    override fun toString() = "EnrollmentSecret([REDACTED])"
}

data class Profile(val givenName: String, val familyName: String)

enum class SessionStatus(val wire: String) {
    PROFILE("profile-pending"), CREDENTIALS("credentials-pending"), REVIEW("review-pending"),
    EMAIL("email-confirmation-pending"), ACTIVATING("activating"), COMPLETE("complete"), FAILED("failed");
    companion object {
        internal fun parse(value: String) = entries.firstOrNull { it.wire == value }
            ?: throw OnboardingProtocolException()
    }
}

enum class EnrollmentMethod(val wire: String) { PASSKEY("passkey"), PASSWORD_MFA("password-mfa") }

data class OnboardingSession(
    val id: String, val status: SessionStatus, val version: Long, val expiresAt: Instant,
    val consentVersion: String, val email: String, val profile: Profile,
    val confirmationId: String? = null, val confirmationExpiresAt: Instant? = null,
    val userPublicId: String? = null, val identityStatus: String = "not-verified",
)

class StartedSession(val session: OnboardingSession, val capability: Capability) {
    override fun toString() = "StartedSession([REDACTED])"
}

class CredentialSetup(
    val id: String, val method: EnrollmentMethod, val status: String, val expiresAt: Instant,
    val creationOptions: String? = null, val secret: EnrollmentSecret? = null,
) {
    override fun toString() = "CredentialSetup(status=$status)"
}

class OnboardingApiException(val statusCode: Int, val code: String) :
    IOException("Onboarding request failed (HTTP $statusCode)")
class OnboardingProtocolException : IOException("Unexpected onboarding response")

internal fun JSONObject.optionalString(name: String): String? =
    if (isNull(name)) null else getString(name).takeIf { it.isNotEmpty() }

internal fun parseSession(json: JSONObject): OnboardingSession {
    val profile = json.getJSONObject("profile")
    return OnboardingSession(
        id = json.getString("sessionId"), status = SessionStatus.parse(json.getString("status")),
        version = json.getLong("version"), expiresAt = Instant.parse(json.getString("expiresAt")),
        consentVersion = json.getString("consentVersion"), email = json.getString("email"),
        profile = Profile(profile.getString("givenName"), profile.getString("familyName")),
        confirmationId = json.optionalString("confirmationId"),
        confirmationExpiresAt = json.optionalString("confirmationExpiresAt")?.let(Instant::parse),
        userPublicId = json.optionalString("userPublicId"),
        identityStatus = json.getString("identityStatus"),
    )
}

internal fun parseSetup(json: JSONObject): CredentialSetup = CredentialSetup(
    id = json.getString("setupId"),
    method = EnrollmentMethod.entries.firstOrNull { it.wire == json.getString("method") }
        ?: throw OnboardingProtocolException(),
    status = json.getString("status"), expiresAt = Instant.parse(json.getString("expiresAt")),
    creationOptions = json.optJSONObject("creationOptions")?.toString(),
    secret = json.optionalString("secretCode")?.let(::EnrollmentSecret),
)
