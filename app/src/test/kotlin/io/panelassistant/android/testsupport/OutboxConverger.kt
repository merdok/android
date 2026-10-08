package io.panelassistant.android.testsupport

import io.panelassistant.android.metrics.FeatureCostRegistry
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.mqtt.MqttStateOutbox
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.state.StateSink
import io.panelassistant.android.util.MonotonicDeadline

/**
 * A state core with MQTT's outbox attached, wired the way the bridge wires them: a channel's publication
 * policy is routed to the outbox before the core registers it, and a native-only channel has none.
 */
internal class OutboxConverger(
    sender: StateSink,
    schedule: (() -> Unit) -> Unit = { it() },
    featureCosts: FeatureCostRegistry = FeatureCosts.registry,
    monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    onObservation: ((String, StateConverger.Observation.Reportable) -> Unit)? = null,
) {
    data class Channel(
        val key: String,
        val observe: () -> StateConverger.Observation,
        val equivalent: (String, String) -> Boolean = String::equals,
        val refreshEligible: (String) -> Boolean = { true },
        val maxSilenceMs: Long? = null,
        val nativeOnly: Boolean = false,
    )

    val core = StateConverger()
    val outbox = MqttStateOutbox(sender, { key -> core.reconcile(key) }, schedule, featureCosts, monotonicMs)

    init {
        if (onObservation != null) core.addSink { channel, observation, _ -> onObservation(channel, observation) }
        core.attach(outbox)
    }

    fun register(channel: Channel) {
        if (!channel.nativeOnly) {
            outbox.route(channel.key, MqttStateOutbox.Policy(channel.equivalent, channel.refreshEligible, channel.maxSilenceMs))
        }
        core.register(StateConverger.Channel(channel.key, channel.observe))
    }

    fun reconcile(key: String, force: Boolean = false, admit: () -> Boolean = { true }) = core.reconcile(key, force, admit)
    fun reconcileAll(force: Boolean = false) = core.reconcileAll(force)
    fun reconcileDirty() = outbox.reconcileDirty()
    fun markAllDirty() = outbox.markAllDirty()
    fun status() = outbox.status()
    fun keys() = core.keys()
    fun close() = core.close()
    fun closeAndDrain(deadline: MonotonicDeadline) = core.closeAndDrain(deadline)
}
