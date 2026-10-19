package io.panelassistant.android.http

import io.panelassistant.android.*
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.util.InstallProgress
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import io.panelassistant.android.panelassistant.*
import io.panelassistant.android.sensors.HaApiSession
import io.panelassistant.android.sensors.HaApiSessionProvider
import java.util.concurrent.atomic.AtomicInteger

class RegisteredInstallLifetimeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun closedRelayWithdrawsTheRegisteredInstallApproval() = probe(close = true)
    @Test fun liveRelayStartsRegisteredCommitOnceAsPositiveControl() = probe(close = false)

    @Test fun aLiveConnectionKeepsItsIdenticalSharedApprovalWhenAnotherConnectionEnds() = probe(close = true, shared = true)

    @Test fun endingTheManagementSessionWithdrawsARegisteredCommit() = probe(close = true, sessionClose = true)

    private fun probe(close: Boolean, shared: Boolean = false, sessionClose: Boolean = false) = testApplication {
        val table = ManageTable()
        val broker = ApprovalBroker(monotonicMs = { System.nanoTime() / 1_000_000 })
        val effects = AtomicInteger()
        val pending = PendingUploadStore(newToken = { "stage-token" }).apply { open() }
        val lease = (pending.begin() as PendingUploadStore.BeginResult.Granted).lease
        val file = temporary.newFile("staged.apk").apply { writeText("probe bytes, never installed") }
        assertNotNull(pending.stage(lease, file, UploadedApkIdentity("example.panel", "1.2.3", null)))
        val routes = ControlPlaneRouteDependencies(
            playAudio = { true }, installComponent = { _, _, _ -> false },
            installedComponentVersion = { null }, buildBackup = { _, _ -> error("unused") },
            backupFileStem = { "unused" },
            apkUpload = ApkUploadRouteDependencies(
                enabled = { true }, rootAvailable = { true }, pending = pending,
                createStagingFile = { error("already staged") }, inspect = { error("already inspected") },
                startInstall = { entry, ticket ->
                    assertEquals("stage-token", entry.token)
                    effects.incrementAndGet()
                    InstallProgress.finish(ticket, "probe effect, no installation")
                },
            ),
            authorize = { call, operation, payload, summary ->
                authorizeSensitiveRequest(call, true, operation, payload, summary, broker)
            },
        )
        application { routing { serveOperations(table); controlPlaneRoutes(routes) } }
        startApplication()
        assertNotNull(table["install.apk.commit"])
        val operations = ManageOperations(table, { broker }, approvalPollMs = 5)
        val door = object : ManageDoor {
            override fun manage(commandId: String, op: String, args: JSONObject, origin: CommandOrigin, blobs: ManageBlobs?, onPending: () -> Unit, done: (ManageReply) -> Unit) =
                operations.manage(commandId, op, args, origin, blobs, onPending, done)
            override fun submit(command: PanelAssistantCommand, origin: CommandOrigin, done: (PanelAssistantCommandResult) -> Unit) = error("not a channel command")
            override fun subscribe(family: String, origin: CommandOrigin, args: JSONObject, sink: ManageSink) = operations.subscribe(family, origin, args, sink)
            override fun closeOrigin(origin: CommandOrigin) = operations.closeOrigin(origin)
            override fun onStopped(action: () -> Unit) = operations.onStopped(action)
        }
        val relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val sent = LinkedBlockingQueue<JSONObject>()
        val frames = UiFrames(door, CommandOrigin.user("administrator").withConnection("relay-1"), relayScope) { frame, delivered ->
            sent.put(frame); delivered()
        }
        val otherSent = LinkedBlockingQueue<JSONObject>()
        val other = UiFrames(door, CommandOrigin.user("administrator").withConnection("relay-2"), relayScope) { frame, delivered ->
            otherSent.put(frame); delivered()
        }
        val requests = PanelAssistantManagementRequests(
            relayScope,
            PanelAssistantManagement.of({ error("unused") }, { _, _ -> error("unused") }, { door },
                PanelAssistantBlobs(HaApiSessionProvider { HaApiSession("http://127.0.0.1", "test-token") })),
            PanelAssistantSession(5, "test-session", listOf("management", "ui_subscribe", "ui_bundle"), "1.0.0"),
            { System.nanoTime() / 1_000_000 },
        )
        fun sessionAnswer(): JSONObject? {
            val until = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < until) {
                requests.next(2L)?.let { return JSONObject(it) }
                Thread.sleep(5)
            }
            return null
        }
        try {
            val request = JSONObject().put("kind", "manage").put("command_id", "commit-1")
                .put("op", "install.apk.commit").put("args", JSONObject().put("token", "stage-token"))
            if (sessionClose) {
                request.put("session", "test-session").put("origin", JSONObject().put("user", "administrator").put("connection", "relay-1"))
                val frame = JSONObject().put("id", 1).put("type", "event").put("event", request)
                requests.onRequest(PanelAssistantTransportProtocol.sessionEvent(frame, 1L) as PanelAssistantSessionEvent.Manage)
            } else frames.handle(request, null)
            assertEquals("pending_approval", (if (sessionClose) sessionAnswer() else sent.poll(5, TimeUnit.SECONDS))?.optString("outcome"))
            assertEquals(0, effects.get())
            val approval = broker.pending().single()
            if (shared) {
                other.handle(JSONObject().put("kind", "manage").put("command_id", "commit-2")
                    .put("op", "install.apk.commit").put("args", JSONObject().put("token", "stage-token")), null)
                assertEquals("pending_approval", otherSent.poll(5, TimeUnit.SECONDS)?.optString("outcome"))
                assertEquals(1, broker.pending().size)
            }
            if (close) { if (sessionClose) requests.close() else frames.close() }
            if (shared) {
                assertTrue(broker.approve(approval.id))
                assertEquals("applied", otherSent.poll(5, TimeUnit.SECONDS)?.optString("outcome"))
                assertEquals(1, effects.get())
            } else if (close) {
                assertFalse(broker.approve(approval.id))
                assertNotNull(pending.peek("stage-token"))
                assertEquals(0, effects.get())
            } else {
                assertTrue(broker.approve(approval.id))
                assertEquals("applied", sent.poll(5, TimeUnit.SECONDS)?.optString("outcome"))
                assertEquals(1, effects.get())
                assertNull(pending.peek("stage-token"))
            }
        } finally { requests.close(); frames.close(); other.close(); relayScope.cancel(); operations.stop(); pending.close() }
    }
}
