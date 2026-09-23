package com.apexfission.android.auth.onboarding

import android.app.Activity
import android.os.Build
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager

/** Invoke from a foreground Activity following an explicit user action. Does not retain Activity. */
class AndroidCredentials {
    val supportsPasskeys: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    suspend fun createPasskey(activity: Activity, creationOptionsJson: String): String {
        check(supportsPasskeys) { "Passkeys require Android 9 or later" }
        val result = diagnostic("passkey-create") { CredentialManager.create(activity).createCredential(
            context = activity,
            request = CreatePublicKeyCredentialRequest(requestJson = creationOptionsJson),
        )
        }
        // Do not override origin/clientDataHash or modify server challenge or RP options.
        return (result as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw OnboardingProtocolException()
    }

    /** Optional password-manager save. A cancelled save never changes backend enrollment. */
    suspend fun savePassword(activity: Activity, email: String, password: String) {
        diagnostic("password-manager-save") { CredentialManager.create(activity).createCredential(
            context = activity,
            request = CreatePasswordRequest(id = email, password = password),
        ) }
    }

    private suspend fun <T> diagnostic(operation: String, block: suspend () -> T): T {
        OnboardingLog.debug("Credential Manager $operation started")
        try {
            return block().also { OnboardingLog.debug("Credential Manager $operation completed") }
        } catch (e: Exception) {
            val dom = (e as? androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException)?.domError?.javaClass?.simpleName
            OnboardingLog.warning("Credential Manager $operation failed type=${e.javaClass.simpleName} dom=${dom ?: "none"}")
            throw e
        }
    }
}
