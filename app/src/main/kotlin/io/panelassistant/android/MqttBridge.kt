package io.panelassistant.android

import io.panelassistant.android.state.SoftwareUpdates
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.AutoBrightnessController
import io.panelassistant.android.control.BootChimeController
import io.panelassistant.android.control.BrightnessController
import io.panelassistant.android.control.CpuController
import io.panelassistant.android.control.LedEffectController
import io.panelassistant.android.control.LedCommandPolicy
import io.panelassistant.android.control.PowerSafetyMutationPolicy
import io.panelassistant.android.hardware.LedEffects
import io.panelassistant.android.util.BrokerEndpoint
import io.panelassistant.android.util.LatestDispatcher
import io.panelassistant.android.util.submit
import io.panelassistant.android.util.HaLink
import io.panelassistant.android.control.NavbarController
import io.panelassistant.android.control.NavbarModeApplyOutcome
import io.panelassistant.android.control.NavbarModeApplyStatus
import io.panelassistant.android.control.NavigateController
import io.panelassistant.android.control.RelayController
import io.panelassistant.android.control.ScreenController
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.control.WatchdogController
import io.panelassistant.android.control.TouchSoundController
import io.panelassistant.android.control.VolumeController
import io.panelassistant.android.control.ZigbeeController
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.Validation
import io.panelassistant.android.hardware.LedController
import io.panelassistant.android.hardware.NoOpLedController
import io.panelassistant.android.input.ButtonBus
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.panelassistant.PanelAssistantChannelCatalog
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.sensors.ProximityReportGate
import io.panelassistant.android.storage.StorageHealthRuntime
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.mqtt.HiveMqTransport
import io.panelassistant.android.mqtt.MqttCallbacks
import io.panelassistant.android.mqtt.MqttConnectionLease
import io.panelassistant.android.mqtt.MqttConnectConfig
import io.panelassistant.android.mqtt.MqttFinalPublish
import io.panelassistant.android.metrics.PanelMetrics
import io.panelassistant.android.control.WifiDiagnosticDemand
import io.panelassistant.android.control.WifiDiagnosticSnapshot
import io.panelassistant.android.control.WifiOutageCounts
import io.panelassistant.android.mqtt.MqttTransport
import io.panelassistant.android.mqtt.MqttConnectionGeneration
import io.panelassistant.android.mqtt.MqttFamilyPreference
import io.panelassistant.android.util.AddressFamilyPolicy
import io.panelassistant.android.mqtt.MqttRoutePlanner
import io.panelassistant.android.mqtt.classifyDisconnect
import io.panelassistant.android.mqtt.mqttFamilyBrokerIdentity
import io.panelassistant.android.mqtt.AuthRecovery
import io.panelassistant.android.mqtt.isAuthRecoveryState
import io.panelassistant.android.mqtt.SoftwareCommandOutcome
import io.panelassistant.android.state.SoftwareComponent
import io.panelassistant.android.mqtt.SoftwareDiscoveryShape
import io.panelassistant.android.mqtt.SoftwareDiscoveryStep
import io.panelassistant.android.mqtt.SoftwareUpdateEntities
import io.panelassistant.android.state.SoftwareUpdateInputs
import io.panelassistant.android.state.SoftwareUpdateSources
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.Json
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.RetirableMutationGate
import io.panelassistant.android.util.ServiceRuntimeOwner
import io.panelassistant.android.util.interruptAndJoin
import io.panelassistant.android.util.shutdownNowAndAwait
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import java.util.Locale
import java.util.WeakHashMap
import kotlin.coroutines.resume
import org.json.JSONObject


internal fun hiddenReadOnlyStateTopic(key: String, panel: String): String? =
    SettingsRegistry.spec(key)?.ha?.takeIf { it.readOnly }?.stateTopic(panel)

internal const val MQTT_MEASUREMENT_REFRESH_AFTER_ACK_MS = 5L * 60L * 1_000L

/** A finite opt-in threshold keeps refreshes on graphable measurements rather than every state topic. */
internal fun mqttMeasurementRefreshAfterAckMs(key: String): Long? =
    SettingsRegistry.spec(key)?.ha
        ?.takeIf { it.periodicRefresh }
        ?.let { MQTT_MEASUREMENT_REFRESH_AFTER_ACK_MS }

internal fun mqttMeasurementPayloadIsRefreshable(payload: String): Boolean =
    payload.toDoubleOrNull()?.isFinite() == true


internal data class AutoSleepMqttPublication(
    val topic: String,
    val payload: String,
    val retain: Boolean = true,
)

/** Discovery availability for an entity that is unavailable both when the panel is offline and when its
 *  own precondition does not hold — `all` mode, so either topic going offline is enough. */
internal fun dualAvailabilityFragment(
    panelAvailabilityTopic: String,
    entityAvailabilityTopic: String,
): String = """"availability":[{"topic":"$panelAvailabilityTopic","payload_available":"online","payload_not_available":"offline"},{"topic":"$entityAvailabilityTopic","payload_available":"online","payload_not_available":"offline"}],"availability_mode":"all""""

internal fun autoSleepAvailabilityFragment(
    panelAvailabilityTopic: String,
    policyAvailabilityTopic: String,
): String = dualAvailabilityFragment(panelAvailabilityTopic, policyAvailabilityTopic)

/**
 * The device identifiers one discovery payload publishes, in order.
 *
 * Home Assistant matches a device on ANY identifier in the set and UNIONS what it did not already
 * hold, so every member of this list is a permanent claim on whichever device it reaches.
 *
 * - `ha-paneld-<panelId>` is the historical primary and is always present: it is what an existing
 *   registration matches on across an upgrade, which is what keeps the user's customisation.
 * - `ha-paneld-uid-<deviceUid>` is this installation's minted identity. It is what makes a later
 *   panel_id change re-attach to the same device rather than mint a duplicate.
 * - `ha-paneld-aid-<androidId>` is the retired identifier from #155 and is emitted ONLY while
 *   [Config.legacyAidBridgePending] — that is, at most once, by an installation that may already be
 *   registered under it. It is cloned across a factory image, so publishing it routinely is what
 *   merged whole fleets into one device, and republishing it would re-merge them the moment a
 *   merged device is deleted.
 *
 * A blank member is omitted rather than published as a bare prefix: `ha-paneld-uid-` would itself be
 * a shared identifier, and would merge every panel that could not read its own.
 */
internal fun mqttDeviceIdentifiers(
    panelId: String,
    deviceUid: String,
    legacyAndroidId: String,
): List<String> = buildList {
    add("ha-paneld-$panelId")
    deviceUid.trim().takeIf(String::isNotEmpty)?.let { add("ha-paneld-uid-$it") }
    legacyAndroidId.trim().takeIf(String::isNotEmpty)?.let { add("ha-paneld-aid-$it") }
}


internal fun autoSleepMqttPublications(
    panel: String,
    exposed: Boolean,
    snapshot: AutoSleepActivitySnapshot,
): List<AutoSleepMqttPublication> {
    val stateTopic = "ha-paneld/$panel/auto_sleep_activity/state"
    val attributesTopic = "ha-paneld/$panel/auto_sleep_activity/attributes"
    val availabilityTopic = "ha-paneld/$panel/auto_sleep_activity/availability"
    if (!exposed) return listOf(
        AutoSleepMqttPublication(stateTopic, ""),
        AutoSleepMqttPublication(attributesTopic, ""),
        AutoSleepMqttPublication(availabilityTopic, ""),
    )
    val projection = autoSleepActivityProjection(snapshot)
    val state = AutoSleepMqttPublication(stateTopic, projection.state)
    val attributes = AutoSleepMqttPublication(attributesTopic, projection.attributes)
    val availability = AutoSleepMqttPublication(availabilityTopic, projection.availability)
    return if (snapshot.policyHealthy) listOf(state, attributes, availability)
    else listOf(availability, state, attributes)
}

internal fun cameraSnapshotUrlTopic(panel: String): String = "ha-paneld/$panel/camera_snapshot/url"

internal fun cameraSnapshotAvailabilityTopic(panel: String): String =
    "ha-paneld/$panel/camera_snapshot/availability"

/** The snapshot image entity's discovery payload. Built here rather than inline in the announcement so
 * its shape is a fact a test can read: a URL topic, never an image topic, because image bytes on the
 * broker would be a frame stored outside the panel, which the camera contract forbids. */
internal fun cameraSnapshotDiscoveryJson(panel: String, availJson: String, deviceJson: String): String =
    """{"name":"Camera snapshot","object_id":"${panel}_camera_snapshot","unique_id":"${panel}_camera_snapshot","url_topic":"${cameraSnapshotUrlTopic(panel)}","icon":"mdi:camera",$availJson,$deviceJson}"""

/** One retained publication for the snapshot image entity. Retained because Home Assistant must find the
 * URL and the availability again after its own restart, without the panel having to notice. */
internal data class CameraSnapshotPublication(val topic: String, val payload: String)

/**
 * What the snapshot image entity publishes for one observation of the camera surface.
 *
 * The URL topic never carries an empty payload while the entity exists: Home Assistant validates every
 * message on it as a URL and logs an error for anything else, so "the camera is off" is said on the
 * availability topic instead of by clearing the URL. Home Assistant drops its cached frame on every
 * message, so republishing the URL is what makes an open card fetch a new one; [refreshUrl] is therefore
 * asked only on a fresh enable and on announcement, and never on a timer, because a periodic republish
 * would turn a card somebody left open into a schedule the panel chose.
 *
 * [announced] false means this profile declares no camera and the entity was not published. The retained
 * URL is then cleared and availability goes offline, so a panel that moved to camera-less hardware leaves
 * no readable address behind for the entity the discovery prune is removing.
 */
/** The `image` discovery payload for the camera snapshot: the discovery document while the entity is
 *  announced, and the empty retained tombstone while it is not. The withdrawal has to be published
 *  rather than skipped, because a config topic written earlier in the same bridge generation stays in
 *  Home Assistant until something overwrites it. Pairs with [cameraSnapshotPublications], which
 *  withdraws the retained URL and availability for the same transition. */
internal fun cameraSnapshotConfigPayload(announced: Boolean, discoveryJson: () -> String): String =
    if (announced) discoveryJson() else ""

internal fun cameraSnapshotPublications(
    panel: String,
    announced: Boolean,
    enabled: Boolean,
    url: String?,
    refreshUrl: Boolean,
): List<CameraSnapshotPublication> {
    val urlTopic = cameraSnapshotUrlTopic(panel)
    val availabilityTopic = cameraSnapshotAvailabilityTopic(panel)
    if (!announced) return listOf(
        CameraSnapshotPublication(availabilityTopic, "offline"),
        CameraSnapshotPublication(urlTopic, ""),
    )
    val live = url?.takeIf { enabled && it.isNotBlank() }
    val availability = CameraSnapshotPublication(
        availabilityTopic, if (live != null) "online" else "offline",
    )
    // The URL precedes the online edge for the reason proximity's retained state does: Home Assistant
    // must not see the entity become available while the topic still holds an earlier address.
    return if (refreshUrl && live != null) {
        listOf(CameraSnapshotPublication(urlTopic, live), availability)
    } else listOf(availability)
}


/** Sticky retirement evidence. Mutation owners must drain synchronously before shared hardware can be
 * dismantled; retained final publication and transport disconnect may finish on the transport owner. */
internal class MqttRetirement {
    val ownersDrained = CompletableFuture<Boolean>()
    val finalization = CompletableFuture<Unit>()
}


/**
 * MQTT bridge — the single uniform control API across the fleet. Publishes Home Assistant
 * MQTT-discovery configs so every panel exposes identical entities (the per-hardware HAL is hidden
 * behind them), subscribes to the command topics, and dispatches to the controllers. Best-effort:
 * disabled silently when no broker is configured (the HTTP /play surface works standalone).
 *
 * Entities published (per panel):
 * - `light.<panel>_screen`  — brightness + on/off (on=wake, off=sleep). HA-driven; no on-device loop.
 * - `light.<panel>_led`     — RGB (only if [led].available()).
 * - `text.<panel>_navigate` — URL navigate.
 * - `event.<panel>_button`  — hardware button events (only if [buttonsEnabled]).
 * - `media_player.<panel>_paneld` — TTS/announce (HTTP /play does the work).
 */
/** One atomic broker-progress observation for the watchdog. The connection generation identifies the
 * concrete transport client that produced the timestamp, so a late ACK from a retiring client cannot
 * prove that its replacement connected. */
internal data class MqttBrokerProgress(
    val lastOkMs: Long,
    val connectionGeneration: Long?,
) {
    /** Preserve the liveness timestamp but expose its provenance only while that concrete connection is
     * still current. A queued fallback can otherwise detach an old auto-reconnect after it ACKed. */
    fun forCurrentConnection(currentGeneration: Long?): MqttBrokerProgress =
        if (currentGeneration != null && connectionGeneration == currentGeneration) this
        else copy(connectionGeneration = null)
}

internal data class MqttWatchdogObservation(
    val state: String,
    val progress: MqttBrokerProgress,
    val holdSelectedFamily: Boolean,
    /** Sticky for this bridge runtime; only online+state readiness may set it. */
    val applicationReadyEver: Boolean,
    val announcementProcessRecoveryAvailable: Boolean,
    val recoveryTicket: MqttRecoveryTicket,
)

/** Immutable proof that queued recovery still targets the exact stale bridge epoch it observed. */
internal data class MqttRecoveryTicket(
    val recoveryEpoch: Long,
    val familyConnectAttempt: Long,
    val stagedBrokerIdentity: String? = null,
)

internal enum class MqttRecoveryOutcome { REBUILT, NO_LONGER_NEEDED, NOT_ADMITTED }

private data class MqttSelectedFamilyRoute(
    val brokerIdentity: String,
    val connectAttempt: Long,
    val preferIpv4: Boolean,
)

enum class MqttAddressFamily(val label: String) { IPV4("IPv4"), IPV6("IPv6") }

internal fun mqttAddressFamily(address: java.net.InetAddress?): MqttAddressFamily? = when (address) {
    is java.net.Inet4Address -> MqttAddressFamily.IPV4
    is java.net.Inet6Address -> MqttAddressFamily.IPV6
    else -> null
}

internal fun mqttTransportLabel(tls: Boolean, family: MqttAddressFamily?): String =
    (if (tls) "TLS" else "TCP") + family?.let { "/${it.label}" }.orEmpty()

internal fun mqttAnnouncementRecoveryIdentity(
    brokerIdentity: String,
    panelId: String,
    profileIdentity: String,
    user: String,
    password: String,
    addressFamilyPolicy: String = SettingsRegistry.DEFAULT_ADDRESS_FAMILY,
    buildVersionCode: Int = BuildConfig.VERSION_CODE,
): String {
    // Internal candidates share VERSION_NAME. Every installed build is a legitimate new bounded
    // recovery epoch because it may contain the fix for the wedge that spent the previous token.
    val framed = listOf(
        Config.VERSION,
        buildVersionCode.toString(),
        brokerIdentity,
        panelId,
        profileIdentity,
        user,
        password,
        addressFamilyPolicy,
    )
        .joinToString(separator = "") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256")
        .digest(framed.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal enum class MqttAnnouncementReadinessBudgetResult {
    PRESERVED_PENDING_BOUNDARY,
    REARMED,
    CLEAR_FAILED,
}

/** Late readiness cannot rearm a boundary already committed by this still-running process. */
internal fun reconcileMqttAnnouncementReadinessBudget(
    consumedHere: Boolean,
    clearInheritedBoundary: () -> Boolean,
): MqttAnnouncementReadinessBudgetResult = when {
    consumedHere -> MqttAnnouncementReadinessBudgetResult.PRESERVED_PENDING_BOUNDARY
    clearInheritedBoundary() -> MqttAnnouncementReadinessBudgetResult.REARMED
    else -> MqttAnnouncementReadinessBudgetResult.CLEAR_FAILED
}

internal data class MqttRecoverySnapshot(
    val revision: Long,
    /**
     * Advances ONLY on genuine recovery progress: a new connect attempt, real broker progress
     * (CONNACK/PUBACK), or a consumed claim. [revision] advances on every lifecycle write, so it cannot
     * decide staleness — a black-holed family disconnects roughly every socket-connect bound, and using
     * [revision] made the watchdog's staged alternate-family recovery read as "already recovered" on
     * every entry, roll itself back, and pin the dead family indefinitely.
     */
    val recoveryEpoch: Long,
    val state: String,
    val addressFamily: MqttAddressFamily?,
    val familyConnectAttempt: Long,
    val connectionGeneration: Long?,
    val brokerProgress: MqttBrokerProgress,
    val applicationReadyEver: Boolean,
)

/** One atomic, directly testable authority for watchdog observation and stale-recovery admission. */
internal class MqttRecoveryAuthority(
    initialState: String,
    initialProgress: MqttBrokerProgress,
) {
    enum class Claim { CLAIMED, STALE_SAME_ATTEMPT, CONSUMED_BY_NEW_ATTEMPT }
    enum class Status { CURRENT, STALE_SAME_ATTEMPT, CONSUMED_BY_NEW_ATTEMPT }

    private val current = AtomicReference(
        MqttRecoverySnapshot(
            revision = 0L,
            recoveryEpoch = 0L,
            state = initialState,
            addressFamily = null,
            familyConnectAttempt = 0L,
            connectionGeneration = null,
            brokerProgress = initialProgress,
            applicationReadyEver = false,
        ),
    )

    fun snapshot(): MqttRecoverySnapshot = current.get()

    fun ticket(
        snapshot: MqttRecoverySnapshot,
        stagedBrokerIdentity: String? = null,
    ): MqttRecoveryTicket = MqttRecoveryTicket(
        snapshot.recoveryEpoch,
        snapshot.familyConnectAttempt,
        stagedBrokerIdentity,
    )

    fun isCurrent(ticket: MqttRecoveryTicket): Boolean = snapshot().matches(ticket)

    fun status(ticket: MqttRecoveryTicket): Status = snapshot().let { observed ->
        when {
            observed.familyConnectAttempt != ticket.familyConnectAttempt ->
                Status.CONSUMED_BY_NEW_ATTEMPT
            observed.matches(ticket) -> Status.CURRENT
            else -> Status.STALE_SAME_ATTEMPT
        }
    }

    fun updateLifecycle(
        state: String,
        connectionGeneration: Long?,
        applicationReadyEver: Boolean,
    ) = update { previous ->
        previous.copy(
            revision = previous.revision + 1L,
            state = state,
            connectionGeneration = connectionGeneration,
            applicationReadyEver = applicationReadyEver,
        )
    }

    fun updateLifecycleWithAddressFamily(
        state: String,
        connectionGeneration: Long?,
        applicationReadyEver: Boolean,
        addressFamily: MqttAddressFamily?,
    ) = update { previous ->
        previous.copy(
            revision = previous.revision + 1L,
            state = state,
            addressFamily = addressFamily,
            connectionGeneration = connectionGeneration,
            applicationReadyEver = applicationReadyEver,
        )
    }

    fun beginConnectAttempt(state: String, connectionGeneration: Long?): Long {
        while (true) {
            val previous = current.get()
            val attempt = previous.familyConnectAttempt + 1L
            val updated = previous.copy(
                revision = previous.revision + 1L,
                recoveryEpoch = previous.recoveryEpoch + 1L,
                state = state,
                addressFamily = null,
                familyConnectAttempt = attempt,
                connectionGeneration = connectionGeneration,
            )
            if (current.compareAndSet(previous, updated)) return attempt
        }
    }

    fun updateAddressFamily(connectAttempt: Long, addressFamily: MqttAddressFamily?): Boolean {
        while (true) {
            val previous = current.get()
            if (previous.familyConnectAttempt != connectAttempt) return false
            val updated = previous.copy(
                revision = previous.revision + 1L,
                addressFamily = addressFamily,
            )
            if (current.compareAndSet(previous, updated)) return true
        }
    }

    fun updateProgress(progress: MqttBrokerProgress) = update { previous ->
        previous.copy(
            revision = previous.revision + 1L,
            recoveryEpoch = previous.recoveryEpoch + 1L,
            brokerProgress = progress,
        )
    }

    fun claim(ticket: MqttRecoveryTicket): Claim {
        while (true) {
            val observed = current.get()
            if (observed.familyConnectAttempt != ticket.familyConnectAttempt) {
                return Claim.CONSUMED_BY_NEW_ATTEMPT
            }
            if (!observed.matches(ticket)) return Claim.STALE_SAME_ATTEMPT
            val consumed = observed.copy(
                revision = observed.revision + 1L,
                recoveryEpoch = observed.recoveryEpoch + 1L,
            )
            if (current.compareAndSet(observed, consumed)) return Claim.CLAIMED
        }
    }

    private fun update(transform: (MqttRecoverySnapshot) -> MqttRecoverySnapshot) {
        while (true) {
            val previous = current.get()
            if (current.compareAndSet(previous, transform(previous))) return
        }
    }

    private fun MqttRecoverySnapshot.matches(ticket: MqttRecoveryTicket): Boolean =
        recoveryEpoch == ticket.recoveryEpoch && familyConnectAttempt == ticket.familyConnectAttempt
}

/**
 * Cross-layer CONNACK boundary: broker progress becomes observable before the lifecycle can become
 * `announcing`. This makes `announcing`/`connected` with a never-recorded first ACK unreachable from
 * the production connection-event path; no speculative first-ACK timer is needed.
 */
internal fun recordMqttConnack(
    authority: MqttRecoveryAuthority,
    connectionGeneration: Long,
    applicationReadyEver: Boolean,
    addressFamily: MqttAddressFamily?,
    nowMs: Long,
): MqttRecoverySnapshot {
    authority.updateProgress(
        MqttBrokerProgress(nowMs.coerceAtLeast(1L), connectionGeneration),
    )
    authority.updateLifecycleWithAddressFamily(
        state = "announcing",
        connectionGeneration = connectionGeneration,
        applicationReadyEver = applicationReadyEver,
        addressFamily = addressFamily,
    )
    return authority.snapshot()
}

internal fun reconcileRejectedMqttRecovery(
    authority: MqttRecoveryAuthority,
    ticket: MqttRecoveryTicket,
    cancelStaged: () -> Boolean,
): Boolean = when (authority.status(ticket)) {
    MqttRecoveryAuthority.Status.CONSUMED_BY_NEW_ATTEMPT -> true
    MqttRecoveryAuthority.Status.CURRENT,
    MqttRecoveryAuthority.Status.STALE_SAME_ATTEMPT -> cancelStaged()
}

/** One exact app/transport generation pair for off-event-loop connection announcement work. */
internal data class MqttConnectAnnouncement(
    val generation: Long,
    val connection: MqttConnectionLease,
)

/** Latest transport lifecycle event. Callbacks only submit here, so HiveMQ's event loop never waits on
 * the bridge's fair mutation gate (which can legitimately be held across slower hardware work). */
internal sealed interface MqttConnectionEvent {
    data class Connected(
        val connection: MqttConnectionLease,
        val addressFamily: MqttAddressFamily? = null,
    ) : MqttConnectionEvent
    data class Disconnected(
        val connection: MqttConnectionLease?,
        val state: String,
        val causeMessage: String?,
    ) : MqttConnectionEvent
}

internal data class SequencedMqttConnectionEvent(
    val sequence: Long,
    val event: MqttConnectionEvent,
)

internal class MqttConnectionEventDispatcher(
    perform: (SequencedMqttConnectionEvent) -> Unit,
) : AutoCloseable {
    private val sequence = AtomicLong()
    private val worker = LatestDispatcher.singleSlot("mqtt-connection-event", perform)

    fun submit(event: MqttConnectionEvent): LatestDispatcher.Admission {
        val submitted = SequencedMqttConnectionEvent(sequence.incrementAndGet(), event)
        return worker.submit(submitted)
    }

    /** Invalidate queued callbacks from a client an explicit fresh start is about to replace. */
    fun supersede() {
        sequence.incrementAndGet()
    }

    fun isCurrent(event: SequencedMqttConnectionEvent): Boolean = sequence.get() == event.sequence
    override fun close() { worker.closeAndJoin(0L) }
    fun closeAndJoin(timeoutMs: Long): Boolean = worker.closeAndJoin(timeoutMs)
}

/** Heavy connect work has its own conflated owner so a newer lifecycle event remains observable even if
 * one client library call never returns. Tests use this exact production boundary against a broker. */
internal class MqttConnectAnnouncementDispatcher(
    perform: (MqttConnectAnnouncement) -> Unit,
) : AutoCloseable {
    private val worker = LatestDispatcher.singleSlot("mqtt-connect-announcement", perform)

    fun submit(announcement: MqttConnectAnnouncement): LatestDispatcher.Admission = worker.submit(announcement)
    override fun close() { worker.closeAndJoin(0L) }
    fun closeAndJoin(timeoutMs: Long): Boolean = worker.closeAndJoin(timeoutMs)
}

internal fun mqttAutomaticReconnectAllowed(
    classifiedState: String,
    admission: LatestDispatcher.Admission,
): Boolean = classifiedState != "auth-failed" && admission != LatestDispatcher.Admission.CLOSED

/** A current disconnect still owns state when its connected event was conflated before bridge admission. */
internal fun mqttDisconnectOwnsBridgeState(
    activeConnection: MqttConnectionLease?,
    disconnectedConnection: MqttConnectionLease?,
): Boolean = activeConnection == null || activeConnection === disconnectedConnection

/** Two independent broker proofs are required before an MQTT session is application-ready. */
internal class MqttAnnouncementReadiness {
    private var announcement: MqttConnectAnnouncement? = null
    private var onlineAcknowledged = false
    private var stateAcknowledged = false
    private var completed = false

    @Synchronized
    fun begin(value: MqttConnectAnnouncement) {
        announcement = value
        onlineAcknowledged = false
        stateAcknowledged = false
        completed = false
    }

    @Synchronized
    fun acknowledgeOnline(value: MqttConnectAnnouncement): MqttConnectAnnouncement? {
        if (announcement != value || completed) return null
        onlineAcknowledged = true
        return completeIfReady()
    }

    @Synchronized
    fun acknowledgeState(generation: Long): MqttConnectAnnouncement? {
        if (announcement?.generation != generation || completed) return null
        stateAcknowledged = true
        return completeIfReady()
    }

    @Synchronized
    fun clear() {
        announcement = null
        onlineAcknowledged = false
        stateAcknowledged = false
        completed = false
    }

    private fun completeIfReady(): MqttConnectAnnouncement? {
        if (!onlineAcknowledged || !stateAcknowledged) return null
        completed = true
        return announcement
    }
}

internal class MqttBridge(
    private val config: Config,
    // The runtime generation's state owner: MQTT is one more destination for its channels.
    private val core: PanelStateCore,
    // The runtime generation's ordered command authority: MQTT commands are one more origin.
    private val commands: PanelCommandDispatcher,
    private val led: LedController,
    private val adb: AdbController,
    private val buttonsEnabled: Boolean,
    // Panel has hardware buttons instrumented via the daemon (evdev) — publish the event entity even
    // when the accessibility key capture is off (e.g. the WF1589T power button).
    private val hasEvdevButtons: Boolean,
    // Panel carries a CHT8305 room temp/humidity chip (daemon-read) — gates the opt-in Room sensors.
    private val hasCht8305: Boolean,
    private val hasButtonBacklight: Boolean,
    // Evaluated for every discovery announcement so DHCP/address changes never leave HA's device-page
    // Visit link pinned to the address captured when the service process started.
    private val configUrl: () -> String? = { null },
    // The panel's own snapshot endpoint, as Home Assistant must dial it. Evaluated per announcement for
    // the same reason as configUrl — a DHCP move must not leave a stale address published. Null when no
    // LAN address is known, which makes the snapshot image entity unavailable rather than wrong.
    private val cameraSnapshotUrl: () -> String? = { null },
    // Resolves HA's LAN IP via mDNS to default the broker when none is configured (injected by the
    // service, wired to MdnsAdvertiser). Returns null if HA isn't found / mDNS unavailable.
    private val discoverHaIp: () -> String? = { null },
    // HA's advertised base URL (scheme+host+port) from zeroconf TXT — for the "Open in HA" device link,
    // so we never guess a port/scheme. Null if HA isn't found / advertises no URL.
    private val discoverHaUrl: (configuredBroker: String) -> String? = { null },
    // Inputs for the MQTT update entities, sampled on this bridge's workers: installed versions,
    // same-policy catalog targets, install-lane ownership and Panel Assistant ownership. Install
    // capability comes from this bridge's own capability snapshot. Null publishes no update entities.
    private val softwareUpdateSources: (() -> SoftwareUpdateSources)? = null,
    // The app-asset source catalogue in English. The service owns Android Context; the MQTT bridge
    // receives only the lookup so the device-facing title and guidance do not duplicate public copy.
    private val migrationNoticeEnglish: (String) -> String,
    private val zigbeeHealth: () -> ZigbeeHealthSnapshot = { ZigbeeHealthSnapshot() },
    // A panel-id replaced by reconfiguration. Its discovery and availability are cleared by the NEW
    // connection, so cleanup cannot be lost when the old client is detached or the broker was offline.
    private val stalePanelId: String? = null,
    // Immutable id of the active profile revision (for example "panel.example@<sha256>"). A profile
    // switch can remove capabilities without changing the app version, so it must trigger the same
    // stale-discovery pruning as a core upgrade. Empty preserves the pre-profile marker for callers
    // that have not opted into runtime profiles.
    private val profileIdentity: String = "",
    // Event types declared by the active runtime profile. Discovery must advertise every value this
    // bridge can publish, not only the historical built-in key list.
    private val profileButtonEventTypes: Set<String> = emptySet(),
    // Immutable connection/advertisement identity for this concrete bridge generation. Config remains
    // live for settings that can be re-projected without replacing the broker connection.
    private val runtimePanelId: String = config.panelId,
    private val runtimeFriendlyName: String = config.friendlyName,
    private val runtimeBroker: String = config.mqttBroker,
    private val runtimeMqttUser: String = config.mqttUser,
    private val runtimeMqttPassword: String = config.mqttPassword,
    private val runtimeMqttAddressFamily: String = config.addressFamily,
    private val transport: MqttTransport = HiveMqTransport(),
) {

    /** Broker actually in use — configured, or auto-discovered as `tcp://<ha-ip>:1883`; "" if none. */
    var activeBroker: String = ""
        private set
    // The configured broker string at construction time. Config is a live mutable store, so the service
    // needs this snapshot to decide whether reconfigure is retiring a genuinely different broker.
    internal val configuredBroker: String = runtimeBroker.trim()

    /**
     * Whether THIS bridge generation was built from the given credentials. The bridge swap after a
     * config save is asynchronous, so between the commit and the swap any state this instance reports
     * was earned by the OLD credentials — on a fresh panel that state is the anonymous discovery
     * connect's auth rejection, which the setup wizard then misattributed to the credentials the user
     * had just typed correctly (first hardware walks, 2-for-2). Callers use this to report a stale
     * generation as "connecting" instead of parroting a verdict the new credentials never earned.
     */
    internal fun servesMqttConfiguration(
        broker: String,
        user: String,
        password: String,
        addressFamily: String,
    ): Boolean = runtimeBroker.trim() == broker.trim() && runtimeMqttUser == user &&
        runtimeMqttPassword == password && runtimeMqttAddressFamily == addressFamily

    /** Whether the active connection uses TLS (a ssl:///mqtts:// broker URL). Surfaced on the info page
     *  + /diag so a TLS setup is visible. */
    @Volatile var tlsActive: Boolean = false
        private set

    /** Live connection state for the UI, so an auth failure reads differently from "unreachable":
     *  connected | announcing | auth-failed | unreachable | connecting | discovering | disabled. */
    private val recoveryAuthority = MqttRecoveryAuthority("disabled", MqttBrokerProgress(0L, null))
    val state: String get() = recoveryAuthority.snapshot().state
    private val lifecycle = RetirableMutationGate()
    private val retirement = AtomicReference<MqttRetirement?>()
    private val connectionGeneration = MqttConnectionGeneration()
    private val familyRecoveryLock = Any()
    @Volatile private var selectedFamilyRoute: MqttSelectedFamilyRoute? = null
    @Volatile private var activeConnection: MqttConnectionLease? = null
    private val adbReassertWorker = LatestDispatcher.singleSlot<Unit>("mqtt-adb-reassert", consume = { adb.reassert() })
    private val discoveryAnnouncementLock = Any()

    fun isConnected(): Boolean = state == "connected"
    internal fun heartbeatConnectionGeneration(): Long? =
        if (lifecycle.isOpen()) connectionGeneration.currentOrNull() else null
    internal fun isCurrentHeartbeatConnection(generation: Long): Boolean =
        lifecycle.isOpen() && connectionGeneration.isCurrent(generation)

    /** Monotonic timestamp (elapsedRealtime) of the last publish that the broker actually ACKed, plus
     *  every (re)connect. This is a TRUE liveness signal — unlike [state]/[isConnected], which reflect
     *  only HiveMQ's own connect/disconnect callbacks and stay "connected" on a half-open (CLOSE-WAIT)
     *  socket the broker already dropped. The service watchdog reconnects when this goes stale. 0 until
     *  the first successful connect. */
    private val announcementBudgetLock = Any()
    @Volatile private var announcementRecoveryIdentity: String? = null
    @Volatile private var announcementProcessRecoveryAvailable = false
    val lastOkMs: Long get() = recoveryAuthority.snapshot().brokerProgress.lastOkMs
    internal fun watchdogObservation(): MqttWatchdogObservation {
        val authority = recoveryAuthority.snapshot()
        // Application readiness clears the volatile hold only after both required ACKs. A concurrent
        // hold transition is conservative; every recovery-mutating field comes from one atomic view.
        val holdSelectedFamily = familyPreference.awaitingProgress
        val progress = authority.brokerProgress
            .forCurrentConnection(authority.connectionGeneration)
        return MqttWatchdogObservation(
            state = authority.state,
            progress = progress,
            holdSelectedFamily = holdSelectedFamily,
            applicationReadyEver = authority.applicationReadyEver,
            announcementProcessRecoveryAvailable = announcementProcessRecoveryAvailable,
            recoveryTicket = recoveryAuthority.ticket(authority),
        )
    }

    /** Lifecycle-gated writer: preserve concurrently published broker progress. */
    private fun publishRecoveryLifecycleState(
        state: String,
        applicationReadyEver: Boolean = recoveryAuthority.snapshot().applicationReadyEver,
    ) {
        recoveryAuthority.updateLifecycle(
            state,
            connectionGeneration.currentOrNull(),
            applicationReadyEver,
        )
    }

    private fun publishRecoveryLifecycleStateWithAddressFamily(
        state: String,
        addressFamily: MqttAddressFamily?,
        applicationReadyEver: Boolean = recoveryAuthority.snapshot().applicationReadyEver,
    ) {
        recoveryAuthority.updateLifecycleWithAddressFamily(
            state,
            connectionGeneration.currentOrNull(),
            applicationReadyEver,
            addressFamily,
        )
    }

    /** PUBACK/CONNACK writer: update only progress, preserving lifecycle state from the gate owner. */
    private fun publishRecoveryBrokerProgress(progress: MqttBrokerProgress) {
        recoveryAuthority.updateProgress(progress)
    }

    /** Happy-eyeballs family preference for the NEXT connect. One broker-scoped tuple survives the
     *  controlled process boundary, so recovery does not forget its IPv4 fallback and restart into the
     *  same failing IPv6 route. A broker change invalidates the tuple and resets to IPv6-first. */
    private val familyPreference = MqttFamilyPreference(
        load = config::mqttFamilyPreference,
        persist = config::rememberMqttFamilyPreference,
        clear = config::forgetMqttFamilyPreference,
        onClearFailure = { Log.w(TAG, "could not invalidate the previous broker's MQTT family preference") },
    )
    @Volatile private var lastPublishedConfigUrl: String? = null
    // Whether the last announcement actually published the snapshot image entity. A panel whose profile
    // declares no camera has no such entity, so later switch or availability publishes would be retained
    // strings on a topic nothing reads.
    @Volatile private var cameraSnapshotAnnounced = false

    private val authRecovery = AuthRecovery(jitter = { base, _ ->
        // Bounded ±20% jitter prevents a fleet-wide broker restart becoming a synchronized retry storm.
        (base * (80 + java.util.concurrent.ThreadLocalRandom.current().nextInt(41)) / 100)
    })
    private val authScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mqtt-auth-recovery").apply { isDaemon = true }
    }

    private fun markOk(expectedGeneration: Long? = connectionGeneration.currentOrNull()) {
        val generation = expectedGeneration ?: return
        if (!connectionGeneration.isCurrent(generation)) return
        val progress = MqttBrokerProgress(SystemClock.elapsedRealtime(), generation)
        publishRecoveryBrokerProgress(progress)
    }

    /** Milliseconds since the last broker-ACKed activity, or 0 if never connected (so a not-yet-started
     *  bridge never looks "stale" to the watchdog — the state-based check covers startup). */
    fun msSinceLastOk(): Long = if (lastOkMs == 0L) 0L else SystemClock.elapsedRealtime() - lastOkMs

    /** Public-paste-safe MQTT status — state + broker-ACKed liveness age + address-family preference,
     *  deliberately WITHOUT the broker host (the host stays in the info page's "MQTT" row, which /diag
     *  omits). This is the row a /diag dump needs to answer "is this panel broker-connected?". */
    fun statusPublic(): String {
        val now = SystemClock.elapsedRealtime()
        val recovery = recoveryAuthority.snapshot()
        if (recovery.state == "disabled") return "disabled"
        if (recovery.state == "config-error") return "config-error · invalid or unsupported broker URL"
        val lastOkMs = recovery.brokerProgress.lastOkMs
        val age = if (lastOkMs == 0L) "never" else "${((now - lastOkMs).coerceAtLeast(0) / 1000)}s ago"
        val transport = mqttTransportLabel(tlsActive, recovery.addressFamily)
        val a = authRecovery.snapshot(now)
        val auth = if (a.consecutiveRejects == 0) "auth-ok" else
            "${a.state} · rejects ${a.consecutiveRejects} · attempt ${a.retryAttempt} · next ${a.nextRetryMs?.let { ((it - now).coerceAtLeast(0) / 1000).toString() + "s" } ?: "none"}"
        val lastAuth = a.lastSuccessMs?.let { "${((now - it).coerceAtLeast(0) / 1000)}s ago" } ?: "never"
        val policy = AddressFamilyPolicy.fromConfig(runtimeMqttAddressFamily)
        val familyPolicy = if (policy == AddressFamilyPolicy.AUTOMATIC) {
            "Automatic (next ${if (familyPreference.preferIpv4) "IPv4" else "IPv6"})"
        } else {
            policy.configValue
        }
        return "${recovery.state} · $transport · last-ok $age · last-auth $lastAuth · $auth · family $familyPolicy"
    }

    private val panel = runtimePanelId
    internal val panelId: String get() = panel
    private val availabilityTopic = "ha-paneld/$panel/availability"
    private val migrationNoticeState = "ha-paneld/$panel/panel_assistant_required/state"
    private val migrationNoticeAttributes = "ha-paneld/$panel/panel_assistant_required/attributes"
    private val cmdScreen = "ha-paneld/$panel/screen/set"
    private val cmdLed = "ha-paneld/$panel/led/set"
    private val cmdNavigate = "ha-paneld/$panel/navigate/set"
    private val cmdVolume = "ha-paneld/$panel/volume/set"
    private val cmdReload = "ha-paneld/$panel/reload/set"
    private val cmdHomeDashboard = "ha-paneld/$panel/home_dashboard/set"
    private val stateHomeDashboard = "ha-paneld/$panel/home_dashboard/state"
    private val cmdReboot = "ha-paneld/$panel/reboot/set"
    private val cmdButtons = "ha-paneld/$panel/buttons/set"
    private val stateButtons = "ha-paneld/$panel/buttons/state"
    private val cmdNavbar = "ha-paneld/$panel/navbar/set"
    private val stateNavbar = "ha-paneld/$panel/navbar/state"
    private val cmdWakeOnWave = "ha-paneld/$panel/wake_on_wave/set"
    private val stateWakeOnWave = "ha-paneld/$panel/wake_on_wave/state"
    private val cmdAutoSleep = "ha-paneld/$panel/auto_sleep/set"
    private val stateAutoSleep = "ha-paneld/$panel/auto_sleep/state"
    private val autoSleepAvailabilityTopic = "ha-paneld/$panel/auto_sleep_activity/availability"
    private val cmdTouchSound = "ha-paneld/$panel/touch_sound/set"
    private val stateTouchSound = "ha-paneld/$panel/touch_sound/state"
    private val cmdWatchdog = "ha-paneld/$panel/watchdog/set"
    private val stateWatchdog = "ha-paneld/$panel/watchdog/state"
    private val cmdKiosk = "ha-paneld/$panel/kiosk_lock/set"
    private val stateKiosk = "ha-paneld/$panel/kiosk_lock/state"
    private val cmdUpdateCompanion = "ha-paneld/$panel/update_companion/set"
    private val cmdUpdatePaneld = "ha-paneld/$panel/update_paneld/set"
    private val cmdSilenceBootChime = "ha-paneld/$panel/silence_boot_chime/set"
    private val stateSilenceBootChime = "ha-paneld/$panel/silence_boot_chime/state"
    private val cmdPreventIdleDim = "ha-paneld/$panel/prevent_idle_dim/set"
    private val statePreventIdleDim = "ha-paneld/$panel/prevent_idle_dim/state"
    private val cmdZigbee = "ha-paneld/$panel/zigbee_router/set"
    private val stateZigbee = "ha-paneld/$panel/zigbee_router/state"
    private val cmdCpuGov = "ha-paneld/$panel/cpu_governor/set"
    private val stateCpuGov = "ha-paneld/$panel/cpu_governor/state"
    private val cmdNetAdb = "ha-paneld/$panel/network_adb/set"
    private val stateNetAdb = "ha-paneld/$panel/network_adb/state"
    private val stateScreen = "ha-paneld/$panel/screen/state"
    private val stateLed = "ha-paneld/$panel/led/state"
    private val stateNavigate = "ha-paneld/$panel/navigate/state"
    private val stateVolume = "ha-paneld/$panel/volume/state"
    private val eventButton = "ha-paneld/$panel/button/event"
    private val stateIlluminance = "ha-paneld/$panel/illuminance/state"
    private val stateProximity = "ha-paneld/$panel/proximity/state"
    private val stateProximityLevel = "ha-paneld/$panel/proximity_level/state"
    private val proximityAvailabilityTopic = "ha-paneld/$panel/proximity/availability"
    private val stateTemperature = "ha-paneld/$panel/temperature/state"
    private val stateHumidity = "ha-paneld/$panel/humidity/state"
    private val stateZigbeeHealth = "ha-paneld/$panel/zigbee_gateway_health/state"
    private val attrZigbeeHealth = "ha-paneld/$panel/zigbee_gateway_health/attributes"
    private val stateStorageHealth = "ha-paneld/$panel/storage_health/state"
    private val attrStorageHealth = "ha-paneld/$panel/storage_health/attributes"
    private val attrWifiOutages = "ha-paneld/$panel/diag_wifi_outages_24h/attributes"
    private val cmdAutoBright = "ha-paneld/$panel/auto_brightness/set"
    private val stateAutoBright = "ha-paneld/$panel/auto_brightness/state"
    private val cmdCameraEnabled = "ha-paneld/$panel/camera_enabled/set"
    private val stateCameraEnabled = "ha-paneld/$panel/camera_enabled/state"
    // The snapshot image entity carries a URL rather than image bytes: the camera contract serves frames
    // only over the panel's own listeners to a client that dialled in, and a retained byte payload would
    // leave a frame sitting on the broker. HA stores this URL and fetches it when somebody actually looks
    // at the card, so the camera opens for a viewer and never for a publish.
    private val cameraSnapshotAvailability = cameraSnapshotAvailabilityTopic(panel)

    private data class SoftwareUpdateDiscoveryContext(val availability: String, val device: String)
    // Captured by each discovery pass so a later shape change can republish with the same identity.
    @Volatile private var softwareUpdateDiscovery: SoftwareUpdateDiscoveryContext? = null
    private val softwareUpdateLock = Any()
    // What this bridge generation last told Home Assistant, seeded from the persisted shape. Guarded
    // by softwareUpdateLock.
    private val softwareShapes = HashMap<SoftwareComponent, SoftwareDiscoveryShape?>()

    // Eager ownership removes the extra lazy/not-yet-closed state: every external producer sees the
    // same converger, and retirement can close it before any late callback tries to publish.
    private val announcementReadiness = MqttAnnouncementReadiness()
    // A connected broker can block inside send(). Keep every pump-originated MQTT publication off
    // the shared observation pump; the bounded queue also covers non-converger state topics.
    private val statePublicationWorker = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(64),
        { task -> Thread(task, "mqtt-state-publish").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )
    private val stateOutbox = io.panelassistant.android.mqtt.MqttStateOutbox(
        sender = ::publishStateObservation,
        reconcile = { key -> core.reconcile(key) },
        policyFor = { key -> stateRouteFor(key)?.policy },
        schedule = ::dispatchStateWork,
    )

    /** What MQTT projects beyond the core's state channels, at the point each transition happens. */
    private val stateProjection = object : StateCoreListener {
        override fun onCapabilitiesObserved(observation: CapabilityObservation) {
            if (observation.grew) requestReAnnounce()
        }

        override fun onDescriptionInputsChanged() = requestReAnnounce()

        // Current retained states precede the entity-specific online edge; the clears follow offline.
        override fun onProximityAvailability(available: Boolean) {
            publish(proximityAvailabilityTopic, if (available) "online" else "offline", retain = true)
            if (!available) {
                publish(stateProximity, "", retain = true)
                publish(stateProximityLevel, "", retain = true)
            }
        }

        // The snapshot entity's URL and availability. The URL topic never carries an empty payload: HA
        // validates every message on it as a URL and logs an error for anything else, so "the camera is
        // off" is said on the availability topic instead of by clearing the URL.
        override fun onCameraSnapshot(refreshUrl: Boolean) {
            cameraSnapshotPublications(
                panel = panel,
                announced = cameraSnapshotAnnounced,
                enabled = config.cameraEnabled,
                url = cameraSnapshotUrl(),
                refreshUrl = refreshUrl,
            ).forEach { publish(it.topic, it.payload, retain = true) }
        }

        override fun onZigbeeHealth(snapshot: ZigbeeHealthSnapshot?) {
            lifecycle.runIfOpen(Unit) {
                if (state != "connected") return@runIfOpen
                val current = snapshot ?: zigbeeHealth()
                publish(stateZigbeeHealth, current.state.wireValue, retain = true)
                publish(attrZigbeeHealth, current.attributesJson(), retain = true)
            }
        }

        override fun onAutoSleepActivity(exposed: Boolean, snapshot: AutoSleepActivitySnapshot) {
            autoSleepMqttPublications(panel, exposed, snapshot).forEach { publication ->
                publish(publication.topic, publication.payload, retain = publication.retain)
            }
        }

        override fun onButton(event: String) {
            publish(eventButton, """{"event_type":"$event"}""")
        }

        override fun onSoftwareUpdatesDue() = reconcileSoftwareUpdates(announcing = false)

        override fun resolveHaLinkWithoutNativeSession() = resolveHaLinkFromBroker()
    }

    init {
        core.addListener(stateProjection)
        core.attach(stateOutbox)
    }

    /** How one core channel publishes on MQTT, or null for a channel only the native transport carries. */
    private fun stateRouteFor(key: String): MqttStateChannel? {
        fun state(topic: String = "ha-paneld/$panel/$key/state", retain: Boolean = true, equivalent: (String, String) -> Boolean = String::equals) =
            mqttStateChannel(key, topic, retain, equivalent)
        // Hardware confirmed after startup publishes change-only, like any retained switch.
        fun capability() = MqttStateChannel(MqttStateRoute("ha-paneld/$panel/$key/state", retain = true))
        val deadband = io.panelassistant.android.mqtt.MqttStateOutbox::numericDeadband
        return when (key) {
            // Native only: MQTT has no media_player platform (protocol section 18), and carries the camera
            // snapshot, auto-sleep activity and Zigbee health on topics of their own.
            "media", "camera_snapshot", "auto_sleep_activity", "auto_sleep_activity_attributes",
            "zigbee_gateway_health", "zigbee_gateway_health_attributes" -> null
            "storage_health_attributes" -> state(attrStorageHealth)
            "diag_wifi_outages_attributes" -> state(attrWifiOutages)
            "illuminance" -> state(retain = false)
            "proximity_level" -> state(equivalent = deadband(4.0))
            "temperature" -> state(retain = false, equivalent = deadband(0.1))
            "humidity" -> state(retain = false, equivalent = deadband(1.0))
            in PanelStateCore.DIAG_KEYS, in PanelStateCore.ROOM_KEYS -> state(
                SettingsRegistry.spec(key)!!.ha!!.stateTopic(panel),
                equivalent = DIAG_DEADBANDS[key]?.let(deadband) ?: String::equals,
            )
            "network_adb", "zigbee_router", "cpu_governor" -> capability()
            else -> when {
                key.startsWith("relay") || key.startsWith("button_led") -> capability()
                key.startsWith("software_update_") -> SoftwareComponent.entries
                    .firstOrNull { SoftwareUpdates.stateChannelKey(it) == key }
                    ?.let { state(SoftwareUpdateEntities.stateTopic(panel, it)) }
                else -> state()
            }
        }
    }
    // HiveMQ invokes both lifecycle callbacks on its own event loop. That thread may never acquire the
    // bridge gate: it only hands the latest event to this fixed-cardinality owner.
    private val connectionEventDispatcher = MqttConnectionEventDispatcher(::performConnectionEvent)
    // Discovery can exhaust outbound flow demand while submitting its large finite announcement. Keep
    // it separate from connection transitions so even a permanently wedged library call remains visible
    // to the watchdog and cannot suppress a later disconnect/reconnect event.
    private val connectAnnouncementDispatcher =
        MqttConnectAnnouncementDispatcher(::performConnectAnnouncement)
    // Synchronous announcement publication inherits the exact transport lease without changing every
    // discovery helper signature. The value exists only on the announcement dispatcher's one thread.
    private val announcementConnection = ThreadLocal<MqttConnectionLease?>()
    private val reannounceDispatcher: MqttReannounceDispatcher = MqttReannounceDispatcher(
        debounceMs = REANNOUNCE_DEBOUNCE_MS,
        perform = ::performReAnnounce,
    )
    // The update-owner lease refreshes its persisted timestamp hourly; only the first positive
    // observation needs to remove this notice from HA.
    private val migrationConnectionSeen = AtomicBoolean(config.panelAssistantConnectionSeen)
    private val migrationConnectionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in Config.PANEL_ASSISTANT_CONNECTION_KEYS &&
            config.panelAssistantConnectionSeen && migrationConnectionSeen.compareAndSet(false, true)
        ) requestReAnnounce()
    }

    /** The process-wide convergence pump is only a scheduler; this bridge's lifecycle gate remains the
     * authority for whether a queued observation may touch this concrete generation. */
    private fun dispatchStateWork(action: () -> Unit) {
        io.panelassistant.android.state.StateConverger.dispatch {
            lifecycle.runIfOpen(Unit, action)
        }
    }

    /** The primary state sink: derives the topic, retain flag and payload bytes from the channel. */
    private fun publishStateObservation(
        channel: String,
        observation: io.panelassistant.android.state.StateConverger.Observation.Reportable,
        done: (Boolean) -> Unit,
    ) {
        val route = checkNotNull(stateRouteFor(channel)) { "no MQTT route for state channel $channel" }.route
        val generation = connectionGeneration.currentOrNull()
        publish(route.topic, mqttStatePayload(observation), route.retain) { acknowledged ->
            if (acknowledged && generation != null && connectionGeneration.isCurrent(generation)) {
                completeAnnouncementIfReady(announcementReadiness.acknowledgeState(generation))
            }
            done(acknowledged)
        }
    }

    fun start() {
        // A successor that has not restored the panel's identity yet must not connect under the one it
        // generated for itself; the process restarts from the restored configuration and starts then.
        if (io.panelassistant.android.migration.IdentityMigrationGate.holdsNetworkIdentity()) return
        lifecycle.runIfOpen(Unit) {
            config.registerChangeListener(migrationConnectionListener)
            try {
                startOpen()
            } catch (failure: Throwable) {
                config.unregisterChangeListener(migrationConnectionListener)
                throw failure
            }
        }
    }

    private fun startOpen() {
        // Linearize the explicit fresh-client boundary before clearing bridge state. A delayed event from
        // the prior client must not overwrite this attempt while it is waiting for its first CONNACK.
        connectionEventDispatcher.supersede()
        val credentials = MqttCredentialsSnapshot(
            runtimeBroker,
            runtimeMqttUser,
            runtimeMqttPassword,
            runtimeMqttAddressFamily,
        )
        authRecovery.configure(
            "${credentials.broker}\u0000${credentials.user}\u0000${credentials.password}\u0000${credentials.addressFamily}",
        )
        activeConnection = null
        connectionGeneration.clear()
        announcementReadiness.clear()
        publishRecoveryLifecycleStateWithAddressFamily("connecting", null)
        var broker = credentials.broker.trim()
        if (broker.isEmpty() && GuidedSetupPresence.activelyWalked(SystemClock.elapsedRealtime())) {
            // A person is mid-wizard on an unconfigured panel: do not probe at all. The probe's anonymous
            // failures accumulate against the broker for the whole journey and were the leading suspect
            // for the first credentialed connect being throttled; the wizard's own broker step is minutes
            // away from providing the real configuration.
            Log.i(TAG, "guided setup in progress — deferring broker discovery probe")
            activeBroker = ""
            publishRecoveryLifecycleState("discovering")
            return
        }
        if (broker.isEmpty()) {
            // No explicit broker — try to find HA on the LAN (mDNS) and default to its :1883.
            discoverHaIp()?.let {
                broker = "tcp://$it:1883"
                Log.i(TAG, "MQTT broker auto-discovered via mDNS (HA at $it): $broker")
            }
        }
        if (broker.isEmpty()) {
            Log.i(TAG, "no broker configured and none discovered — waiting for network discovery")
            activeBroker = ""
            publishRecoveryLifecycleState("discovering")
            return
        }
        activeBroker = broker
        try {
            val ep = BrokerEndpoint.endpoint(broker)
            if (ep == null) {
                connectionGeneration.clear()
                publishRecoveryLifecycleState("config-error")
                Log.e(TAG, "invalid or unsupported MQTT broker URL")
                return
            }
            val (host, port) = ep.host to ep.port
            tlsActive = ep.tls
            val familyIdentity = checkNotNull(mqttFamilyBrokerIdentity(broker))
            val familyPolicy = AddressFamilyPolicy.fromConfig(credentials.addressFamily)
            val announcementIdentity = mqttAnnouncementRecoveryIdentity(
                familyIdentity,
                panel,
                profileIdentity,
                credentials.user,
                credentials.password,
                familyPolicy.configValue,
            )
            synchronized(announcementBudgetLock) {
                // This latch is process-wide rather than bridge-wide: a runtime replacement can complete
                // during the restart grace, but it must not rearm the already-scheduled boundary.
                ANNOUNCEMENT_BOUNDARY_CONSUMED_HERE.getAndUpdate { consumed ->
                    consumed?.takeIf { it == announcementIdentity }
                }
                announcementRecoveryIdentity = announcementIdentity
                announcementProcessRecoveryAvailable =
                    config.mqttAnnouncementBoundaryAvailable(announcementIdentity)
            }
            val selectedRoute = synchronized(familyRecoveryLock) {
                // Only building a fresh client consumes a staged route. Hive automatic reconnect may
                // create many sessions on that client, but cannot make owner retry flip back.
                val attempt = recoveryAuthority.beginConnectAttempt(
                    state = "connecting",
                    connectionGeneration = connectionGeneration.currentOrNull(),
                )
                val learned = familyPreference.selectForConnect(familyIdentity, attempt)
                val selected = if (familyPolicy == AddressFamilyPolicy.AUTOMATIC) {
                    learned
                } else {
                    familyPolicy.initialPreferIpv4
                }
                MqttSelectedFamilyRoute(familyIdentity, attempt, selected).also {
                    selectedFamilyRoute = it
                }
            }
            val routePlanner = MqttRoutePlanner(
                logicalHost = host,
                port = port,
                policy = familyPolicy,
                initialPreferIpv4 = selectedRoute.preferIpv4,
                // A retained Automatic route gets the established watchdog grace. All other first
                // attempts may suppress one failed family immediately, without changing reconnect backoff.
                rapidInitialFallbackAllowed = familyPolicy != AddressFamilyPolicy.AUTOMATIC ||
                    !familyPreference.awaitingProgress,
            )
            val initialRoute = routePlanner.resolveInitial()
            if (initialRoute == null) {
                connectionGeneration.clear()
                publishRecoveryLifecycleState("unreachable")
                Log.w(TAG, "MQTT Force IPv4 found no IPv4 address for the configured broker")
                return
            }
            val connectAddressFamily = initialRoute.family
            if (!recoveryAuthority.updateAddressFamily(selectedRoute.connectAttempt, connectAddressFamily)) return

            // The client lifecycle — build, connect, the connected/disconnected listeners, and the
            // superseded-client generation guard — lives in the transport (see HiveMqTransport). This
            // bridge only supplies the connection config + callbacks and keeps the HA semantics.
            // There is no heartbeat authority until this attempt reaches onConnected. HiveMQ can also
            // reconnect this client internally, so successful connection callbacks own generation changes.
            transport.connect(
                MqttConnectConfig(
                    host = host,
                    port = port,
                    tls = ep.tls,
                    clientId = "ha-paneld-$panel",
                    user = credentials.user.ifEmpty { null },
                    password = credentials.password,
                    keepAliveSeconds = KEEPALIVE_SEC,
                    willTopic = availabilityTopic,
                    willPayload = "offline",
                    // The credential-less discovery probe must not own a transport-level retry loop: its
                    // repeated anonymous failures poison the broker's throttle before the user ever types
                    // credentials, and a leaked probe client used to reconnect forever (broker-log
                    // evidence). Configured, credentialed connections keep auto-reconnect.
                    automaticReconnect = credentials.user.isNotEmpty() || configuredBroker.isNotEmpty(),
                    routePlanner = routePlanner,
                ),
                object : MqttCallbacks {
                    override fun onConnected(
                        connection: MqttConnectionLease,
                        addressFamily: MqttAddressFamily?,
                    ) = this@MqttBridge.onConnected(connection, addressFamily)
                    override fun onDisconnected(
                        connection: MqttConnectionLease?,
                        causeMessage: String?,
                    ) = this@MqttBridge.onDisconnected(connection, causeMessage)
                },
            )
            Log.i(
                TAG,
                "MQTT connecting to $host:$port " +
                    "(policy=${familyPolicy.configValue}, route=${connectAddressFamily?.label ?: "DNS"}) for $panel",
            )
        } catch (e: Exception) {
            Log.w(TAG, "MQTT connect failed", e)
        }
    }

    private fun scheduleAuthRetry(atMs: Long) {
        if (!lifecycle.isOpen()) return
        val generation = authRecovery.beginRetry()
        val delay = (atMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        runCatching {
            authScheduler.schedule({
                if (authRecovery.isCurrentRetry(generation) && isAuthRecoveryState(state)) {
                    Log.i(TAG, "MQTT auth retry — building fresh client with unchanged address family")
                    reconnect()
                }
            }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.onFailure { if (lifecycle.isOpen()) Log.w(TAG, "failed to schedule MQTT auth retry", it) }
    }

    /**
     * Select and persist the alternate route before recovery is handed to [ServiceRuntimeOwner]. The
     * owner's bounded recovery worker is intentionally allowed to wedge without entering its callback;
     * staging here means the controlled process boundary still restores the route we meant to try.
     *
     * This method never takes the bridge mutation gate: the watchdog must not wait behind the MQTT call
     * it is trying to recover. The tuple is broker-scoped, so a concurrent runtime replacement for a
     * different broker cannot consume it.
     */
    internal fun recoveryTicketForReconnect(expected: MqttRecoveryTicket): MqttRecoveryTicket? =
        expected.takeIf {
            lifecycle.isOpen() && recoveryAuthority.isCurrent(it) &&
                state != "disabled" && state != "config-error"
        }

    internal fun stageAlternateFamilyForReconnect(baseline: MqttRecoveryTicket): MqttRecoveryTicket? {
        if (AddressFamilyPolicy.fromConfig(runtimeMqttAddressFamily) !=
            AddressFamilyPolicy.AUTOMATIC
        ) {
            // Explicit policy is the durable bound. A steady-state watchdog rebuild may retry it, but
            // must not persist an opposing learned preference behind the user's back.
            return recoveryTicketForReconnect(baseline)
        }
        var identity: String? = null
        val selected = synchronized(familyRecoveryLock) {
            if (recoveryTicketForReconnect(baseline) == null) return null
            val brokerIdentity = mqttFamilyBrokerIdentity(activeBroker) ?: return null
            identity = brokerIdentity
            familyPreference.stageAlternate(
                brokerIdentity,
                baselineConnectAttempt = baseline.familyConnectAttempt,
            )
        }
        if (!selected.changed && selected.durable) {
            Log.i(
                TAG,
                "MQTT recovery retained the already-staged ${if (selected.preferIpv4) "IPv4" else "IPv6"} route after owner rejection",
            )
        } else if (selected.durable) {
            Log.i(
                TAG,
                "MQTT recovery staged and retained ${if (selected.preferIpv4) "IPv4" else "IPv6"} for the active broker",
            )
        } else {
            Log.w(
                TAG,
                "MQTT recovery cannot proceed: ${if (selected.preferIpv4) "IPv4" else "IPv6"} was selected in memory but is not durable",
            )
        }
        // Fail closed: owner work that may never enter is admitted only after the alternate route is
        // known to survive the process boundary. The same choice is retried next tick without flipping.
        return baseline.copy(stagedBrokerIdentity = identity).takeIf { selected.durable }
    }

    /** Owner rejected before entering. No fresh client consumed this stage, so cancel it unless a newer
     * attempt now owns the route; a still-stale connection can stage the same alternate again next tick. */
    internal fun reconcileRejectedRecovery(ticket: MqttRecoveryTicket): Boolean =
        synchronized(familyRecoveryLock) {
            reconcileRejectedMqttRecovery(recoveryAuthority, ticket) {
                ticket.stagedBrokerIdentity?.let { identity ->
                    familyPreference.cancelStaged(identity, ticket.familyConnectAttempt)
                } ?: true
            }
        }

    /** Durably consume this configuration's sole pre-readiness process boundary. */
    internal fun consumeAnnouncementProcessRecovery(ticket: MqttRecoveryTicket): Boolean =
        synchronized(announcementBudgetLock) {
            val identity = announcementRecoveryIdentity ?: return@synchronized false
            val before = recoveryAuthority.snapshot()
            if (!recoveryAuthority.isCurrent(ticket) || before.state != "announcing" ||
                !announcementProcessRecoveryAvailable
            ) return@synchronized false
            if (!config.consumeMqttAnnouncementBoundary(identity)) return@synchronized false
            ANNOUNCEMENT_BOUNDARY_CONSUMED_HERE.set(identity)
            announcementProcessRecoveryAvailable = false
            val after = recoveryAuthority.snapshot()
            if (after.state != "announcing" ||
                recoveryAuthority.claim(ticket) != MqttRecoveryAuthority.Claim.CLAIMED
            ) {
                // Progress won during the durable write. Give the still-unspent boundary back.
                if (config.clearMqttAnnouncementBoundary(identity)) {
                    ANNOUNCEMENT_BOUNDARY_CONSUMED_HERE.compareAndSet(identity, null)
                    announcementProcessRecoveryAvailable = true
                }
                return@synchronized false
            }
            true
        }

    /**
     * Force a fresh connection attempt, disposing any existing client first. Called by the service-level
     * reconnect watchdog and the connectivity-regained callback when HiveMQ's built-in auto-reconnect has
     * stalled — e.g. after a transient `NOT_AUTHORIZED` during an HA/broker restart (broker back up before
     * its auth backend is ready), or when the reconnect thread is deferred by Android power management.
     * Unlike [stop] it does NOT publish a retained "offline" — the availability LWT already covered the
     * drop and we're trying to come back, so we must not flap HA to offline on every retry.
     */
    fun reconnect() {
        lifecycle.runIfOpen(Unit) reconnect@{
            if (state == "disabled" || state == "config-error") return@reconnect
            // Detach the old client FIRST and tear it down on a throwaway daemon thread: disconnect() on a
            // WEDGED client (half-open socket, frozen reactor — the very case that triggers a liveness
            // rebuild) can block on an internal client monitor, and that must never delay the replacement
            // connection. In the wedged case the old reactor is frozen anyway, so it won't fight the new
            // client's session; in the healthy case the background disconnect completes normally.
            transport.disconnectDetached()
            startOpen()
        }
    }

    /** Execute only while the exact stale epoch that requested recovery still owns this bridge. */
    internal fun reconnect(ticket: MqttRecoveryTicket): MqttRecoveryOutcome {
        return lifecycle.runIfOpen(MqttRecoveryOutcome.NOT_ADMITTED) reconnect@{
            if (state == "disabled" || state == "config-error") {
                return@reconnect MqttRecoveryOutcome.NOT_ADMITTED
            }
            when (recoveryAuthority.claim(ticket)) {
                MqttRecoveryAuthority.Claim.CONSUMED_BY_NEW_ATTEMPT -> {
                    // Another fresh-client start consumed the selection. Never roll it back.
                    return@reconnect MqttRecoveryOutcome.NO_LONGER_NEEDED
                }
                MqttRecoveryAuthority.Claim.STALE_SAME_ATTEMPT -> {
                // The original attempt made real broker progress while owner work waited. Ordinary
                // reconnect churn on a dead address family is NOT progress and must not reach here:
                // rolling the staged alternate back on every disconnect is what pinned a panel to a
                // black-holed family. Restore the actual route while the lifecycle gate excludes a
                // newer startOpen from consuming the staged alternate.
                    ticket.stagedBrokerIdentity?.let { identity ->
                        val rolledBack = synchronized(familyRecoveryLock) {
                            familyPreference.cancelStaged(identity, ticket.familyConnectAttempt)
                        }
                        if (!rolledBack) {
                            Log.w(TAG, "MQTT recovered before fallback entry, but the staged family rollback was not durable")
                        }
                    }
                    return@reconnect MqttRecoveryOutcome.NO_LONGER_NEEDED
                }
                MqttRecoveryAuthority.Claim.CLAIMED -> Unit
            }
            transport.disconnectDetached()
            startOpen()
            MqttRecoveryOutcome.REBUILT
        }
    }

    /**
     * HiveMQ connected-listener boundary. It runs on the event loop that must process PUBACKs, so this
     * method performs one nonblocking fixed-cardinality enqueue and nothing else.
     */
    private fun onConnected(connection: MqttConnectionLease, addressFamily: MqttAddressFamily?) {
        connectionEventDispatcher.submit(MqttConnectionEvent.Connected(connection, addressFamily))
    }

    /** Runs on the connection-event owner, never HiveMQ's event loop. */
    private fun performConnectionEvent(sequenced: SequencedMqttConnectionEvent) {
        if (!connectionEventDispatcher.isCurrent(sequenced)) return
        when (val event = sequenced.event) {
            is MqttConnectionEvent.Connected -> {
                val announcement = lifecycle.runIfOpen<MqttConnectAnnouncement?>(null) {
                    if (!connectionEventDispatcher.isCurrent(sequenced) ||
                        !transport.isCurrent(event.connection)
                    ) return@runIfOpen null
                    val generation = connectionGeneration.advance()
                    event.addressFamily?.let { connectedFamily ->
                        selectedFamilyRoute = selectedFamilyRoute?.copy(
                            preferIpv4 = connectedFamily == MqttAddressFamily.IPV4,
                        )
                    }
                    MqttConnectAnnouncement(generation, event.connection).also {
                        activeConnection = event.connection
                        authRecovery.authenticated(SystemClock.elapsedRealtime())
                        // CONNACK is transport liveness, not application readiness.
                        announcementReadiness.begin(it)
                        recordMqttConnack(
                            authority = recoveryAuthority,
                            connectionGeneration = generation,
                            applicationReadyEver = recoveryAuthority.snapshot().applicationReadyEver,
                            addressFamily = event.addressFamily,
                            nowMs = SystemClock.elapsedRealtime(),
                        )
                        stateOutbox.markAllDirty()
                    }
                } ?: return
                when (connectAnnouncementDispatcher.submit(announcement)) {
                    LatestDispatcher.Admission.COALESCED ->
                        Log.i(TAG, "MQTT connect announcement advanced to generation ${announcement.generation}")
                    LatestDispatcher.Admission.CLOSED ->
                        Log.w(TAG, "MQTT connect announcement rejected during retirement")
                    LatestDispatcher.Admission.ACCEPTED,
                    LatestDispatcher.Admission.REJECTED -> Unit // single slot never rejects
                }
            }
            is MqttConnectionEvent.Disconnected -> lifecycle.runIfOpen(Unit) {
                if (!connectionEventDispatcher.isCurrent(sequenced)) return@runIfOpen
                // A queued disconnect from a superseded automatic-reconnect session owns no state in
                // the replacement generation. A pre-CONNACK failure owns state only before any session.
                if (!mqttDisconnectOwnsBridgeState(activeConnection, event.connection)) return@runIfOpen
                activeConnection = null
                connectionGeneration.clear()
                announcementReadiness.clear()
                if (event.state == "auth-failed" && runtimeMqttUser.isEmpty() && configuredBroker.isEmpty()) {
                    // A credential-LESS discovery probe (fresh panel, broker unconfigured, anonymous
                    // connect to the mDNS-found broker) getting NOT_AUTHORIZED is the broker requiring
                    // credentials, not credentials being wrong. Publishing "auth-failed" here pre-armed
                    // the exact state the setup wizard's credential verdict keys on. No retry either —
                    // the earlier cadence hammered the broker with anonymous failures for the whole
                    // first-run journey (broker-log evidence), and the auth-retry guard would drop a
                    // "discovering" retry anyway. This probe answered its question: a broker exists and
                    // wants credentials. The real connection arrives with the user's configuration.
                    publishRecoveryLifecycleState("discovering")
                } else if (event.state == "auth-failed") {
                    val now = SystemClock.elapsedRealtime()
                    val retryAt = authRecovery.rejected(now)
                    publishRecoveryLifecycleState(authRecovery.snapshot(now).state)
                    scheduleAuthRetry(retryAt)
                } else publishRecoveryLifecycleState(event.state)
                Log.w(TAG, "MQTT disconnected ($state): ${event.causeMessage}")
                stateOutbox.markAllDirty()
            }
        }
    }

    /** Runs off HiveMQ's event loop on every current (re)connect. */
    private fun performConnectAnnouncement(announcement: MqttConnectAnnouncement) {
        if (!connectionAnnouncementIsCurrent(announcement)) return
        announcementConnection.set(announcement.connection)
        try {
            performConnectAnnouncementBound(announcement)
        } finally {
            announcementConnection.remove()
        }
    }

    private fun performConnectAnnouncementBound(announcement: MqttConnectAnnouncement) {
        // Capture before command subscriptions can initialize the state-channel registry. Every entity
        // in this announcement sees the same immutable values; later bounded snapshots may add channels
        // when a transiently unavailable live probe recovers.
        val capabilitySnapshot = core.capabilities.snapshot()
        synchronized(discoveryAnnouncementLock) {
            if (!connectionAnnouncementIsCurrent(announcement)) return@synchronized
            subscribe("ha-paneld/$panel/+/set") { topic, payload, retained -> onCommand(topic, payload, retained) }
            // Re-announce discovery when HA (re)starts — its birth message on homeassistant/status. With
            // non-retained discovery this is what rebuilds our entities after an HA restart. The retained
            // online delivered while this connect announcement is still running is suppressed below.
            subscribe("homeassistant/status") { _, payload, _ ->
                if (mqttIsHaOnline(payload)) requestReAnnounce()
            }
            mqttStalePanelCleanup(stalePanelId, panel).forEach {
                publish(it.topic, it.payload, retain = it.retain)
            }
            publishDiscovery(capabilitySnapshot)
            // On a core upgrade or active-profile revision switch, actively clear any entity the previous
            // runtime published but this one no longer does. A profile switch can remove hardware-backed
            // entities without changing Config.VERSION, so both immutable identities belong in the marker.
            // publishDiscovery above populated publishedConfigTopics for the current capability set.
            val brokerIdentity = mqttFamilyBrokerIdentity(activeBroker) ?: activeBroker.trim()
            val discoveryMarker = mqttDiscoveryCleanupMarker(Config.VERSION, profileIdentity, brokerIdentity)
            if (config.lastDiscoveryVersion != discoveryMarker) {
                pruneStaleDiscovery { acknowledged ->
                    if (acknowledged && connectionAnnouncementIsCurrent(announcement)) {
                        config.setLastDiscoveryVersion(discoveryMarker)
                    } else if (lifecycle.isOpen()) {
                        Log.w(TAG, "discovery prune was not fully acknowledged; retrying after reconnect")
                    }
                }
            }
            core.withProximity { proximityAvailable ->
                if (proximityAvailable) {
                    // Current retained states must precede the entity-specific online edge. These are the
                    // first state-converger admissions on this connection, so the bounded outbox has room.
                    core.reconcile("proximity", force = true)
                    core.reconcile("proximity_level", force = true)
                    publish(proximityAvailabilityTopic, "online", retain = true)
                } else {
                    publish(proximityAvailabilityTopic, "offline", retain = true)
                    // Old releases retained a guessed ON/OFF value. Remove it while the learner is not
                    // authoritative so HA cannot display a stale per-firmware heuristic as current data.
                    publish(stateProximity, "", retain = true)
                    publish(stateProximityLevel, "", retain = true)
                }
            }
            core.restoreAndReconcile()
            core.reconcileAll()
            // Entity-specific availability and retained state must converge before the panel-wide
            // online edge; otherwise HA can briefly resurrect a previous process's proximity value.
            publish(availabilityTopic, "online", retain = true) { acknowledged ->
                if (acknowledged && connectionAnnouncementIsCurrent(announcement)) {
                    completeAnnouncementIfReady(announcementReadiness.acknowledgeOnline(announcement))
                } else if (lifecycle.isOpen() && connectionGeneration.isCurrent(announcement.generation)) {
                    Log.w(TAG, "MQTT connect announcement online edge was not acknowledged")
                }
            }
            // A retained birth admitted during subscribe is already covered by this later announcement.
            // Invalidate that queued generation while still holding the shared announcement lock.
            reannounceDispatcher.suppressPending()
        }
        if (!connectionAnnouncementIsCurrent(announcement)) return
        commands.reconcileZigbeeBoot() // boot-restore: start the gateway if left ON and nothing else has
        // A reconnect storm must not create one blocked privileged thread per callback.
        adbReassertWorker.submit(Unit)
        core.maybeResolveHaLink() // native HA session first, MQTT credential fallback; off-thread, best-effort
    }

    private fun connectionAnnouncementIsCurrent(announcement: MqttConnectAnnouncement): Boolean =
        lifecycle.isOpen() && (state == "announcing" || state == "connected") &&
            connectionGeneration.isCurrent(announcement.generation) &&
            transport.isCurrent(announcement.connection)

    /** PUBACK callbacks only enqueue this constant state transition; they never wait on bridge work. */
    private fun completeAnnouncementIfReady(announcement: MqttConnectAnnouncement?) {
        announcement ?: return
        io.panelassistant.android.state.StateConverger.dispatch {
            lifecycle.runIfOpen(Unit) {
                if (state == "announcing" && connectionAnnouncementIsCurrent(announcement)) {
                    publishRecoveryLifecycleState("connected", applicationReadyEver = true)
                    // Restored-family grace ends only at application readiness, never at CONNACK or an
                    // unrelated heartbeat ACK that can succeed while discovery remains wedged.
                    selectedFamilyRoute
                        ?.takeIf {
                            AddressFamilyPolicy.fromConfig(runtimeMqttAddressFamily) ==
                                AddressFamilyPolicy.AUTOMATIC
                        }
                        ?.let { selected ->
                        val confirmed = synchronized(familyRecoveryLock) {
                            familyPreference.confirmConnectedRoute(
                                selected.brokerIdentity,
                                selected.connectAttempt,
                                selected.preferIpv4,
                            )
                        }
                        if (!confirmed) {
                            Log.w(TAG, "MQTT readiness could not durably confirm its selected address family")
                        }
                    }
                    familyPreference.markBrokerProgress()
                    synchronized(announcementBudgetLock) {
                        announcementRecoveryIdentity?.let { identity ->
                            when (reconcileMqttAnnouncementReadinessBudget(
                                consumedHere = ANNOUNCEMENT_BOUNDARY_CONSUMED_HERE.get() == identity,
                                clearInheritedBoundary = {
                                    config.clearMqttAnnouncementBoundary(identity)
                                },
                            )) {
                                MqttAnnouncementReadinessBudgetResult.PRESERVED_PENDING_BOUNDARY -> {
                                    announcementProcessRecoveryAvailable = false
                                    Log.i(TAG, "MQTT readiness arrived after its process boundary was committed; preserving the spent token")
                                }
                                MqttAnnouncementReadinessBudgetResult.REARMED ->
                                    announcementProcessRecoveryAvailable = true
                                MqttAnnouncementReadinessBudgetResult.CLEAR_FAILED -> {
                                    announcementProcessRecoveryAvailable = false
                                    Log.w(TAG, "MQTT readiness could not durably clear its process-recovery budget")
                                }
                            }
                        }
                    }
                    Log.i(TAG, "MQTT connected — online + state acknowledged for $panel")
                }
            }
        }
    }

    /** Pure classification plus one bounded enqueue; HiveMQ's event loop never enters bridge work. */
    private fun onDisconnected(
        connection: MqttConnectionLease?,
        causeMessage: String?,
    ): Boolean {
        val classified = classifyDisconnect(causeMessage)
        val admission = connectionEventDispatcher.submit(
            MqttConnectionEvent.Disconnected(connection, classified, causeMessage),
        )
        // A stop-racing callback sees the closed owner and must suppress Hive's automatic reconnect.
        return mqttAutomaticReconnectAllowed(classified, admission)
    }

    /** Re-publish discovery + current states — on HA's `online` birth (non-retained discovery must be
     *  re-sent when HA restarts). No-op if not connected. */
    private fun requestReAnnounce() {
        when (reannounceDispatcher.submit()) {
            LatestDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE)
            LatestDispatcher.Admission.REJECTED, // unreachable for a single slot
            LatestDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE)
            LatestDispatcher.Admission.ACCEPTED -> Unit
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE,
            reannounceDispatcher.pendingCount(),
        )
    }

    private fun performReAnnounce(generation: Long) {
        synchronized(discoveryAnnouncementLock) {
            FeatureCosts.registry.setBacklog(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE, 0)
            val cost = FeatureCosts.registry.span(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE)
            try {
                if (!lifecycle.isOpen() || !isConnected() || !reannounceDispatcher.isCurrent(generation)) {
                    cost.outcome(FeatureCostOutcome.CANCELLED)
                    return@synchronized
                }
                // HA frequently emits a retained birth immediately after our connect announcement and may
                // repeat births while its MQTT integration settles. Reuse one monotonic-TTL snapshot across
                // that bounded burst instead of repeating privileged probes for byte-identical discovery.
                publishDiscovery(core.capabilities.snapshot(REANNOUNCE_CAPABILITY_TTL_MS))
                core.restoreAndReconcile()
                cost.work(units = publishedConfigTopics.size.toLong())
                Log.i(TAG, "HA online — re-announced discovery for $panel")
            } catch (e: Exception) {
                cost.outcome(FeatureCostOutcome.FAILURE)
                Log.w(TAG, "HA discovery reannouncement failed", e)
            } finally {
                cost.close()
            }
        }
    }

    /** Republish discovery only when the live panel URL changed. Called by Android network callbacks;
     * reconnect and HA-birth paths already call [publishDiscovery] and evaluate the same supplier. */
    internal fun refreshDiscoveryAddress() {
        val current = configUrl()?.takeIf(String::isNotBlank)
        if (!shouldRepublishDiscoveryAddress(isConnected(), lastPublishedConfigUrl, current)) return
        requestReAnnounce()
    }

    /** Re-evaluate transport-dependent discovery after Android changes the default network. */
    internal fun refreshNetworkState() {
        if (!isConnected()) return
        core.capabilities.invalidate()
        requestReAnnounce()
    }

    /** Re-project live Config into discovery/state without replacing a healthy broker connection. */
    internal fun refreshConfiguration(reannounce: Boolean, resolveHaLink: Boolean) {
        if (reannounce) requestReAnnounce()
        if (resolveHaLink) core.maybeResolveHaLink()
    }

    /**
     * The compatibility "Open in HA" link for a panel with no native renderer connection: MQTT
     * username/password against the Home Assistant the broker belongs to. Runs on the state core's link
     * worker, which always prefers the native session.
     */
    private fun resolveHaLinkFromBroker() {
        val credentials = config.mqttCredentialsSnapshot()
        if (credentials.user.isBlank() || credentials.password.isBlank()) return
        // Prefer mDNS (broker-matched). Else derive HA from the broker HOST: a working broker is
        // likely the HA server too. HaLink reads /api/config for its canonical browser link.
        val base = discoverHaUrl(credentials.broker) ?: brokerHttpsUrl(credentials.broker) ?: return
        val target = HaLink.resolutionTarget(base, panel)
        if (config.haDeviceLinkIsFresh(target, System.currentTimeMillis(), HA_LINK_TTL_MS)) return
        val linkFamilyPolicy = AddressFamilyPolicy.fromConfig(config.addressFamily)
        val link = HaLink.resolve(
            base, credentials.user, credentials.password, listOf(panel, runtimeFriendlyName),
            preferIpv4 = linkFamilyPolicy.initialPreferIpv4,
            ipv4Only = linkFamilyPolicy.ipv4Only,
        ) ?: return
        lifecycle.runIfOpen(Unit) {
            if (config.haUrl.isBlank() && config.panelId == panel &&
                config.mqttCredentialsSnapshot() == credentials
            ) config.setHaDeviceUrl(link, target)
        }
    }

    /** `https://<broker-host>` when the broker is a hostname (wildcard/SAN certs make HTTPS verifiable);
     *  null for a raw IP broker (cert would mismatch) or no broker. */
    private fun brokerHttpsUrl(broker: String): String? {
        val host = broker.substringAfter("://").substringBefore(":").substringBefore("/").trim()
        if (host.isBlank() || host.contains(":") || host.matches(Regex("[0-9.]+"))) return null
        return "https://$host"
    }

    // ---- command edge ----

    private fun onCommand(topic: String, payloadBytes: ByteArray, retained: Boolean) {
        val retired = !lifecycle.isOpen()
        if (!mqttAcceptsCommand(retired, retained, config.panelAssistantAuthority)) {
            if (!retired && !retained) {
                // A panel cut over to the native transport takes no command from MQTT, session or not.
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
                if (nativeAuthorityDropLogged.compareAndSet(false, true)) {
                    Log.w(TAG, "ignoring MQTT commands: Panel Assistant holds command authority for this panel")
                }
            }
            if (retained && !retired) {
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
                Log.w(TAG, "ignoring RETAINED command on $topic (${payloadBytes.size} bytes)")
            }
            return
        }
        // Commands are keyed by their transport-neutral channel, derived here at the MQTT edge. HiveMQ
        // reuses callback-owned values, so the dispatcher copies nothing: copy only after the cheap gates,
        // then return immediately so a root call or slow controller can never pin its network callback.
        val channel = mqttCommandChannel(panel, topic)
        val admission = channel?.takeIf { commands.kind(it) != null && payloadBytes.size <= MAX_COMMAND_PAYLOAD_BYTES }
            ?.let { admitted ->
                commands.submit(admitted, payloadBytes.copyOf(), CommandOrigin.MQTT) {
                    // Authority may have changed while this command waited behind a slow handler.
                    mqttAcceptsCommand(!lifecycle.isOpen(), retained, config.panelAssistantAuthority)
                }
            }
        if (admission == null) FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_COMMAND_DISPATCH)
    }

    private val nativeAuthorityDropLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Converge Home Assistant's view of both update entities. Discovery is republished only when the
     * entity's shape changes (or on an announcement), and an entity that has lost `latest_version` is
     * recreated, because Home Assistant keeps a value that a later state message omits.
     */
    private fun reconcileSoftwareUpdates(announcing: Boolean) {
        if (softwareUpdateSources == null) return
        val discovery = softwareUpdateDiscovery ?: return
        synchronized(softwareUpdateLock) {
            for (component in SoftwareComponent.entries) {
                val inputs = core.softwareUpdateInputs(component) ?: return
                val current = SoftwareUpdateEntities.shape(inputs)
                val previous = if (component in softwareShapes) softwareShapes[component]
                else SoftwareDiscoveryShape.decode(config.softwareUpdateDiscoveryShape(component.wire))
                val configTopic = SoftwareUpdateEntities.configTopic(panel, component)
                val step = SoftwareUpdateEntities.transition(previous, current, announcing)
                val plan = SoftwareUpdateEntities.discoveryPlan(
                    panel, inputs, step, discovery.availability, discovery.device,
                )
                for (publication in plan) {
                    if (publication.topic == configTopic) {
                        // Records the topic for stale-discovery pruning; retains exactly the tombstones.
                        publishConfig("update", SoftwareUpdateEntities.uniqueId(panel, component), publication.payload)
                    } else {
                        publish(publication.topic, publication.payload, retain = publication.retain)
                    }
                }
                softwareShapes[component] = current
                if (previous != current) {
                    config.setSoftwareUpdateDiscoveryShape(component.wire, current.encode())
                }
                core.reconcile(
                    SoftwareUpdates.stateChannelKey(component),
                    force = announcing || step != SoftwareDiscoveryStep.NONE,
                )
            }
        }
    }

    // ---- discovery ----

    /**
     * Publish — or, when the user has hidden it via the per-panel "expose to HA" toggle, CLEAR — one
     * discovery entity. Hiding publishes an empty payload to the config topic, which removes the
     * entity from HA entirely (zero recorder / state-machine cost); with the 0.8.5 un-retained
     * discovery model nothing on the broker can resurrect it, and [reAnnounce] re-evaluates this gate
     * on every HA birth. [publishState] runs only when the entity is exposed. This is what makes the
     * HA footprint configurable per panel.
     */
    private fun exposable(
        key: String,
        component: String,
        objectId: String,
        payload: () -> String,
        capabilitySnapshot: Capabilities?,
        availableOverride: Boolean? = null,
        publishState: () -> Unit,
    ) {
        // Honour the spec's per-setting default so a setting declared haExposedByDefault=false is
        // local-only (HTTP UI) until the user opts in via the expose pip — matching what the Configure
        // UI/schema shows. Falls back to true for keys with no registry spec (e.g. relay/button_led).
        // An UNAVAILABLE setting (availableWhen false — e.g. Companion auto-update with no Companion
        // installed) publishes the empty config, removing any stale HA entity, regardless of the pip.
        val spec = SettingsRegistry.spec(key)
        val default = spec?.haExposedByDefault ?: true
        val available = availableOverride
            ?: capabilitySnapshot?.let { spec?.availableWhen?.invoke(it) }
            ?: true
        if (available && config.haExposed(key, default)) {
            publishConfig(component, objectId, payload())
            publishState()
        } else {
            publishConfig(component, objectId, "")
            // Read-only sensor states are retained unless explicitly declared otherwise. Removing only
            // discovery would leave sensitive values such as an SSID stored on the broker after opt-out.
            if (key == "auto_sleep_activity") {
                autoSleepMqttPublications(panel, exposed = false, snapshot = AutoSleepActivitySnapshot())
                    .forEach { publication -> publish(publication.topic, publication.payload, retain = publication.retain) }
            } else hiddenReadOnlyStateTopic(key, panel)?.let { stateTopic ->
                if (key in WIFI_DIAGNOSTIC_KEYS) {
                    // The converger serializes this clear behind any older in-flight value for the same
                    // topic, so a delayed SSID/RSSI acknowledgement cannot win after the tombstone.
                    core.reconcile(key, force = true)
                } else {
                    publish(stateTopic, "", retain = true)
                }
            }
        }
    }

    private fun publishDiscovery(capabilitySnapshot: Capabilities?) {
        val channelShape = core.ensureCapabilityChannels(capabilitySnapshot).possible
        if (config.panelAssistantMqttDiscovery == PanelAssistantTransportProtocol.MQTT_DISCOVERY_WITHDRAW) {
            publish(migrationNoticeState, "", retain = true)
            publish(migrationNoticeAttributes, "", retain = true)
            withdrawDiscovery()
            return
        }
        fun exposable(
            key: String,
            component: String,
            objectId: String,
            payload: () -> String,
            availableOverride: Boolean? = null,
            publishState: () -> Unit,
        ) = this@MqttBridge.exposable(
            key, component, objectId, payload, capabilitySnapshot, availableOverride, publishState,
        )

        // device.name = the configurable friendly name; entity names are the capability ONLY, so HA
        // composes a clean `<domain>.<panel>_<cap>` entity_id without doubling the panel id.
        // configuration_url -> HA renders a "Visit" link on the device page (the panel's info UI).
        val currentConfigUrl = configUrl()?.takeIf(String::isNotBlank)
        lastPublishedConfigUrl = currentConfigUrl
        val cu = currentConfigUrl?.let { ""","configuration_url":"${jsonEsc(it)}"""" } ?: ""
        val name = jsonEsc(runtimeFriendlyName)
        val mfr = jsonEsc(config.manufacturer)
        val mdl = jsonEsc(config.model)
        // hw_version = panel firmware/build; surfaces in HA's device-info section (sw_version is
        // ha-paneld's own version). serial_number = stable Android id.
        val hw = jsonEsc("Android ${Build.VERSION.RELEASE} · ${Build.DISPLAY}")
        // Two device identifiers: the panel_id one (historical primary — existing registrations match
        // on it) plus this installation's minted identity. HA merges a device on ANY matching
        // identifier, so a later panel_id change re-attaches to the SAME HA device instead of minting a
        // duplicate.
        //
        // The minted identity replaced `ha-paneld-aid-<ANDROID_ID>` (#155). Panels flashed from one
        // factory image share the Android 8.1 SSAID seed, so that identifier was identical across a
        // fleet and Home Assistant merged every such panel into a single device. Because HA UNIONS
        // identifiers and never removes one, a shared identifier published even once is permanent for
        // that device, and republishing it would re-merge those panels as soon as the merged device is
        // deleted — so it is published at most once more, by an installation that was already
        // registered under it, and then retired below.
        val legacyAid = if (config.legacyAidBridgePending) config.androidId else ""
        val identifiers = mqttDeviceIdentifiers(panel, config.deviceUid, legacyAid)
        val ids = identifiers.joinToString(",", "[", "]") { """"${jsonEsc(it)}"""" }
        // suggested_area applies only when HA first registers the device (it auto-creates the area and
        // never overrides a manual move) — exactly the semantics of the local ha_area REQUEST, whose
        // canonical source stays Home Assistant once the device exists.
        val sa = config.haArea.takeIf(String::isNotBlank)?.let { ""","suggested_area":"${jsonEsc(it)}"""" } ?: ""
        val softwareVersion = jsonEsc(appVersion())
        val device = """"device":{"identifiers":$ids,"name":"$name","manufacturer":"$mfr","model":"$mdl","sw_version":"$softwareVersion","hw_version":"$hw","serial_number":"${config.androidId}"$sa$cu}"""
        val avail = """"availability_topic":"$availabilityTopic","payload_available":"online","payload_not_available":"offline""""
        val proximityAvail = """"availability":[{"topic":"$availabilityTopic","payload_available":"online","payload_not_available":"offline"},{"topic":"$proximityAvailabilityTopic","payload_available":"online","payload_not_available":"offline"}],"availability_mode":"all""""
        val cameraSnapshotAvail = dualAvailabilityFragment(availabilityTopic, cameraSnapshotAvailability)

        // Keep this a normal device entity: diagnostic entities are easy to overlook on the HA device
        // page. The English guidance comes from shell.migration.body in the shared source catalogue;
        // the device's existing Visit URL remains the panel UI, while the entity attributes carry the
        // installation URL. A previously announced entity is tombstoned after the first connection.
        if (config.panelAssistantConnectionSeen) {
            publishConfig("binary_sensor", "${panel}_panel_assistant_required", "")
            publish(migrationNoticeState, "", retain = true)
            publish(migrationNoticeAttributes, "", retain = true)
        } else {
            publishConfig(
                "binary_sensor", "${panel}_panel_assistant_required",
                """{"name":"${jsonEsc(migrationNoticeEnglish("shell.migration.title"))}","object_id":"${panel}_panel_assistant_required","unique_id":"${panel}_panel_assistant_required","state_topic":"$migrationNoticeState","payload_on":"ON","payload_off":"OFF","device_class":"problem","json_attributes_topic":"$migrationNoticeAttributes",$avail,$device}""",
            )
            publish(migrationNoticeState, "ON", retain = true)
            publish(
                migrationNoticeAttributes,
                """{"message":"${jsonEsc(migrationNoticeEnglish("shell.migration.body"))}","url":"${MigrationNotice.URL}"}""",
                retain = true,
            )
        }

        // Every HA entity backed by a SettingsRegistry descriptor derives its identity and discovery
        // payload from that ONE declaration (HaEntity.buildDiscoveryJson) — byte-identical to the
        // legacy hand-written JSON and pinned by DiscoveryParityTest. Runtime command/state handling
        // remains intentionally separate because it is controller-owned rather than presentation data.
        fun registryExposable(
            key: String,
            availability: String = avail,
            availableOverride: Boolean? = null,
            publishState: () -> Unit,
        ) {
            val spec = requireNotNull(SettingsRegistry.spec(key)) { "setting missing from registry: $key" }
            val entity = requireNotNull(spec.ha) { "HA entity missing from registry: $key" }
            // A spec with per-choice gates renders its {options} placeholder from the same capability
            // snapshot the Configure form uses, so HA never offers a choice the panel would reject.
            // Specs without gates carry no placeholder and keep their fixed payload bytes.
            // Fail closed when the snapshot is unavailable: an all-false Capabilities drops every gated
            // choice rather than offering one the panel may not honour, and always yields a valid array
            // so a missing snapshot can never emit `"options":,`.
            val optionsJson = spec.discoveryOptionsJson(capabilitySnapshot ?: Capabilities())
            exposable(
                key = key,
                component = entity.component,
                objectId = "${panel}_${entity.objectSuffix}",
                payload = { entity.buildDiscoveryJson(panel, availability, device, optionsJson = optionsJson) },
                capabilitySnapshot = capabilitySnapshot,
                availableOverride = availableOverride,
                publishState = publishState,
            )
        }

        registryExposable("screen") {
            core.reconcile("screen", force = true)
        }
        val autoSleepAvail = autoSleepAvailabilityFragment(availabilityTopic, autoSleepAvailabilityTopic)

        if (led.available()) {
            val modes = if (led.colorCapable()) """["rgb"]""" else """["brightness"]"""
            publishConfig(
                "light", "${panel}_led",
                """{"name":"LED","object_id":"${panel}_led","unique_id":"${panel}_led","schema":"json","brightness":true,"supported_color_modes":$modes,"effect":true,"effect_list":["none","strobe","blink","pulse"],"command_topic":"$cmdLed","state_topic":"$stateLed",$avail,$device}""",
            )
        }

        publishConfig(
            "text", "${panel}_navigate",
            """{"name":"Navigate","object_id":"${panel}_navigate","unique_id":"${panel}_navigate","command_topic":"$cmdNavigate","state_topic":"$stateNavigate","mode":"text","icon":"mdi:monitor-dashboard",$avail,$device}""",
        )

        // Per-panel intended "home" dashboard path (e.g. /lovelace/0) — reload re-navigates here once the
        // frontend is back up. Empty = keep the Companion default. Config category.
        publishConfig(
            "text", "${panel}_home_dashboard",
            """{"name":"Home dashboard","object_id":"${panel}_home_dashboard","unique_id":"${panel}_home_dashboard","command_topic":"$cmdHomeDashboard","state_topic":"$stateHomeDashboard","mode":"text","icon":"mdi:home-search","entity_category":"config",$avail,$device}""",
        )
        core.reconcile("home_dashboard", force = true)

        // The button event entity surfaces a11y key capture AND daemon-instrumented evdev buttons
        // (e.g. the WF1589T power key), so publish it whenever either source exists.
        if (buttonsEnabled || hasEvdevButtons) {
            val eventTypes = mqttButtonEventTypes(profileButtonEventTypes)
                .joinToString(",") { "\"${jsonEsc(it)}\"" }
            publishConfig(
                "event", "${panel}_button",
                """{"name":"Button","object_id":"${panel}_button","unique_id":"${panel}_button","state_topic":"$eventButton","event_types":[$eventTypes],$avail,$device}""",
            )
        }
        // TTS/announce playback volume (STREAM_MUSIC). HA has no MQTT media_player platform, so
        // volume is a number entity rather than a media_player slider.
        registryExposable("volume") {
            core.reconcile("volume", force = true)
        }

        // Panel sensors — exposed as data only; room sensors stay the occupancy/lux authority. Their
        // registry descriptors are shared with Configure/API availability and remain the sole schema.
        // Live answer; unsettled neither creates nor deletes (not even by the upgrade prune) an entity.
        when (val available = core.hardwareAvailability("illuminance", null)) {
            null -> SettingsRegistry.spec("illuminance")?.ha?.let { keepConfig(it.component, "${panel}_${it.objectSuffix}") }
            else -> registryExposable("illuminance", availableOverride = available) {
                core.reconcile("illuminance", force = true)
            }
        }
        val learnedProximity = capabilitySnapshot?.hasLearnedProximity == true
        registryExposable("proximity", proximityAvail, core.hardwareAvailability("proximity", learnedProximity)) {
            core.reconcile("proximity", force = true)
        }
        registryExposable("proximity_level", proximityAvail, core.hardwareAvailability("proximity_level", learnedProximity)) {
            core.reconcile("proximity_level", force = true)
        }
        registryExposable("auto_sleep_activity", autoSleepAvail, availableOverride = config.autoSleep) {
            core.publishAutoSleepActivityNow()
        }
        // SensorManager climate. On CHT8305 panels (TPA10) these are suppressed in favour of the
        // daemon-read room_temp/room_humidity, so publish an EMPTY config (tombstone) instead of
        // skipping — a panel upgrading from a version that DID expose them then sheds the now-duplicate,
        // stale entity from HA (SensorManager never streams a value on that chip). Empty on a panel that
        // never had the sensor is a harmless no-op.
        registryExposable("temperature", availableOverride = core.hardwareAvailability("temperature", null)) {
            core.reconcile("temperature", force = true)
        }
        registryExposable("humidity", availableOverride = core.hardwareAvailability("humidity", null)) {
            core.reconcile("humidity", force = true)
        }
        if (hasButtonBacklight) {
            publishConfig(
                "light", "${panel}_buttons",
                """{"name":"Button backlight","object_id":"${panel}_buttons","unique_id":"${panel}_buttons","schema":"json","brightness":true,"supported_color_modes":["brightness"],"command_topic":"$cmdButtons","state_topic":"$stateButtons",$avail,$device}""",
            )
        }
        // Config switches/numbers — each honours its per-panel "expose to HA" toggle (hidden → the
        // retained discovery payload is cleared, so the entity leaves HA with zero recorder cost).
        // Registry-backed entities are built from SettingsRegistry (the single source of truth,
        // golden-tested for byte-parity with these payloads by DiscoveryParityTest).
        registryExposable("wake_on_wave") {
            core.reconcile("wake_on_wave", force = true)
        }
        registryExposable("auto_sleep") {
            core.reconcile("auto_sleep", force = true)
        }
        registryExposable("touch_sound") {
            core.reconcile("touch_sound", force = true)
        }

        publishConfig(
            "button", "${panel}_update_companion",
            """{"name":"Update Companion app","object_id":"${panel}_update_companion","unique_id":"${panel}_update_companion","command_topic":"$cmdUpdateCompanion","icon":"mdi:home-assistant","entity_category":"config",$avail,$device}""",
        )

        publishConfig(
            "button", "${panel}_update_paneld",
            """{"name":"Update ha-paneld","object_id":"${panel}_update_paneld","unique_id":"${panel}_update_paneld","command_topic":"$cmdUpdatePaneld","icon":"mdi:package-up","entity_category":"config",$avail,$device}""",
        )
        // Update entities for ha-paneld and the Companion app. The buttons above stay for one release
        // and share these command topics; see SoftwareUpdateEntities.
        softwareUpdateDiscovery = SoftwareUpdateDiscoveryContext(avail, device)
        reconcileSoftwareUpdates(announcing = true)
        registryExposable("kiosk_lock") {
            core.reconcile("kiosk_lock", force = true)
        }
        // Auto-brightness — optional on-panel adaptive engine. Sensitivity and an optional native HA
        // illuminance subscription are configured on the panel; HA retains only the enable switch.
        registryExposable("auto_brightness") {
            core.reconcile("auto_brightness", force = true)
        }
        // The native camera now owns this setting; withdraw its redundant legacy switch.
        publishConfig("switch", "${panel}_camera_enabled", "")
        core.reconcile("camera_enabled", force = true)
        // The snapshot as an `image` entity carrying a URL. Home Assistant stores the URL and fetches a
        // frame only when somebody looks at the card, so a still exists without the panel ever taking one
        // — and no frame is parked on the broker, which the camera contract forbids outright. Its own
        // availability topic carries the master switch, so an off camera reads `unavailable` instead of
        // showing a stale frame that would imply the panel is still watching.
        // Keep the saved legacy exposure preference for existing snapshot entities until their
        // retirement is evaluated. The setting no longer exposes a separate HA switch.
        cameraSnapshotAnnounced = capabilitySnapshot?.hasCamera == true &&
            config.haExposed("camera_enabled", false)
        // Published unconditionally: the empty payload is the retained tombstone that actually removes
        // the entity. Skipping the publish instead would leave an image announced earlier in this bridge
        // generation live in Home Assistant, because published topics accumulate within a generation and
        // stale pruning only runs when the discovery marker changes. `publishCameraSnapshot` below
        // clears the retained URL and drives availability offline, so discovery and state withdraw
        // together.
        publishConfig(
            "image", "${panel}_camera_snapshot",
            cameraSnapshotConfigPayload(cameraSnapshotAnnounced) {
                cameraSnapshotDiscoveryJson(panel, cameraSnapshotAvail, device)
            },
        )
        core.publishCameraSnapshot(refreshUrl = true)
        publishConfig(
            "sensor", "${panel}_storage_health",
            """{"name":"Storage health","object_id":"${panel}_storage_health","unique_id":"${panel}_storage_health","state_topic":"$stateStorageHealth","json_attributes_topic":"$attrStorageHealth","icon":"mdi:database-alert","entity_category":"diagnostic",$avail,$device}""",
        )
        core.reconcile("storage_health", force = true)
        core.reconcile("storage_health_attributes", force = true)
        // Zigbee router — only on panels with the Sonoff gateway package (NSPanel Pro). present()
        // costs a su exec; safe here because onConnected runs off the main thread.
        if (channelShape.zigbee) {
            publishConfig(
                "sensor", "${panel}_zigbee_gateway_health",
                """{"name":"Zigbee gateway health","object_id":"${panel}_zigbee_gateway_health","unique_id":"${panel}_zigbee_gateway_health","state_topic":"$stateZigbeeHealth","json_attributes_topic":"$attrZigbeeHealth","icon":"mdi:zigbee","entity_category":"diagnostic",$avail,$device}""",
            )
            core.publishZigbeeHealth()
        }

        // On-board relays (Smatek S9E `st_relay`). count() probes sysfs via su — off-main-thread here.
        for (n in 1..channelShape.relays) {
            publishConfig(
                "switch", "${panel}_relay$n",
                """{"name":"Relay $n","object_id":"${panel}_relay$n","unique_id":"${panel}_relay$n","command_topic":"ha-paneld/$panel/relay$n/set","state_topic":"ha-paneld/$panel/relay$n/state","icon":"mdi:electric-switch",$avail,$device}""",
            )
            core.reconcile("relay$n", force = true)
        }

        // S9E button LEDs (gpio147-150) — on/off lights, gated on the gpio nodes being present.
        for (n in 1..channelShape.buttonLeds) {
            publishConfig(
                "light", "${panel}_button_led$n",
                """{"name":"Button LED $n","object_id":"${panel}_button_led$n","unique_id":"${panel}_button_led$n","command_topic":"ha-paneld/$panel/button_led$n/set","state_topic":"ha-paneld/$panel/button_led$n/state","icon":"mdi:led-on",$avail,$device}""",
            )
            core.reconcile("button_led$n", force = true)
        }

        // CPU governor (select) — su panels with cpufreq. Three intent-based tiers (Performance /
        // Efficiency / Auto) rather than raw kernel governor names; CpuController maps each to this
        // SoC's governor (Auto = its dynamic governor — ramps up on interaction, idles low).
        if (channelShape.cpu) {
            registryExposable("cpu_governor", availableOverride = true) {
                core.baselineCpuGovernor()
                core.reconcile("cpu_governor", force = true)
            }
        }

        // Soft navbar (select) — overlay Back/Home/Recents bar for panels whose firmware hides the
        // native navbar. Published on all panels; Off by default, the user opts a panel in. Drawing
        // needs SYSTEM_ALERT_WINDOW (root-granted by NavbarController); a no-op select otherwise.
        registryExposable("navbar_mode") {
            core.reconcile("navbar", force = true)
        }

        // Persistent network adb (switch) — opt-in; root panels only. Standing LAN adb port when ON.
        if (channelShape.networkAdb) {
            registryExposable("network_adb", availableOverride = true) {
                core.reconcile("network_adb", force = true)
            }
        }

        // Diagnostic sensors (read-only) — all OPT-IN (haExposedByDefault=false), so a panel is silent
        // in HA until a pip is enabled. Publish the current value on expose; syncLocalState() refreshes
        // them each heartbeat tick with a deadband. Boot time is constant, so it's published only here.
        for (key in PanelStateCore.DIAG_KEYS) {
            registryExposable(key) { core.reconcile(key, force = true) }
        }

        // Room climate (CHT8305 panels only, e.g. TPA10) — real environmental sensors reported by
        // default from the Sensors card. Same publish machinery (deadbanded heartbeat refresh).
        if (hasCht8305) for (key in PanelStateCore.ROOM_KEYS) {
            registryExposable(key) { core.reconcile(key, force = true) }
        }

        // Panel actions (root via su; graceful no-op without it).
        publishConfig(
            "button", "${panel}_reload",
            """{"name":"Reload dashboard","object_id":"${panel}_reload","unique_id":"${panel}_reload","command_topic":"$cmdReload","icon":"mdi:web-refresh",$avail,$device}""",
        )
        publishConfig(
            "button", "${panel}_reboot",
            """{"name":"Reboot","object_id":"${panel}_reboot","unique_id":"${panel}_reboot","command_topic":"$cmdReboot","device_class":"restart","icon":"mdi:restart",$avail,$device}""",
        )

        // Every config topic above carried the device block, so the legacy Android-id identifier has now
        // been published for the last time and Home Assistant has had its chance to union the minted
        // identity onto the existing device. Retire it here, at the end of a complete pass, rather than
        // where the block is built: a pass that threw part-way has not published the whole device.
        if (legacyAid.isNotBlank()) config.retireLegacyAidBridge()
    }

    private fun jsonEsc(s: String): String = Json.esc(s)

    // Discovery configs are published NON-retained. Entities still rebuild on an HA restart because we
    // re-announce on HA's `homeassistant/status` = online birth message (+ on our own every connect) —
    // but a deleted/renamed/decommissioned entity's config no longer lingers retained on the broker to
    // resurrect it. (State topics + the availability LWT stay retained.)
    // Every discovery config topic published THIS session — recorded as we publish, so teardown/prune act
    // on exactly what we announced (no hardcoded drift). Thread-safe: publishConfig runs on the MQTT
    // thread; prune/clear may run from reconfigure on another thread.
    private val publishedConfigTopics = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * The Panel Assistant integration holds this panel's entities natively, so every discovery topic this
     * panel has ever announced receives a retained empty payload instead of a config: Home Assistant removes
     * the MQTT entities and keeps them removed across reconnects and its own restarts, because this runs
     * wherever discovery would. State, availability and the command subscription are untouched; commands
     * are already refused under the native authority. The value is persisted, so an outage or an
     * uninstalled integration never brings the entities back; only a hello reply that releases them does.
     */
    private fun withdrawDiscovery() {
        lastPublishedConfigUrl = configUrl()?.takeIf(String::isNotBlank)
        // The update entities converge on the heartbeat only while an announcement described them.
        softwareUpdateDiscovery = null
        // The historical superset plus whatever this generation announced outside it.
        val topics = knownConfigTopics() + synchronized(publishedConfigTopics) { publishedConfigTopics.toSet() }
        topics.forEach { topic ->
            publishedConfigTopics.add(topic)
            publish(topic, "", retain = true)
        }
        Log.i(TAG, "MQTT discovery withdrawn: ${topics.size} tombstones for $panel")
    }

    /** The persisted discovery value changed under a live connection; the next connect reads it anyway. */
    internal fun refreshPanelAssistantDiscovery() = requestReAnnounce()

    private fun publishConfig(component: String, objectId: String, payload: String) {
        val topic = keepConfig(component, objectId)
        // Live configs are deliberately non-retained. Empty payloads are different: retaining the
        // tombstone clears any config an older retain=true release left on the broker, so a hidden or
        // unavailable entity cannot resurrect while this panel is offline.
        publish(topic, withDefaultEntityId(component, objectId, payload), retain = mqttDiscoveryRetain(payload))
    }

    /** Account for a config this announcement leaves as it is, so [pruneStaleDiscovery] does not delete it. */
    private fun keepConfig(component: String, objectId: String): String =
        "homeassistant/$component/$objectId/config".also(publishedConfigTopics::add)

    /**
     * The historical SUPERSET of every entity ha-paneld has ever published for a panel — the tombstone
     * list. KEEP entities here even after they're removed from [publishDiscovery], so an upgrade can
     * actively clear a now-refactored-away entity (see [pruneStaleDiscovery]) instead of zombie-ing it.
     */
    private fun knownConfigTopics(): Set<String> = mqttKnownConfigTopics(panel)

    /**
     * Active upgrade migration: clear any KNOWN entity we did NOT publish this session — i.e. one a prior
     * previous runtime announced but this runtime refactored away (or no longer has as a capability) —
     * so it is removed from HA instead of lingering as a zombie. Called once after a core-version or
     * profile-revision change. Empty retained payload also clears configs an older, retain=true version
     * left on the broker.
     */
    private fun pruneStaleDiscovery(onComplete: (Boolean) -> Unit) {
        val published = synchronized(publishedConfigTopics) { publishedConfigTopics.toSet() }
        val stale = knownConfigTopics().filter { it !in published } + mqttRetiredStateTopics(panel)
        if (stale.isEmpty()) {
            onComplete(true)
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(stale.size)
        val allAcknowledged = AtomicBoolean(true)
        stale.forEach { topic ->
            publish(topic, "", retain = true) { acknowledged ->
                if (!acknowledged) allAcknowledged.set(false)
                if (remaining.decrementAndGet() == 0) onComplete(allAcknowledged.get())
            }
        }
        Log.i(TAG, "discovery prune: submitted ${stale.size} refactored-away/absent entities for $panel")
    }

    private fun publish(
        topic: String,
        payload: String,
        retain: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        // No CONNACK means there is no broker to receive a publication. In particular, HiveMQ's
        // pre-connect client can block inside send(); a local/native observation must not wait for it.
        val publishedGeneration = connectionGeneration.currentOrNull()
        if (!lifecycle.isOpen() || publishedGeneration == null) {
            onComplete?.invoke(false)
            return
        }
        // Attribute the ACK to the connection that submitted this publication. The transport filters
        // superseded clients, and this second generation check closes the detach-between-check-and-callback
        // race before broker progress reaches the watchdog.
        val expectedConnection = announcementConnection.get()
        val send: () -> Unit = {
            if (!lifecycle.isOpen() || !connectionGeneration.isCurrent(publishedGeneration)) {
                onComplete?.invoke(false)
            } else {
                try {
                    transport.publish(
                        topic = topic,
                        payload = payload.toByteArray(),
                        retain = retain,
                        onComplete = { acknowledged ->
                            if (acknowledged && connectionGeneration.isCurrent(publishedGeneration)) {
                                // HiveMQ completes this on its network loop; never wait for the bridge gate.
                                markOk(publishedGeneration)
                            }
                            onComplete?.invoke(acknowledged)
                        },
                        expectedConnection = expectedConnection,
                    )
                } catch (_: Exception) {
                    onComplete?.invoke(false)
                }
            }
        }
        // Announcement stays on its existing thread and retains its discovery/state wire order.
        if (!io.panelassistant.android.state.StateConverger.onPumpThread()) {
            send()
            return
        }
        try {
            statePublicationWorker.execute(send)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onComplete?.invoke(false)
            Log.w(TAG, "MQTT state publication queue full; topic=$topic")
        }
    }

    private fun subscribe(
        topicFilter: String,
        onMessage: (topic: String, payload: ByteArray, retained: Boolean) -> Unit,
    ) {
        transport.subscribe(
            topicFilter = topicFilter,
            expectedConnection = announcementConnection.get(),
            onMessage = onMessage,
        )
    }

    /**
     * Liveness probe: publish a monotonic-independent `last_seen_at` (epoch seconds) so a healthy link keeps
     * [lastOkMs] fresh even when nothing else is publishing, and a dead half-open link stops ACKing and
     * goes stale (→ watchdog reconnect). Non-retained (a stale retained heartbeat would be misleading).
     * Local observations have separate watchdog admission; this only probes the broker connection.
     */
    fun heartbeat() {
        if (!lifecycle.isOpen()) return
        if (state == "disabled" || state == "config-error") return
        // The transport probe remains sacrificial: if HiveMQ wedges, it owns no lifecycle mutation lock.
        runCatching { publish("ha-paneld/$panel/last_seen_at", (System.currentTimeMillis() / 1000).toString()) }
    }

    fun convergenceStatus(): String = stateOutbox.status().let {
        "${core.stateChannelKeys().size} channels · ${it.dirty} dirty · ${it.inFlight} in-flight · ${it.unknown} unknown · " +
            "ack ${it.successes}/${it.failures} · pending ${it.pending.joinToString(",").ifEmpty { "none" }}"
    }

    fun stop(
        deadline: MonotonicDeadline,
        publishOffline: Boolean = true,
        clearDiscovery: Boolean = false,
    ): MqttRetirement {
        val result = MqttRetirement()
        if (!retirement.compareAndSet(null, result)) return checkNotNull(retirement.get())

        // Fence every mutation source before spending the shared budget waiting on any one of them.
        lifecycle.closeAdmission()
        config.unregisterChangeListener(migrationConnectionListener)
        announcementReadiness.clear()
        connectionEventDispatcher.close()
        connectAnnouncementDispatcher.close()
        // A broker-facing send may ignore interruption. Its generation and lifecycle are fenced;
        // runtime replacement must not wait for this transport's blocked I/O to return.
        statePublicationWorker.shutdownNow()
        stateOutbox.close()
        authRecovery.supersedeRetry()
        authScheduler.shutdownNow()
        adbReassertWorker.closeAndJoin(0L)
        reannounceDispatcher.close()
        FeatureCosts.registry.setBacklog(FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE, 0)

        try {
            val lifecycleDrained = lifecycle.awaitDrained(deadline)
            val connectionEventDrained = connectionEventDispatcher.closeAndJoin(deadline.remainingMs())
            val connectAnnouncementDrained = connectAnnouncementDispatcher.closeAndJoin(deadline.remainingMs())
            val authDrained = authScheduler.shutdownNowAndAwait(deadline)
            val adbReassertDrained = adbReassertWorker.closeAndJoin(deadline.remainingMs())
            val reannounceDrained = reannounceDispatcher.closeAndJoin(deadline.remainingMs())
            val ownersDrained = lifecycleDrained && connectionEventDrained && connectAnnouncementDrained &&
                authDrained && adbReassertDrained && reannounceDrained

            // An admitted start may have created these immediately before the lifecycle gate drained.
            activeConnection = null
            connectionGeneration.clear()
            publishRecoveryLifecycleStateWithAddressFamily("disabled", null)
            result.ownersDrained.complete(ownersDrained)

            if (!ownersDrained) {
                Log.w(
                    TAG,
                    "MQTT retirement did not drain all mutation owners " +
                        "(lifecycle=$lifecycleDrained connection=$connectionEventDrained " +
                        "announce=$connectAnnouncementDrained " +
                        "auth=$authDrained " +
                        "adb=$adbReassertDrained reannounce=$reannounceDrained)",
                )
                transport.disconnectDetached()
                result.finalization.completeExceptionally(
                    IllegalStateException("MQTT mutation owners did not drain"),
                )
                return result
            }

            val finalPublications = buildList {
                if (clearDiscovery) knownConfigTopics().forEach {
                    add(MqttFinalPublish(it, byteArrayOf(), retain = true))
                }
                if (publishOffline) {
                    add(MqttFinalPublish(availabilityTopic, "offline".toByteArray(), retain = true))
                }
            }
            val transportFinalization = if (finalPublications.isNotEmpty()) {
                val remainingMs = deadline.remainingMs()
                if (remainingMs <= 0L) {
                    transport.disconnectDetached()
                    result.finalization.completeExceptionally(
                        IllegalStateException("MQTT retirement deadline expired before final publication"),
                    )
                    return result
                }
                transport.publishThenDisconnect(
                    finalPublications,
                    timeoutMs = remainingMs,
                )
            } else {
                transport.disconnectDetached()
            }
            transportFinalization.whenComplete { _, failure ->
                if (failure == null) result.finalization.complete(Unit)
                else result.finalization.completeExceptionally(failure)
            }
        } catch (failure: Throwable) {
            result.ownersDrained.complete(false)
            runCatching { transport.disconnectDetached() }
            result.finalization.completeExceptionally(failure)
        }
        return result
    }

    companion object {
        private const val TAG = "ha-paneld/mqtt"
        private val ANNOUNCEMENT_BOUNDARY_CONSUMED_HERE = AtomicReference<String?>(null)
        private const val MAX_COMMAND_PAYLOAD_BYTES = 64 * 1024
        private const val HA_LINK_TTL_MS = 6 * 3_600_000L // re-resolve the "Open in HA" link at most every 6h

        /**
         * Inject `default_entity_id` = `<component>.<objectId>` as the FIRST key of a discovery payload —
         * the field HA actually honours to pin the entity_id. HA's `object_id` discovery key is IGNORED
         * (it isn't in the MQTT schema; verified against live HA 2026.7.1 source + registry): MQTT entities
         * are ALWAYS `has_entity_name=True`, so without this the entity_id derives from the mutable
         * device/friendly name (`slug(device.name + entity.name)`) and drifts when the friendly name is
         * renamed. `default_entity_id` anchors it to the stable panel_id instead, while `device.name` stays
         * the pretty friendly name. HA applies it at REGISTRATION only, so existing entities keep their id
         * (no churn). Empty payload = tombstone (returned unchanged). Idempotent. Pure — tested in
         * DefaultEntityIdTest.
         */
        fun withDefaultEntityId(component: String, objectId: String, payload: String): String =
            if (payload.isNotEmpty() && !payload.contains("\"default_entity_id\"")) {
                """{"default_entity_id":"$component.$objectId",""" + payload.substring(1)
            } else {
                payload
            }

        // MQTT keepalive: PINGREQ every this-many idle seconds. Short enough to detect a dead link within
        // ~1.5× this, well under the service liveness-watchdog's stale threshold.
        private const val KEEPALIVE_SEC = 30
        private const val REANNOUNCE_DEBOUNCE_MS = 250L
        private const val REANNOUNCE_CAPABILITY_TTL_MS = 5_000L
        private val DIAG_DEADBANDS = mapOf(
            "diag_cpu" to 5.0, "diag_memory" to 3.0, "diag_soc_temp" to 0.5,
            "diag_wifi_rssi" to 3.0,
            "room_temp" to 0.2, "room_humidity" to 1.0,
        )
    }
}

/** Debounces a burst into one latest request while keeping callback admission constant-space. */
internal class MqttReannounceDispatcher(
    private val debounceMs: Long,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val perform: (Long) -> Unit,
) {
    private val requestedGeneration = AtomicLong()
    private val closed = AtomicBoolean()
    private val worker = LatestDispatcher.singleSlot<Long>("mqtt-discovery-reannounce", consume = worker@{ generation ->
        try {
            pause(debounceMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return@worker
        }
        if (isCurrent(generation)) perform(generation)
    })

    fun submit(): LatestDispatcher.Admission = worker.submit(requestedGeneration.incrementAndGet())

    fun pendingCount(): Int = worker.pendingCount()

    fun isCurrent(generation: Long): Boolean = !closed.get() && generation == requestedGeneration.get()

    /** Invalidate a retained birth that arrived while a newer connect announcement was in progress. */
    fun suppressPending() {
        requestedGeneration.incrementAndGet()
    }

    fun close() {
        closed.set(true)
        worker.closeAndJoin(0L)
    }

    fun closeAndJoin(timeoutMs: Long): Boolean {
        close()
        return worker.closeAndJoin(timeoutMs)
    }
}


/**
 * The historical discovery superset is deliberately explicit: if a registry entity is later removed,
 * its topic must remain here so an upgrade or panel-id change can still delete the old HA entity. The
 * coverage test requires every current registry entity to be added before it can ship.
 */
internal fun mqttKnownConfigTopics(panel: String): Set<String> = listOf(
    "light" to "${panel}_screen", "light" to "${panel}_led",
    "binary_sensor" to "${panel}_panel_assistant_required",
    "text" to "${panel}_navigate", "text" to "${panel}_home_dashboard", "event" to "${panel}_button",
    "button" to "${panel}_back", "button" to "${panel}_recents",
    "number" to "${panel}_volume", "sensor" to "${panel}_illuminance",
    "binary_sensor" to "${panel}_proximity",
    "sensor" to "${panel}_proximity_level",
    "binary_sensor" to "${panel}_auto_sleep_activity",
    "sensor" to "${panel}_temperature", "sensor" to "${panel}_humidity",
    "sensor" to "${panel}_room_temp", "sensor" to "${panel}_room_humidity",
    "light" to "${panel}_buttons", "switch" to "${panel}_wake_on_wave", "switch" to "${panel}_auto_sleep",
    "switch" to "${panel}_touch_sound", "switch" to "${panel}_watchdog", "switch" to "${panel}_kiosk_lock",
    "switch" to "${panel}_silence_boot_chime", "switch" to "${panel}_prevent_idle_dim",
    "switch" to "${panel}_companion_auto_update", "button" to "${panel}_update_companion",
    "select" to "${panel}_companion_update_channel",
    "switch" to "${panel}_self_update", "select" to "${panel}_update_channel",
    "button" to "${panel}_update_paneld",
    "update" to "${panel}_ha_paneld_update", "update" to "${panel}_ha_companion_update",
    "switch" to "${panel}_webview_auto_update",
    "switch" to "${panel}_zigbee_router",
    "switch" to "${panel}_auto_brightness", "number" to "${panel}_brightness_bias",
    "number" to "${panel}_ambient_lux",
    "switch" to "${panel}_relay1", "switch" to "${panel}_relay2",
    "switch" to "${panel}_relay3", "switch" to "${panel}_relay4",
    "light" to "${panel}_button_led1", "light" to "${panel}_button_led2",
    "light" to "${panel}_button_led3", "light" to "${panel}_button_led4",
    "select" to "${panel}_cpu_governor", "select" to "${panel}_navbar",
    "switch" to "${panel}_network_adb",
    "switch" to "${panel}_voice_assistant", "sensor" to "${panel}_voice_state",
    "sensor" to "${panel}_diag_ip", "sensor" to "${panel}_diag_cpu",
    "sensor" to "${panel}_diag_memory", "sensor" to "${panel}_diag_soc_temp",
    "sensor" to "${panel}_diag_boot", "sensor" to "${panel}_diag_wifi_ssid",
    "sensor" to "${panel}_diag_wifi_rssi",
    "sensor" to "${panel}_diag_wifi_outages_24h", "sensor" to "${panel}_diag_wifi_outages_7d",
    "sensor" to "${panel}_diag_schema_reconcile",
    "sensor" to "${panel}_storage_health",
    "switch" to "${panel}_camera_enabled", "image" to "${panel}_camera_snapshot",
    "button" to "${panel}_reload", "button" to "${panel}_reboot",
    "button" to "${panel}_launcher", "button" to "${panel}_home",
    "button" to "${panel}_admin_launcher",
).mapTo(linkedSetOf()) { (comp, obj) -> "homeassistant/$comp/$obj/config" }

internal fun mqttButtonEventTypes(profileEventTypes: Set<String>): List<String> =
    (setOf(
        "KEYCODE_POWER", "KEYCODE_MUTE", "KEYCODE_F", "KEYCODE_F1", "KEYCODE_F2", "KEYCODE_F3",
        "KEYCODE_F4", "KEYCODE_BACK", "KEYCODE_HOME", "KEYCODE_DPAD_CENTER", "KEYCODE_VOLUME_UP",
        "KEYCODE_VOLUME_DOWN",
    ) + profileEventTypes).sorted()

internal data class MqttCleanupPublication(val topic: String, val payload: String, val retain: Boolean)

/**
 * Durable identity for the discovery shape which last completed stale-topic pruning. The explicit
 * shape revision forces cleanup when entities are retired without an app-version change. A non-empty
 * profile identity is length-prefixed so arbitrary profile ids cannot collide with the other fields.
 */
internal fun mqttDiscoveryCleanupMarker(
    coreVersion: String,
    profileIdentity: String,
    brokerIdentity: String,
    discoveryShapeRevision: Int = MQTT_DISCOVERY_SHAPE_REVISION,
): String {
    require(discoveryShapeRevision > 0) { "discovery shape revision must be positive" }
    val shape = "$coreVersion|d$discoveryShapeRevision|b${brokerIdentity.length}:$brokerIdentity"
    return if (profileIdentity.isEmpty()) shape else "$shape|p${profileIdentity.length}:$profileIdentity"
}

private const val MQTT_DISCOVERY_SHAPE_REVISION = 6

/** Cleanup for a renamed panel is owned by the replacement connection and therefore retries with it. */
internal fun mqttStalePanelCleanup(stalePanel: String?, currentPanel: String): List<MqttCleanupPublication> {
    if (stalePanel == null || stalePanel == currentPanel) return emptyList()
    return buildList {
        mqttKnownConfigTopics(stalePanel).forEach { add(MqttCleanupPublication(it, "", retain = true)) }
        mqttRetiredStateTopics(stalePanel).forEach { add(MqttCleanupPublication(it, "", retain = true)) }
        add(MqttCleanupPublication("ha-paneld/$stalePanel/availability", "offline", retain = true))
    }
}

internal fun mqttRetiredStateTopics(panel: String): Set<String> = setOf(
    // Auto-update preferences no longer have current producers or command handlers.
    "ha-paneld/$panel/companion_auto_update/state",
    "ha-paneld/$panel/companion_update_channel/state",
    "ha-paneld/$panel/self_update/state",
    "ha-paneld/$panel/webview_auto_update/state",
    "ha-paneld/$panel/update_channel/state",
    "ha-paneld/$panel/diag_schema_reconcile/state",
    // Retired before release; its retained state must be cleared too, or a same-version upgrade
    // leaves a ghost entity holding a stale count.
    "ha-paneld/$panel/diag_wifi_outages_7d/state",
    "ha-paneld/$panel/diag_wifi_outages_7d/attributes",
)

/** Avoid a late offline/online race on an in-place rebuild; retire identities or old brokers explicitly.
 * Broker sameness uses the single family-recovery canonicalizer; an unparseable broker falls back to its
 * trimmed literal so two genuinely different unusable strings still retire the old availability. */
internal fun mqttReconfigurePublishesOffline(
    oldPanel: String,
    newPanel: String,
    oldBroker: String,
    newBroker: String,
): Boolean = oldPanel != newPanel || mqttReconfigureBrokerIdentity(oldBroker) != mqttReconfigureBrokerIdentity(newBroker)

private fun mqttReconfigureBrokerIdentity(raw: String): String =
    mqttFamilyBrokerIdentity(raw) ?: raw.trim()

internal fun mqttAcceptsCommand(stopped: Boolean, retained: Boolean, panelAssistantAuthority: String = ""): Boolean =
    !stopped && !retained && panelAssistantAuthority != PanelAssistantTransportProtocol.AUTHORITY_NATIVE


internal fun mqttIsHaOnline(payload: ByteArray): Boolean =
    String(payload, Charsets.UTF_8).trim().equals("online", ignoreCase = true)

internal fun mqttDiscoveryRetain(payload: String): Boolean = payload.isEmpty()

internal fun shouldRepublishDiscoveryAddress(
    connected: Boolean,
    lastPublishedUrl: String?,
    currentUrl: String?,
): Boolean = connected && lastPublishedUrl != currentUrl
