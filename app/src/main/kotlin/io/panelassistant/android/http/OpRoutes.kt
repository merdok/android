package io.panelassistant.android.http

import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.ManageKind
import io.panelassistant.android.ManageReply
import io.panelassistant.android.ManageRequest
import io.panelassistant.android.ManageTable
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * The request a route handler serves, whichever door carried it. Over HTTP every member forwards to the
 * Ktor call, so the route answers exactly as before; through [io.panelassistant.android.PanelCommandDispatcher.manage]
 * the query, path parameters and body all come from the request's `args`, and the first response becomes
 * the [ManageReply] (protocol section 20.3).
 */
internal abstract class OpCall {
    abstract val origin: CommandOrigin
    abstract val request: OpCallRequest
    abstract val response: OpCallResponse
    /** Path parameters. */
    abstract val parameters: Parameters
    /** The HTTP call underneath, or null when another door carries the request. */
    abstract val http: ApplicationCall?
    /** The `args` of a managed request, or null over HTTP. */
    abstract val args: JSONObject?
    abstract fun embedMode(): EmbedMode?
    abstract suspend fun respondText(text: String, contentType: ContentType? = null, status: HttpStatusCode? = null)
    abstract suspend fun respondBytes(bytes: ByteArray, contentType: ContentType? = null, status: HttpStatusCode? = null)
    abstract suspend fun respondRedirect(url: String, permanent: Boolean = false)
    abstract suspend fun respondOutputStream(
        contentType: ContentType? = null,
        status: HttpStatusCode? = null,
        producer: suspend OutputStream.() -> Unit,
    )
    abstract suspend fun receiveStream(): InputStream
    abstract suspend fun respondTextWriter(
        contentType: ContentType? = null,
        status: HttpStatusCode? = null,
        writer: suspend java.io.Writer.() -> Unit,
    )
}

internal class OpCallRequest(
    val queryParameters: Parameters,
    val headers: Headers,
    val origin: OpCallOrigin,
    val httpMethod: HttpMethod,
    val uri: String,
)

internal class OpCallOrigin(val remoteAddress: String, val method: HttpMethod)

internal class OpCallResponse(private val append: (String, String) -> Unit) {
    val headers = Headers()

    inner class Headers {
        fun append(name: String, value: String) = append.invoke(name, value)
    }
}

/** The receiver of an operation's handler body; [call] is what the body reads and answers. */
internal class OpContext(val call: OpCall)

/** The HTTP door: every member forwards to [call]. */
internal class HttpOpCall(private val call: ApplicationCall) : OpCall() {
    override val origin: CommandOrigin by lazy { CommandOrigin.peer(call.request.origin.remoteAddress) }
    override val request: OpCallRequest by lazy {
        OpCallRequest(
            call.request.queryParameters,
            call.request.headers,
            OpCallOrigin(call.request.origin.remoteAddress, call.request.origin.method),
            call.request.httpMethod,
            call.request.uri,
        )
    }
    override val response = OpCallResponse { name, value -> call.response.headers.append(name, value) }
    override val parameters: Parameters get() = call.parameters
    override val http: ApplicationCall get() = call
    override val args: JSONObject? get() = null
    override fun embedMode(): EmbedMode? = call.embedMode()
    override suspend fun respondText(text: String, contentType: ContentType?, status: HttpStatusCode?) =
        call.respondText(text, contentType, status)
    override suspend fun respondBytes(bytes: ByteArray, contentType: ContentType?, status: HttpStatusCode?) =
        call.respondBytes(bytes, contentType, status)
    override suspend fun respondRedirect(url: String, permanent: Boolean) = call.respondRedirect(url, permanent)
    override suspend fun respondOutputStream(
        contentType: ContentType?,
        status: HttpStatusCode?,
        producer: suspend OutputStream.() -> Unit,
    ) = call.respondOutputStream(contentType, status, producer = producer)
    override suspend fun receiveStream(): InputStream = call.receiveStream()
    override suspend fun respondTextWriter(
        contentType: ContentType?,
        status: HttpStatusCode?,
        writer: suspend java.io.Writer.() -> Unit,
    ) = call.respondTextWriter(contentType, status, writer)
}

/** Wrap an HTTP call for a helper that serves both doors. */
internal fun ApplicationCall.op(): OpCall = HttpOpCall(this)

/** The managed door: `args` stand in for the query, path parameters and body; the first answer is kept. */
internal class ManageOpCall(
    private val managed: ManageRequest,
    method: HttpMethod,
    path: String,
) : OpCall() {
    private var answered: ManageReply? = null
    private val extraHeaders = mutableListOf<Pair<String, String>>()

    override val origin: CommandOrigin get() = managed.origin
    override val args: JSONObject get() = managed.args
    // An embedded request's `lang` is the Home Assistant user's language (the embed switch's), which stands in
    // for Automatic and never overrides a chosen language, so it is not an explicit `?lang`.
    override val parameters: Parameters = managed.args.asParameters(
        setOfNotNull(ACCEPT_LANGUAGE_ARG, HOST_ARG, RAW_BODY_ARG, "lang".takeIf { managed.args.optBoolean("embed", false) }),
    )

    /**
     * A raw body, as the page transport sends one (the bytes its `args.blob` names, or the text of `args.body`),
     * replaces the `args` JSON as the request body; its type is the blob's, or plain text.
     */
    private val rawBodyType: String? = managed.args.optJSONObject("blob")?.optString("type")?.takeIf(String::isNotBlank)
        ?: (if (managed.args.opt(RAW_BODY_ARG) is String) "text/plain; charset=utf-8" else null)

    override val request = OpCallRequest(
        parameters,
        Headers.build {
            append(io.ktor.http.HttpHeaders.ContentType, rawBodyType ?: ContentType.Application.Json.toString())
            // A managed caller reads every answer as data, so a route that offers JSON or an HTML page answers JSON.
            append(io.ktor.http.HttpHeaders.Accept, ContentType.Application.Json.toString())
            managed.args.optString(ACCEPT_LANGUAGE_ARG).takeIf(String::isNotBlank)?.let {
                append(io.ktor.http.HttpHeaders.AcceptLanguage, it)
            }
            managed.args.optString(HOST_ARG).takeIf(String::isNotBlank)?.let { append(io.ktor.http.HttpHeaders.Host, it) }
        },
        OpCallOrigin(managed.origin.peerAddress.orEmpty(), method),
        method,
        path,
    )
    override val response = OpCallResponse { name, value -> extraHeaders += name to value }
    override val http: ApplicationCall? get() = null

    override fun embedMode(): EmbedMode? {
        val embedded = managed.args.optBoolean("embed", false)
        val lang = managed.args.optString("lang").takeIf(String::isNotBlank)
        val theme = managed.args.optString("theme").takeIf { it == "light" || it == "dark" }
        val hide = managed.args.optJSONArray("hide")?.let { list ->
            (0 until list.length()).mapNotNull { list.optString(it).lowercase().takeIf { tab -> tab in EmbedMode.TABS } }.toSet()
        } ?: managed.args.optString("hide").split(',').map { it.trim().lowercase() }.filter { it in EmbedMode.TABS }.toSet()
        if (!embedded && theme == null && hide.isEmpty()) return null
        return EmbedMode(lang, theme, hide)
    }

    override suspend fun respondText(text: String, contentType: ContentType?, status: HttpStatusCode?) {
        val type = contentType ?: ContentType.Text.Plain
        // A download (an attachment) keeps its exact bytes, as the file HTTP would have saved.
        if (managed.blobs != null && extraHeaders.any { (name, value) ->
                name.equals(io.ktor.http.HttpHeaders.ContentDisposition, ignoreCase = true) && value.startsWith("attachment", ignoreCase = true)
            }
        ) return respondBytes(text.toByteArray(Charsets.UTF_8), type, status)
        val json = type.match(ContentType.Application.Json)
        val body: Any = if (json) parseJsonBody(text) else text
        answer(status ?: HttpStatusCode.OK, body, type)
    }

    override suspend fun respondBytes(bytes: ByteArray, contentType: ContentType?, status: HttpStatusCode?) {
        val type = contentType ?: ContentType.Application.OctetStream
        val blobs = managed.blobs ?: return failNoBlobs()
        answer(status ?: HttpStatusCode.OK, JSONObject().put("blob", blobs.put(bytes, type.toString())), type)
    }

    override suspend fun respondRedirect(url: String, permanent: Boolean) {
        val status = if (permanent) HttpStatusCode.MovedPermanently else HttpStatusCode.Found
        answer(status, JSONObject().put("location", url), ContentType.Application.Json)
    }

    override suspend fun respondOutputStream(
        contentType: ContentType?,
        status: HttpStatusCode?,
        producer: suspend OutputStream.() -> Unit,
    ) {
        val bytes = ByteArrayOutputStream().also { producer(it) }.toByteArray()
        respondBytes(bytes, contentType, status)
    }

    override suspend fun respondTextWriter(
        contentType: ContentType?,
        status: HttpStatusCode?,
        writer: suspend java.io.Writer.() -> Unit,
    ) {
        val text = java.io.StringWriter().also { writer(it) }.toString()
        respondText(text, contentType, status)
    }

    /** A request's body: the bytes its `blob` reference names, else its `args` as JSON. */
    override suspend fun receiveStream(): InputStream {
        val ref = managed.args.optJSONObject("blob")
        if (ref != null) {
            return managed.blobs?.take(ref) ?: throw java.io.IOException("blob unavailable")
        }
        (managed.args.opt(RAW_BODY_ARG) as? String)?.let { return ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) }
        return ByteArrayInputStream(managed.args.toString().toByteArray(Charsets.UTF_8))
    }

    private fun failNoBlobs() {
        answered = answered ?: ManageReply.failed()
    }

    private fun answer(status: HttpStatusCode, body: Any?, type: ContentType) {
        if (answered != null) return
        answered = manageReplyFor(status.value, body, type.toString(), extraHeaders.toList())
    }

    /** The answer the handler gave; a handler that returned without answering failed. */
    fun reply(): ManageReply = answered ?: ManageReply.failed()
}

private fun parseJsonBody(text: String): Any {
    val trimmed = text.trim()
    return runCatching {
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> text
        }
    }.getOrDefault(text)
}

/**
 * The outcome a route's HTTP status reads as, the same table the page transport's HTTP implementation uses
 * (`outcomeFor` in transport.js), so a page sees one outcome on either transport: 2xx applies (a redirect is not
 * an answer a request can use, so 3xx fails like any other unexpected status), an approval
 * request is pending, `403` refuses as `refused_hardened`, `404`/`405`/`410` as `unknown_command`,
 * `409`/`423`/`429` as `not_commandable`, any other client error as `invalid_value`; `503` fails as
 * `hardware_unavailable` and any other server error fails.
 */
internal fun manageReplyFor(status: Int, body: Any?, contentType: String?, headers: List<Pair<String, String>>): ManageReply {
    val approvalRequired = status == 202 && (body as? JSONObject)?.optString("error") == "approval-required"
    val (outcome, code) = when {
        approvalRequired -> PanelAssistantTransportProtocol.OUTCOME_PENDING_APPROVAL to null
        status in 200..299 -> PanelAssistantTransportProtocol.OUTCOME_APPLIED to null
        status == 403 -> PanelAssistantTransportProtocol.OUTCOME_REFUSED to PanelAssistantCommandProcessor.CODE_REFUSED_HARDENED
        status == 404 || status == 405 || status == 410 ->
            PanelAssistantTransportProtocol.OUTCOME_REFUSED to PanelAssistantCommandProcessor.CODE_UNKNOWN_COMMAND
        status == 409 || status == 423 || status == 429 ->
            PanelAssistantTransportProtocol.OUTCOME_REFUSED to PanelAssistantCommandProcessor.CODE_NOT_COMMANDABLE
        status in 400..499 -> PanelAssistantTransportProtocol.OUTCOME_REFUSED to PanelAssistantCommandProcessor.CODE_INVALID_VALUE
        status == 503 -> PanelAssistantTransportProtocol.OUTCOME_FAILED to PanelAssistantCommandProcessor.CODE_HARDWARE_UNAVAILABLE
        else -> PanelAssistantTransportProtocol.OUTCOME_FAILED to PanelAssistantCommandProcessor.CODE_FAILED
    }
    return ManageReply(outcome, code, body, status, contentType, headers)
}

/** The `args` key that carries a raw text body (YAML, for the profile editor), as the page transport sends it. */
private const val RAW_BODY_ARG = "body"

/** `args` as request parameters: strings, numbers and booleans by name; an array gives one value per item. */
private fun JSONObject.asParameters(except: Set<String>): Parameters = Parameters.build {
    keys().asSequence().filter { it !in except }.forEach { key ->
        when (val value = opt(key)) {
            null, JSONObject.NULL, is JSONObject -> Unit
            is JSONArray -> for (i in 0 until value.length()) {
                val item = value.opt(i)
                if (item != null && item != JSONObject.NULL && item !is JSONObject && item !is JSONArray) append(key, item.toString())
            }
            else -> append(key, value.toString())
        }
    }
}

/** Where the routes find the manage table: an attribute of the root routing node, set by the server. */
internal val ManageTableKey = AttributeKey<ManageTable>("PanelManageTable")

/**
 * Registers routes that are also named operations. Each [get] or [post] mounts the HTTP route exactly as
 * Ktor's own does and enters the operation's name, kind and handler in the manage table, so the name, method,
 * path and kind are defined once. The name follows protocol section 20.3 (the path after `/api/v1/`, `/`
 * as `.`, `-` as `_`, path parameters dropped); [name] overrides it where section 20.6 names it otherwise.
 */
internal class OpRoutes(private val route: Route) {
    private val table: ManageTable? = route.manageTable()

    fun get(path: String, name: String? = null, kind: ManageKind = ManageKind.READ, body: suspend OpContext.() -> Unit) {
        route.get(path) { OpContext(HttpOpCall(call)).body() }
        register(HttpMethod.Get, path, name, kind, body)
    }

    fun post(path: String, name: String? = null, kind: ManageKind = ManageKind.COMMAND, body: suspend OpContext.() -> Unit) {
        route.post(path) { OpContext(HttpOpCall(call)).body() }
        register(HttpMethod.Post, path, name, kind, body)
    }

    /**
     * Enter a named read whose path no route of its own mounts: the stub's one handler serves the path and
     * answers it through the dispatcher (protocol section 20.7).
     */
    fun served(path: String, name: String, body: suspend OpContext.() -> Unit) =
        register(HttpMethod.Get, path, name, ManageKind.READ, body)

    private fun register(method: HttpMethod, path: String, name: String?, kind: ManageKind, body: suspend OpContext.() -> Unit) {
        val table = table ?: return
        val full = fullPath(path)
        val resolved = name ?: operationName(full) ?: error("no operation name for ${method.value} $full")
        table.register(resolved, kind) { request ->
            val managed = ManageOpCall(request, method, full)
            OpContext(managed).body()
            managed.reply()
        }
    }

    private fun fullPath(path: String): String {
        val prefix = generateSequence(route as? RoutingNode) { it.parent }
            .mapNotNull { (it.selector as? PathSegmentConstantRouteSelector)?.value }
            .toList()
            .asReversed()
        return "/" + (prefix + path.split('/')).filter(String::isNotEmpty).joinToString("/")
    }
}

/** Make every operation registered below this routing root enter [table]. */
internal fun Route.serveOperations(table: ManageTable) {
    (this as RoutingNode).attributes.put(ManageTableKey, table)
}

/** The manage table this route tree registers into, or null when it is mounted without one (route tests). */
internal fun Route.manageTable(): ManageTable? = generateSequence(this as? RoutingNode) { it.parent }
    .firstNotNullOfOrNull { it.attributes.getOrNull(ManageTableKey) }

/** Section 20.3's name for an `/api/v1/` path, or null for any other path. */
internal fun operationName(path: String): String? {
    if (!path.startsWith("/api/v1/")) return null
    return path.removePrefix("/api/v1/").split('/')
        .filter { it.isNotEmpty() && !it.startsWith("{") }
        .joinToString(".") { it.replace('-', '_') }
        .takeIf(ManageTable.NAME::matches)
}

/** Register named operations alongside their HTTP routes; see [OpRoutes]. */
internal inline fun Route.ops(block: OpRoutes.() -> Unit) = OpRoutes(this).block()
