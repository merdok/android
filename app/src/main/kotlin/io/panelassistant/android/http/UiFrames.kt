package io.panelassistant.android.http

import io.ktor.http.ContentType
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.ManageBlobs
import io.panelassistant.android.ManageDoor
import io.panelassistant.android.ManageReply
import io.panelassistant.android.ManageSink
import io.panelassistant.android.ManageSubscription
import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantChannelCatalog
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.LocalApprovalBroker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One connection's request objects (protocol section 20.2), whichever door carries them: the stub's `/ui` socket
 * and the sidebar relay over Panel Assistant's session each make one per connection and hand it every request.
 * Every request enters [dispatcher] with [origin]; answers, interim `pending_approval` answers (marked `interim`)
 * and subscription updates leave through [send] as one frame each. A `pending_approval` without `interim` is
 * final, as an HTTP 202 approval answer is. [close] ends every subscription the connection holds.
 *
 * [uploads] answers `blob.upload` itself (the stub's own blob slots); without it that name reaches the dispatcher,
 * which serves no such name, as the relay requires (Panel Assistant answers it before the panel sees it).
 */
internal class UiFrames(
    private val dispatcher: ManageDoor,
    private val origin: CommandOrigin,
    private val scope: CoroutineScope,
    private val uploads: StubBlobs? = null,
    private val send: (frame: JSONObject, after: () -> Unit) -> Unit,
) {
    private val subscriptions = ConcurrentHashMap<String, ManageSubscription>()
    private val open = AtomicBoolean(true)

    private fun send(frame: JSONObject) = send(frame) {}

    private fun answer(commandId: String, reply: ManageReply) = send(
        JSONObject().put("command_id", commandId).put("outcome", reply.outcome)
            .apply { reply.code?.let { put("code", it) } }
            // The route's own status, when it did not succeed, as the HTTP transport reports it.
            .apply { if (reply.status !in 200..299) put("status", reply.status) }
            .apply { if (reply.result != null) put("result", reply.result) }
            .apply { replyHeaders(reply)?.let { put("headers", it) } },
    )

    private fun answer(commandId: String, outcome: String, code: String? = null, result: Any? = null) =
        answer(commandId, ManageReply(outcome, code, result))

    private fun interim(commandId: String, approvalId: String? = null) = send(
        JSONObject().put("command_id", commandId).put("outcome", PanelAssistantTransportProtocol.OUTCOME_PENDING_APPROVAL)
            .put("interim", true).apply { approvalId?.let { put("result", JSONObject().put("approval_id", it)) } },
    )

    /** Handle one request object; [request] is null when the frame was not one. [blobs] carry its bodies. */
    fun handle(request: JSONObject?, blobs: ManageBlobs?) {
        val commandId = request?.optString("command_id").orEmpty()
        if (request == null || commandId.isEmpty()) {
            return answer(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, PanelAssistantTransportProtocol.CODE_INVALID_FORMAT)
        }
        val args = request.optJSONObject("args") ?: JSONObject()
        when (request.optString("kind")) {
            "command" -> command(commandId, request)
            "manage" -> when (val op = request.optString("op")) {
                "subscribe" -> subscribe(commandId, args)
                "unsubscribe" -> {
                    subscriptions.remove(args.optString("subscription"))?.close()
                    answer(commandId, PanelAssistantTransportProtocol.OUTCOME_APPLIED)
                }
                else -> if (op == "blob.upload" && uploads != null) {
                    upload(commandId, uploads, args)
                } else {
                    dispatcher.manage(commandId, op, args, origin, blobs, onPending = { interim(commandId) }) { reply -> answer(commandId, reply) }
                }
            }
            else -> answer(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, PanelAssistantTransportProtocol.CODE_INVALID_FORMAT)
        }
    }

    /** End the connection: its commands still waiting fail, and every subscription it holds ends. */
    fun close() {
        open.set(false)
        dispatcher.closeOrigin(origin)
    }

    private fun command(commandId: String, request: JSONObject) {
        val deadlineMs = request.optLong("deadline_ms", DEFAULT_DEADLINE_MS)
        val value = request.opt("value")
        val channel = request.optString("channel")
        // The value decodes as Panel Assistant's does. A channel that takes no commands keeps its raw value, so the
        // dispatcher answers it as the unknown channel it is.
        val descriptor = PanelAssistantChannelCatalog.describe(channel)
            ?.takeIf { it.platform in PanelAssistantCommandProcessor.COMMANDABLE_PLATFORMS }
        val decoded = if (descriptor == null) CommandValue.Text(value?.toString().orEmpty())
        else io.panelassistant.android.panelassistant.decode(descriptor, value)
        if (decoded == null) {
            answer(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, PanelAssistantCommandProcessor.CODE_INVALID_VALUE)
            return
        }
        // A sensitive command is held for its approval on the panel's screen: the door answers an interim
        // pending_approval, waits, runs it again once approved and answers the outcome, as Panel Assistant's
        // processor does on the session.
        fun run(held: Boolean) {
            val deadlineAt = System.nanoTime() / 1_000_000 + deadlineMs
            dispatcher.submit(
                PanelAssistantCommand(
                    channel = channel,
                    value = decoded,
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
                scope.launch {
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

    private fun subscribe(commandId: String, args: JSONObject) {
        val family = args.optString("family")
        // Updates queue behind the `applied` answer that names their subscription.
        val answered = CompletableDeferred<Unit>()
        // Every other argument reaches each read of the family (a visible wizard's presence).
        val familyArgs = JSONObject(args.toString()).apply { remove("family") }
        val subscription = dispatcher.subscribe(family, origin, sink = ManageSink { value, known, delivered ->
            val update = JSONObject().put("subscription", commandId).put(
                "observations",
                JSONArray().put(
                    JSONObject().put("channel", family).put("state", if (known) "known" else "unavailable")
                        .put("value", if (known) value ?: JSONObject.NULL else JSONObject.NULL),
                ),
            )
            // An update racing this subscription's unsubscribe is dropped, never sent after its answer.
            scope.launch { answered.await(); if (subscriptions.containsKey(commandId)) send(update, delivered) else delivered() }
        }, args = familyArgs)
        if (subscription == null) {
            answer(commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, PanelAssistantCommandProcessor.CODE_INVALID_VALUE)
        } else {
            subscriptions[commandId] = subscription
            answer(commandId, PanelAssistantTransportProtocol.OUTCOME_APPLIED, result = JSONObject().put("subscription", commandId))
            answered.complete(Unit)
        }
    }

    /** Section 20.5: the stub answers this itself, without reaching the panel's operations. */
    private fun upload(commandId: String, slots: StubBlobs, args: JSONObject) = when (val minted = slots.mintUpload(
        args.optString("type").ifEmpty { ContentType.Application.OctetStream.toString() },
        args.optLong("size", -1).takeIf { it >= 0 },
    )) {
        null -> answer(
            commandId, PanelAssistantTransportProtocol.OUTCOME_REFUSED, PanelAssistantCommandProcessor.CODE_INVALID_VALUE,
            JSONObject().put("error", "too-large").put("max_size", StubBlobs.MAX_BYTES),
        )
        else -> answer(commandId, PanelAssistantTransportProtocol.OUTCOME_APPLIED, result = minted)
    }

    private companion object {
        const val APPROVAL_POLL_MS = 1_000L
        const val DEFAULT_DEADLINE_MS = 10_000L
    }
}

/**
 * The `x-*` headers a route set (screenshot id, input route, profile presentation) and a download's
 * `content-disposition` (its file name), lower-cased as the page transport reads them over HTTP; null when there
 * are none.
 */
private fun replyHeaders(reply: ManageReply): JSONObject? {
    val named = reply.headers.filter { (name, _) ->
        val lower = name.lowercase()
        (lower.startsWith("x-") && lower != "x-frame-options" && lower != "x-content-type-options") || lower == "content-disposition"
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
