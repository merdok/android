package io.panelassistant.android.http

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.InteractiveController
import io.panelassistant.android.control.SystemController
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A request through the stub's `/ui` socket answers as the same request does over HTTP, through the production
 * mount: the headers the page's own request carries (Host, Accept-Language, a JSON answer), the Controls card's
 * actions, a download's exact bytes and name, and a failed route's status.
 */
class SocketDoorParityTest {
    @Test fun theControlsCardActionsAreServedOverTheSocket() {
        PaneldServerHttpFixture().use { fixture ->
            val owner = RemoteControlRoutes(
                fixture.config, InteractiveController(canSu = false),
                SystemController(FakeSystemEnv(), FakeRootShell(), FakeDaemon(), isDeviceSecure = { false }),
                stepVolume = {}, stopping = { false }, cacheScreenshot = { null },
            )
            field(fixture.server, "remoteControlRoutes", owner)
            try {
                testApplication {
                    application { fixture.mount(this) }
                    val (back, dashboard) = socket {
                        request("action", JSONObject().put("a", "back")) to request("action.dashboard", JSONObject())
                    }
                    assertEquals(back.toString(), "applied", back.getString("outcome"))
                    assertEquals(dashboard.toString(), "applied", dashboard.getString("outcome"))
                }
            } finally {
                owner.closeAndJoin(1_000)
            }
        }
    }

    @Test fun anOperationReadsTheSocketsHostAndLanguageAndAsksForJson() = testApplication {
        application { echoRoutes() }
        val overHttp = JSONObject(client.get("/api/v1/echo") { header(HttpHeaders.AcceptLanguage, "de") }.bodyAsText())
        val (echo, named) = socket(language = "de") {
            request("echo", JSONObject()) to request("echo", JSONObject().put("accept_language", "fr"))
        }
        val result = echo.getJSONObject("result")
        assertEquals("the socket's upgrade Host", "localhost", result.getString("host"))
        assertEquals(overHttp.getString("language"), result.getString("language"))
        assertEquals("de", result.getString("language"))
        assertEquals("a request naming its own language keeps it", "fr", named.getJSONObject("result").getString("language"))
        assertEquals("application/json", result.getString("accept"))
    }

    @Test fun anOAuthStartOverTheSocketBindsThePanelOrigin() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val (oauth, failed) = socket {
                    request("ha.oauth.start", JSONObject().put("ha_url", "http://ha.local:8123")) to
                        request("ha.oauth.start", JSONObject().put("ha_url", ""))
                }
                assertNotEquals(oauth.toString(), "invalid-panel-origin", oauth.optJSONObject("result")?.optString("error"))
                assertEquals("invalid-ha-url", failed.getJSONObject("result").getString("error"))
                assertEquals("the route's status travels with a failed answer", 400, failed.getInt("status"))
            }
        }
    }

    @Test fun aDownloadKeepsItsBytesAndFileName() = testApplication {
        application { echoRoutes() }
        val overHttp = client.get("/api/v1/download")
        val answer = socket { request("download", JSONObject()) }
        val blob = answer.getJSONObject("result").getJSONObject("blob")
        assertArrayEquals(overHttp.bodyAsBytes(), client.get(blob.getString("url")).bodyAsBytes())
        assertEquals(overHttp.headers[HttpHeaders.ContentDisposition], answer.getJSONObject("headers").getString("content-disposition"))
    }

    /** Two operations on the production root, the socket door and its blobs: one echoes its headers, one is a download. */
    private fun io.ktor.server.application.Application.echoRoutes() {
        val table = io.panelassistant.android.ManageTable()
        paneldRoot({ emptySet() }, { false }, { "/setup" }) {
            serveOperations(table)
            route("/api/v1") {
                ops {
                    get("/echo") {
                        call.respondText(
                            JSONObject().put("host", call.request.headers[HttpHeaders.Host])
                                .put("language", call.request.headers[HttpHeaders.AcceptLanguage])
                                .put("accept", call.request.headers[HttpHeaders.Accept]).toString(),
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    get("/download") {
                        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"panel-config.json\"")
                        call.respondText("{\n  \"a\": 1\n}\n", io.ktor.http.ContentType.Application.Json)
                    }
                }
            }
            socketDoorOnto(table)
        }
    }

    private suspend fun <T> ApplicationTestBuilder.socket(language: String? = null, block: suspend DefaultClientWebSocketSession.() -> T): T {
        var result: Result<T>? = null
        // A browser's upgrade request always names the Host it opened; the test engine's names none unless asked.
        createClient { install(WebSockets) }.webSocket("/ui", request = {
            header(HttpHeaders.Host, "localhost")
            language?.let { header(HttpHeaders.AcceptLanguage, it) }
        }) {
            result = runCatching { block() }
        }
        return result!!.getOrThrow()
    }

    private suspend fun DefaultClientWebSocketSession.request(op: String, args: JSONObject): JSONObject {
        val id = "r" + System.nanoTime()
        send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", id).put("op", op).put("args", args).toString()))
        return withTimeout(10_000) {
            while (true) {
                val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                if (frame.optString("command_id") == id && !frame.optBoolean("interim")) return@withTimeout frame
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private fun field(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
