# Verification record — 2026-09-23

- Compiled every Kotlin source in `auth` with Kotlin 2.2.10, the matching Compose compiler plugin, Android API 37 classes and the library's actual Gradle-resolved dependency artifacts. Compilation succeeded.
- Compiled the sample Kotlin/Compose sources against resolved dependencies and the compiled library, using a temporary stand-in for the four generated BuildConfig string constants. This checks source compatibility, not Android packaging.
- Ran all 15 repository JVM tests with JUnit 4: 13 onboarding tests plus the two existing example tests. All passed. This included real OkHttp/MockWebServer contract tests and coroutine-controlled ViewModel tests, not live AWS calls.
- Independent code review found an idempotency retry issue and a password-manager effect cancellation issue; both were fixed and rechecked. The busy-server retry is covered by a regression test.
- Gradle evaluated the library/sample build configuration. The sample explicitly enables `resValues`, required by this project's AGP version for generated Digital Asset Links strings.
- Full `:auth:testDebugUnitTest :auth:lintDebug :app:assembleDebug` via Android Gradle Plugin could not complete in this environment: Android SDK platform/build-tools installation and license state were unavailable. The successful compilation and JUnit runs above used a separate compiler invocation; they do **not** establish successful APK assembly, manifest/resource merging, Android lint or instrumentation testing.

Before treating this as release-ready, run the documented Gradle commands with a configured Android SDK, then exercise passkey creation on a real Android device using the actual app certificate/RP association and complete final email verification against the deployed backend. Test rotation while a provider dialog is open and restart after process death. The sample's sign-in handoff deliberately awaits the separate authentication feature.
