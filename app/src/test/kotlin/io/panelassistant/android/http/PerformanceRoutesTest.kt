package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceRoutesTest {
    @Test fun `performance reads preserve admission binding errors and root guards`() = testApplication {
        var admitted = true
        var bindingCalls = 0
        var perfCalls = 0
        application {
            paneldRoot(
                allowedHosts = { emptySet() },
                setupNeedsUser = { false },
                setupRedirectLocation = { "/setup" },
            ) {
                route("/api/v1") {
                    performanceRoutes(
                        admit = { call ->
                            if (!admitted) call.respondText("active denied\n", status = HttpStatusCode.Forbidden)
                            admitted
                        },
                        perf = { perfCalls++; "{\"sample\":1}" },
                        binding = { bindingCalls++; if (bindingCalls == 1) null else "{\"bound\":true}" },
                    )
                }
            }
        }

        val perf = client.get("/api/v1/perf")
        assertEquals(HttpStatusCode.OK, perf.status)
        assertEquals(ContentType.Application.Json, perf.contentType())
        assertEquals("{\"sample\":1}", perf.bodyAsText())
        assertEquals(1, perfCalls)

        val invalid = client.get("/api/v1/perf/binding?comparison_id=wrong")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("{\"error\":\"invalid comparison_id\"}", invalid.bodyAsText())
        assertEquals(0, bindingCalls)

        val validId = "0123456789abcdef0123456789abcdef"
        val unavailable = client.get("/api/v1/perf/binding?comparison_id=$validId")
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertEquals("{\"error\":\"stable device identity unavailable\"}", unavailable.bodyAsText())
        val bound = client.get("/api/v1/perf/binding?comparison_id=$validId")
        assertEquals(HttpStatusCode.OK, bound.status)
        assertEquals("{\"bound\":true}", bound.bodyAsText())

        admitted = false
        val refused = client.get("/api/v1/perf")
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("active denied\n", refused.bodyAsText())
        assertEquals(1, perfCalls)
        val badHost = client.get("/api/v1/perf") { header(HttpHeaders.Host, "elsewhere.example") }
        assertEquals(HttpStatusCode.Forbidden, badHost.status)
        assertEquals("host not allowed\n", badHost.bodyAsText())
    }
}
