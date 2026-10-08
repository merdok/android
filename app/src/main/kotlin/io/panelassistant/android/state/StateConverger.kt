package io.panelassistant.android.state

import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.RetirableMutationGate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One destination for converged observations, addressed by transport-neutral channel id. [done] reports
 * true only when this destination acknowledged the exact observation on the connection that sent it.
 * A sink must return promptly: it runs on the convergence pump.
 */
typealias StateSink = (channel: String, observation: StateConverger.Observation.Reportable, done: (Boolean) -> Unit) -> Unit

/**
 * Registry-driven state observation. Every state-bearing entity supplies one authoritative observation
 * under a transport-neutral channel id; commands, reconnects, local events and periodic audits all flow
 * through this class, and every admitted observation reaches each sink in admission order. Transport
 * addressing and delivery bookkeeping belong to the destination, never to the channel.
 */
class StateConverger {
    sealed interface Observation {
        /** What a sink may receive. Unknown publishes nothing, so it is never reported. */
        sealed interface Reportable : Observation
        data class Known(val payload: String) : Reportable
        data object Unknown : Observation
        data object Unavailable : Reportable
    }

    data class Channel(
        /** Transport-neutral channel id matching [CHANNEL_ID]. */
        val key: String,
        val observe: () -> Observation,
    )

    /**
     * A destination that keeps per-channel delivery state of its own: the MQTT outbox, which acknowledges,
     * deduplicates and bounds what it publishes, and goes when MQTT does. [register] and [offer] run under
     * this converger's monitor, in admission order and after every sink; [offer] receives Unknown too,
     * and returns the send to run once the monitor is released, or null.
     */
    interface Delivery {
        fun register(key: String)
        fun offer(key: String, observation: Observation, force: Boolean, admit: () -> Boolean): (() -> Unit)?
        fun close()
    }

    private val channels = linkedMapOf<String, Channel>()
    private val sinks = CopyOnWriteArrayList<StateSink>()
    private var delivery: Delivery? = null
    private var closed = false
    private val lifecycle = RetirableMutationGate()

    @Synchronized
    fun register(channel: Channel) {
        check(!closed) { "state converger is closed" }
        require(CHANNEL_ID.matches(channel.key)) { "invalid state channel id ${channel.key}" }
        check(channel.key !in channels) { "duplicate state channel ${channel.key}" }
        channels[channel.key] = channel
        delivery?.register(channel.key)
    }

    /** Every later admitted observation also reaches [sink]; it never changes delivery state. */
    fun addSink(sink: StateSink) {
        sinks += sink
    }

    /** Attach the one [Delivery]; channels registered before it are handed over first. */
    @Synchronized
    fun attach(delivery: Delivery) {
        check(this.delivery == null) { "state delivery already attached" }
        this.delivery = delivery
        channels.keys.forEach(delivery::register)
    }

    /**
     * [admit] is evaluated under this monitor immediately before an observation is accepted, so a caller
     * can withdraw a reconcile whose observation was overtaken (e.g. a command read-back superseded by
     * a newer command mid-observation). A refused admission changes no channel state: the caller
     * guarantees a later reconcile for the same channel is already ordered behind it.
     */
    fun reconcile(key: String, force: Boolean = false, admit: () -> Boolean = ALWAYS_ADMIT) {
        lifecycle.runIfOpen(Unit) { reconcileAdmitted(key, force, admit) }
    }

    private fun reconcileAdmitted(key: String, force: Boolean, admit: () -> Boolean) {
        val channel = synchronized(this) { if (closed) null else channels[key] } ?: return
        val observation = runCatching { channel.observe() }.getOrDefault(Observation.Unknown)
        val send = synchronized(this) {
            if (closed) return
            // Withdraw superseded command read-backs before any destination receives them. Keep
            // observation delivery ordered under the monitor, ahead of MQTT's ACK/capacity gates.
            if (!admit()) return
            if (observation is Observation.Reportable) {
                for (sink in sinks) {
                    try {
                        sink(key, observation) {}
                    } catch (_: Exception) {
                        // A sink never changes another destination's admission or acknowledgement state.
                    }
                }
            }
            delivery?.offer(key, observation, force, admit)
        } ?: return
        send()
    }

    fun reconcileAll(force: Boolean = false) {
        val keys = synchronized(this) { if (closed) emptyList() else channels.keys.toList() }
        keys.forEach { reconcile(it, force) }
    }

    /** Terminal owner boundary: reject queued audits and invalidate every late completion. */
    fun close() {
        lifecycle.closeAdmission()
        synchronized(this) {
            if (closed) return
            closed = true
            delivery?.close()
        }
    }

    /** Close admission and prove no observer or sender call can cross into owner teardown. */
    internal fun closeAndDrain(deadline: MonotonicDeadline): Boolean {
        close()
        return lifecycle.awaitDrained(deadline)
    }

    @Synchronized
    fun keys(): Set<String> = channels.keys.toSet()

    companion object {
        /** Protocol §7 channel identity; a colon can never appear, so `http:<key>` keys cannot collide. */
        val CHANNEL_ID = Regex("^[a-z][a-z0-9_]{0,47}$")
        private val ALWAYS_ADMIT: () -> Boolean = { true }
        private val pumpThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        private val PUMP = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "state-convergence").apply { isDaemon = true; pumpThread.set(this) }
        }

        internal fun onPumpThread(): Boolean = Thread.currentThread() === pumpThread.get()

        /** Serialize local UI/hardware notifications with acknowledgement-driven outbox pumping. */
        internal fun dispatch(task: () -> Unit) = PUMP.execute(task)
    }
}
