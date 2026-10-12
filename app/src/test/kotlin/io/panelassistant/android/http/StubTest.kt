package io.panelassistant.android.http

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.response.respondText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.Config
import io.panelassistant.android.ManageFamily
import io.panelassistant.android.ManageKind
import io.panelassistant.android.ManageReply
import io.panelassistant.android.ManageTable
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.PanelAssistantHelloOutcome
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.testsupport.PanelRuntimeRig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The `:8888` stub of protocol section 20.7 through the production root and its real dispatcher: the closed
 * list and its 410s (section 20.9 check 6), the gate of section 20.8, the `/ui` door's origins, blobs, and
 * hardened mode's loopback-only listener (check 3).
 */
internal class StubTest : PanelRuntimeRig() {
    private val rigs = mutableListOf<Rig>()

    @After fun tearDown() {
        rigs.forEach { it.close() }
        LocalApprovalBroker.instance.clear()
    }

    private fun managedRig(table: ManageTable, families: Map<String, ManageFamily> = emptyMap(), configure: (Config) -> Unit = {}): Rig =
        rig(manageTable = table, manageFamilies = families, approvalPollMs = 20, configure = configure).also { it.start(); rigs += it }

    @Test fun withTheRoutesClosedOnlyTheClosedListServesAndEveryOtherPathIsCountedGone() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.values[Config.UI_ROUTES_CLOSED_PREF] = true
            fixture.useWarmDiagnostics("Panel diagnostics\n", stopping = true)
            testApplication {
                var root: RoutingNode? = null
                application { fixture.mount(this); root = plugin(RoutingRoot) }
                startApplication()
                val gone = client.get("/api/v1/radio")
                assertEquals(HttpStatusCode.Gone, gone.status)
                assertEquals("gone", JSONObject(gone.bodyAsText()).getString("error"))
                val diag = client.get("/diag").bodyAsText()
                assertTrue(diag, diag.contains("GET /api/v1/radio 1\n"))

                val registered = registeredRoutes(requireNotNull(root))
                assertTrue("every closed endpoint is mounted", registered.containsAll(StubEndpoints.ENDPOINTS))

                val serving = registered.filter { (method, template) -> probe(method, template) != HttpStatusCode.Gone }.toSet()

                assertEquals(StubEndpoints.ENDPOINTS, serving)
            }
        }
    }

    @Test fun withTheRoutesOpenRestAnswersAsBeforeAndOnlyTheStepOneRetirementsAreGone() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.useWarmDiagnostics("Panel diagnostics\n", stopping = true)
            testApplication {
                application { fixture.mount(this) }
                val radio = client.get("/api/v1/radio")
                assertEquals(HttpStatusCode.OK, radio.status)
                assertEquals("{\"present\":false,\"status\":\"none\"}", radio.bodyAsText())
                for (path in listOf("/test", "/api/v1/sensortrace", "/api/v1/proximity/teach")) {
                    assertEquals(path, HttpStatusCode.Gone, client.get(path).status)
                }
                assertTrue(client.get("/api/v1/diag").bodyAsText().contains("GET /api/v1/sensortrace 1\n"))
            }
        }
    }

    @Test fun theGatePersistsAcrossARestartAndALaterHelloWithoutBothGrantsReopensIt() {
        val closing = hello(PanelAssistantTransportProtocol.CAPABILITY_UI_SUBSCRIBE, PanelAssistantTransportProtocol.CAPABILITY_UI_BUNDLE, "management")
        val persisted = PaneldServerHttpFixture().use { first ->
            assertTrue(first.config.recordPanelAssistantGrant(closing.capabilities))
            first.values.toMap()
        }
        PaneldServerHttpFixture().use { restarted ->
            restarted.values.putAll(persisted)
            testApplication {
                application { restarted.mount(this) }
                assertEquals(HttpStatusCode.Gone, client.get("/api/v1/radio").status)

                assertTrue(restarted.config.recordPanelAssistantGrant(hello(PanelAssistantTransportProtocol.CAPABILITY_UI_SUBSCRIBE).capabilities))

                assertEquals(HttpStatusCode.OK, client.get("/api/v1/radio").status)
            }
        }
    }

    @Test fun theSameRequestThroughTheSocketAndThroughPanelAssistantReachesTheOneEntryWithItsOwnOrigin() = testApplication {
        val table = ManageTable()
        val origins = CopyOnWriteArrayList<CommandOrigin>()
        table.register("probe.origin", ManageKind.READ) { request ->
            origins += request.origin
            ManageReply("applied", null, JSONObject().put("ok", true))
        }
        val rig = managedRig(table)
        stub { rig.commands }

        val reply = socket { exchange(JSONObject().put("kind", "manage").put("command_id", "r1").put("op", "probe.origin").put("origin", JSONObject().put("user", "x"))) }
        val session = LinkedBlockingQueue<ManageReply>()
        rig.commands.manage("r1", "probe.origin", JSONObject(), CommandOrigin.panelAssistant()) { session.put(it) }

        assertEquals("applied", reply.getString("outcome"))
        assertEquals("applied", session.poll(10, TimeUnit.SECONDS)?.outcome)
        assertEquals(2, origins.size)
        assertTrue("the socket's origin is its peer, not the frame's: ${origins[0].approvalKey}", origins[0].approvalKey.startsWith("peer:"))
        assertEquals(CommandOrigin.PANEL_ASSISTANT, origins[1].approvalKey)
    }

    @Test fun aSocketSubscriptionSendsObservationFramesAndEndsWithTheSocket() = testApplication {
        val table = ManageTable()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        table.register("probe", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("v", reads.incrementAndGet())) }
        val rig = managedRig(table, families = mapOf("probe" to ManageFamily("probe", 100, listOf("probe"))))
        stub { rig.commands }

        val update = socket {
            val applied = exchange(JSONObject().put("kind", "manage").put("command_id", "s1").put("op", "subscribe").put("args", JSONObject().put("family", "probe")))
            assertEquals("s1", applied.getJSONObject("result").getString("subscription"))
            JSONObject((incoming.receive() as Frame.Text).readText())
        }

        assertEquals("s1", update.getString("subscription"))
        val observation = update.getJSONArray("observations").getJSONObject(0)
        assertEquals("probe", observation.getString("channel"))
        assertEquals("known", observation.getString("state"))
        assertEquals(1, observation.getJSONObject("value").getInt("v"))
        val afterClose = reads.get()
        Thread.sleep(400)
        assertTrue("the closed socket's subscription stopped reading", reads.get() <= afterClose + 1)
    }

    @Test fun anUploadedBlobIsTakenOnceAndOnlyWithItsOwnHash() = testApplication {
        val table = ManageTable()
        table.register("probe.upload", ManageKind.COMMAND) { request ->
            val bytes = request.blobs?.take(request.args.getJSONObject("blob"))
            if (bytes == null) ManageReply.refused("invalid_value") else ManageReply("applied", null, JSONObject().put("size", bytes.size))
        }
        val rig = managedRig(table)
        stub { rig.commands }
        val payload = ByteArray(300_000) { (it % 251).toByte() }

        val (first, again, mismatched) = socket {
            suspend fun upload(): String {
                val url = exchange(JSONObject().put("kind", "manage").put("command_id", "u${System.nanoTime()}").put("op", "blob.upload"))
                    .getJSONObject("result").getString("url")
                val put = client.put(url) { setBody(payload) }
                assertEquals(HttpStatusCode.OK, put.status)
                return url
            }
            suspend fun use(id: String, url: String, sha256: String) = exchange(
                JSONObject().put("kind", "manage").put("command_id", id).put("op", "probe.upload").put(
                    "args", JSONObject().put("blob", JSONObject().put("url", url).put("size", payload.size).put("sha256", sha256)),
                ),
            )
            val url = upload()
            val first = use("c1", url, sha256(payload))
            val again = use("c2", url, sha256(payload))
            val mismatched = use("c3", upload(), sha256(payload + 1))
            Triple(first, again, mismatched)
        }

        assertEquals("applied", first.getString("outcome"))
        assertEquals(payload.size, first.getJSONObject("result").getInt("size"))
        assertEquals("refused", again.getString("outcome"))
        assertEquals("refused", mismatched.getString("outcome"))
    }

    @Test fun aBlobAReplyCarriesIsReadOnce() = testApplication {
        val table = ManageTable()
        val bytes = ByteArray(1024) { it.toByte() }
        table.register("probe.download", ManageKind.READ) { request ->
            ManageReply("applied", null, JSONObject().put("blob", requireNotNull(request.blobs).put(bytes, "application/octet-stream")))
        }
        val rig = managedRig(table)
        stub { rig.commands }

        val reference = socket { exchange(JSONObject().put("kind", "manage").put("command_id", "d1").put("op", "probe.download")) }
            .getJSONObject("result").getJSONObject("blob")

        assertEquals(sha256(bytes), reference.getString("sha256"))
        val read = client.get(reference.getString("url"))
        assertEquals(HttpStatusCode.OK, read.status)
        assertArrayEquals(bytes, read.bodyAsBytes())
        assertEquals(HttpStatusCode.NotFound, client.get(reference.getString("url")).status)
    }

    @Test fun aCrossSitePageCannotOpenTheSocket() = testApplication {
        val rig = managedRig(ManageTable())
        stub { rig.commands }
        val ws = createClient { install(ClientWebSockets) }

        var answered: Frame? = null
        runCatching {
            ws.webSocket("/ui", request = { headers.append("Origin", "http://elsewhere.example") }) {
                send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", "x").put("op", "health").toString()))
                answered = incoming.receive()
            }
        }

        assertTrue("a cross-site upgrade gets no answer: $answered", answered !is Frame.Text)
    }

    @Test fun inHardenedModeAPanelAssistantSensitiveCommandWaitsForApprovalWhileALoopbackPeerIsExempt() {
        val rig = managedRig(ManageTable()) { it.setSecurityMode(Config.SecurityMode.HARDENED) }

        assertTrue(submit(rig, "reboot", CommandOrigin.panelAssistant()) is PanelAssistantCommandResult.ApprovalPending)
        assertTrue(submit(rig, "reboot", CommandOrigin.peer("192.0.2.7")) is PanelAssistantCommandResult.ApprovalPending)
        assertEquals(PanelAssistantCommandResult.Applied, submit(rig, "reboot", CommandOrigin.peer("127.0.0.1")))
    }

    @Test fun inHardenedModeTheLanKeepsRestUntilTheRoutesCloseButNeverReachesTheDoorOrBlobs() {
        val lan = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLinkLocalAddress && io.panelassistant.android.util.isLocalSource(it.hostAddress) }
        assumeTrue("needs a private-range LAN IPv4 address", lan != null)
        val lanHost = lan!!.hostAddress
        val table = ManageTable()
        table.register("probe.origin", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("ok", true)) }
        val rig = managedRig(table) { it.setSecurityMode(Config.SecurityMode.HARDENED) }
        val port = ServerSocket(0).use { it.localPort }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = PaneldListener(scope, { port }, { listenerOffLan(rig.config) }) {
            paneldRoot({ emptySet() }, { false }, { "/setup" }, uiRoutesClosed = { rig.config.uiRoutesClosed }, hardened = { rig.config.hardenedSecurityEnabled }) {
                stubRoutes({ rig.commands }, StubBlobs(), health = { "health\n" }, diag = { "diag\n" })
                route("/api/v1") { discoveryRoutes({ """{"peers":[]}""" }, { """{"apps":[]}""" }) }
            }
        }
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) { install(ClientWebSockets); expectSuccess = false }
        try {
            listener.start(closeIngress = {})
            kotlinx.coroutines.runBlocking {
                // Routes open: the LAN keeps REST, health and diag as today.
                val peers = http.get("http://$lanHost:$port/api/v1/peers")
                assertEquals(peers.bodyAsText(), HttpStatusCode.OK, peers.status)
                assertEquals(HttpStatusCode.OK, http.get("http://$lanHost:$port/health").status)
                assertEquals(HttpStatusCode.OK, http.get("http://$lanHost:$port/diag").status)
                // ...but never the door or blobs, which loopback still reaches.
                assertEquals(HttpStatusCode.Forbidden, http.get("http://$lanHost:$port/blob/x").status)
                assertEquals(HttpStatusCode.NotFound, http.get("http://127.0.0.1:$port/blob/x").status)
                // An admitted socket would answer this frame at once; a refused upgrade never opens.
                val lanDoor = runCatching {
                    withTimeout(10_000) {
                        http.webSocket("ws://$lanHost:$port/ui") {
                            exchange(JSONObject().put("kind", "manage").put("command_id", "lan").put("op", "probe.origin"))
                        }
                    }
                }
                assertTrue("a LAN peer cannot open the door in hardened mode", lanDoor.isFailure)
                var reply: JSONObject? = null
                http.webSocket("ws://127.0.0.1:$port/ui") {
                    reply = exchange(JSONObject().put("kind", "manage").put("command_id", "h1").put("op", "probe.origin"))
                }
                assertEquals("applied", reply?.getString("outcome"))
            }
            assertTrue("routes open: the LAN connects", connects(lan, port))

            assertTrue(rig.config.recordPanelAssistantGrant(listOf(
                PanelAssistantTransportProtocol.CAPABILITY_UI_SUBSCRIBE, PanelAssistantTransportProtocol.CAPABILITY_UI_BUNDLE,
            )))
            assertTrue(listener.rebind())

            assertFalse("routes closed in hardened mode: no LAN connection", connects(lan, port))
            kotlinx.coroutines.runBlocking {
                assertEquals(HttpStatusCode.OK, http.get("http://127.0.0.1:$port/health").status)
            }
        } finally {
            http.close()
            listener.stop()
            scope.cancel()
        }
    }

    // ---- helpers ----

    private fun ApplicationTestBuilder.stub(door: () -> io.panelassistant.android.ManageDoor?) {
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                stubRoutes(door, StubBlobs(), health = { "health\n" }, diag = { "diag\n" })
            }
        }
    }

    private suspend fun <T> ApplicationTestBuilder.socket(block: suspend DefaultClientWebSocketSession.() -> T): T {
        val ws = createClient { install(ClientWebSockets) }
        var result: Result<T>? = null
        ws.webSocket("/ui") { result = runCatching { block() } }
        return result!!.getOrThrow()
    }

    private suspend fun DefaultClientWebSocketSession.exchange(request: JSONObject): JSONObject {
        send(Frame.Text(request.toString()))
        return withTimeout(10_000) {
            while (true) {
                val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                if (frame.optString("command_id") == request.getString("command_id") && frame.optString("outcome") != "pending_approval") {
                    return@withTimeout frame
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private fun registeredRoutes(root: RoutingNode): Set<Pair<HttpMethod, String>> {
        return root.getAllRoutes().mapNotNull { leaf ->
            val method = generateSequence(leaf) { it.parent }.firstNotNullOfOrNull { (it.selector as? HttpMethodRouteSelector)?.method }
                ?: return@mapNotNull null
            val segments = generateSequence(leaf) { it.parent }.toList().asReversed().mapNotNull { node ->
                when (val selector = node.selector) {
                    is PathSegmentConstantRouteSelector -> selector.value
                    is PathSegmentParameterRouteSelector -> "{${selector.name}}"
                    is PathSegmentTailcardRouteSelector -> "{${selector.name}...}"
                    else -> null
                }
            }
            method to "/" + segments.joinToString("/")
        }.toSet()
    }

    private suspend fun ApplicationTestBuilder.probe(method: HttpMethod, template: String): HttpStatusCode {
        val path = template.replace(Regex("\\{[^}]*\\}"), "x")
        return client.request(path) { this.method = method }.status
    }

    private fun hello(vararg capabilities: String) = (PanelAssistantTransportProtocol.helloOutcome(
        JSONObject().put("id", 1).put("type", "result").put("success", true).put(
            "result",
            JSONObject().put("protocol", 5).put("session", "opaque").put("capabilities", JSONArray(capabilities.toList()))
                .put("integration", JSONObject().put("version", "1.0.0")),
        ),
        1L,
        offered = PanelAssistantTransportProtocol.CAPABILITIES +
            PanelAssistantTransportProtocol.CAPABILITY_UI_SUBSCRIBE + PanelAssistantTransportProtocol.CAPABILITY_UI_BUNDLE,
    ) as PanelAssistantHelloOutcome.Accepted).session

    private fun submit(rig: Rig, channel: String, origin: CommandOrigin): PanelAssistantCommandResult {
        val answer = LinkedBlockingQueue<PanelAssistantCommandResult>()
        rig.commands.submit(PanelAssistantCommand(channel, "PRESS", admit = { null }), origin) { answer.put(it) }
        return requireNotNull(answer.poll(20, TimeUnit.SECONDS)) { "$channel answered" }
    }

    private fun connects(address: InetAddress, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(address, port), 2_000) }
    }.isSuccess

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
