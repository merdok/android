package io.panelassistant.android.mqtt

import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCostRegistry
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.state.StateConverger.Observation
import io.panelassistant.android.state.StateSink

/**
 * MQTT's delivery half of state convergence. Publication state advances only after [sender] acknowledges
 * the exact generation that was sent; one physical publish is in flight per channel and at most
 * [MAX_IN_FLIGHT] across them. Observations come from the state core, which calls [offer] in admission
 * order; a channel without a [Policy] is native-only and never published.
 */
internal class MqttStateOutbox(
    private val sender: StateSink,
    /** A fresh observation through the state core, which offers it back here. */
    private val reconcile: (key: String) -> Unit,
    /** How a channel publishes; null for a native-only channel, which this outbox never holds. */
    private val policyFor: (key: String) -> Policy?,
    private val schedule: (() -> Unit) -> Unit = StateConverger::dispatch,
    private val featureCosts: FeatureCostRegistry = FeatureCosts.registry,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : StateConverger.Delivery {
    data class Policy(
        val equivalent: (acknowledged: String, observed: String) -> Boolean = String::equals,
        /** Whether a known payload is meaningful enough to repeat solely for freshness. Semantic
         *  changes still publish normally even when this returns false. */
        val refreshEligible: (String) -> Boolean = { true },
        /** Maximum acknowledged silence for this channel. Null keeps change-only
         *  publication. The deadline never republishes Unknown or Unavailable observations. */
        val maxSilenceMs: Long? = null,
    ) {
        init {
            require(maxSilenceMs == null || maxSilenceMs > 0L) { "maxSilenceMs must be positive" }
        }
    }

    private data class Runtime(
        val policy: Policy,
        var acknowledged: String? = null,
        var sent: String? = null,
        var generation: Long = 0,
        var inFlight: Boolean = false,
        var queuedLatest: Boolean = false,
        var dirty: Boolean = true,
        var unknown: Boolean = false,
        var sentAtMs: Long = 0,
        var acknowledgedAtMs: Long? = null,
        var cost: FeatureCostRegistry.Span? = null,
    )

    private val channels = linkedMapOf<String, Runtime>()
    private var successes = 0L
    private var failures = 0L
    private var closed = false

    @Synchronized
    override fun register(key: String) {
        if (closed || key in channels) return
        channels[key] = Runtime(policyFor(key) ?: return)
    }

    override fun offer(
        key: String,
        observation: Observation,
        force: Boolean,
        admit: () -> Boolean,
    ): (() -> Unit)? {
        val runtime: Runtime
        val generation: Long
        val payload: String
        val admittedCost: FeatureCostRegistry.Span
        synchronized(this) {
            if (closed) return null
            runtime = channels[key] ?: return null
            runtime.unknown = observation !is Observation.Known
            val observedPayload = when (observation) {
                is Observation.Known -> observation.payload
                Observation.Unavailable -> ""
                Observation.Unknown -> {
                    // An unreadable sample cannot free an MQTT slot still awaiting acknowledgement.
                    if (!runtime.inFlight) runtime.dirty = false
                    return null
                }
            }
            val acknowledged = runtime.acknowledged
            val equivalent = acknowledged?.let { runtime.policy.equivalent(it, observedPayload) } == true
            val refreshDue = !runtime.unknown && runtime.policy.refreshEligible(observedPayload) &&
                runtime.policy.maxSilenceMs?.let { maximum ->
                runtime.acknowledgedAtMs?.let { acknowledgedAt ->
                    (monotonicMs() - acknowledgedAt).coerceAtLeast(0L) >= maximum
                }
            } == true
            // A cadence report repeats the acknowledged representative after freshly observing an
            // equivalent value. This proves freshness without allowing sub-deadband drift to move the
            // baseline a little every interval. Semantic/deadband-crossing and forced updates send the
            // current observation as before.
            payload = if (!force && refreshDue && equivalent) requireNotNull(acknowledged) else observedPayload
            if (runtime.inFlight && runtime.sent == payload) return null
            if (runtime.inFlight) {
                // Exactly one physical publish per channel. The observer remains the latest-value
                // authority; one dirty bit conflates any alternating flood until the ACK frees its slot.
                runtime.queuedLatest = true
                runtime.dirty = true
                featureCosts.recordCoalesced(FeatureCostOperation.MQTT_STATE_OUTBOX)
                updateBacklog()
                return null
            }
            if (!force && !runtime.dirty && equivalent && !refreshDue) return null
            if (channels.values.count { it.inFlight } >= MAX_IN_FLIGHT) {
                // A capacity refusal must not lose the observation. A CLEAN channel reconciled here
                // (e.g. just commanded during a burst) would otherwise stay silent until the next
                // periodic audit; dirty keeps it in the ACK pump's reconcileDirty drain.
                runtime.dirty = true
                updateBacklog()
                return null
            }
            // Native recording and equivalence checks may overlap a newer command's admission.
            // Retain MQTT's final read-back check immediately before committing its publication.
            if (!admit()) return null
            generation = ++runtime.generation
            runtime.sent = payload
            runtime.inFlight = true
            runtime.queuedLatest = false
            runtime.dirty = true
            runtime.sentAtMs = System.currentTimeMillis()
            admittedCost = featureCosts.span(FeatureCostOperation.MQTT_STATE_OUTBOX)
                .work(units = 1, bytes = payload.toByteArray(Charsets.UTF_8).size.toLong())
            runtime.cost = admittedCost
            updateBacklog()
        }

        val completion: (Boolean) -> Unit = { success ->
            var pump = false
            synchronized(this) {
                if (runtime.cost === admittedCost) runtime.cost = null
                if (closed || generation != runtime.generation) return@synchronized
                runtime.inFlight = false
                if (success) {
                    successes++
                    runtime.acknowledged = payload
                    runtime.acknowledgedAtMs = monotonicMs()
                } else {
                    failures++
                }
                val queued = runtime.queuedLatest
                runtime.queuedLatest = false
                runtime.dirty = queued || !success
                // A success frees capacity for other dirty channels; a queued latest value also pumps
                // after failure, while a lone failed value waits for the ordinary retry/audit cadence.
                pump = success || queued
                updateBacklog()
            }
            admittedCost.outcome(if (success) FeatureCostOutcome.SUCCESS else FeatureCostOutcome.FAILURE).close()
            if (pump) schedule { reconcileDirty() }
        }
        // Unavailable never takes the cadence path (it marks the channel unknown), so an unavailable
        // observation always sends itself; every other admitted payload is a known value.
        val reported = if (observation is Observation.Unavailable) Observation.Unavailable else Observation.Known(payload)
        return {
            try {
                sender(key, reported, completion)
            } catch (_: Exception) {
                completion(false)
            }
        }
    }

    /** Drain only channels already queued/dirty; do not turn ACK completion into a fresh sensor poll. */
    fun reconcileDirty() {
        val keys = synchronized(this) {
            if (closed) emptyList() else channels.filterValues { it.dirty && !it.inFlight }.keys.toList()
        }
        keys.forEach(reconcile)
    }

    /**
     * Invalidate every acknowledgement from the previous broker connection. A QoS acknowledgement is
     * evidence about the connection that produced it, not permission to suppress publication forever:
     * a replacement broker/session may have no retained copy. Incrementing each generation also makes
     * completions from the superseded connection harmless if they arrive after reconnect.
     */
    @Synchronized
    fun markAllDirty() {
        if (closed) return
        channels.values.forEach {
            it.cost?.outcome(FeatureCostOutcome.CANCELLED)?.close()
            it.cost = null
            it.generation++
            it.acknowledged = null
            it.acknowledgedAtMs = null
            it.sent = null
            it.dirty = true
            it.inFlight = false
            it.queuedLatest = false
        }
        updateBacklog()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        channels.values.forEach {
            it.cost?.outcome(FeatureCostOutcome.CANCELLED)?.close()
            it.cost = null
            it.generation++
            it.inFlight = false
            it.queuedLatest = false
            it.dirty = false
        }
        featureCosts.setBacklog(FeatureCostOperation.MQTT_STATE_OUTBOX, 0)
    }

    data class Status(
        val channels: Int,
        val dirty: Int,
        val inFlight: Int,
        val unknown: Int,
        val successes: Long,
        val failures: Long,
        val pending: List<String>,
    )

    @Synchronized
    fun status(): Status = Status(
        channels = channels.size,
        dirty = channels.values.count { it.dirty },
        inFlight = channels.values.count { it.inFlight },
        unknown = channels.values.count { it.unknown },
        successes = successes,
        failures = failures,
        pending = channels.filterValues { it.inFlight }.map { (key, it) ->
            "$key:${((System.currentTimeMillis() - it.sentAtMs).coerceAtLeast(0) / 1000)}s"
        },
    )

    /** Must be called under this monitor. */
    private fun updateBacklog() {
        featureCosts.setBacklog(
            FeatureCostOperation.MQTT_STATE_OUTBOX,
            channels.values.count { it.queuedLatest || it.dirty && !it.inFlight },
        )
    }

    companion object {
        private const val MAX_IN_FLIGHT = 4

        fun numericDeadband(deadband: Double): (String, String) -> Boolean = { acknowledged, observed ->
            val old = acknowledged.toDoubleOrNull()
            val new = observed.toDoubleOrNull()
            if (old != null && old.isFinite() && new != null && new.isFinite()) {
                kotlin.math.abs(new - old) < deadband
            } else {
                acknowledged == observed
            }
        }
    }
}
