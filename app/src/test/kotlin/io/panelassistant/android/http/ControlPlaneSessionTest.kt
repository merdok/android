package io.panelassistant.android.http

import com.sun.net.httpserver.HttpServer
import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.panelassistant.PanelAssistantBlobs
import io.panelassistant.android.panelassistant.PanelAssistantManageReply
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.sensors.HaApiSession
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.InstallProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * The update and backup routes Panel Assistant asks for over its session (Epic E slice 5), driven through
 * the session entry the transport calls, against a stand-in for Panel Assistant's blob view on a real
 * loopback socket.
 */
class ControlPlaneSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var homeAssistant: HttpServer
    private val served = mutableMapOf<String, ByteArray>()
    private val received = mutableMapOf<String, ByteArray>()
    private val requests = mutableListOf<String>()
    private var receiptOverride: String? = null

    @Before fun startHomeAssistant() {
        homeAssistant = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(PanelAssistantBlobs.PATH) { exchange ->
                val token = exchange.requestURI.path.removePrefix(PanelAssistantBlobs.PATH)
                synchronized(requests) { requests += "${exchange.requestMethod} $token ${exchange.requestHeaders.getFirst("Authorization")}" }
                if (exchange.requestHeaders.getFirst("Authorization") != "Bearer panel-token") {
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                    return@createContext
                }
                when (exchange.requestMethod) {
                    "GET" -> {
                        val bytes = served.remove(token)
                        if (bytes == null) exchange.sendResponseHeaders(404, -1)
                        else {
                            exchange.sendResponseHeaders(200, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                        }
                    }
                    "PUT" -> {
                        val bytes = exchange.requestBody.readBytes()
                        received[token] = bytes
                        val receipt = (receiptOverride ?: """{"size":${bytes.size},"sha256":"${sha256(bytes)}"}""").toByteArray()
                        exchange.sendResponseHeaders(200, receipt.size.toLong())
                        exchange.responseBody.write(receipt)
                    }
                    else -> exchange.sendResponseHeaders(405, -1)
                }
                exchange.close()
            }
            start()
        }
    }

    @After fun stopHomeAssistant() = homeAssistant.stop(0)

    private val blobs by lazy {
        PanelAssistantBlobs({ HaApiSession("http://127.0.0.1:${homeAssistant.address.port}", "panel-token") })
    }

    private val installed = mutableListOf<ByteArray>()
    private val pending = PendingUploadStore { "upload-token" }.also { it.open() }
    private var built: PanelBackup.Artifact? = null
    private val backupRequests = mutableListOf<Pair<CompanionBackupRequest, String>>()

    private fun routes() = ControlPlaneRouteDependencies(
        playAudio = { true },
        installComponent = { _, _, _ -> true },
        installedComponentVersion = { null },
        buildBackup = { request, passphrase ->
            backupRequests += request to passphrase
            PanelBackup.Artifact(temporary.newFile("panel-backup.zip").apply { writeBytes(BACKUP) }, "zip").also { built = it }
        },
        backupFileStem = { "test-panel" },
        apkUpload = ApkUploadRouteDependencies(
            enabled = { true },
            rootAvailable = { false },
            confirmedSelfUpdate = { true },
            ownPackage = "own.panel",
            pending = pending,
            createStagingFile = { temporary.newFile() },
            inspect = { staged -> UploadedApkIdentity("own.panel", String(staged.readBytes()), null) },
            startInstall = { claimed, progress ->
                installed += claimed.file.readBytes()
                InstallProgress.finish(progress, "installed")
            },
            maxBytes = 64,
            receiveBody = { input, output, maxBytes -> BoundedStreams.copy(input, output, maxBytes) },
        ),
    )

    private fun request(
        op: String,
        args: JSONObject,
        authorize: suspend (SensitiveOperation, String, String) -> String? = { _, _, _ -> null },
    ): PanelAssistantManageReply? = runBlocking { ControlPlaneSessionOps.request(op, args, routes(), blobs, authorize) }

    private fun offer(bytes: ByteArray, token: String = "download-token-0001", sha: String = sha256(bytes), size: Long = bytes.size.toLong()) =
        JSONObject().put(
            "blob",
            JSONObject().put("url", PanelAssistantBlobs.PATH + token).put("size", size).put("sha256", sha)
                .put("type", "application/vnd.android.package-archive"),
        ).also { served[token] = bytes }

    @Test fun anUpdatePulledFromHomeAssistantStagesAndCommitsLikeAnUpload() {
        val apk = "1.0.1".toByteArray()
        val staged = request("install.apk", offer(apk))!!
        assertEquals("applied", staged.outcome)
        // The body the HTTP upload route answers, so Panel Assistant checks the same package, version and signer.
        assertEquals(
            JSONObject("""{"ok":true,"token":"upload-token","package":"own.panel","version":"1.0.1","signer":"unsigned"}""").fields(),
            staged.result!!.fields(),
        )
        assertEquals(listOf("GET download-token-0001 Bearer panel-token"), requests)

        val committed = request("install.apk.commit", JSONObject().put("token", "upload-token"))!!
        assertEquals("applied" to mapOf<String, Any?>("status" to "started"), committed.outcome to committed.result!!.fields())
        assertArrayEquals(apk, installed.single())
    }

    @Test fun bytesThatAreNotTheAnnouncedOnesAreRefusedAndStageNothing() {
        val apk = "1.0.1".toByteArray()
        val tampered = request("install.apk", offer(apk, sha = sha256("other".toByteArray())))!!
        assertEquals(listOf("refused", "invalid_value", "blob-mismatch"), listOf(tampered.outcome, tampered.code, tampered.result?.optString("error")))
        val longer = request("install.apk", offer(apk, token = "download-token-0002", size = 3))!!
        assertEquals("blob-mismatch", longer.result?.optString("error"))
        assertNull(pending.pendingSummary())

        // The single staging slot was released: an honest pull still stages.
        assertEquals("applied", request("install.apk", offer(apk, token = "download-token-0003"))!!.outcome)
    }

    @Test fun aReferenceOutsideThePanelsOwnBlobViewIsRefusedBeforeAnyConnection() {
        for (url in listOf("/api/other/download-token-0001", "http://elsewhere.example/api/panel_assistant/blob/download-token-0001", PanelAssistantBlobs.PATH + "short")) {
            val reply = request(
                "install.apk",
                JSONObject().put("blob", JSONObject().put("url", url).put("size", 5).put("sha256", sha256("1.0.1".toByteArray()))),
            )!!
            assertEquals(listOf("refused", "invalid-request"), listOf(reply.outcome, reply.result?.optString("error")))
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun aBackupIsBuiltAsTheHttpOneAndUploadedWithItsReference() {
        val reply = request(
            "backup",
            JSONObject().put("allow_plaintext", "1").put("include_companion", "false").put("blob_token", "upload-token-00001"),
        )!!
        assertEquals("applied", reply.outcome)
        val blob = reply.result!!.getJSONObject("blob")
        assertEquals(
            listOf(PanelAssistantBlobs.PATH + "upload-token-00001", BACKUP.size.toLong(), sha256(BACKUP)),
            listOf(blob.getString("url"), blob.getLong("size"), blob.getString("sha256")),
        )
        assertArrayEquals(BACKUP, received.getValue("upload-token-00001"))
        assertEquals(listOf(CompanionBackupRequest.EXCLUDED to ""), backupRequests)
        // The archive was released once uploaded, and the operation lane is free again.
        assertFalse(built!!.file.exists())
        InstallProgress.start("probe")!!.also { InstallProgress.finish(it, "released") }
    }

    @Test fun aBackupHomeAssistantDidNotReceiveIntactIsNotReportedAsLanded() {
        receiptOverride = """{"size":1,"sha256":"${sha256(byteArrayOf(9))}"}"""
        val reply = request(
            "backup",
            JSONObject().put("allow_plaintext", "1").put("include_companion", "false").put("blob_token", "upload-token-00001"),
        )!!
        assertEquals(listOf("refused", "blob-unavailable"), listOf(reply.outcome, reply.result?.optString("error")))
        assertFalse(built!!.file.exists())
    }

    @Test fun aBackupKeepsTheHttpRequestRulesAndItsApproval() {
        val unacknowledged = request("backup", JSONObject().put("include_companion", "false").put("blob_token", "upload-token-00001"))!!
        assertEquals("passphrase-required", unacknowledged.result?.optString("error"))
        val asked = mutableListOf<SensitiveOperation>()
        val denied = request(
            "backup",
            JSONObject().put("allow_plaintext", "1").put("include_companion", "false").put("blob_token", "upload-token-00001"),
        ) { operation, _, _ -> asked += operation; "approval_denied" }!!
        assertEquals(listOf("refused", "approval_denied"), listOf(denied.outcome, denied.code))
        assertEquals(listOf(SensitiveOperation.BACKUP_EXPORT), asked)
        assertTrue(backupRequests.isEmpty())
        assertTrue(received.isEmpty())
    }

    @Test fun aCommitAsksForTheSameApprovalAsTheHttpCommit() {
        request("install.apk", offer("1.0.1".toByteArray()))
        val asked = mutableListOf<Pair<SensitiveOperation, String>>()
        val refused = request("install.apk.commit", JSONObject().put("token", "upload-token")) { operation, _, summary ->
            asked += operation to summary
            "approval_denied"
        }!!
        assertEquals("approval_denied", refused.code)
        assertEquals(SensitiveOperation.APK_INSTALL, asked.single().first)
        assertTrue(installed.isEmpty())
    }

    @Test fun aNameThisBuildDoesNotServeIsLeftToTheUnknownCommandAnswer() {
        assertNull(request("install.component", JSONObject()))
    }

    @Test fun hardenedSessionRequestsWaitForTheirPhysicalApproval() = runTest {
        val broker = ApprovalBroker(monotonicMs = { testScheduler.currentTime })
        val pendingAnswers = mutableListOf<Unit>()
        // Relaxed mode admits a session request as it admits a LAN one: nothing waits on the panel.
        assertNull(authorizeSessionRequest(false, SensitiveOperation.APK_INSTALL, "p", "s", broker, onPending = { pendingAnswers += Unit }))
        assertEquals(0, pendingAnswers.size)
        assertTrue(broker.pending().isEmpty())

        val approved = async { authorizeSessionRequest(true, SensitiveOperation.APK_INSTALL, "p", "s", broker, onPending = { pendingAnswers += Unit }) }
        runCurrent()
        assertEquals(1, pendingAnswers.size)
        val record = broker.pending().single()
        assertEquals(PANEL_ASSISTANT_APPROVAL_PEER, record.peer)
        broker.approve(record.id)
        advanceTimeBy(1_500)
        assertNull(approved.await())
        assertTrue(broker.pending().isEmpty())

        val denied = async { authorizeSessionRequest(true, SensitiveOperation.BACKUP_EXPORT, "q", "s", broker, onPending = {}) }
        runCurrent()
        broker.deny(broker.pending().single().id)
        advanceTimeBy(1_500)
        assertEquals("approval_denied", denied.await())

        // A wait the session abandons leaves nothing to approve on the panel.
        val abandoned = async { authorizeSessionRequest(true, SensitiveOperation.BACKUP_EXPORT, "r", "s", broker, onPending = {}) }
        runCurrent()
        abandoned.cancel()
        runCurrent()
        assertTrue(broker.pending().isEmpty())
    }

    private companion object {
        val BACKUP = "PK-backup-archive".toByteArray()

        fun JSONObject.fields(): Map<String, Any?> = keys().asSequence().associateWith { opt(it) }

        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
