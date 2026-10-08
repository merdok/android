package io.panelassistant.android.util

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdminEndpointTest {
    @Test fun externalUrlUsesConfiguredPortAndPreferredIpv4() {
        assertEquals(
            "http://192.0.2.8:9123/configure",
            LocalAdminEndpoint.externalUrl("192.0.2.8", "2001:db8::8", 9123, "/configure"),
        )
    }

    @Test fun externalUrlBracketsIpv6Fallback() {
        assertEquals(
            "http://[2001:db8::8]:9123/",
            LocalAdminEndpoint.externalUrl(null, "2001:db8::8", 9123),
        )
    }

    @Test fun loopbackUrlNormalizesPath() {
        assertEquals("http://127.0.0.1:8888/health", LocalAdminEndpoint.loopbackUrl(8888, "health"))
    }

    @Test fun readinessStopsAtFirstSuccess() = runTest {
        var probes = 0
        val pauses = mutableListOf<Long>()
        val ready = LocalAdminReadiness(attempts = 5, retryDelayMs = 20).await(
            probe = { ++probes == 3 },
            pause = { pauses += it },
        )
        assertTrue(ready)
        assertEquals(3, probes)
        assertEquals(listOf(20L, 20L), pauses)
    }

    @Test fun readinessIsBoundedWhenProbeFailsOrThrows() = runTest {
        var probes = 0
        var pauses = 0
        val ready = LocalAdminReadiness(attempts = 4, retryDelayMs = 0).await(
            probe = {
                probes++
                if (probes % 2 == 0) throw IllegalStateException("not ready")
                false
            },
            pause = { pauses++ },
        )
        assertFalse(ready)
        assertEquals(4, probes)
        assertEquals(3, pauses)
    }

    @Test fun healthReadinessRejectsForeignOrMalformedSuccessResponses() {
        assertTrue(LocalAdminReadiness.isExpectedHealthResponse(200, "ha-paneld 0.9.3 panel=test\n"))
        assertFalse(LocalAdminReadiness.isExpectedHealthResponse(200, "another local service\n"))
        assertFalse(LocalAdminReadiness.isExpectedHealthResponse(503, "ha-paneld unavailable\n"))
    }

    /** A screen offers an address only when that page answers with itself, not a redirect or nothing. */
    @Test
    fun aPageAnswersOnlyWithItsOwnOk() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/configure") { exchange ->
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "/configure")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val port = server.address.port
            assertTrue(LocalAdminReadiness.pageAnswers(LocalAdminEndpoint.url("127.0.0.1", port, "/configure#cfg-ha-oauth")))
            assertFalse("a redirect is not the page", LocalAdminReadiness.pageAnswers(LocalAdminEndpoint.url("127.0.0.1", port, "/moved")))
            assertFalse("a missing page is not the page", LocalAdminReadiness.pageAnswers(LocalAdminEndpoint.url("127.0.0.1", port, "/nothing")))
        } finally {
            server.stop(0)
        }
        val closed = java.net.ServerSocket(0).use { it.localPort }
        assertFalse("nothing listening", LocalAdminReadiness.pageAnswers(LocalAdminEndpoint.url("127.0.0.1", closed, "/configure"), timeoutMs = 300))
    }
}
