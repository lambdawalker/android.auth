package com.apexfission.android.auth.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** Public state excludes session capabilities and pending request bodies. Do not persist it. */
data class OnboardingUiState(
    val session: OnboardingSession? = null,
    val setup: CredentialSetup? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val canRetry: Boolean = false,
    val restartRequired: Boolean = false,
)

/** Call actions on the main thread. A new instance deliberately starts a new onboarding flow. */
class OnboardingViewModel(
    private val api: OnboardingApi,
    val consentVersion: String,
) : ViewModel() {
    init { require(consentVersion.isNotBlank()) }
    private val mutable = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = mutable.asStateFlow()
    private var capability: Capability? = null
    private var pending: (() -> Unit)? = null
    private var pendingSince = 0L
    private var job: Job? = null
    private var generation = 0

    fun start(email: String) {
        if (state.value.session != null || email.isBlank()) return
        val key = key()
        execute({ api.start(email.trim(), consentVersion, key) }) {
            capability = it.capability
            applySession(it.session)
        }
    }

    fun saveProfile(givenName: String, familyName: String, email: String) {
        val session = active() ?: return
        if (session.status !in setOf(SessionStatus.PROFILE, SessionStatus.CREDENTIALS, SessionStatus.REVIEW, SessionStatus.EMAIL)) return
        val profile = Profile(givenName.trim(), familyName.trim())
        if (profile.givenName.isBlank() || profile.familyName.isBlank() || email.isBlank()) return
        val cap = capability ?: return
        val key = key()
        execute({ api.profile(cap, session.id, profile, email.trim(), session.version, key) }, ::applySession)
    }

    fun chooseMethod(method: EnrollmentMethod) {
        OnboardingLog.debug("Credential method selected: ${method.wire}")
        val session = active() ?: return
        if (session.status != SessionStatus.CREDENTIALS || state.value.setup != null) return
        val cap = capability ?: return
        val key = key()
        execute({ api.createSetup(cap, method, key) }) {
            mutable.value = state.value.copy(setup = it)
            if (method == EnrollmentMethod.PASSKEY) setupAction("passkey-registration")
        }
    }

    fun submitPasskey(registrationJson: String) {
        if (state.value.setup?.status != "passkey-pending") return
        val credential = try { JSONObject(registrationJson) } catch (_: Exception) {
            message("The credential provider returned an invalid response."); return
        }
        setupAction("passkey-registration/complete", obj("credential" to credential))
    }

    fun setPassword(password: String) {
        if (state.value.setup?.method != EnrollmentMethod.PASSWORD_MFA || state.value.setup?.status != "created") return
        if (password.toByteArray(Charsets.UTF_8).size !in 12..256) { message("Use a password between 12 and 256 UTF-8 bytes."); return }
        setupAction("password", obj("password" to password))
    }
    fun confirmTotp(code: String) {
        if (state.value.setup?.status != "totp-pending") return
        if (!code.matches(Regex("[0-9]{6}"))) { message("Enter the six-digit authenticator code."); return }
        setupAction("totp-enrollment/confirm", obj("code" to code))
    }
    fun continueCredentials() {
        when (state.value.setup?.status) {
            "created" -> if (state.value.setup?.method == EnrollmentMethod.PASSKEY) setupAction("passkey-registration")
            "password-set" -> setupAction("totp-enrollment")
            "ready" -> setupAction("complete")
            "complete" -> checkStatus()
        }
    }

    private fun setupAction(action: String, body: JSONObject = JSONObject()) {
        if (active()?.status != SessionStatus.CREDENTIALS) return
        val setup = state.value.setup ?: return
        if (!Instant.now().isBefore(setup.expiresAt)) { requireRestart("Credential setup expired. Please start again."); return }
        val cap = capability ?: return
        val key = key()
        execute({ api.setupAction(cap, setup.id, action, body, key) }) {
            mutable.value = state.value.copy(setup = it)
            when (it.status) {
                "password-set" -> setupAction("totp-enrollment")
                "ready" -> setupAction("complete")
                "complete" -> checkStatus()
                "uncertain" -> requireRestart("Credential enrollment could not be confirmed. Please start again.")
            }
        }
    }

    /** Host must show the terms matching consentVersion and obtain explicit acceptance first. */
    fun acceptReview() {
        val session = active() ?: return
        if (session.status != SessionStatus.REVIEW) return
        if (session.consentVersion != consentVersion) { requireRestart("The terms changed. Update the displayed terms before restarting."); return }
        val cap = capability ?: return
        val key = key()
        execute({ api.review(cap, session.id, session.version, consentVersion, key) }, ::applySession)
    }
    fun sendEmail() {
        val session = active() ?: return
        if (session.status != SessionStatus.EMAIL) return
        val cap = capability ?: return
        val key = key()
        execute({ api.sendEmail(cap, session.id, session.confirmationId, key) }, ::applySession)
    }
    fun confirmEmail(code: String) {
        val session = active() ?: return
        val id = session.confirmationId ?: return
        if (session.status != SessionStatus.EMAIL) return
        if (!code.matches(Regex("[0-9]{8}"))) { message("Enter the eight-digit email code."); return }
        val cap = capability ?: return
        val key = key()
        execute({ api.confirm(cap, session.id, id, code, key) }, ::applySession)
    }

    /** Reconcile with the server after an uncertain delivery or response. Never replays a mutation. */
    fun checkStatus() {
        if (state.value.busy) return
        val session = active() ?: return
        val cap = capability ?: return
        pending = null
        mutable.value = state.value.copy(restartRequired = false, canRetry = false)
        execute({ api.status(cap, session.id) }) { current ->
            applySession(current)
            val previous = state.value.setup
            if (current.status == SessionStatus.CREDENTIALS && previous != null) {
                execute({ api.setupStatus(cap, previous.id) }) { fresh ->
                    OnboardingLog.debug("Setup reconciliation state=${OnboardingLog.label(fresh.status)} hasCreationOptions=${previous.creationOptions != null} hasTotpSecret=${previous.secret != null}")
                    val restored = CredentialSetup(fresh.id, fresh.method, fresh.status, fresh.expiresAt, previous.creationOptions, previous.secret)
                    mutable.value = state.value.copy(setup = restored)
                    if (fresh.status == "uncertain" || (fresh.status == "passkey-pending" && restored.creationOptions == null) || (fresh.status == "totp-pending" && restored.secret == null)) {
                        requireRestart("Enrollment cannot safely resume. Please start again.")
                    }
                }
            }
        }
    }

    fun retry() {
        OnboardingLog.debug("Retry requested busy=${state.value.busy} pending=${pending != null}")
        if (state.value.busy) return
        if (System.nanoTime() - pendingSince >= 240_000_000_000L) {
            requireRestart("The retry window expired. Check status or start again."); return
        }
        pending?.invoke()
    }
    fun restart() {
        OnboardingLog.debug("Onboarding restarted; clearing local session")
        generation++
        job?.cancel()
        job = null
        capability = null
        pending = null
        mutable.value = OnboardingUiState()
    }
    fun credentialProviderMessage(value: String) { if (!state.value.busy) message(value) }

    override fun onCleared() {
        capability = null
        pending = null
        mutable.value = OnboardingUiState()
        super.onCleared()
    }

    private fun applySession(session: OnboardingSession) {
        OnboardingLog.debug("Session transition ${state.value.session?.status?.wire ?: "none"} -> ${session.status.wire}")
        mutable.value = state.value.copy(session = session, setup = if (session.status == SessionStatus.CREDENTIALS) state.value.setup else null)
        if (session.status == SessionStatus.COMPLETE) { capability = null; pending = null }
        if (session.status == SessionStatus.FAILED) requireRestart("Account creation could not be completed. Sign in to an existing account or start again.")
        if (session.status == SessionStatus.ACTIVATING) pollActivation(session.id)
    }
    private fun pollActivation(id: String) {
        val cap = capability ?: return
        val currentGeneration = generation
        mutable.value = state.value.copy(busy = true)
        job = viewModelScope.launch {
            try {
                repeat(6) {
                    delay(2000)
                    val session = api.status(cap, id)
                    if (generation != currentGeneration) return@launch
                    mutable.value = state.value.copy(session = session)
                    if (session.status != SessionStatus.ACTIVATING) {
                        mutable.value = state.value.copy(busy = false)
                        applySession(session)
                        return@launch
                    }
                }
                message("Activation is still processing. Check status in a moment.")
            } catch (e: CancellationException) { throw e }
            catch (_: IOException) { message("Could not check activation. Check status when your connection is available.") }
            finally { if (generation == currentGeneration) mutable.value = state.value.copy(busy = false) }
        }
    }
    private fun active(): OnboardingSession? {
        val session = state.value.session ?: return null
        if (session.status == SessionStatus.COMPLETE) return null
        if (!Instant.now().isBefore(session.expiresAt)) { requireRestart("Your onboarding session expired. Please start again."); return null }
        return session
    }
    private fun <T> execute(call: suspend () -> T, apply: (T) -> Unit) {
        if (state.value.busy || pending != null || state.value.restartRequired) {
            OnboardingLog.debug("Operation blocked busy=${state.value.busy} pending=${pending != null} restartRequired=${state.value.restartRequired}")
            return
        }
        pendingSince = System.nanoTime()
        val currentGeneration = generation
        val task: () -> Unit = {
            mutable.value = state.value.copy(busy = true, canRetry = false, message = null)
            job = viewModelScope.launch {
                try {
                    val result = call()
                    if (generation == currentGeneration) {
                        pending = null
                        mutable.value = state.value.copy(busy = false, canRetry = false)
                        apply(result)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: OnboardingApiException) {
                    OnboardingLog.warning("API failure status=${e.statusCode} code=${OnboardingLog.label(e.code)}")
                    if (generation == currentGeneration) {
                        if (e.code == "operation_in_progress") {
                            mutable.value = state.value.copy(busy = false, canRetry = true, message = "The previous request is still processing. Wait a moment, then retry the same request.")
                            return@launch
                        }
                        pending = null
                        mutable.value = state.value.copy(busy = false)
                        when {
                            e.statusCode == 401 || e.code in restartCodes -> requireRestart("This enrollment cannot continue safely. Please start again.")
                            e.code == "confirmation_delivery_uncertain" -> requireRestart("Email delivery is uncertain. Check status before requesting another email.")
                            e.statusCode >= 500 -> requireRestart("The server could not confirm the operation. Check status or start again.")
                            e.statusCode == 429 -> message("Too many attempts. Wait at least a minute before trying again.")
                            e.code == "invalid_confirmation" -> message("The email code is incorrect, expired, or exhausted. Try again or request a new code.")
                            e.code == "invalid_code" -> message("Check the authenticator code and try again.")
                            e.code in setOf("version_conflict", "onboarding_not_ready") -> message("The session changed. Check status before continuing.")
                            e.code in setOf("consent_version_changed", "consent_required") -> requireRestart("The terms changed. Update the displayed terms before restarting.")
                            else -> message("The request was not accepted. Check your input or check session status.")
                        }
                    }
                } catch (_: OnboardingProtocolException) {
                    if (generation == currentGeneration) { pending = null; mutable.value = state.value.copy(busy = false); requireRestart("Unexpected server response. Check the API configuration before restarting.") }
                } catch (_: IOException) {
                    if (generation == currentGeneration) mutable.value = state.value.copy(busy = false, canRetry = true, message = "Connection interrupted. Retry the same request, check status, or start again.")
                } catch (_: Exception) {
                    if (generation == currentGeneration) { pending = null; mutable.value = state.value.copy(busy = false); requireRestart("Could not complete onboarding. Please start again.") }
                }
            }
        }
        pending = task
        task()
    }
    private fun message(value: String) { mutable.value = state.value.copy(message = value) }
    private fun requireRestart(value: String) { OnboardingLog.warning("Restart required: $value"); pending = null; mutable.value = state.value.copy(message = value, canRetry = false, restartRequired = true) }
    private fun key() = UUID.randomUUID().toString()

    companion object {
        private val restartCodes = setOf("restart_onboarding_required", "setup_unavailable", "credential_operation_failed", "outcome_uncertain_reauthenticate", "onboarding_unavailable")
        fun factory(api: OnboardingApi, consentVersion: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(OnboardingViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return OnboardingViewModel(api, consentVersion) as T
            }
        }
    }
}
