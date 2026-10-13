package io.panelassistant.android.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.ManageBlobs
import io.panelassistant.android.ManageDoor
import io.panelassistant.android.ManageOperations
import io.panelassistant.android.ManageReply
import io.panelassistant.android.ManageSink
import io.panelassistant.android.ManageTable
import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.util.InstallProgress
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * APK upload and commit, and the backup download, as named operations (protocol section 20.6): the production
 * control-plane routes under the production root, reached over HTTP, over the `/ui` socket with bodies as blobs,
 * and through the managed door with the caller's own origin, with the production sensitive-request decision.
 */
class ControlPlaneDoorTest {
    @get:Rule val temporary = TemporaryFolder()

    @After fun clearApprovals() = LocalApprovalBroker.instance.clear()

    private val table = ManageTable()
    private val operations = ManageOperations(table, { LocalApprovalBroker.instance })
    private val door = object : ManageDoor {
        override fun manage(
            commandId: String, op: String, args: JSONObject, origin: CommandOrigin,
            blobs: ManageBlobs?, onPending: () -> Unit, done: (ManageReply) -> Unit,
        ) = operations.manage(commandId, op, args, origin, blobs, onPending, done)
        override fun submit(command: PanelAssistantCommand, origin: CommandOrigin, done: (PanelAssistantCommandResult) -> Unit) =
            done(PanelAssistantCommandResult.Failed("failed"))
        override fun subscribe(family: String, origin: CommandOrigin, args: JSONObject, sink: ManageSink) = operations.subscribe(family, origin, args, sink)
        override fun closeOrigin(origin: CommandOrigin) = operations.closeOrigin(origin)
    }
    private val pending = PendingUploadStore { "apk-token" }.apply { open() }
    private val installed = LinkedBlockingQueue<ByteArray>()
    private val inspections = AtomicInteger()
    private var hardened = false

    private fun upload(maxBytes: Long) = ApkUploadRouteDependencies(
        enabled = { true },
        rootAvailable = { true },
        pending = pending,
        createStagingFile = { File.createTempFile("upload-", ".apk", temporary.root) },
        inspect = {
            inspections.incrementAndGet()
            UploadedApkIdentity("com.example.panel", "2.4.1", "abcdef0123456789")
        },
        startInstall = { entry, progress ->
            installed.put(entry.file.readBytes())
            InstallProgress.finish(progress, "installed")
        },
        maxBytes = maxBytes,
    )

    private fun ApplicationTestBuilder.mount(peer: String? = null, maxBytes: Long = 1024) {
        val routes = ControlPlaneRouteDependencies(
            playAudio = { true },
            installComponent = { _, _, _ -> true },
            installedComponentVersion = { null },
            buildBackup = { _, passphrase ->
                PanelBackup.Artifact(temporary.newFile("built-${System.nanoTime()}.hpb").apply {
                    writeBytes(PanelBackup.seal(BACKUP_PLAINTEXT, passphrase))
                })
            },
            backupFileStem = { "test-panel" },
            apkUpload = upload(maxBytes),
            // What the server's own authorizeSensitive decides, with this test's security mode.
            authorize = { call, operation, payload, summary ->
                authorizeSensitiveRequest(call, hardened, operation, payload, summary, LocalApprovalBroker.instance)
            },
        )
        application {
            if (peer != null) intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
                context.mutableOriginConnectionPoint.remoteAddress = peer
            }
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                serveOperations(table)
                stubRoutes({ door }, StubBlobs(), health = { "health\n" }, diag = { "diag\n" }, hardened = { hardened })
                controlPlaneRoutes(routes)
            }
        }
    }

    @Test fun anApkUploadedAsABlobOverTheSocketIsStagedAndCommittedWithItsOwnBytes() = testApplication {
        mount()
        val apk = "the uploaded apk".toByteArray()

        val (staged, committed) = socket {
            val staged = manage("s1", "install.apk", JSONObject().put("blob", upload(client, apk)))
            staged to manage("s2", "install.apk.commit", JSONObject().put("token", resultOf(staged).optString("token")))
        }

        assertEquals(staged.toString(), "applied", staged.optString("outcome"))
        assertEquals("apk-token", resultOf(staged).optString("token"))
        assertEquals(committed.toString(), "applied", committed.optString("outcome"))
        assertEquals("started", resultOf(committed).optString("status"))
        val bytes = installed.poll(10, TimeUnit.SECONDS)
        assertNotNull("the committed entry was installed", bytes)
        assertArrayEquals(apk, bytes)
    }

    @Test fun anApkBlobLargerThanTheUploadLimitIsRefusedOverTheSocketBeforeInspection() = testApplication {
        mount(maxBytes = 4)

        val reply = socket { manage("l1", "install.apk", JSONObject().put("blob", upload(client, ByteArray(10)))) }

        assertEquals(reply.toString(), "refused", reply.optString("outcome"))
        assertEquals("upload-too-large", resultOf(reply).optString("error"))
        assertEquals(0, inspections.get())
        assertNull(pending.pendingSummary())
        assertFalse(InstallProgress.running)
    }

    @Test fun aCommitOverTheManagedDoorFromALanPeerInHardenedModeWaitsForApprovalAndInstallsNothing() = testApplication {
        hardened = true
        mount()
        startApplication()
        stage()

        val answer = LinkedBlockingQueue<ManageReply>()
        door.manage("c1", "install.apk.commit", JSONObject().put("token", "apk-token"), CommandOrigin.peer("192.0.2.9"), null, {}) {
            answer.put(it)
        }
        val reply = answer.poll(20, TimeUnit.SECONDS)

        assertNotNull("the commit answered", reply)
        assertEquals("pending_approval", reply!!.outcome)
        assertEquals("approval-required", (reply.result as? JSONObject)?.optString("error"))
        assertNull("nothing was installed", installed.poll(200, TimeUnit.MILLISECONDS))
        assertNotNull("the upload is still pending", pending.peek("apk-token"))
        assertFalse(InstallProgress.running)
    }

    @Test fun aCommitOverHttpFromALanPeerInHardenedModeAnswersTheApprovalChallengeAndInstallsNothing() = testApplication {
        hardened = true
        mount(peer = "192.168.50.20")
        stage()

        val response = client.post("/api/v1/install/apk/commit") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("token=apk-token")
        }

        val text = response.bodyAsText()
        assertEquals(text, HttpStatusCode.Accepted, response.status)
        assertEquals("approval-required", JSONObject(text).optString("error"))
        assertNull("nothing was installed", installed.poll(200, TimeUnit.MILLISECONDS))
        assertNotNull("the upload is still pending", pending.peek("apk-token"))
        assertFalse(InstallProgress.running)
    }

    @Test fun aBackupOverTheSocketAnswersAOneUseBlobHoldingTheSealedArchive() = testApplication {
        mount()

        val reply = socket { manage("k1", "backup", JSONObject().put("passphrase", "correct horse").put("include_companion", "0")) }

        assertEquals(reply.toString(), "applied", reply.optString("outcome"))
        val blob = resultOf(reply).optJSONObject("blob")
        assertNotNull(reply.toString(), blob)
        val read = client.get(blob!!.optString("url"))
        assertEquals(HttpStatusCode.OK, read.status)
        val bytes = read.bodyAsBytes()
        assertEquals(blob.optLong("size"), bytes.size.toLong())
        assertEquals(blob.optString("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        assertArrayEquals("the blob is the sealed archive", BACKUP_PLAINTEXT, PanelBackup.open(bytes, "correct horse"))
        assertEquals("a reply blob is read once", HttpStatusCode.NotFound, client.get(blob.optString("url")).status)
        assertFalse(InstallProgress.running)
    }

    private fun stage() {
        val lease = (pending.begin() as PendingUploadStore.BeginResult.Granted).lease
        val file = temporary.newFile("inspected.apk").apply { writeText("apk") }
        assertNotNull(pending.stage(lease, file, UploadedApkIdentity("com.example.panel", "2.4.1", "abcdef0123456789")))
    }

    private suspend fun <T> ApplicationTestBuilder.socket(block: suspend DefaultClientWebSocketSession.() -> T): T {
        var result: Result<T>? = null
        createClient { install(ClientWebSockets) }.webSocket("/ui") { result = runCatching { block() } }
        return result!!.getOrThrow()
    }

    private suspend fun DefaultClientWebSocketSession.manage(id: String, op: String, args: JSONObject): JSONObject {
        send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", id).put("op", op).put("args", args).toString()))
        return withTimeout(20_000) {
            while (true) {
                val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                if (frame.optString("command_id") == id && !frame.optBoolean("interim")) return@withTimeout frame
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    /** `blob.upload`, then its PUT: the reference a request names in `args.blob`. */
    private suspend fun DefaultClientWebSocketSession.upload(http: HttpClient, bytes: ByteArray): JSONObject {
        val url = manage("u${System.nanoTime()}", "blob.upload", JSONObject()).getJSONObject("result").getString("url")
        val put = http.put(url) { setBody(bytes) }
        assertEquals(HttpStatusCode.OK, put.status)
        return JSONObject(put.bodyAsText())
    }

    private fun resultOf(reply: JSONObject): JSONObject {
        assertTrue(reply.toString(), reply.opt("result") is JSONObject)
        return reply.getJSONObject("result")
    }

    private companion object {
        val BACKUP_PLAINTEXT = """{"kind":"ha-paneld-backup","schema":15,"config":{}}""".toByteArray()
    }
}
