package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRoutesTest {
    @Test fun `full mount rejects devtools before privileged effects`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                    assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
                    val start = client.post("/api/v1/inspect/start")
                    assertEquals(HttpStatusCode.Conflict, start.status)
                    assertEquals(
                        """{"ok":false,"error":"devtools-incompatible-with-hardened-mode","message":"Switch to Relaxed mode before exposing WebView developer tools to the LAN."}""",
                        start.bodyAsText(),
                    )
                    for (path in listOf("/api/v1/inspect/start", "/api/v1/inspect/stop")) {
                        val foreign = client.post(path) { header(HttpHeaders.Origin, "http://elsewhere.example") }
                        assertEquals(HttpStatusCode.Forbidden, foreign.status)
                    }
            }
        }
    }
}
