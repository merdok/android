package io.panelassistant.android.http

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.haToken
import io.panelassistant.android.haUrl
import io.panelassistant.android.setHaConnection
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Live settings: the Configure page follows the panel through the `config` family and sends each field as it is
 *  committed, through the production routes and the stub socket door. */
class LiveSettingsRouteTest {
    @Test fun `a config subscription over the stub socket follows a setting changed through another door`() =
        withFullReadServer { config, fixture ->
            config.setHaConnection("http://ha.test", "private-test-token")
            testApplication {
                application { fixture.mount(this) }
                val read = client.get("/api/v1/config/values")
                assertEquals(HttpStatusCode.OK, read.status)
                assertEquals("no-store", read.headers[HttpHeaders.CacheControl])
                createClient { install(io.ktor.client.plugins.websocket.WebSockets) }.webSocket("/ui") {
                    send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", "s1").put("op", "subscribe")
                        .put("args", JSONObject().put("family", "config")).toString()))
                    /** The next known value the subscription carries that satisfies [done]. */
                    suspend fun next(done: (JSONObject) -> Boolean): JSONObject = kotlinx.coroutines.withTimeout(10_000) {
                        var found: JSONObject? = null
                        while (found == null) {
                            val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                            if (!frame.has("subscription")) {
                                if (frame.optString("command_id") == "s1") assertEquals("applied", frame.getString("outcome"))
                                continue
                            }
                            val observation = frame.getJSONArray("observations").getJSONObject(0)
                            assertEquals("known", observation.getString("state"))
                            val value = observation.get("value")
                            val body = value as? JSONObject ?: JSONObject(value.toString())
                            if (done(body)) found = body
                        }
                        found!!
                    }
                    val first = next { true }
                    assertEquals("Contract panel", first.getJSONObject("settings").getString("friendly_name"))
                    assertEquals("", first.getJSONObject("settings").getString("ha_token"), "a secret reads blank")
                    assertTrue(first.getJSONObject("ha_auth").getBoolean("configured"))
                    assertTrue(first.isNull("renderer_pending"), "no built-in renderer runs")

                    // Another origin changes the setting; the subscription carries the new value without a reload.
                    val saved = client.submitForm("/api/v1/config", Parameters.build { append("friendly_name", "Renamed panel") }) {
                        accept(ContentType.Application.Json)
                    }
                    assertEquals(HttpStatusCode.OK, saved.status)
                    val changed = next { it.getJSONObject("settings").getString("friendly_name") != "Contract panel" }
                    assertEquals("Renamed panel", changed.getJSONObject("settings").getString("friendly_name"))
                }
            }
        }

    // Configure sends each field as it is committed, so a token can reach the panel before its first address.
    @Test fun `a token sent before the first address stays with it and never follows the panel to another`() =
        withRouteConfig { config, _, server, _ ->
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } }
                    }
                }
                suspend fun send(key: String, value: String) {
                    val response = client.submitForm("/api/v1/config", Parameters.build { append(key, value) }) {
                        accept(ContentType.Application.Json)
                    }
                    // This rig's renderer effect fails for credential changes; what matters is what was stored.
                    val applied = JSONObject(response.bodyAsText()).getJSONArray("applied")
                    assertTrue((0 until applied.length()).any { applied.getString(it) == key }, "$key was not applied")
                }
                assertEquals("", config.haUrl)
                send("ha_token", "first-token")
                send("ha_url", "http://ha.first.test:8123")
                assertEquals("http://ha.first.test:8123", config.haUrl)
                assertEquals("first-token", config.haToken, "the token sent first belongs to the first address")
                send("ha_url", "http://ha.other.test:8123")
                assertEquals("", config.haToken, "a token never follows the panel to another Home Assistant")
            }
        }
}
