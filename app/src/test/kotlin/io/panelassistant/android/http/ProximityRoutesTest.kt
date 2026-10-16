package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class ProximityRoutesTest {
    @Test fun `the socket's own page may run proximity calibration`() = testApplication {
        val table = io.panelassistant.android.ManageTable()
        var received = ""
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                route("/api/v1") {
                    proximityRoutes(hasProximity = { true }, proximityJson = { "{\"present\":true}" }, calibrate = { action, id -> received = "$action/$id"; true })
                }
                socketDoorOnto(table)
            }
        }

        val reply = socketRequest("proximity.calibration", org.json.JSONObject().put("action", "start").put("sessionId", "session-3"))

        assertEquals("applied", reply.getString("outcome"))
        assertEquals("start/session-3", received)
    }

    @Test fun `the sidebar relay's page may run proximity calibration`() = testApplication {
        val table = io.panelassistant.android.ManageTable()
        var received = ""
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                route("/api/v1") {
                    proximityRoutes(hasProximity = { true }, proximityJson = { "{\"present\":true}" }, calibrate = { action, id -> received = "$action/$id"; true })
                }
            }
        }
        startApplication()
        val answers = java.util.concurrent.LinkedBlockingQueue<io.panelassistant.android.ManageReply>()
        // The relay's origin: the Home Assistant user Panel Assistant checked, on its relay connection.
        io.panelassistant.android.ManageOperations(table, { io.panelassistant.android.security.LocalApprovalBroker.instance }).manage(
            "p1", "proximity.calibration", org.json.JSONObject().put("action", "start").put("sessionId", "session-4"),
            io.panelassistant.android.CommandOrigin.user("0123456789abcdef0123456789abcdef").withConnection("relay-1"),
        ) { answers.put(it) }

        assertEquals("applied", answers.poll(10, java.util.concurrent.TimeUnit.SECONDS)?.outcome)
        assertEquals("start/session-4", received)
    }

    @Test fun `proximity calibration keeps UI origin form and source guards`() = testApplication {
        var present = false
        var accepted = false
        var received = ""
        application {
            paneldRoot(
                allowedHosts = { emptySet() },
                setupNeedsUser = { false },
                setupRedirectLocation = { "/setup" },
            ) {
                route("/api/v1") {
                    proximityRoutes(
                        hasProximity = { present },
                        proximityJson = { "{\"present\":$present}" },
                        calibrate = { action, id -> received = "$action/$id"; accepted },
                    )
                }
            }
        }

        assertEquals("{\"present\":false}", client.get("/api/v1/proximity").bodyAsText())
        val unmarked = client.post("/api/v1/proximity/calibration")
        assertEquals(HttpStatusCode.Forbidden, unmarked.status)
        assertEquals("{\"error\":\"Start proximity setup from this panel's HTML UI.\"}", unmarked.bodyAsText())

        suspend fun submit(action: String) = client.submitForm(
            "/api/v1/proximity/calibration",
            Parameters.build { append("action", action); append("sessionId", "session-1") },
        ) {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            header("X-Proximity-UI", "1")
        }

        val invalid = submit("unknown")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("{\"error\":\"Unsupported calibration action.\"}", invalid.bodyAsText())
        val absent = submit("start")
        assertEquals(HttpStatusCode.Conflict, absent.status)
        assertEquals("{\"error\":\"proximity_source_required\"}", absent.bodyAsText())
        assertEquals("", received)

        present = true
        val rejected = submit("heartbeat")
        assertEquals(HttpStatusCode.Conflict, rejected.status)
        assertEquals("heartbeat/session-1", received)
        assertEquals("no-store", rejected.headers[HttpHeaders.CacheControl])
        assertEquals("{\"present\":true}", rejected.bodyAsText())
        accepted = true
        val started = submit("start")
        assertEquals(HttpStatusCode.Accepted, started.status)
        assertEquals("start/session-1", received)
    }
}
