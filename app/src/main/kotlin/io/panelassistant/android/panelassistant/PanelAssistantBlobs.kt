package io.panelassistant.android.panelassistant

import io.panelassistant.android.sensors.HaApiSessionProvider
import io.panelassistant.android.util.BoundedStreams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The panel's side of a one-use blob (protocol section 20.5): bytes too large for a session frame move
 * through Panel Assistant's blob view, and the panel always opens that connection, to its own Home
 * Assistant, with its own credential.
 *
 * A reference names only a path under [PATH]; it is resolved against the URL the panel's session reaches
 * Home Assistant at, so a request can never point the panel anywhere else. Plain `http` is allowed: the
 * size and SHA-256 come over the authenticated session and are checked on every byte, and whatever the
 * bytes are for checks them again (an APK's signer and protocol range).
 */
internal class PanelAssistantBlobs(
    private val auth: HaApiSessionProvider,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    data class Reference(val path: String, val size: Long, val sha256: String, val type: String) {
        fun json(): JSONObject = JSONObject()
            .put("url", path).put("size", size).put("sha256", sha256).put("type", type)
    }

    enum class Fetched { WRITTEN, MISMATCH, UNAVAILABLE }

    /** GET [reference] into [destination]; anything but exactly its size and SHA-256 is refused. */
    suspend fun download(reference: Reference, destination: File): Fetched = withContext(Dispatchers.IO) {
        val connection = runCatching { open(reference.path, "GET") }.getOrNull() ?: return@withContext Fetched.UNAVAILABLE
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext Fetched.UNAVAILABLE
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > reference.size) return@withContext Fetched.MISMATCH
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total == reference.size && hex(digest.digest()) == reference.sha256) Fetched.WRITTEN else Fetched.MISMATCH
        } catch (_: IOException) {
            Fetched.UNAVAILABLE
        } finally {
            connection.disconnect()
        }
    }

    /**
     * PUT [file] to the upload slot [token] names; the reference to it once Panel Assistant confirms it
     * received exactly these bytes, else null.
     */
    suspend fun upload(token: String, file: File, type: String): Reference? = upload(token, file.length(), type, file::inputStream)

    /** [upload] for bytes already in memory (a relayed request's answer). */
    suspend fun upload(token: String, bytes: ByteArray, type: String): Reference? =
        upload(token, bytes.size.toLong(), type) { bytes.inputStream() }

    private suspend fun upload(token: String, size: Long, type: String, body: () -> java.io.InputStream): Reference? = withContext(Dispatchers.IO) {
        if (!TOKEN.matches(token)) return@withContext null
        val path = PATH + token
        val connection = runCatching { open(path, "PUT") }.getOrNull() ?: return@withContext null
        try {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(size)
            connection.setRequestProperty("Content-Type", type)
            val digest = MessageDigest.getInstance("SHA-256")
            connection.outputStream.use { output ->
                body().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val sha256 = hex(digest.digest())
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val received = runCatching {
                JSONObject(connection.inputStream.use { String(BoundedStreams.readBytes(it, RECEIPT_BYTES), Charsets.UTF_8) })
            }.getOrNull() ?: return@withContext null
            if (received.optLong("size", -1) != size || received.optString("sha256") != sha256) return@withContext null
            Reference(path, size, sha256, type)
        } catch (_: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection? {
        val session = auth.resolve(false)
        val token = session.accessToken ?: return null
        return openConnection(URL(session.baseUrl.trim().trimEnd('/') + path)).apply {
            // The bearer token must never follow a redirect off the panel's own Home Assistant.
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    companion object {
        const val PATH = "/api/panel_assistant/blob/"
        private val TOKEN = Regex("^[A-Za-z0-9_-]{16,128}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private const val BUFFER_BYTES = 64 * 1024
        private const val RECEIPT_BYTES = 4096L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000

        fun validToken(raw: Any?): String? = (raw as? String)?.takeIf(TOKEN::matches)

        /** A reference as the request carries it, or null when any part of it is malformed or out of bounds. */
        fun reference(raw: JSONObject?, maxBytes: Long): Reference? {
            if (raw == null) return null
            val path = raw.opt("url") as? String ?: return null
            if (!path.startsWith(PATH) || validToken(path.removePrefix(PATH)) == null) return null
            val size = when (val value = raw.opt("size")) {
                is Int -> value.toLong()
                is Long -> value
                else -> return null
            }
            if (size !in 1..maxBytes) return null
            val sha256 = (raw.opt("sha256") as? String)?.takeIf(SHA256::matches) ?: return null
            return Reference(path, size, sha256, raw.opt("type") as? String ?: "application/octet-stream")
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * A relayed request's bodies (protocol section 20.5): an answer's bytes go up once through the request's own
 * upload [token], and a body the reader uploaded is fetched from the reference Panel Assistant minted and checked
 * before use. The download is written to a file in [dir] (the default temporary directory when null) and deleted
 * once read.
 */
internal class PanelAssistantRelayBlobs(
    private val blobs: PanelAssistantBlobs,
    private val token: String?,
    private val dir: File? = null,
    private val maxBytes: Long = io.panelassistant.android.http.StubBlobs.MAX_BYTES,
) : io.panelassistant.android.ManageBlobs {
    private val spent = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Whether the upload token is still unused, so a large answer can still move as a blob. */
    val canUpload: Boolean get() = token != null && !spent.get()

    override suspend fun put(bytes: ByteArray, type: String): JSONObject {
        val upload = token?.takeIf { spent.compareAndSet(false, true) } ?: throw IOException("no blob token for this answer")
        return (blobs.upload(upload, bytes, type) ?: throw IOException("blob upload failed")).json()
    }

    override suspend fun take(ref: JSONObject): java.io.InputStream? {
        val reference = PanelAssistantBlobs.reference(ref, maxBytes) ?: return null
        dir?.mkdirs()
        val file = File.createTempFile("relay", ".blob", dir)
        if (blobs.download(reference, file) != PanelAssistantBlobs.Fetched.WRITTEN) {
            file.delete()
            return null
        }
        return object : java.io.FilterInputStream(file.inputStream()) {
            override fun close() {
                try { super.close() } finally { file.delete() }
            }
        }
    }
}
