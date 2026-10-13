package io.panelassistant.android

import android.util.Log
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.ApprovalBroker
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Whether a named operation may change state. A read may be repeated; a command keeps the duplicate rule. */
internal enum class ManageKind { READ, COMMAND }

/**
 * One answer to a named operation (protocol section 20.2): its [outcome] and refusal [code], and the body the
 * HTTP route returns for the same request as [result] — a [JSONObject] or [JSONArray] for a JSON body, a
 * [String] for a text body, a blob reference object for binary, or null for an empty body. [status],
 * [contentType] and [headers] are what HTTP would have sent, kept so a door can reproduce them.
 */
internal data class ManageReply(
    val outcome: String,
    val code: String?,
    val result: Any? = null,
    val status: Int = 200,
    val contentType: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        fun refused(code: String) = ManageReply(PanelAssistantTransportProtocol.OUTCOME_REFUSED, code, status = 400)
        fun failed(code: String = PanelAssistantCommandProcessor.CODE_FAILED) =
            ManageReply(PanelAssistantTransportProtocol.OUTCOME_FAILED, code, status = 500)
    }
}

/**
 * Binary bodies and uploads (protocol section 20.5), supplied by the door that carries the request. [put]
 * stores bytes a reply carries and returns the reference that stands in for them; [take] returns the bytes a
 * request's reference names, or null when it names nothing usable.
 */
internal interface ManageBlobs {
    fun put(bytes: ByteArray, type: String): JSONObject
    fun take(ref: JSONObject): ByteArray?
}

/** One named request as its handler sees it. */
internal class ManageRequest(
    val op: String,
    val args: JSONObject,
    val origin: CommandOrigin,
    val blobs: ManageBlobs?,
)

internal fun interface ManageHandler {
    suspend fun handle(request: ManageRequest): ManageReply
}

/**
 * The dispatcher entries a door calls (protocol section 20.2): named operations, channel commands and
 * subscriptions, each with the origin the door sets. [PanelCommandDispatcher] is the production one; a door
 * holds no handlers of its own.
 */
internal interface ManageDoor {
    fun manage(
        commandId: String,
        op: String,
        args: JSONObject,
        origin: CommandOrigin,
        blobs: ManageBlobs? = null,
        onPending: () -> Unit = {},
        done: (ManageReply) -> Unit,
    )
    fun submit(
        command: io.panelassistant.android.panelassistant.PanelAssistantCommand,
        origin: CommandOrigin,
        done: (io.panelassistant.android.panelassistant.PanelAssistantCommandResult) -> Unit,
    )
    fun subscribe(family: String, origin: CommandOrigin, sink: ManageSink): ManageSubscription?
    fun closeOrigin(origin: CommandOrigin)
}

/** A sensitive operation from Panel Assistant is waiting for [approvalId] on the panel's own screen. */
internal class ManageApprovalPending(val approvalId: String) : RuntimeException("approval pending")

/** The live log lines the `logs` family follows: the same source the Server-Sent Events stream reads. */
internal interface ManageLogSource {
    /** The lines a new viewer is shown first; null when the source cannot be followed now. */
    fun backlog(): List<String>?
    fun follow(onLine: (String) -> Unit): AutoCloseable
}

/**
 * The named operations this panel serves, each registered once by the route that also mounts it over HTTP.
 * Registering a name again replaces it, so remounting the routes cannot leave two definitions.
 */
internal class ManageTable {
    data class Entry(val name: String, val kind: ManageKind, val handler: ManageHandler)

    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile var logSource: ManageLogSource? = null

    fun register(name: String, kind: ManageKind, handler: ManageHandler) {
        require(NAME.matches(name)) { "operation name $name" }
        entries[name] = Entry(name, kind, handler)
    }

    operator fun get(name: String): Entry? = entries[name]

    fun names(): Set<String> = entries.keys.toSortedSet()

    companion object {
        val NAME = Regex("^[a-z][a-z0-9_.]{0,63}$")
    }
}

/** A subscription family (protocol section 20.4): the reads whose bodies make its value, and its floor. */
internal data class ManageFamily(val name: String, val floorMs: Long, val reads: List<String>, val append: Boolean = false)

internal object ManageFamilies {
    val DEFAULT: Map<String, ManageFamily> = listOf(
        ManageFamily("perf", 2_000, listOf("perf")),
        ManageFamily("sensors", 2_000, listOf("sensors")),
        ManageFamily("camera", 2_000, listOf("camera.status")),
        ManageFamily("health", 5_000, listOf("health")),
        ManageFamily("entities", 5_000, listOf("dashboard.entities", "dashboard.entities.sync", "dashboard.entities.issues")),
        ManageFamily("proximity", 1_000, listOf("proximity")),
        ManageFamily("microphone", 2_000, listOf("voice.microphone")),
        ManageFamily("radio", 5_000, listOf("radio")),
        ManageFamily("install", 2_000, listOf("install.status", "install.apk.pending")),
        ManageFamily("setup", 2_000, listOf("setup", "ha.oauth.status")),
        ManageFamily("logs", 1_000, emptyList(), append = true),
    ).associateBy(ManageFamily::name)
}

/** Receives a subscription's updates. [delivered] is called once the update has left, which frees the slot. */
internal fun interface ManageSink {
    fun update(value: Any?, delivered: () -> Unit)
}

internal interface ManageSubscription : AutoCloseable {
    val family: String
}

/**
 * The single entry every door calls for named operations and subscriptions, owned by one command
 * dispatcher generation. Reads and commands run on this owner's own workers, never on the ordered command
 * worker, so a long command (a backup, an APK fetch) cannot hold up channel commands; a handler that changes
 * a setting enters the ordered worker itself, through the settings path.
 */
internal class ManageOperations(
    private val table: ManageTable,
    private val approvals: () -> ApprovalBroker,
    private val families: Map<String, ManageFamily> = ManageFamilies.DEFAULT,
    private val approvalPollMs: Long = 1_000,
    private val approvalTtlMs: Long = ApprovalBroker.DEFAULT_TTL_MS,
    private val maxRememberedPerOrigin: Int = 64,
    private val maxSubscriptionsPerOrigin: Int = 8,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private class Recorded(var answer: ManageReply?, val waiters: MutableList<(ManageReply) -> Unit>)

    private val lock = Any()
    /** Command answers by origin approval key, then command ID, oldest first; a running command has none. */
    private val remembered = HashMap<String, LinkedHashMap<String, Recorded>>()
    private val subscriptions = HashMap<String, LinkedHashMap<String, FamilySubscription>>()

    /**
     * Run [op] with [args] for [origin]; [done] receives exactly one final answer. An unknown name is
     * refused. A command whose [commandId] this origin already used answers that command's answer without
     * running again. A sensitive command from Panel Assistant calls [onPending] once, waits for its approval
     * on the panel, then answers its final outcome.
     */
    fun manage(
        commandId: String,
        op: String,
        args: JSONObject,
        origin: CommandOrigin,
        blobs: ManageBlobs? = null,
        onPending: () -> Unit = {},
        done: (ManageReply) -> Unit,
    ) {
        val entry = table[op]
        if (entry == null || !job.isActive) {
            return done(ManageReply.refused(PanelAssistantCommandProcessor.CODE_UNKNOWN_COMMAND))
        }
        val request = ManageRequest(op, args, origin, blobs)
        if (entry.kind == ManageKind.READ) {
            scope.launch { done(run(entry, request, onPending)) }
            return
        }
        val record = synchronized(lock) {
            val byId = remembered.getOrPut(origin.approvalKey) { LinkedHashMap() }
            val previous = byId[commandId]
            if (previous != null) {
                previous.answer?.let { answer -> return@synchronized { done(answer) } }
                previous.waiters += done
                return
            }
            byId[commandId] = Recorded(null, mutableListOf(done))
            while (byId.size > maxRememberedPerOrigin) {
                val oldest = byId.entries.firstOrNull { it.value.answer != null } ?: break
                byId.remove(oldest.key)
            }
            null
        }
        if (record != null) return record()
        scope.launch {
            val answer = run(entry, request, onPending)
            val waiters = synchronized(lock) {
                val recorded = remembered[origin.approvalKey]?.get(commandId) ?: return@synchronized emptyList()
                recorded.answer = answer
                recorded.waiters.toList().also { recorded.waiters.clear() }
            }
            waiters.forEach { it(answer) }
        }
    }

    private suspend fun run(entry: ManageTable.Entry, request: ManageRequest, onPending: () -> Unit): ManageReply {
        var pendingSent = false
        while (true) {
            val approvalId = try {
                return entry.handler.handle(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (pending: ManageApprovalPending) {
                pending.approvalId
            } catch (e: Exception) {
                Log.w(TAG, "operation ${request.op} failed", e)
                return ManageReply.failed()
            }
            // Asked again after an approval was consumed: its record expired between the approval and
            // the run. One interim answer per request, so this one is final.
            if (pendingSent) {
                return ManageReply.refused(PanelAssistantCommandProcessor.CODE_APPROVAL_TIMEOUT)
            }
            pendingSent = true
            onPending()
            val heldAt = System.nanoTime()
            while (true) {
                delay(approvalPollMs)
                when (approvals().state(approvalId)) {
                    ApprovalBroker.State.PENDING -> continue
                    ApprovalBroker.State.APPROVED -> break
                    // The broker keeps no denied state: gone before its lifetime ran out was denied.
                    ApprovalBroker.State.ABSENT -> return ManageReply.refused(
                        if ((System.nanoTime() - heldAt) / 1_000_000 >= approvalTtlMs) {
                            PanelAssistantCommandProcessor.CODE_APPROVAL_TIMEOUT
                        } else {
                            PanelAssistantCommandProcessor.CODE_APPROVAL_DENIED
                        },
                    )
                }
            }
        }
    }

    /**
     * Follow [family] for [origin]. The first update carries the current value; later ones follow changes,
     * never more often than the family's floor, with at most one update outstanding (a newer value replaces
     * an unsent one). One owner ([CommandOrigin.subscriptionOwner]: the socket, or the origin) subscribing twice
     * to a family shares one subscription, whose updates go to the newest sink. Null when the family is unknown
     * or the owner already holds the most it may.
     */
    fun subscribe(family: String, origin: CommandOrigin, sink: ManageSink): ManageSubscription? {
        val definition = families[family] ?: return null
        if (!job.isActive) return null
        synchronized(lock) {
            val held = subscriptions.getOrPut(origin.subscriptionOwner) { LinkedHashMap() }
            held[family]?.let { shared ->
                shared.replaceSink(sink)
                return shared
            }
            if (held.size >= maxSubscriptionsPerOrigin) return null
            val created = FamilySubscription(definition, origin, sink)
            held[family] = created
            created.start()
            return created
        }
    }

    /** End every subscription [origin]'s owner holds (that socket only, when it has one), as a door does when its connection closes. */
    fun closeOrigin(origin: CommandOrigin) {
        val held = synchronized(lock) { subscriptions.remove(origin.subscriptionOwner)?.values?.toList().orEmpty() }
        held.forEach { it.stop() }
    }

    fun stop() {
        job.cancel()
        synchronized(lock) {
            subscriptions.clear()
            remembered.clear()
        }
    }

    private suspend fun readFamily(definition: ManageFamily, origin: CommandOrigin): Any? {
        val values = definition.reads.map { name ->
            val entry = table[name] ?: return null
            val reply = run(entry, ManageRequest(name, JSONObject(), origin, null)) {}
            if (reply.outcome != PanelAssistantTransportProtocol.OUTCOME_APPLIED) return null
            name to reply.result
        }
        // A family made of one read carries that read's body; one made of several carries each by name.
        return values.singleOrNull()?.second ?: JSONObject().apply { values.forEach { (name, body) -> put(name, body) } }
    }

    private inner class FamilySubscription(
        private val definition: ManageFamily,
        private val origin: CommandOrigin,
        @Volatile private var sink: ManageSink,
    ) : ManageSubscription {
        override val family: String get() = definition.name
        private var worker: Job? = null
        private val slot = Any()
        private var outstanding = false
        private var unsent: Any? = null
        private var hasUnsent = false
        /** The last value sent or queued, compared by its encoding; null before the first. */
        private var last: String? = null

        fun start() {
            worker = scope.launch {
                if (definition.append) followLogs() else poll()
            }
        }

        fun replaceSink(replacement: ManageSink) {
            sink = replacement
            synchronized(slot) { last?.let { last = null } }
        }

        private suspend fun poll() {
            while (currentCoroutineContext().isActive) {
                val value = runCatching { readFamily(definition, origin) }.getOrNull()
                if (value != null) {
                    val encoded = encode(value)
                    val changed = synchronized(slot) { (encoded != last).also { if (it) last = encoded } }
                    if (changed) offer(value, append = false)
                }
                delay(definition.floorMs)
            }
        }

        private suspend fun followLogs() {
            val source = table.logSource ?: return
            val backlog = source.backlog() ?: return
            val arrived = ArrayDeque<String>()
            val follower = source.follow { line ->
                synchronized(arrived) {
                    arrived.addLast(line)
                    while (arrived.size > MAX_LOG_LINES) arrived.removeFirst()
                }
            }
            try {
                offer(JSONArray(backlog), append = true)
                while (currentCoroutineContext().isActive) {
                    delay(definition.floorMs)
                    val lines = synchronized(arrived) { arrived.toList().also { arrived.clear() } }
                    if (lines.isNotEmpty()) offer(JSONArray(lines), append = true)
                }
            } finally {
                runCatching { follower.close() }
            }
        }

        private fun offer(value: Any, append: Boolean) {
            val send = synchronized(slot) {
                if (outstanding) {
                    // Latest wins for a replacing family; an append family keeps every line, oldest first.
                    unsent = if (append && hasUnsent) concat(unsent as JSONArray, value as JSONArray) else value
                    hasUnsent = true
                    false
                } else {
                    outstanding = true
                    true
                }
            }
            if (send) deliver(value)
        }

        private fun deliver(value: Any) {
            val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
            sink.update(value) {
                if (!delivered.compareAndSet(false, true)) return@update
                val next = synchronized(slot) {
                    if (hasUnsent) {
                        hasUnsent = false
                        unsent.also { unsent = null }
                    } else {
                        outstanding = false
                        null
                    }
                }
                if (next != null && worker?.isActive == true) deliver(next)
            }
        }

        fun stop() {
            worker?.cancel()
        }

        override fun close() {
            synchronized(lock) {
                val held = subscriptions[origin.subscriptionOwner]
                if (held?.get(definition.name) === this) held.remove(definition.name)
            }
            stop()
        }
    }

    private companion object {
        const val TAG = "ha-paneld/manage"
        const val MAX_LOG_LINES = 512

        fun encode(value: Any?): String = when (value) {
            is JSONObject, is JSONArray -> value.toString()
            null -> "null"
            else -> JSONObject().put("v", value).toString()
        }

        fun concat(first: JSONArray, second: JSONArray): JSONArray = JSONArray().apply {
            for (i in 0 until first.length()) put(first.get(i))
            for (i in 0 until second.length()) put(second.get(i))
        }
    }
}
