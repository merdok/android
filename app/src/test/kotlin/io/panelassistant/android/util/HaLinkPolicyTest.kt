package io.panelassistant.android.util

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import org.junit.Assert.assertThrows

class HaLinkPolicyTest {
    @Test fun websocketResolutionIsBoundedForUntrustedConfiguredEndpoints() {
        assertTrue(HaLink.MAX_WS_FRAME_BYTES in 1L..(32L * 1024L * 1024L))
        assertTrue(HaLink.WS_RESOLUTION_DEADLINE_MS in 1_000L..30_000L)
    }

    @Test fun deviceLinkResolutionHonorsForceIpv4() {
        // Both resolve entry points take non-defaulted policy parameters, so no caller can omit
        // them; this pins that the policy genuinely reaches the socket: an IPv6-only answer under
        // Force IPv4 must fail with the named policy verdict rather than dial IPv6.
        val v6only = listOf(java.net.InetAddress.getByName("2001:db8::1"))
        try {
            HaLink.deviceIdViaWs(
                "ws://ha.test:9", "token", "entry",
                preferIpv4 = true, ipv4Only = true, resolver = { v6only },
            )
            org.junit.Assert.fail("expected the Force IPv4 policy verdict")
        } catch (expected: Exception) {
            val chain = generateSequence<Throwable>(expected) { it.cause }.toList()
            assertTrue(
                "failure names the Force IPv4 conflict, was: $chain",
                chain.any { it.message.orEmpty().contains("Force IPv4") },
            )
        }
    }

    @Test fun httpResolutionResponsesAreBoundedBeforeTextDecoding() {
        assertTrue(HaLink.MAX_HTTP_RESPONSE_BYTES in 1L..(4L * 1024L * 1024L))
        assertEquals("{}", HaLink.readHttpBody(ByteArrayInputStream("{}".toByteArray())))
        assertThrows(ByteLimitExceeded::class.java) {
            HaLink.readHttpBody(ByteArrayInputStream(ByteArray(HaLink.MAX_HTTP_RESPONSE_BYTES.toInt() + 1)))
        }
    }

    @Test fun `resolution target retains the exact native base path and the entry`() {
        assertEquals(
            "v1:25:https://native.example/ha:01J00000000000000000000AAA",
            HaLink.resolutionTarget("  https://native.example/ha/ ", "01J00000000000000000000AAA"),
        )
    }

    @Test fun `resolution target is safe for SharedPreferences XML`() {
        val target = HaLink.resolutionTarget("https://native.example/ha", "01J00000000000000000000AAA")

        assertEquals(-1, target.indexOf('\u0000'))
    }

    /** Entity ids are localized and renamed freely; only the entry's device identifier names the panel. */
    @Test fun `the device Panel Assistant registered for the entry is the link whatever its entities are called`() {
        val commands = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket("/api/websocket") {
                    send(Frame.Text("""{"type":"auth_required"}"""))
                    incoming.receive()
                    send(Frame.Text("""{"type":"auth_ok"}"""))
                    for (frame in incoming) {
                        val command = JSONObject((frame as Frame.Text).readText())
                        commands += command.getString("type")
                        check(command.getString("type") == "config/device_registry/list") { "unexpected HA command" }
                        val devices = JSONArray()
                            .put(JSONObject().put("id", "kuchyne-device")
                                .put("identifiers", JSONArray().put(JSONArray().put("zha").put("kuchyne_panel"))))
                            .put(JSONObject().put("id", "panel-device")
                                .put("identifiers", JSONArray().put(JSONArray().put("panel_assistant").put("entry-test"))))
                        send(Frame.Text(JSONObject().put("id", command.getInt("id")).put("type", "result")
                            .put("success", true).put("result", devices).toString()))
                    }
                }
            }
        }.start(wait = false)
        try {
            val base = "http://127.0.0.1:${kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors() }.single().port}"
            assertEquals(
                "$base/config/devices/device/panel-device",
                HaLink.resolveWithAccessToken(base, "token", "entry-test", preferIpv4 = false, ipv4Only = false),
            )
            assertNull(
                "an entry no device carries has no device destination",
                HaLink.resolveWithAccessToken(base, "token", "entry-other", preferIpv4 = false, ipv4Only = false),
            )
            assertEquals(listOf("config/device_registry/list", "config/device_registry/list"), commands.toList())
        } finally {
            server.stop(0, 0)
        }
    }
}
