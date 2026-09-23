package com.apexfission.android.auth.onboarding

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No automatic mutation retries, redirects, cookies, cache, credentials or HTTP body logging. */
class HttpOnboardingApi internal constructor(
    baseUrl: String,
    client: OkHttpClient,
    allowLoopbackHttp: Boolean,
) : OnboardingApi {
    constructor(baseUrl: String) : this(baseUrl, OkHttpClient(), false)

    private val base: HttpUrl = baseUrl.toHttpUrl().also {
        require(it.isHttps || (allowLoopbackHttp && it.host in setOf("localhost", "127.0.0.1", "::1"))) { "An HTTPS API URL is required" }
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) { "API URL must not contain credentials, query or fragment" }
    }
    private val http = client.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).cache(null)
        .callTimeout(30, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()

    override suspend fun start(email: String, consentVersion: String, key: String): StartedSession {
        val json = request("POST", listOf("onboarding", "sessions"), body = obj("email" to email, "consentVersion" to consentVersion), key = key)
        return decode { StartedSession(parseSession(json), Capability(json.getString("sessionToken"))) }
    }
    override suspend fun status(capability: Capability, sessionId: String) =
        session("GET", sessionPath(sessionId), capability)
    override suspend fun profile(capability: Capability, sessionId: String, profile: Profile, email: String, version: Long, key: String) =
        session("PATCH", sessionPath(sessionId) + "profile", capability, obj("givenName" to profile.givenName, "familyName" to profile.familyName, "email" to email, "expectedVersion" to version), key)
    override suspend fun createSetup(capability: Capability, method: EnrollmentMethod, key: String) =
        decode { parseSetup(request("POST", listOf("auth", "credential-setups"), capability, obj("method" to method.wire), key)) }
    override suspend fun setupStatus(capability: Capability, setupId: String) =
        decode { parseSetup(request("GET", listOf("auth", "credential-setups", setupId), capability)) }
    override suspend fun setupAction(capability: Capability, setupId: String, action: String, body: JSONObject, key: String): CredentialSetup {
        require(action in setOf("passkey-registration", "passkey-registration/complete", "password", "totp-enrollment", "totp-enrollment/confirm", "complete"))
        return decode { parseSetup(request("POST", listOf("auth", "credential-setups", setupId) + action.split('/'), capability, body, key)) }
    }
    override suspend fun review(capability: Capability, sessionId: String, version: Long, consentVersion: String, key: String) =
        session("POST", sessionPath(sessionId) + "review", capability, obj("expectedVersion" to version, "consentVersion" to consentVersion, "acceptTerms" to true), key)
    override suspend fun sendEmail(capability: Capability, sessionId: String, confirmationId: String?, key: String): OnboardingSession {
        val path = sessionPath(sessionId) + "email-confirmations" + (confirmationId?.let { listOf(it, "resend") } ?: emptyList())
        return session("POST", path, capability, JSONObject(), key)
    }
    override suspend fun confirm(capability: Capability, sessionId: String, confirmationId: String, code: String, key: String) =
        session("POST", sessionPath(sessionId) + listOf("email-confirmations", confirmationId, "confirm"), capability, obj("code" to code), key)

    private suspend fun session(method: String, path: List<String>, capability: Capability, body: JSONObject? = null, key: String? = null) =
        decode { parseSession(request(method, path, capability, body, key)) }
    private fun sessionPath(id: String) = listOf("onboarding", "sessions", id)

    private suspend fun request(method: String, path: List<String>, capability: Capability? = null, body: JSONObject? = null, key: String? = null): JSONObject {
        val url = base.newBuilder().apply {
            if (base.pathSegments.lastOrNull() == "") removePathSegment(base.pathSize - 1)
            path.forEach { require(it.isNotBlank() && it != "." && it != ".."); addPathSegment(it) }
        }.build()
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        require(bytes == null || bytes.size <= 65536) { "Request too large" }
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Cache-Control", "no-store").apply {
                capability?.let { header("Authorization", "Onboarding ${it.value}") }
                if (method != "GET") {
                    require(key != null && key.length in 8..128)
                    header("Idempotency-Key", key)
                }
            }.method(method, bytes?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val operation = "$method ${OnboardingLog.route(path)}"
        val trace = requestSequence.incrementAndGet()
        val startedAt = System.nanoTime()
        OnboardingLog.debug("http[$trace] start $operation")
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { OnboardingLog.debug("http[$trace] cancelled $operation"); call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    OnboardingLog.warning("http[$trace] transport failure $operation type=${e.javaClass.simpleName}")
                    if (!continuation.isCancelled) continuation.resumeWithException(IOException("Onboarding connection failed"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        OnboardingLog.debug("http[$trace] response $operation status=${response.code} elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}")
                        try {
                            val source = response.body?.source() ?: throw OnboardingProtocolException()
                            source.request(262145)
                            if (source.buffer.size > 262144) throw OnboardingProtocolException()
                            val json = try { JSONObject(source.readUtf8()) } catch (_: Exception) { throw OnboardingProtocolException() }
                            if (!response.isSuccessful) OnboardingLog.warning("http[$trace] rejected code=${OnboardingLog.label(json.optString("code"))} requestId=${OnboardingLog.label(json.optString("requestId"))}")
                            else OnboardingLog.debug("http[$trace] accepted state=${OnboardingLog.label(json.optString("status"))}")
                            if (!response.isSuccessful) throw OnboardingApiException(response.code, json.optString("code", "request_failed"))
                            continuation.resume(json)
                        } catch (e: Exception) {
                            OnboardingLog.warning("http[$trace] response failure type=${e.javaClass.simpleName}")
                            if (!continuation.isCancelled) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }
    private inline fun <T> decode(block: () -> T): T = try { block() }
    catch (e: IOException) { throw e }
    catch (e: Exception) {
        OnboardingLog.warning("Response decoding failed type=${e.javaClass.simpleName}")
        throw OnboardingProtocolException()
    }
    private companion object { val requestSequence = java.util.concurrent.atomic.AtomicLong() }
}

internal fun obj(vararg values: Pair<String, Any>): JSONObject = JSONObject().apply { values.forEach { (k, v) -> put(k, v) } }
