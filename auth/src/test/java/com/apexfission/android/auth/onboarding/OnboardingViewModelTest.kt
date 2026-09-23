package com.apexfission.android.auth.onboarding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `email is only sent after credentials and explicit review`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = OnboardingViewModel(api, "terms-v1")
        vm.start("david@example.com"); runCurrent()
        vm.sendEmail(); runCurrent()
        assertEquals(0, api.emails)
        vm.saveProfile("David", "Garcia", "david@example.com"); runCurrent()
        vm.chooseMethod(EnrollmentMethod.PASSKEY); runCurrent()
        assertEquals("passkey-pending", vm.state.value.setup?.status)
        vm.submitPasskey("""{"id":"credential"}"""); runCurrent()
        assertEquals(SessionStatus.REVIEW, vm.state.value.session?.status)
        assertEquals(0, api.emails)
        vm.acceptReview(); runCurrent()
        assertEquals(SessionStatus.EMAIL, vm.state.value.session?.status)
        vm.sendEmail(); runCurrent()
        assertEquals(1, api.emails)
        vm.confirmEmail("01234567"); runCurrent()
        assertEquals(SessionStatus.COMPLETE, vm.state.value.session?.status)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `uncertain request retry preserves key and blocks duplicate starts`() = runTest(dispatcher) {
        val api = FakeApi().apply { failStart = true }
        val vm = OnboardingViewModel(api, "terms-v1")
        vm.start("david@example.com"); vm.start("another@example.com"); runCurrent()
        assertTrue(vm.state.value.canRetry)
        vm.start("another@example.com"); runCurrent()
        assertEquals(1, api.startKeys.size)
        api.failStart = false
        vm.retry(); runCurrent()
        assertEquals(2, api.startKeys.size)
        assertEquals(api.startKeys[0], api.startKeys[1])
        assertEquals(SessionStatus.PROFILE, vm.state.value.session?.status)
    }

    @Test fun `busy server preserves the same request across uncertain retries`() = runTest(dispatcher) {
        val api = FakeApi().apply { failStart = true }
        val vm = OnboardingViewModel(api, "terms-v1")
        vm.start("david@example.com"); runCurrent()
        api.failStart = false
        api.operationInProgress = true
        vm.retry(); runCurrent()
        assertTrue(vm.state.value.canRetry)
        api.operationInProgress = false
        vm.retry(); runCurrent()
        assertEquals(3, api.startKeys.size)
        assertEquals(1, api.startKeys.distinct().size)
        assertEquals(SessionStatus.PROFILE, vm.state.value.session?.status)
    }

    @Test fun `password fallback verifies TOTP before review and clears enrollment secret`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = OnboardingViewModel(api, "terms-v1")
        vm.start("david@example.com"); runCurrent()
        vm.saveProfile("David", "Garcia", "david@example.com"); runCurrent()
        vm.chooseMethod(EnrollmentMethod.PASSWORD_MFA); runCurrent()
        vm.setPassword("LongPassword!123"); runCurrent()
        assertEquals("totp-pending", vm.state.value.setup?.status)
        assertNotNull(vm.state.value.setup?.secret)
        assertEquals(SessionStatus.CREDENTIALS, vm.state.value.session?.status)
        vm.confirmTotp("123456"); runCurrent()
        assertEquals(SessionStatus.REVIEW, vm.state.value.session?.status)
        assertNull(vm.state.value.setup)
        assertEquals(0, api.emails)
    }

    @Test fun `uncertain credentials require restart and restart discards memory state`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = OnboardingViewModel(api, "terms-v1")
        vm.start("david@example.com"); runCurrent()
        vm.saveProfile("David", "Garcia", "david@example.com"); runCurrent()
        vm.chooseMethod(EnrollmentMethod.PASSKEY); runCurrent()
        api.failCredential = true
        vm.submitPasskey("""{"id":"credential"}"""); runCurrent()
        assertTrue(vm.state.value.restartRequired)
        vm.restart()
        assertNull(vm.state.value.session)
        assertNull(vm.state.value.setup)
        assertFalse(vm.state.value.canRetry)
    }

    @Test fun `restart cancels an in-flight operation without restoring the old session`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi().apply { startGate = gate }
        val vm = OnboardingViewModel(api, "terms-v1")
        vm.start("david@example.com"); runCurrent()
        assertTrue(vm.state.value.busy)
        vm.restart()
        gate.complete(Unit); runCurrent()
        assertNull(vm.state.value.session)
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.canRetry)
    }

    @Test fun `activation polling is bounded and completion needs fresh authentication`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = OnboardingViewModel(api, "terms-v1")
        vm.start("david@example.com"); runCurrent()
        api.session = api.session.copy(status = SessionStatus.ACTIVATING)
        vm.checkStatus(); advanceUntilIdle()
        assertEquals(SessionStatus.ACTIVATING, vm.state.value.session?.status)
        assertTrue(api.statusReads in 2..8)
        api.session = api.session.copy(status = SessionStatus.COMPLETE, userPublicId = "public-id")
        vm.checkStatus(); runCurrent()
        assertEquals("public-id", vm.state.value.session?.userPublicId)
    }
}

private class FakeApi : OnboardingApi {
    var session = parseSession(JSONObject(sessionJson("profile-pending")))
    var setup = CredentialSetup("setup", EnrollmentMethod.PASSKEY, "created", Instant.parse("2099-01-01T00:00:00Z"))
    var startGate: CompletableDeferred<Unit>? = null
    var failStart = false
    var operationInProgress = false
    var failCredential = false
    var emails = 0
    var statusReads = 0
    val startKeys = mutableListOf<String>()
    override suspend fun start(email: String, consentVersion: String, key: String): StartedSession {
        startKeys += key
        startGate?.await()
        if (failStart) throw IOException()
        if (operationInProgress) throw OnboardingApiException(409, "operation_in_progress")
        return StartedSession(session, Capability("secret"))
    }
    override suspend fun status(capability: Capability, sessionId: String): OnboardingSession { statusReads++; return session }
    override suspend fun profile(capability: Capability, sessionId: String, profile: Profile, email: String, version: Long, key: String): OnboardingSession {
        session = session.copy(profile = profile, email = email, version = version + 1, status = SessionStatus.CREDENTIALS)
        return session
    }
    override suspend fun createSetup(capability: Capability, method: EnrollmentMethod, key: String): CredentialSetup {
        setup = CredentialSetup("setup", method, "created", setup.expiresAt)
        return setup
    }
    override suspend fun setupStatus(capability: Capability, setupId: String) = setup
    override suspend fun setupAction(capability: Capability, setupId: String, action: String, body: JSONObject, key: String): CredentialSetup {
        if (failCredential) throw OnboardingApiException(422, "credential_operation_failed")
        setup = when(action) {
            "passkey-registration" -> CredentialSetup("setup", setup.method, "passkey-pending", setup.expiresAt, creationOptions = "{}")
            "password" -> CredentialSetup("setup", setup.method, "password-set", setup.expiresAt)
            "totp-enrollment" -> CredentialSetup("setup", setup.method, "totp-pending", setup.expiresAt, secret = EnrollmentSecret("ABCDEF"))
            "complete" -> { session = session.copy(status = SessionStatus.REVIEW); CredentialSetup("setup", setup.method, "complete", setup.expiresAt) }
            else -> CredentialSetup("setup", setup.method, "ready", setup.expiresAt)
        }
        return setup
    }
    override suspend fun review(capability: Capability, sessionId: String, version: Long, consentVersion: String, key: String): OnboardingSession {
        session = session.copy(status = SessionStatus.EMAIL); return session
    }
    override suspend fun sendEmail(capability: Capability, sessionId: String, confirmationId: String?, key: String): OnboardingSession {
        emails++; session = session.copy(confirmationId = "confirmation-$emails"); return session
    }
    override suspend fun confirm(capability: Capability, sessionId: String, confirmationId: String, code: String, key: String): OnboardingSession {
        session = session.copy(status = SessionStatus.COMPLETE, userPublicId = "public-id"); return session
    }
}
