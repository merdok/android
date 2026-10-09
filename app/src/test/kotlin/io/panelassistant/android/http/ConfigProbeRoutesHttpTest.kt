package io.panelassistant.android.http

import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigProbeRoutesHttpTest {

    @Test fun `log probe emits a datagram without claiming collector acknowledgement`() {
        java.net.DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 3_000
            PaneldServerHttpFixture().use { fixture ->
                testApplication {
                    application { fixture.mount(this) }
                    val response = client.submitForm(
                        "/api/v1/config/probe-log-sink",
                        Parameters.build {
                            append("host", "127.0.0.1")
                            append("port", receiver.localPort.toString())
                            append("protocol", "syslog-udp")
                        },
                    )
                    assertEquals(HttpStatusCode.OK, response.status)
                    val body = JSONObject(response.bodyAsText())
                    assertEquals(true, body.getBoolean("ok"))
                    assertEquals(false, body.getBoolean("delivered"))
                    assertEquals("syslog-udp", body.getString("protocol"))
                    assertEquals(receiver.localPort, body.getInt("port"))
                    val packet = java.net.DatagramPacket(ByteArray(4096), 4096)
                    receiver.receive(packet)
                    val frame = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    org.junit.Assert.assertTrue(frame, frame.startsWith("<14>1 "))
                    org.junit.Assert.assertTrue(frame, frame.contains(" contract-panel ha-paneld - - "))
                    org.junit.Assert.assertTrue(frame, frame.endsWith(body.getString("marker")))
                }
            }
        }
    }

    @Test fun `network probe validation and body limits run behind production root guards`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.server.mount(this) }

                val badPort = client.submitForm(
                    "/api/v1/config/probe-log-sink",
                    Parameters.build { append("port", "70000") },
                )
                assertEquals(HttpStatusCode.OK, badPort.status)
                assertEquals("invalid-port", JSONObject(badPort.bodyAsText()).getString("error"))
                assertEquals("nosniff", badPort.headers["X-Content-Type-Options"])

                val blankHost = client.submitForm(
                    "/api/v1/config/probe-log-sink",
                    Parameters.build { append("host", ""); append("port", "514") },
                )
                assertEquals(HttpStatusCode.OK, blankHost.status)
                assertEquals("no-host", JSONObject(blankHost.bodyAsText()).getString("error"))

                val tooLarge = client.submitForm(
                    "/api/v1/config/probe-log-sink",
                    Parameters.build { append("host", "x".repeat(16 * 1024 + 1)) },
                )
                assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
                assertEquals("request too large\n", tooLarge.bodyAsText())

                val crossOrigin = client.submitForm(
                    "/api/v1/config/probe-log-sink", Parameters.Empty,
                ) { header(HttpHeaders.Origin, "http://elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)
                assertEquals("cross-origin refused\n", crossOrigin.bodyAsText())
            }
        }
    }
}
