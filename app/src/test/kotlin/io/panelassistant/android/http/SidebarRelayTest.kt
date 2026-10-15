package io.panelassistant.android.http

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveStream
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.ManageFamily
import io.panelassistant.android.ManageKind
import io.panelassistant.android.ManageReply
import io.panelassistant.android.ManageTable
import io.panelassistant.android.panelassistant.PanelAssistantBlobs
import io.panelassistant.android.panelassistant.PanelAssistantManagement
import io.panelassistant.android.panelassistant.PanelAssistantManagementRequests
import io.panelassistant.android.panelassistant.PanelAssistantSession
import io.panelassistant.android.panelassistant.PanelAssistantSessionEvent
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.sensors.HaApiSession
import io.panelassistant.android.sensors.HaApiSessionProvider
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sidebar relay (protocol section 20.2, Epic E slice 6): `manage` and `command` events Panel Assistant relays
 * with a browser's `origin`, parsed by the session's own parser and answered by the session's management requests,
 * against a stand-in for Panel Assistant's blob view on a real loopback socket.
 */
class SidebarRelayTest {
    private lateinit var homeAssistant: HttpServer
    private val received = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val served = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    @Before fun startHomeAssistant() {
        homeAssistant = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(PanelAssistantBlobs.PATH) { exchange ->
                val token = exchange.requestURI.path.removePrefix(PanelAssistantBlobs.PATH)
                when (exchange.requestMethod) {
                    "PUT" -> {
                        val bytes = exchange.requestBody.readBytes()
                        received[token] = bytes
                        val receipt = """{"size":${bytes.size},"sha256":"${sha256(bytes)}"}""".toByteArray()
                        exchange.sendResponseHeaders(200, receipt.size.toLong())
                        exchange.responseBody.write(receipt)
                    }
                    "GET" -> {
                        val bytes = served.remove(token)
                        if (bytes == null) exchange.sendResponseHeaders(404, -1) else {
                            exchange.sendResponseHeaders(200, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                        }
                    }
                    else -> exchange.sendResponseHeaders(405, -1)
                }
                exchange.close()
            }
            start()
        }
    }

    @After fun stop() {
        homeAssistant.stop(0)
        LocalApprovalBroker.instance.clear()
    }

    private val auth = HaApiSessionProvider { HaApiSession("http://127.0.0.1:${homeAssistant.address.port}", "panel-token") }

    /** One session's management requests, fed events as Home Assistant sends them and drained as the session loop does. */
    private inner class Session(
        door: () -> io.panelassistant.android.ManageDoor?,
        serve: suspend (String, JSONObject, () -> Unit) -> io.panelassistant.android.panelassistant.PanelAssistantManageReply? = { _, _, _ -> null },
    ) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = PanelAssistantManagementRequests(
            scope,
            PanelAssistantManagement.of({ error("no snapshot") }, { _, _ -> error("no settings") }, door, PanelAssistantBlobs(auth), serve),
            PanelAssistantSession(5, TOKEN, listOf("management", "ui_subscribe", "ui_bundle"), "1.0.0"),
            { System.nanoTime() / 1_000_000 },
        )
        val sent = CopyOnWriteArrayList<JSONObject>()
        private var id = 2L

        fun deliver(event: JSONObject) {
            val frame = JSONObject().put("id", 1).put("type", "event").put("event", event.put("session", TOKEN))
            when (val parsed = PanelAssistantTransportProtocol.sessionEvent(frame, 1L)) {
                is PanelAssistantSessionEvent.Manage -> requests.onRequest(parsed)
                is PanelAssistantSessionEvent.Command -> requests.onRelayed(parsed)
                else -> error("not a request: $parsed")
            }
        }

        fun drain() {
            while (true) sent += JSONObject(requests.next(id++) ?: return)
        }

        fun await(what: String, predicate: (JSONObject) -> Boolean): JSONObject {
            val until = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < until) {
                drain()
                sent.firstOrNull(predicate)?.let { return it }
                Thread.sleep(10)
            }
            throw AssertionError("no $what in $sent")
        }

        fun answer(commandId: String) = await("final answer to $commandId") {
            it.optString("type") == PanelAssistantTransportProtocol.COMMAND_COMMAND_RESULT &&
                it.optString("command_id") == commandId && !it.optBoolean("interim")
        }

        override fun close() {
            requests.close()
            scope.cancel()
        }
    }

    private fun manage(id: String, op: String, args: JSONObject = JSONObject(), relayed: Boolean = true) =
        JSONObject().put("kind", "manage").put("command_id", id).put("op", op).put("args", args)
            .apply { if (relayed) put("origin", JSONObject().put("user", USER).put("connection", "relay-1")) }

    @Test fun aRelayedCommandRunsAsItsUserWhileTheSameEventWithoutAnOriginIsRefused() {
        PaneldServerHttpFixture().use { fixture ->
            val origins = CopyOnWriteArrayList<CommandOrigin>()
            fixture.manageTable.register("probe.write", ManageKind.COMMAND) { request ->
                origins += request.origin
                ManageReply("applied", null, JSONObject().put("written", true))
            }
            Session({ fixture.door }) { op, args, onPending -> fixture.server.managementRequest(op, args, onPending, auth) }.use { session ->
                session.deliver(manage("w1", "probe.write"))
                session.deliver(manage("w2", "probe.write", relayed = false))

                val relayed = session.answer("w1")
                assertEquals(listOf(TOKEN, "applied"), listOf(relayed.getString("session"), relayed.getString("outcome")))
                assertTrue(relayed.getJSONObject("result").getBoolean("written"))
                val own = session.answer("w2")
                assertEquals(listOf("refused", "not_commandable"), listOf(own.getString("outcome"), own.getString("code")))
                assertEquals(listOf("user:$USER"), origins.map { it.approvalKey })
            }
        }
    }

    @Test fun aMalformedOriginNeverRunsAndNeverAsPanelAssistant() {
        val ran = AtomicInteger()
        val table = ManageTable().apply { register("probe.write", ManageKind.COMMAND) { ran.incrementAndGet(); ManageReply("applied", null) } }
        Session({ tableDoor(table) }) { _, _, _ -> ran.incrementAndGet(); null }.use { session ->
            session.deliver(manage("m1", "probe.write").put("origin", JSONObject().put("user", "x y")))
            val answer = session.answer("m1")
            assertEquals(listOf("refused", "invalid_format"), listOf(answer.getString("outcome"), answer.getString("code")))
            assertEquals(0, ran.get())
        }
    }

    @Test fun inHardenedModeARelayedExemptOperationRunsAndANonExemptOneAnswersInterimPendingApproval() = testApplication {
        val table = ManageTable()
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                route("/api/v1") {
                    ops {
                        for ((path, operation) in listOf("exempt" to SensitiveOperation.DISPLAY_CONFIGURATION, "guarded" to SensitiveOperation.BACKUP_EXPORT)) {
                            post("/probe/$path") {
                                if (!authorizeSensitiveRequest(call, true, operation, path, path, LocalApprovalBroker.instance)) return@post
                                call.respondText("""{"ok":true}""", ContentType.Application.Json)
                            }
                        }
                    }
                }
            }
        }
        startApplication()
        Session({ tableDoor(table, approvalPollMs = 20) }).use { session ->
            session.deliver(manage("e1", "probe.exempt"))
            assertEquals("applied", session.answer("e1").getString("outcome"))

            session.deliver(manage("g1", "probe.guarded"))
            val interim = session.await("interim answer") { it.optString("command_id") == "g1" }
            assertEquals(listOf("pending_approval", "true"), listOf(interim.getString("outcome"), interim.get("interim").toString()))
            val pending = LocalApprovalBroker.instance.pending().single()
            assertEquals(listOf(SensitiveOperation.BACKUP_EXPORT, "user:$USER"), listOf(pending.operation, pending.peer))
            assertTrue(LocalApprovalBroker.instance.approve(pending.id))
            assertEquals("applied", session.answer("g1").getString("outcome"))
        }
    }

    @Test fun aRelayedSubscriptionSendsUiUpdatesAndStopsOnUnsubscribeAndWithTheSession() {
        val reads = AtomicInteger()
        val table = ManageTable().apply { register("probe", ManageKind.READ) { ManageReply("applied", null, JSONObject().put("v", reads.incrementAndGet())) } }
        val door = tableDoor(table, families = mapOf("probe" to ManageFamily("probe", 50, listOf("probe"))))
        fun settled(): Int {
            val before = reads.get()
            Thread.sleep(300)
            return reads.get() - before
        }
        Session({ door }).use { session ->
            session.deliver(manage("s1", "subscribe", JSONObject().put("family", "probe")))
            assertEquals("s1", session.answer("s1").getJSONObject("result").getString("subscription"))
            val update = session.await("update") { it.optString("type") == PanelAssistantTransportProtocol.COMMAND_UI_UPDATE }
            assertEquals(listOf(TOKEN, "s1"), listOf(update.getString("session"), update.getString("subscription")))
            val observation = update.getJSONArray("observations").getJSONObject(0)
            assertEquals(listOf("probe", "known"), listOf(observation.getString("channel"), observation.getString("state")))
            assertTrue(observation.getJSONObject("value").getInt("v") >= 1)

            session.deliver(manage("u1", "unsubscribe", JSONObject().put("subscription", "s1")))
            assertEquals("applied", session.answer("u1").getString("outcome"))
            assertTrue("unsubscribed: reads stop", settled() <= 1)

            session.deliver(manage("s2", "subscribe", JSONObject().put("family", "probe")))
            session.await("second update") { it.optString("subscription") == "s2" && it.has("observations") }
            assertTrue("subscribed again: reads run", settled() > 1)
            session.close()
            assertTrue("the session ended: reads stop", settled() <= 1)
        }
    }

    @Test fun aLargeRelayedResultMovesAsABlobThroughItsTokenAndAnUploadedBodyIsFetchedAndChecked() = testApplication {
        val table = ManageTable()
        val large = "x".repeat(300 * 1024)
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                route("/api/v1") {
                    ops {
                        get("/probe/large") { call.respondText(JSONObject().put("text", large).toString(), ContentType.Application.Json) }
                        get("/probe/small") { call.respondText("""{"small":true}""", ContentType.Application.Json) }
                        post("/probe/echo") {
                            val body = call.receiveStream().use { it.readBytes() }
                            call.respondText(JSONObject().put("size", body.size).put("text", String(body)).toString(), ContentType.Application.Json)
                        }
                    }
                }
            }
        }
        startApplication()
        Session({ tableDoor(table) }).use { session ->
            session.deliver(manage("l1", "probe.large", JSONObject().put("blob_token", "relay-token-000001")))
            session.deliver(manage("l2", "probe.small", JSONObject().put("blob_token", "relay-token-000002")))

            val blob = session.answer("l1").getJSONObject("result").getJSONObject("blob")
            val uploaded = requireNotNull(received["relay-token-000001"]) { "the answer went up through its token" }
            assertEquals(large, JSONObject(String(uploaded)).getString("text"))
            assertEquals(listOf(PanelAssistantBlobs.PATH + "relay-token-000001", uploaded.size.toLong(), sha256(uploaded)), listOf(blob.getString("url"), blob.getLong("size"), blob.getString("sha256")))
            assertTrue("a small answer rides the frame", session.answer("l2").getJSONObject("result").getBoolean("small"))
            assertFalse("an unused token is left for Panel Assistant to discard", received.containsKey("relay-token-000002"))

            val body = "uploaded by the reader".toByteArray()
            fun reference(sha: String) = JSONObject().put("url", PanelAssistantBlobs.PATH + "reader-token-00001").put("size", body.size)
                .put("sha256", sha).put("type", "text/plain")
            served["reader-token-00001"] = body
            session.deliver(manage("p1", "probe.echo", JSONObject().put("blob", reference(sha256(body)))))
            assertEquals("uploaded by the reader", session.answer("p1").getJSONObject("result").getString("text"))
            served["reader-token-00001"] = body
            session.deliver(manage("p2", "probe.echo", JSONObject().put("blob", reference("0".repeat(64)))))
            assertFalse("a mismatched body is never used", session.answer("p2").getString("outcome") == "applied")
        }
    }

    @Test fun theBundleUploadsOneZipOfExactlyTheFilesTheStubServesKeyedByTheHealthBuild() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                val reply = requireNotNull(kotlinx.coroutines.runBlocking {
                    fixture.server.managementRequest(
                        PanelAssistantTransportProtocol.MANAGE_BUNDLE, JSONObject().put("build", "old").put("blob_token", "bundle-token-0001"), {}, auth,
                    )
                })
                assertEquals("applied", reply.outcome)
                val result = reply.result as JSONObject
                val health = client.get("/health").bodyAsText()
                val build = requireNotNull(Regex("build=(\\S+)").find(health)) { health }.groupValues[1]
                assertEquals("keyed by the health line's build token", build, result.getString("build"))
                val zip = requireNotNull(received["bundle-token-0001"])
                assertEquals(listOf(zip.size.toLong(), sha256(zip)), result.getJSONObject("blob").let { listOf(it.getLong("size"), it.getString("sha256")) })

                val entries = linkedMapOf<String, ByteArray>()
                ZipInputStream(zip.inputStream()).use { input ->
                    while (true) entries[(input.nextEntry ?: break).name] = input.readBytes()
                }
                // Source-text reason: the APK's bundled assets are what the bundle must carry, listed from where they ship.
                val root = java.io.File("src/main/assets")
                val expected = listOf("info.js", "info.css", "icon.svg", "favicon.svg") +
                    root.walkTopDown().filter { it.isFile }.map { "assets/" + it.relativeTo(root).invariantSeparatorsPath }
                assertEquals(expected.toSortedSet(), entries.keys.toSortedSet())
                // Each entry is the file the stub serves at that path, relative to the page base.
                for ((path, bytes) in entries) {
                    val response = client.get("/$path")
                    assertEquals(path, HttpStatusCode.OK, response.status)
                    if (path.substringAfterLast('.') in setOf("js", "css", "svg", "json", "html")) {
                        assertTrue(path, bytes.contentEquals(response.bodyAsBytes()))
                    }
                }
            }
        }
    }

    private companion object {
        const val TOKEN = "relay-session"
        const val USER = "0123456789abcdef0123456789abcdef"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
