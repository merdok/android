package io.panelassistant.android.mqtt

import io.panelassistant.android.state.SoftwareComponent
import io.panelassistant.android.state.SoftwareUpdateInputs
import io.panelassistant.android.state.SoftwareUpdates

import io.panelassistant.android.appVersion
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.Json
import io.panelassistant.android.util.ReleaseCatalog
import io.panelassistant.android.util.UpdateChecker

/**
 * Home Assistant MQTT `update` entities for the two release-backed apps ha-paneld manages: itself and
 * the HA Companion app. Pure: the bridge supplies one [SoftwareUpdateInputs] per component and publishes
 * what these functions return, so discovery bytes, state, admission and discovery-shape transitions are
 * all decided here and unit-tested without a broker.
 *
 * The entities replace the stateless update buttons. For one release both are published, and they
 * share the buttons' command topics: Home Assistant's button sends `PRESS`, the update entity sends
 * [PAYLOAD_INSTALL], and nothing else is accepted.
 */
internal enum class SoftwareCommand { INSTALL, LEGACY_PRESS, REJECTED }

internal sealed interface SoftwareCommandOutcome {
    data object Legacy : SoftwareCommandOutcome
    data class Started(val tag: String) : SoftwareCommandOutcome
    /** Admitted, but the shared destructive-operation lane was already owned. */
    data class Busy(val tag: String) : SoftwareCommandOutcome
    data class Refused(val reason: String) : SoftwareCommandOutcome
}

internal sealed interface SoftwareInstallAdmission {
    /** Install exactly [tag]; [downgrade] is the explicit Companion safety-cap remediation. */
    data class Admitted(val tag: String, val downgrade: Boolean = false) : SoftwareInstallAdmission
    data class Refused(val reason: String) : SoftwareInstallAdmission
}

/** What Home Assistant has been told about one entity. Persisted so a restart can still clear it. */
internal data class SoftwareDiscoveryShape(
    val announced: Boolean,
    val installable: Boolean,
    val hasLatest: Boolean,
) {
    fun encode(): String = "v1:" + listOf(announced, installable, hasLatest).joinToString("") { if (it) "1" else "0" }

    companion object {
        fun decode(raw: String?): SoftwareDiscoveryShape? {
            if (raw == null || raw.length != 6 || !raw.startsWith("v1:")) return null
            val bits = raw.substring(3).map {
                when (it) {
                    '1' -> true
                    '0' -> false
                    else -> return null
                }
            }
            return SoftwareDiscoveryShape(bits[0], bits[1], bits[2])
        }
    }
}

internal data class SoftwarePublication(val topic: String, val payload: String, val retain: Boolean)

internal enum class SoftwareDiscoveryStep {
    NONE,
    ANNOUNCE,
    /** Home Assistant cannot unset `latest_version` from state, so the entity is recreated. */
    CLEAR_THEN_ANNOUNCE,
    WITHDRAW,
}

internal object SoftwareUpdateEntities {
    const val PAYLOAD_INSTALL = "install"
    /** Home Assistant's default MQTT button payload; the legacy buttons send it to the same topics. */
    const val PAYLOAD_LEGACY_PRESS = "PRESS"

    fun objectId(panel: String, component: SoftwareComponent) = "${panel}_${component.objectSuffix}"
    fun uniqueId(panel: String, component: SoftwareComponent) = "${objectId(panel, component)}_update"
    fun configTopic(panel: String, component: SoftwareComponent) =
        "homeassistant/update/${uniqueId(panel, component)}/config"
    fun stateTopic(panel: String, component: SoftwareComponent) =
        "ha-paneld/$panel/update/${component.objectSuffix}/state"
    fun commandTopic(panel: String, component: SoftwareComponent) = "ha-paneld/$panel/update_${component.wire}/set"

    /** Payloads are matched exactly: a URL, a version, JSON or a changed case is never an install. */
    fun classifyCommand(payload: String): SoftwareCommand = when (payload) {
        PAYLOAD_INSTALL -> SoftwareCommand.INSTALL
        PAYLOAD_LEGACY_PRESS -> SoftwareCommand.LEGACY_PRESS
        else -> SoftwareCommand.REJECTED
    }

    /**
     * Route one command received on an update topic. The legacy button's `PRESS` keeps its existing
     * behaviour for the coexistence release; `install` is admitted against a fresh [inputs] read and
     * then approved and started with the catalog tag it resolved, never with anything from the payload;
     * every other payload is refused before inputs are read. [authorize] throws to refuse approval.
     */
    fun route(
        payload: String,
        inputs: () -> SoftwareUpdateInputs?,
        legacy: () -> Unit,
        authorize: (tag: String) -> Unit,
        install: (tag: String) -> Boolean,
    ): SoftwareCommandOutcome = when (classifyCommand(payload)) {
        SoftwareCommand.REJECTED -> SoftwareCommandOutcome.Refused("payload")
        SoftwareCommand.LEGACY_PRESS -> {
            legacy()
            SoftwareCommandOutcome.Legacy
        }
        SoftwareCommand.INSTALL -> when (val admission = inputs()?.let(::admit)
            ?: SoftwareInstallAdmission.Refused("unwired")) {
            is SoftwareInstallAdmission.Refused -> SoftwareCommandOutcome.Refused(admission.reason)
            is SoftwareInstallAdmission.Admitted -> {
                authorize(admission.tag)
                if (install(admission.tag)) SoftwareCommandOutcome.Started(admission.tag)
                else SoftwareCommandOutcome.Busy(admission.tag)
            }
        }
    }






    /** An install command is advertised only when this panel can actually carry one out. */
    fun installable(inputs: SoftwareUpdateInputs): Boolean =
        !SoftwareUpdates.withheld(inputs) && !inputs.externallyManaged && inputs.canInstall && inputs.target != null

    fun shape(inputs: SoftwareUpdateInputs): SoftwareDiscoveryShape =
        if (SoftwareUpdates.withheld(inputs)) SoftwareDiscoveryShape(announced = false, installable = false, hasLatest = false)
        else SoftwareDiscoveryShape(
            announced = true,
            installable = installable(inputs),
            hasLatest = SoftwareUpdates.latestVersion(inputs) != null,
        )

    /**
     * The discovery work needed to move Home Assistant from [previous] to [current]. [announcing] is a
     * connect or Home Assistant birth, where non-retained discovery must be sent again regardless.
     */
    fun transition(
        previous: SoftwareDiscoveryShape?,
        current: SoftwareDiscoveryShape,
        announcing: Boolean,
    ): SoftwareDiscoveryStep = when {
        !current.announced ->
            if (announcing || previous?.announced != false) SoftwareDiscoveryStep.WITHDRAW else SoftwareDiscoveryStep.NONE
        previous?.announced == true && previous.hasLatest && !current.hasLatest ->
            SoftwareDiscoveryStep.CLEAR_THEN_ANNOUNCE
        announcing || previous != current -> SoftwareDiscoveryStep.ANNOUNCE
        else -> SoftwareDiscoveryStep.NONE
    }

    /**
     * The exact MQTT publications, in order, that carry out [step] for [inputs]. The state channel's own
     * convergence follows them: the retained state for an announced entity, an empty payload for a
     * withdrawn one. Live discovery is non-retained like every other entity; tombstones are retained.
     */
    fun discoveryPlan(
        panel: String,
        inputs: SoftwareUpdateInputs,
        step: SoftwareDiscoveryStep,
        availability: String,
        device: String,
    ): List<SoftwarePublication> {
        val component = inputs.component
        val config = configTopic(panel, component)
        fun announce() = SoftwarePublication(
            config,
            discoveryJson(panel, component, installable(inputs), availability, device),
            retain = false,
        )
        val tombstone = SoftwarePublication(config, "", retain = true)
        return when (step) {
            SoftwareDiscoveryStep.NONE -> emptyList()
            SoftwareDiscoveryStep.ANNOUNCE -> listOf(announce())
            // The new retained state must be on the broker before the recreated entity subscribes, or
            // the entity would restore the very version this step exists to clear.
            SoftwareDiscoveryStep.CLEAR_THEN_ANNOUNCE -> listOf(
                SoftwarePublication(stateTopic(panel, component), SoftwareUpdates.stateJson(inputs), retain = true),
                tombstone,
                announce(),
            )
            SoftwareDiscoveryStep.WITHDRAW -> listOf(tombstone)
        }
    }

    fun discoveryJson(
        panel: String,
        component: SoftwareComponent,
        installable: Boolean,
        availability: String,
        device: String,
    ): String {
        val objectId = objectId(panel, component)
        val command = if (installable) {
            """"command_topic":"${commandTopic(panel, component)}","payload_install":"$PAYLOAD_INSTALL","""
        } else ""
        return """{"default_entity_id":"update.$objectId","name":"${component.entityName}",""" +
            """"object_id":"$objectId","unique_id":"${uniqueId(panel, component)}",""" +
            """"state_topic":"${stateTopic(panel, component)}",$command""" +
            """"entity_category":"config",$availability,$device}"""
    }






    /**
     * Strict admission for an `install` command: a known, installable component; a target resolved from
     * the signed release catalog under the current policy and carrying a valid tag; and strictly newer
     * than what is installed. The one deliberate exception is a Companion already above this panel's
     * safety cap, where installing is the explicit remediation the entity advertised. The installer then
     * verifies the pinned signer and database compatibility before anything is replaced.
     */
    fun admit(inputs: SoftwareUpdateInputs): SoftwareInstallAdmission {
        fun refused(reason: String) = SoftwareInstallAdmission.Refused(reason)
        if (inputs.externallyManaged) return refused("externally-managed")
        if (!inputs.canInstall) return refused("no-install-route")
        if (inputs.installing) return refused("in-progress")
        val target = inputs.target ?: return refused("unresolved")
        if (!ReleaseCatalog.validTag(target.tag)) return refused("invalid-tag")
        if (inputs.component == SoftwareComponent.COMPANION &&
            !CompanionInstaller.withinCap(target.version, inputs.cap)
        ) return refused("exceeds-cap")
        // Neither component installs from nothing here: the Companion entity is withheld while the app
        // is absent, and a first Companion install stays on the Install page.
        val installed = inputs.installedVersion ?: return refused("not-installed")
        val comparison = UpdateChecker.compareVersions(
            SoftwareUpdates.normalized(inputs.component, target.version),
            SoftwareUpdates.normalized(inputs.component, installed),
        ) ?: return refused("uncomparable")
        return when {
            comparison > 0 -> SoftwareInstallAdmission.Admitted(target.tag)
            SoftwareUpdates.companionAboveCap(inputs) -> SoftwareInstallAdmission.Admitted(target.tag, downgrade = true)
            comparison == 0 -> refused("up-to-date")
            else -> refused("downgrade")
        }
    }
}
