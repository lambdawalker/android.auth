# Android onboarding

The `auth` Android library implements **direct onboarding** against `go.attestra`'s `/v1` API. Kotlin client, `OnboardingViewModel`, Credential Manager bridge and Material 3 Compose screen are included. `app` is a runnable integration example.

## Flow

1. Start a 30-minute session with email and published terms version. No email is sent yet.
2. Enter given name, family name and email.
3. Prefer a passkey (Android 9+). Alternatively set a password and verify a six-digit authenticator TOTP. There is no password-only fallback.
4. Review the current profile revision and explicitly accept the displayed terms.
5. Request the final confirmation email and enter its **eight-digit** code. Resending rotates the code; wait at least 60 seconds between requests. Server limits are authoritative.
6. Reconcile asynchronous activation if needed, then hand off to a fresh login. An activated account's identity remains `not-verified`.

ID scanning, identity documents, invitations, authentication and recovery are not part of this module's current flow. The optional camera flow was deferred in the backend requirements.

## Run the sample on Windows

Keep the repository's Android Studio/AGP/Gradle toolchain and install its requested Android SDK. The repository currently targets API 37 and its daemon criteria request JDK 25. No Make or WSL is needed.

Add these **non-secret** settings to `%USERPROFILE%\.gradle\gradle.properties`, or pass them as `-P` Gradle arguments:

```properties
attestra.apiBaseUrl=https://YOUR_API.execute-api.us-east-2.amazonaws.com/v1
attestra.consentVersion=YOUR_PUBLISHED_TERMS_VERSION
attestra.termsUrl=https://YOUR_DOMAIN/terms
attestra.rpDomain=YOUR_RP_DOMAIN
```

Use the `apiUrl` output of the Go project's Pulumi stack. The consent version must exactly match `onboardingConsentVersion`, and the terms URL must show that version. These four values are compiled into the sample; rebuild after changing them. No production URL or AWS credentials are committed. Configure SES/DNS and the backend before attempting live confirmation.

```powershell
.\gradlew.bat :auth:testDebugUnitTest :auth:lintDebug :app:assembleDebug
.\gradlew.bat :app:installDebug
```

The sample enables `FLAG_SECURE` to keep enrollment secrets out of screenshots and recent-task previews. Its completion screen is a handoff placeholder for the separate login feature, not a simulated login.

## Embed in another app

```kotlin
implementation(project(":auth"))
```

The host needs Compose Material 3, lifecycle ViewModel Compose and core-library desugaring for `java.time` on API 24–25 (see the sample build file). Inside your themed activity content:

```kotlin
val api = remember { HttpOnboardingApi("https://YOUR_API/v1") }
val onboarding: OnboardingViewModel = viewModel(
    factory = OnboardingViewModel.factory(api, "terms-v1")
)
OnboardingScreen(
    viewModel = onboarding,
    onOpenTerms = { /* Display the published terms-v1 */ },
    onAuthenticationRequired = { userPublicId -> /* Navigate to your login flow */ }
)
```

Use a separate ViewModel owner/key per enrollment flow. Call `restart()` when intentionally abandoning a flow. Never reuse a ViewModel with a different backend or terms configuration. Custom UIs can observe `state` and use the same actions. Actions must be called on the main thread. `acceptReview()` is only for an explicit acceptance of the displayed terms; do not call it automatically.

`AndroidCredentials.createPasskey(activity, creationOptionsJson)` returns registration JSON for `submitPasskey()`. It leaves origin, RP, challenge, user verification and attestation options untouched. Call it in the foreground after a user action. The API handles Cognito; Android does not need a Cognito SDK, client secret or AWS keys.

## Passkey domain association

Publish Digital Asset Links at:

```text
https://YOUR_RP_DOMAIN/.well-known/assetlinks.json
```

The sample generates its `asset_statements` string resource and manifest metadata from `attestra.rpDomain`. Other host apps must provide the same manifest metadata and a string containing an include of their RP domain’s assetlinks URL, as shown in the Android prerequisites below.

Use the RP domain configured in Cognito (`cognitoRelyingPartyId`), which can differ from your API Gateway hostname. The application ID is the **host app's** ID, not `com.apexfission.android.auth` (the library namespace). For this sample it is `com.apexfission.android`.

```json
[
  {
    "relation": ["delegate_permission/common.get_login_creds"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.apexfission.android",
      "sha256_cert_fingerprints": ["REPLACE_WITH_SIGNING_CERTIFICATE_SHA256"]
    }
  }
]
```

Serve valid JSON over HTTPS, with status 200 and no redirect. Obtain debug/release signing fingerprints using `gradlew.bat :app:signingReport`; for Play-distributed builds use the **Play App Signing certificate**, not just the upload certificate. Register only certificates you intend to trust. A debug association on a development RP domain should not become a production trust policy.

See [Android Credential Manager prerequisites](https://developer.android.com/identity/credential-manager/prerequisites) and [passkey creation](https://developer.android.com/identity/passkeys/create-passkeys). Confirm native Android origin acceptance in the deployed Cognito configuration with a real device; unit tests cannot establish domain association or provider compatibility.

## Endpoints used

All paths below are under the supplied `/v1` base URL. Mutations use a random `Idempotency-Key`. After creation, requests use `Authorization: Onboarding <sessionToken>`, **not** `Bearer`.

| Method | Path | Purpose |
|---|---|---|
| POST | `/onboarding/sessions` | Start with email and consent version |
| GET | `/onboarding/sessions/{id}` | State and activation reconciliation |
| PATCH | `/onboarding/sessions/{id}/profile` | Names/email plus `expectedVersion` |
| POST | `/auth/credential-setups` | Select `passkey` or `password-mfa` |
| GET | `/auth/credential-setups/{id}` | Reconcile setup state |
| POST | `/auth/credential-setups/{id}/passkey-registration` | Credential Manager creation options |
| POST | `/auth/credential-setups/{id}/passkey-registration/complete` | Registration JSON as `credential` object |
| POST | `/auth/credential-setups/{id}/password` | Initial password |
| POST | `/auth/credential-setups/{id}/totp-enrollment` | Authenticator secret |
| POST | `/auth/credential-setups/{id}/totp-enrollment/confirm` | Six-digit code |
| POST | `/auth/credential-setups/{id}/complete` | Finalize credential setup |
| POST | `/onboarding/sessions/{id}/review` | Revision, consent version and explicit acceptance |
| POST | `/onboarding/sessions/{id}/email-confirmations` | Send final email |
| POST | `/onboarding/sessions/{id}/email-confirmations/{confirmationId}/resend` | Rotate and resend code |
| POST | `/onboarding/sessions/{id}/email-confirmations/{confirmationId}/confirm` | Eight-digit code; accept complete or activating |

## Lifecycle, failures and privacy

- ViewModel retains the capability across rotation. Nothing is written to preferences, disk, `SavedStateHandle` or saved-instance state. Process death starts a fresh flow; expired provisional accounts are cleaned up by the backend.
- Only one API operation runs at once. After transport failure, explicit retry reuses the exact body and idempotency key; other mutations are blocked. The retry window is four minutes, below the backend receipt lifetime. Check status reconciles without replaying a mutation.
- A credential setup lasts five minutes. Cancellation of the native passkey dialog can reuse the in-memory challenge before expiry. Switching methods after creating a setup requires restarting because the backend permits only one initial setup.
- A server-side uncertain credential operation requires restart. An uncertain email send is reconciled via status, then a deliberate resend after the cooldown. No automatic mutation retries or arbitrary provider fallback.
- HTTP redirects, cleartext production URLs, response caching and automatic connection retries are disabled. No HTTP logging interceptors are installed. Raw response bodies and capability values are excluded from exception messages.
- Passwords, TOTP secrets and pending credential JSON are memory-only. Password-manager saving is optional and cancellation does not cancel enrollment. Keep the host screen secure while displaying the TOTP secret. JVM strings cannot be reliably zeroized; clear references promptly and do not log UI state.
- Activation polls at most six times, two seconds apart, then offers a manual status action. On completion the capability and enrollment secret are discarded. No login tokens are minted or fabricated on Android.
- Profile edits invalidate review/email proof on the backend. The returned revision controls the next request; the client does not invent version numbers.

## Verification

Tests cover HTTP paths, JSON encoding, capabilities/idempotency headers, HTTPS enforcement, response/error handling, passkey and password/TOTP progression, email-last ordering, duplicate taps, retry key reuse, uncertain enrollment and bounded activation polling. Real-device validation is still required for Credential Manager, fingerprint/PIN prompts, autofill, rotation during a provider dialog, SES delivery and final login handoff.
