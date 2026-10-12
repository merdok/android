package io.panelassistant.android.http

import android.util.Log
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.isLoopbackPeer
import io.panelassistant.android.util.Json
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import java.security.MessageDigest

/**
 * Canonical approval payload for a materialized HTTP request. Distinct query-name order is not
 * semantically significant, but duplicate value order is because Ktor's first-value lookup can
 * affect behavior. Group by name while retaining each value list's order, then length-frame every
 * field and collection count so no name/value grouping can share an approval accidentally.
 */
internal fun exactHttpApprovalPayload(
    method: String,
    path: String,
    parameters: List<Pair<String, String>>,
    bodyDigest: String,
): String = buildString {
    fun frame(value: String) {
        append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value)
    }

    val grouped = parameters.groupBy({ it.first }, { it.second }).toSortedMap()
    frame(method.uppercase())
    frame(path)
    frame(grouped.size.toString())
    grouped.forEach { (name, values) ->
        frame(name)
        frame(values.size.toString())
        values.forEach(::frame)
    }
    frame(bodyDigest)
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

/** Shared sensitive-request decision. Hardened policy is intentionally remote-only: trusted loopback
 * callers retain the established exemption while every non-loopback request remains peer-, operation-
 * and payload-bound through the one-shot approval broker. A request Panel Assistant proved was made by a
 * Home Assistant administrator skips approval for the operations [EmbedProof.EXEMPT] names, and only those. */
internal suspend fun authorizeSensitiveRequest(
    call: ApplicationCall,
    hardened: Boolean,
    peer: String,
    operation: SensitiveOperation,
    payload: String,
    summary: String,
    broker: ApprovalBroker,
    audit: (String) -> Unit = { line -> Log.i("ha-paneld/http", line) },
): Boolean {
    if (!hardened || isLoopbackPeer(peer)) return true
    val proven = call.provenEmbedRequest()
    if (proven != null && proven.exempts(operation)) {
        audit("approved by Home Assistant administrator ${proven.userId} via Panel Assistant: ${operation.name}")
        return true
    }
    val (decision, id) = broker.request(operation, peer, payload, summary)
    if (decision == ApprovalBroker.Decision.APPROVED) return true
    call.respondText(approvalRequiredBody(id), ContentType.Application.Json, HttpStatusCode.Accepted)
    return false
}

internal fun Parameters.canonicalDigest(): String {
    val framed = buildString {
        fun frame(value: String) {
            append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value)
        }

        val fields = entries().sortedBy { it.key }
        frame(fields.size.toString())
        fields.forEach { (name, values) ->
            frame(name)
            frame(values.size.toString())
            values.forEach { value ->
                // Parameters.get(name) consumes the first submitted value, so value order is part
                // of the request's behavior and must remain part of its approval identity.
                frame(value)
            }
        }
    }
    return sha256Hex(framed.toByteArray(Charsets.UTF_8))
}

internal fun exactHttpApprovalPayload(call: ApplicationCall, bodyDigest: String): String =
    exactHttpApprovalPayload(call.op(), bodyDigest)

/** A managed request's payload frames its route's method and path with its `args` as the parameters. */
internal fun exactHttpApprovalPayload(call: OpCall, bodyDigest: String): String =
    exactHttpApprovalPayload(
        method = call.request.httpMethod.value,
        path = call.request.uri.substringBefore('?'),
        parameters = call.request.queryParameters.entries()
            .flatMap { (name, values) -> values.map { name to it } },
        bodyDigest = bodyDigest,
    )

/**
 * [authorizeSensitiveRequest] for a request on either door. Over HTTP it is that function, bound to the peer
 * address. A managed request binds its approval to its origin's approval key (protocol section 20.2), so an
 * approval earned by one origin is never consumed by another: a loopback peer is exempt as on HTTP; a
 * Home Assistant administrator relayed by Panel Assistant is exempt for exactly the operations an embed
 * proof exempts; a Panel Assistant request waiting for its approval raises [ManageApprovalPending] so the
 * dispatcher answers `pending_approval` and its final outcome; any other answers the HTTP `202` body.
 */
internal suspend fun authorizeSensitiveRequest(
    call: OpCall,
    hardened: Boolean,
    operation: SensitiveOperation,
    payload: String,
    summary: String,
    broker: ApprovalBroker,
): Boolean {
    call.http?.let { http ->
        return authorizeSensitiveRequest(http, hardened, call.request.origin.remoteAddress, operation, payload, summary, broker)
    }
    val origin = call.origin
    if (!hardened || origin.peerAddress?.let(::isLoopbackPeer) == true) return true
    if (origin.isUser && operation in EmbedProof.EXEMPT) return true
    val (decision, id) = broker.request(operation, origin.approvalKey, payload, summary)
    if (decision == ApprovalBroker.Decision.APPROVED) return true
    if (origin.isPanelAssistant) throw io.panelassistant.android.ManageApprovalPending(id)
    call.respondText(approvalRequiredBody(id), ContentType.Application.Json, HttpStatusCode.Accepted)
    return false
}

private fun approvalRequiredBody(id: String): String =
    "{\"ok\":false,\"error\":\"approval-required\",\"approval_id\":${Json.str(id)}," +
        "\"message\":\"Approve this request physically on the panel, then retry it; it cannot be approved remotely.\"}"
