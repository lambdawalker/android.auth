# Android direct onboarding

Implements the existing Go API contract on feature/authentication-onboarding (44db9f9). Scope: direct onboarding, passkey first, password plus authenticator TOTP fallback, profile review and terms consent, final email confirmation, activation reconciliation, then fresh authentication. Identity scanning, invitations, recovery and login are separate features.

The auth library supplies an HTTPS-only OkHttp client, typed API models, a ViewModel with StateFlow, Android Credential Manager bridge and an embeddable Compose screen. The app module demonstrates integration. No AWS client secrets or provider tokens belong on Android. Passkey registration JSON travels unchanged between Cognito and Credential Manager.

The server owns lifecycle and revision numbers. The client serializes requests, retains the same idempotency key for explicit retries of uncertain network operations, and prevents other mutations until resolved or restarted. Retry is limited to less than the server's five-minute receipt lifetime. Definitive errors allow corrected input; uncertain credential operations require restart. Email confirmation 202 is reconciled by bounded status polling and a manual status action. Completion is not login.

Session capabilities, passwords, TOTP secrets and pending request bodies are memory-only and are never logged or saved in bundles. ViewModel retention handles rotation; process death starts over. Setup expires after five minutes, onboarding after thirty. Changing email/profile resets review and confirmation on the server. Passkey cancellation permits retry of the same in-memory creation challenge while valid; changing method after setup creation requires restart.

Host responsibilities: supply HTTPS API URL including /v1, published consent version, terms content/link, Digital Asset Links for the actual application ID and signing certificate on the configured RP domain, and an authentication-required callback. Devices below API 28 use the fallback. No camera permission.

Verification: HTTP contract tests, controller lifecycle/error/retry tests, Gradle unit tests, lint and assembly where available, then real-device passkey and SES integration. Do not claim native end-to-end success from unit tests.
