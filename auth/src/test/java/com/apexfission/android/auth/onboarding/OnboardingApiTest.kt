package com.apexfission.android.auth.onboarding

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnboardingApiTest {
    @Test fun `production client rejects cleartext and URL credentials`() {
        for (url in listOf("http://example.com/v1", "https://user:secret@example.com/v1", "https://example.com/v1?token=x")) {
            assertThrows(IllegalArgumentException::class.java) { HttpOnboardingApi(url) }
        }
    }

    @Test fun `start has idempotency but no authorization and profile uses capability and revision`() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpOnboardingApi(server.url("/v1/").toString(), OkHttpClient(), allowLoopbackHttp = true)
            server.enqueue(MockResponse().setResponseCode(201).setBody(sessionJson("profile-pending", token = true)))
            val started = api.start("david@example.com", "terms-v1", "request-123")
            val start = server.takeRequest()
            assertEquals("/v1/onboarding/sessions", start.path)
            assertNull(start.getHeader("Authorization"))
            assertEquals("request-123", start.getHeader("Idempotency-Key"))
            assertEquals("terms-v1", JSONObject(start.body.readUtf8()).getString("consentVersion"))
            server.enqueue(MockResponse().setBody(sessionJson("credentials-pending")))
            api.profile(started.capability, "sid", Profile("David", "Garcia"), "david@example.com", 7, "request-456")
            val profile = server.takeRequest()
            assertEquals("PATCH", profile.method)
            assertEquals("Onboarding secret-token", profile.getHeader("Authorization"))
            assertEquals(7, JSONObject(profile.body.readUtf8()).getInt("expectedVersion"))
            assertFalse(started.toString().contains("secret-token"))
        }
    }

    @Test fun `registration credential is an object and responses including 202 are parsed`() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpOnboardingApi(server.url("/v1").toString(), OkHttpClient(), allowLoopbackHttp = true)
            server.enqueue(MockResponse().setBody(setupJson("ready")))
            api.setupAction(Capability("secret"), "setup", "passkey-registration/complete", JSONObject().put("credential", JSONObject("""{"id":"key","response":{"clientDataJSON":"opaque"}}""")), "request-123")
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("opaque", body.getJSONObject("credential").getJSONObject("response").getString("clientDataJSON"))
            server.enqueue(MockResponse().setResponseCode(202).setBody(sessionJson("activating")))
            assertEquals(SessionStatus.ACTIVATING, api.confirm(Capability("secret"), "sid", "proof", "01234567", "request-456").status)
            val confirm = server.takeRequest()
            assertTrue(confirm.path!!.endsWith("/email-confirmations/proof/confirm"))
            assertEquals("01234567", JSONObject(confirm.body.readUtf8()).getString("code"))
        }
    }

    @Test fun `redirect never forwards the onboarding capability`() = runBlocking {
        MockWebServer().use { source -> MockWebServer().use { target ->
            val api = HttpOnboardingApi(source.url("/v1").toString(), OkHttpClient(), allowLoopbackHttp = true)
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/steal")).setBody("""{"code":"redirect"}"""))
            try { api.status(Capability("secret"), "sid"); fail("expected rejection") }
            catch (e: OnboardingApiException) { assertEquals(307, e.statusCode) }
            assertEquals(0, target.requestCount)
        } }
    }

    @Test fun `resend uses the current confirmation id and accepts its replacement`() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpOnboardingApi(server.url("/v1").toString(), OkHttpClient(), allowLoopbackHttp = true)
            val response = JSONObject(sessionJson("email-confirmation-pending")).put("confirmationId", "replacement")
            server.enqueue(MockResponse().setBody(response.toString()))
            val session = api.sendEmail(Capability("secret"), "sid", "previous", "request-123")
            assertEquals("/v1/onboarding/sessions/sid/email-confirmations/previous/resend", server.takeRequest().path)
            assertEquals("replacement", session.confirmationId)
        }
    }

    @Test fun `server errors expose code but never raw bodies`() = runBlocking {
        MockWebServer().use { server ->
            val api = HttpOnboardingApi(server.url("/v1").toString(), OkHttpClient(), allowLoopbackHttp = true)
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":"confirmation_throttled","secret":"do-not-log"}"""))
            try { api.status(Capability("secret"), "sid"); fail("expected API error") }
            catch (e: OnboardingApiException) {
                assertEquals(429, e.statusCode)
                assertEquals("confirmation_throttled", e.code)
                assertFalse(e.toString().contains("do-not-log"))
            }
        }
    }
}

internal fun sessionJson(status: String, token: Boolean = false) = """{
"sessionId":"sid","status":"$status","version":1,"expiresAt":"2099-01-01T00:00:00Z",
"consentVersion":"terms-v1","email":"david@example.com","profile":{"givenName":"David","familyName":"Garcia"},
"identityStatus":"not-verified","allowedActions":[],"userPublicId":"public-id"${if (token) ",\"sessionToken\":\"secret-token\"" else ""}}
"""
internal fun setupJson(status: String) = """{"setupId":"setup","method":"passkey","status":"$status","expiresAt":"2099-01-01T00:00:00Z","nextStep":"complete"}"""
