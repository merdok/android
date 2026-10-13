package io.panelassistant.android.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.decodeURLPart
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.ManageBlobs
import io.panelassistant.android.ManageDoor
import io.panelassistant.android.ManageReply
import io.panelassistant.android.ManageSubscription
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.SelfUpdater
import io.panelassistant.android.util.isLoopbackPeer
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.LocalApprovalBroker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject

/**
 * The stub's closed endpoint list (protocol section 20.7), defined once. Paths are Ktor route templates: a
 * `{name}` segment is one path segment and a `{name...}` segment is the rest of the path. With the REST routes
 * closed (section 20.8, [io.panelassistant.android.Config.uiRoutesClosed]) every other request answers [GONE_BODY];
 * the step-1 retirements of section 20.8 answer it in every build.
 */
internal object StubEndpoints {
    /** The rendered pages and the `page.<name>` each one answers. */
    val PAGES: Map<String, String> = linkedMapOf(
        "/" to "info", "/configure" to "configure", "/setup" to "setup", "/profiles" to "profiles",
        "/install" to "install", "/logs" to "logs", "/entities" to "entities",
    )
    val HEALTH = listOf("/health", "/api/v1/health")
    val DIAG = listOf("/diag", "/api/v1/diag")
    const val UI = "/ui"
    const val BLOB = "/blob/{token}"
    /**
     * Sign-in navigations, served by their existing handlers until the setup page carries them (protocol section
     * 20.6): the panel's own WebView opens the first on loopback (DashboardRecovery.kt) and Home Assistant
     * redirects the browser to the second. Neither is a page module's REST read, so closing the routes must not
     * end sign-in.
     */
    val SIGN_IN = listOf("/api/v1/ha/oauth/panel-start", "/api/v1/ha/oauth/callback")

    val ENDPOINTS: Set<Pair<HttpMethod, String>> = buildSet {
        // 1. Bundle files, served from the APK.
        listOf("/info.js", "/info.css", "/icon.svg", "/favicon.svg", "/assets/{f...}").forEach { add(HttpMethod.Get to it) }
        // 2. The pages, 3. health, 4. diag, 5. the door, 6. blobs; and the sign-in navigations.
        (PAGES.keys + HEALTH + DIAG + UI + BLOB + SIGN_IN).forEach { add(HttpMethod.Get to it) }
        add(HttpMethod.Put to BLOB)
    }

    /** Step 1 of section 20.8: retired in every build, whatever the method. */
    val RETIRED: Set<String> = setOf(
        "/test",
        "/api/v1/proximity/capture", "/api/v1/proximity/relearn", "/api/v1/proximity/reset",
        "/api/v1/proximity/sensitivity", "/api/v1/proximity/teach", "/api/v1/proximity/test",
        "/api/v1/proximity/threshold",
        "/api/v1/config/probe-log-sink", "/api/v1/logship/status", "/api/v1/perf/costs", "/api/v1/perf/history",
        "/api/v1/power-safety/state", "/api/v1/provisioning/plan", "/api/v1/provisioning/plan.txt",
        "/api/v1/sensortrace", "/api/v1/ui/layout",
    )

    val GONE_BODY: String = JSONObject().put("error", "gone").put("docs", SelfUpdater.RELEASES_URL).toString()

    /** Whether the stub serves [method] [path]. HEAD reads as GET. */
    fun admits(method: HttpMethod, path: String): Boolean {
        val asked = if (method == HttpMethod.Head) HttpMethod.Get else method
        return ENDPOINTS.any { (m, template) -> m == asked && matches(template, path) }
    }

    /** [rawPath] as routing reads it: percent-decoded, repeated slashes collapsed, no trailing slash. */
    fun normalize(rawPath: String): String =
        "/" + runCatching { rawPath.decodeURLPart() }.getOrDefault(rawPath).split('/').filter(String::isNotEmpty).joinToString("/")

    /** Whether [rawPath] is gone: a step-1 retirement, or any path the stub does not serve once the routes close. */
    fun gone(method: HttpMethod, rawPath: String, routesClosed: Boolean): Boolean {
        val path = normalize(rawPath)
        return path in RETIRED || (routesClosed && !admits(method, path))
    }

    private fun matches(template: String, path: String): Boolean {
        val want = template.split('/').filter(String::isNotEmpty)
        val got = path.split('/').filter(String::isNotEmpty)
        want.forEachIndexed { i, segment ->
            if (segment.startsWith("{") && segment.endsWith("...}")) return got.size > i
            val actual = got.getOrNull(i) ?: return false
            if (!(segment.startsWith("{") && segment.endsWith("}")) && segment != actual) return false
        }
        return want.size == got.size
    }
}

/** The stub's 410 answers by method and path: the first [limit] distinct, then one overflow count (section 20.7). */
internal class GoneCounts(private val limit: Int = 64) {
    private val counts = LinkedHashMap<String, Long>()
    private var overflow = 0L

    @Synchronized fun count(method: HttpMethod, path: String) {
        val key = "${method.value} $path"
        when {
            key in counts -> counts[key] = counts.getValue(key) + 1
            counts.size < limit -> counts[key] = 1
            else -> overflow++
        }
    }

    /** The lines `/diag` prints. */
    @Synchronized fun text(): String = buildString {
        append("gone (410) answers: ").append(counts.values.sum() + overflow).append('\n')
        counts.forEach { (key, n) -> append("  ").append(key).append(' ').append(n).append('\n') }
        if (overflow > 0) append("  other paths ").append(overflow).append('\n')
    }
}

/**
 * One-use blob transfers on the stub (protocol section 20.5): a token lives ten minutes and is spent by its
 * first use. [put] holds a reply's bytes for one `GET /blob/<token>`; [mintUpload] answers `blob.upload` with a
 * token one `PUT /blob/<token>` fills; [take] hands those bytes to the request whose `args.blob` names them,
 * only when the reference's size and SHA-256 match.
 */
internal class StubBlobs(
    private val maxBytes: Int = MAX_BYTES,
    private val ttlMs: Long = 10 * 60_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : ManageBlobs {
    private class Slot(
        val upload: Boolean,
        val expiresAt: Long,
        @Volatile var bytes: ByteArray?,
        val type: String,
        /** For an upload: the most bytes its PUT may carry (the size `blob.upload` declared, within the cap). */
        val limit: Long = 0,
    )

    private val slots = ConcurrentHashMap<String, Slot>()
    private val random = SecureRandom()

    private fun mint(slot: Slot): String {
        purge()
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        slots[token] = slot
        // Bound what a stream of unread transfers can hold: the oldest is dropped first.
        while (slots.size > MAX_SLOTS) slots.entries.minByOrNull { it.value.expiresAt }?.let { slots.remove(it.key) }
        return token
    }

    private fun purge() {
        val now = nowMs()
        slots.entries.removeIf { it.value.expiresAt <= now }
    }

    override fun put(bytes: ByteArray, type: String): JSONObject {
        val token = mint(Slot(upload = false, expiresAt = nowMs() + ttlMs, bytes = bytes, type = type))
        return reference(token, bytes, type)
    }

    /**
     * Answer `blob.upload`: where the reader PUTs the bytes it then names in `args.blob`. A declared [size] bounds
     * that PUT; without one the cap does.
     */
    fun mintUpload(type: String, size: Long? = null): JSONObject {
        val limit = size?.takeIf { it in 0..maxBytes.toLong() } ?: maxBytes.toLong()
        val token = mint(Slot(upload = true, expiresAt = nowMs() + ttlMs, bytes = null, type = type, limit = limit))
        return JSONObject().put("url", "/blob/$token").put("max_size", limit)
    }

    /** The most bytes a PUT to [token] may carry, or null when [token] names no open upload. */
    fun uploadLimit(token: String): Long? =
        slots[token]?.takeIf { it.upload && it.bytes == null && it.expiresAt > nowMs() }?.limit

    override fun take(ref: JSONObject): ByteArray? {
        val token = ref.optString("url").substringAfterLast('/')
        val slot = slots.remove(token)?.takeIf { it.upload && it.expiresAt > nowMs() } ?: return null
        val bytes = slot.bytes ?: return null
        if (ref.optLong("size", -1) != bytes.size.toLong() || !ref.optString("sha256").equals(sha256Hex(bytes), ignoreCase = true)) {
            return null
        }
        return bytes
    }

    /** The download [token] names, spent by this read; null when there is none. */
    fun download(token: String): Pair<ByteArray, String>? {
        val slot = slots.remove(token)?.takeIf { !it.upload && it.expiresAt > nowMs() } ?: return null
        return (slot.bytes ?: return null) to slot.type
    }

    /** Fill the upload [token] once; false when there is no such open upload. */
    fun fill(token: String, bytes: ByteArray): Boolean {
        val slot = slots[token]?.takeIf { it.upload && it.bytes == null && it.expiresAt > nowMs() } ?: return false
        slot.bytes = bytes
        return true
    }

    companion object {
        /** Backups reach 64 MiB (section 20.1). */
        const val MAX_BYTES = 64 * 1024 * 1024
        private const val MAX_SLOTS = 4

        fun reference(token: String, bytes: ByteArray, type: String): JSONObject = JSONObject()
            .put("url", "/blob/$token").put("size", bytes.size).put("sha256", sha256Hex(bytes)).put("type", type)
    }
}

/**
 * The stub of protocol section 20.7, mounted beneath the production root guards: pages answered by
 * `page.<name>` through [door], the health line, the diag text, the `/ui` socket and blob transfers. Bundle
 * files stay with [assetRoutes]. It holds no operation handlers of its own.
 */
internal fun Route.stubRoutes(
    door: () -> ManageDoor?,
    blobs: StubBlobs,
    health: suspend () -> String,
    diag: suspend () -> String,
    /** Hardened mode: the door and blobs, which did not exist before, never answer a LAN peer (section 20.7). */
    hardened: () -> Boolean = { false },
) {
    StubEndpoints.PAGES.forEach { (path, name) -> get(path) { call.answerPage(door, "page.$name") } }
    StubEndpoints.HEALTH.forEach { path -> get(path) { call.respondText(health()) } }
    StubEndpoints.DIAG.forEach { path -> get(path) { call.respondText(diag(), ContentType.Text.Plain) } }
    // Checked after routing, on the routes themselves, so every spelling of a path that reaches them is covered.
    val loopbackOnly = createRouteScopedPlugin("StubLoopbackOnly") {
        onCall { call ->
            if (hardened() && !isLoopbackPeer(call.request.origin.remoteAddress)) {
                call.respondText("loopback only in hardened mode\n", status = HttpStatusCode.Forbidden)
            }
        }
    }
    route(StubEndpoints.BLOB) {
    install(loopbackOnly)
    get {
        val found = blobs.download(call.parameters["token"].orEmpty())
            ?: return@get call.respondText("no such blob\n", status = HttpStatusCode.NotFound)
        call.respondBytes(found.first, ContentType.parse(found.second))
    }
    put {
        val token = call.parameters["token"].orEmpty()
        // The token first, then the declared length, so nothing is read for a request that cannot be accepted.
        val limit = blobs.uploadLimit(token)
            ?: return@put call.respondText("no such upload\n", status = HttpStatusCode.NotFound)
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > limit) return@put call.respondText("too large\n", status = HttpStatusCode.PayloadTooLarge)
        val bytes = try {
            java.io.ByteArrayOutputStream().also { out ->
                call.receiveChannel().toInputStream().use { BoundedStreams.copy(it, out, limit) }
            }.toByteArray()
        } catch (_: ByteLimitExceeded) {
            return@put call.respondText("too large\n", status = HttpStatusCode.PayloadTooLarge)
        }
        when {
            !blobs.fill(token, bytes) -> call.respondText("no such upload\n", status = HttpStatusCode.NotFound)
            else -> call.respondText(StubBlobs.reference(token, bytes, "application/octet-stream").toString(), ContentType.Application.Json)
        }
    }
    }
    route(StubEndpoints.UI, HttpMethod.Get) {
        install(loopbackOnly)
        uiSocket(door, blobs, hardened)
    }
}

/**
 * A page through the dispatcher. Its `args` are the locale signals of the query (`lang`, and `ha_lang`, which the
 * Setup redirect carries), the presentation the embed header asks for, and `Accept-Language`: the query never
 * switches presentation, so `?embed=1` alone renders the ordinary page.
 */
private suspend fun ApplicationCall.answerPage(door: () -> ManageDoor?, op: String) {
    val dispatcher = door() ?: return respondText("starting\n", status = HttpStatusCode.ServiceUnavailable)
    val args = JSONObject()
    PAGE_QUERY_ARGS.forEach { name -> request.queryParameters[name]?.let { args.put(name, it) } }
    embedMode()?.let { embed ->
        args.put("embed", true)
        embed.lang?.let { if (!args.has("lang")) args.put("lang", it) }
        embed.theme?.let { args.put("theme", it) }
        if (embed.hiddenTabs.isNotEmpty()) args.put("hide", JSONArray(embed.hiddenTabs.sorted()))
    }
    request.headers[HttpHeaders.AcceptLanguage]?.let { args.put(ACCEPT_LANGUAGE_ARG, it) }
    val reply = suspendCancellableCoroutine { waiting ->
        dispatcher.manage(UUID.randomUUID().toString(), op, args, CommandOrigin.peer(request.origin.remoteAddress)) {
            if (waiting.isActive) waiting.resume(it)
        }
    }
    reply.headers.forEach { (name, value) -> response.headers.append(name, value) }
    val type = reply.contentType?.let(ContentType::parse) ?: ContentType.Text.Plain
    respondText(reply.result as? String ?: reply.result?.toString().orEmpty(), type, HttpStatusCode.fromValue(reply.status))
}

/** The query parameters a page reads: its locale signals. */
private val PAGE_QUERY_ARGS = listOf("lang", "ha_lang")

/** The `args` key a page request carries the browser's `Accept-Language` in. */
internal const val ACCEPT_LANGUAGE_ARG = "accept_language"

/**
 * `GET /ui`: the stub's door (protocol section 20.2). One text frame per request object; the origin is the
 * peer's address whatever the frame says; replies, interim `pending_approval` answers (marked `interim`) and
 * subscription updates are one text frame each. A `pending_approval` without `interim` is final, as an HTTP
 * 202 approval answer is. Closing the socket ends every subscription its origin holds; turning hardened mode on
 * closes a LAN peer's socket.
 */
private fun Route.uiSocket(door: () -> ManageDoor?, blobs: StubBlobs, hardened: () -> Boolean) {
    application.pluginOrNull(WebSockets) ?: application.install(WebSockets) { maxFrameSize = MAX_FRAME_BYTES }
    webSocket {
        // A cross-site page opening the socket is the WebSocket form of the cross-site write OriginGuard refuses.
        if (!OriginGuard.allowed(HttpMethod.Post.value, call.request.headers[HttpHeaders.Origin], null, call.request.headers[HttpHeaders.Host])) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "cross-origin refused"))
        }
        val dispatcher = door() ?: return@webSocket close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "starting"))
        val peerAddress = call.request.origin.remoteAddress
        // Approvals bind to the peer; subscriptions belong to this socket alone.
        val origin = CommandOrigin.peer(peerAddress).withConnection(UUID.randomUUID().toString())
        // Hardened mode keeps the door to loopback: a LAN socket open when it turns on is closed at once.
        if (!isLoopbackPeer(peerAddress)) launch {
            while (isActive) {
                if (hardened()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "loopback only in hardened mode"))
                    break
                }
                delay(HARDENED_CHECK_MS)
            }
        }
        val subscriptions = ConcurrentHashMap<String, ManageSubscription>()
        val open = java.util.concurrent.atomic.AtomicBoolean(true)
        // One sender, so frames leave in the order they were queued.
        val queued = Channel<Pair<JSONObject, () -> Unit>>(Channel.UNLIMITED)
        launch {
            for ((frame, after) in queued) {
                try {
                    outgoing.send(Frame.Text(frame.toString()))
                    after()
                } catch (_: ClosedSendChannelException) {
                }
            }
        }
        fun send(frame: JSONObject, after: () -> Unit = {}) {
            queued.trySend(frame to after)
        }
        fun answer(commandId: String, reply: ManageReply) = send(
            JSONObject().put("command_id", commandId).put("outcome", reply.outcome)
                .apply { reply.code?.let { put("code", it) } }
                .apply { if (reply.result != null) put("result", reply.result) }
                .apply { replyHeaders(reply)?.let { put("headers", it) } },
        )
        fun answer(commandId: String, outcome: String, code: String? = null, result: Any? = null) =
            answer(commandId, ManageReply(outcome, code, result))
        fun interim(commandId: String, approvalId: String? = null) = send(
            JSONObject().put("command_id", commandId).put("outcome", PanelAssistantTransportProtocol.OUTCOME_PENDING_APPROVAL)
                .put("interim", true).apply { approvalId?.let { put("result", JSONObject().put("approval_id", it)) } },
        )
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val request = runCatching { JSONObject(frame.readText()) }.getOrNull()
                val commandId = request?.optString("command_id").orEmpty()
                if (request == null || commandId.isEmpty()) {
                    answer(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, PanelAssistantTransportProtocol.CODE_INVALID_FORMAT)
                    continue
                }
                val args = request.optJSONObject("args") ?: JSONObject()
                when (request.optString("kind")) {
                    "command" -> {
                        val deadlineMs = request.optLong("deadline_ms", DEFAULT_DEADLINE_MS)
                        val value = request.opt("value")
                        val channel = request.optString("channel")
                        val payload = if (value is String) value else value?.toString().orEmpty()
                        // A sensitive command is held for its approval on the panel's screen: the door answers
                        // an interim pending_approval, waits, runs it again once approved and answers the
                        // outcome, as Panel Assistant's processor does on the session.
                        fun run(held: Boolean) {
                            val deadlineAt = System.nanoTime() / 1_000_000 + deadlineMs
                            dispatcher.submit(
                                PanelAssistantCommand(
                                    channel = channel,
                                    payload = payload,
                                    requestId = commandId,
                                    admit = {
                                        when {
                                            !open.get() -> PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED)
                                            System.nanoTime() / 1_000_000 > deadlineAt ->
                                                PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_EXPIRED)
                                            else -> null
                                        }
                                    },
                                ),
                                origin,
                            ) { result ->
                                if (result !is PanelAssistantCommandResult.ApprovalPending) return@submit answer(commandId, commandReply(result))
                                // Asked again after an approval was consumed: one interim answer per command.
                                if (held) return@submit answer(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, PanelAssistantCommandProcessor.CODE_APPROVAL_TIMEOUT)
                                interim(commandId, result.approvalId)
                                launch {
                                    val heldAt = System.nanoTime()
                                    while (true) {
                                        delay(APPROVAL_POLL_MS)
                                        when (LocalApprovalBroker.instance.state(result.approvalId)) {
                                            ApprovalBroker.State.PENDING -> continue
                                            ApprovalBroker.State.APPROVED -> { run(held = true); break }
                                            ApprovalBroker.State.ABSENT -> {
                                                val expired = (System.nanoTime() - heldAt) / 1_000_000 >= ApprovalBroker.DEFAULT_TTL_MS
                                                answer(
                                                    commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED,
                                                    if (expired) PanelAssistantCommandProcessor.CODE_APPROVAL_TIMEOUT else PanelAssistantCommandProcessor.CODE_APPROVAL_DENIED,
                                                )
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        run(held = false)
                    }
                    "manage" -> when (val op = request.optString("op")) {
                        "subscribe" -> {
                            val family = args.optString("family")
                            // Updates queue behind the `applied` answer that names their subscription.
                            val answered = CompletableDeferred<Unit>()
                            val subscription = dispatcher.subscribe(family, origin) { value, delivered ->
                                val update = JSONObject().put("subscription", commandId).put(
                                    "observations",
                                    JSONArray().put(JSONObject().put("channel", family).put("state", "known").put("value", value ?: JSONObject.NULL)),
                                )
                                launch { answered.await(); send(update, after = delivered) }
                            }
                            if (subscription == null) {
                                answer(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, PanelAssistantCommandProcessor.CODE_INVALID_VALUE)
                            } else {
                                subscriptions[commandId] = subscription
                                answer(commandId, PanelAssistantTransportProtocol.OUTCOME_APPLIED, result = JSONObject().put("subscription", commandId))
                                answered.complete(Unit)
                            }
                        }
                        "unsubscribe" -> {
                            subscriptions.remove(args.optString("subscription"))?.close()
                            answer(commandId, PanelAssistantTransportProtocol.OUTCOME_APPLIED)
                        }
                        // Section 20.5: the door answers this itself, without reaching the panel's operations.
                        "blob.upload" -> answer(
                            commandId, PanelAssistantTransportProtocol.OUTCOME_APPLIED,
                            result = blobs.mintUpload(
                                args.optString("type").ifEmpty { ContentType.Application.OctetStream.toString() },
                                args.optLong("size", -1).takeIf { it >= 0 },
                            ),
                        )
                        else -> dispatcher.manage(
                            commandId, op, args, origin, blobs,
                            onPending = { interim(commandId) },
                        ) { reply -> answer(commandId, reply) }
                    }
                    else -> answer(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, PanelAssistantTransportProtocol.CODE_INVALID_FORMAT)
                }
            }
        } finally {
            open.set(false)
            queued.close()
            dispatcher.closeOrigin(origin)
        }
    }
}

/**
 * The `x-*` headers a route set (screenshot id, input route, profile presentation), lower-cased as the page
 * transport reads them over HTTP; null when there are none.
 */
private fun replyHeaders(reply: ManageReply): JSONObject? {
    val named = reply.headers.filter { (name, _) ->
        val lower = name.lowercase()
        lower.startsWith("x-") && lower != "x-frame-options" && lower != "x-content-type-options"
    }
    if (named.isEmpty()) return null
    return JSONObject().apply { named.forEach { (name, value) -> put(name.lowercase(), value) } }
}

private fun commandReply(result: PanelAssistantCommandResult): ManageReply = when (result) {
    PanelAssistantCommandResult.Applied -> ManageReply(PanelAssistantTransportProtocol.OUTCOME_APPLIED, null)
    PanelAssistantCommandResult.Superseded -> ManageReply(PanelAssistantTransportProtocol.OUTCOME_SUPERSEDED, null)
    is PanelAssistantCommandResult.ApprovalPending -> ManageReply(
        PanelAssistantTransportProtocol.OUTCOME_PENDING_APPROVAL, null, JSONObject().put("approval_id", result.approvalId),
    )
    is PanelAssistantCommandResult.Refused -> ManageReply(PanelAssistantTransportProtocol.OUTCOME_REFUSED, result.code)
    is PanelAssistantCommandResult.Failed -> ManageReply(PanelAssistantTransportProtocol.OUTCOME_FAILED, result.code)
}

private const val HARDENED_CHECK_MS = 500L
private const val APPROVAL_POLL_MS = 1_000L

/** Frames into the panel are capped at 2 MiB (section 20.1); larger bodies move as blobs. */
private const val MAX_FRAME_BYTES = 2L * 1024 * 1024
private const val DEFAULT_DEADLINE_MS = 10_000L
