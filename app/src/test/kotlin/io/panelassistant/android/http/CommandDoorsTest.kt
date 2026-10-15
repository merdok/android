package io.panelassistant.android.http

import io.ktor.client.request.accept
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.LiveSettingAuthority
import io.panelassistant.android.navbarMode
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.ControlApplyOutcome
import io.panelassistant.android.control.TouchSoundHardware
import io.panelassistant.android.control.TouchSoundState
import io.panelassistant.android.panelassistant.PanelAssistantApprovalState
import io.panelassistant.android.panelassistant.PanelAssistantChannelCatalog
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.PanelAssistantCommandSink
import io.panelassistant.android.panelassistant.PanelAssistantSession
import io.panelassistant.android.panelassistant.PanelAssistantSessionEvent
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One setting value, whichever door it comes through: Panel Assistant's session processor and the HTTP config
 * route each decode their own input once, and the panel's dispatcher and hardware end up the same.
 */
internal class CommandDoorsTest : PanelRuntimeRig() {
    private val rigs = mutableListOf<Rig>()

    @After fun tearDown() {
        rigs.forEach { it.close() }
    }

    private fun doorRig(): Rig = rig(
        navbarAcknowledges = true,
        cpuGovernor = "schedutil",
        touchSoundHardware = AcceptingTouchSound(),
        deviceSecure = false,
        // The config route reads the panel's identity, which a running service has always settled.
        configure = { config ->
            config.ensurePanelId { "door_panel" }
            config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Door panel")
        },
    ).also { it.start(); rigs += it }

    @Test fun panelAssistantCommandsReachTheirHandlersAsTypedValues() {
        val rig = doorRig()
        assertTrue(rig.config.commitTouchSound(true))
        val session = PanelAssistantSession(5, "session-token", listOf("state", "commands", "approval"), "0.3.0")
        val processor = PanelAssistantCommandProcessor(
            // The service's sink: the runtime's dispatcher, under the Panel Assistant origin.
            sink = object : PanelAssistantCommandSink {
                override fun submit(command: PanelAssistantCommand, done: (PanelAssistantCommandResult) -> Unit) =
                    rig.commands.submit(command, CommandOrigin.panelAssistant(command.requestId), done)

                override fun approvalState(approvalId: String) = PanelAssistantApprovalState.ABSENT

                override fun withdrawApproval(approvalId: String) = Unit
            },
            session = session,
            channels = listOf("navbar", "cpu_governor", "volume", "led", "touch_sound").map {
                requireNotNull(PanelAssistantChannelCatalog.describe(it)) { it }
            },
            monotonicMillis = { System.nanoTime() / 1_000_000 },
            approvalTtlMs = 600_000L,
            log = {},
        )
        var nextId = 0L
        fun command(channel: String, value: Any?): String {
            val id = "c${nextId++}"
            processor.onCommand(PanelAssistantSessionEvent.Command(id, session.token, channel, value, 10_000L))
            val deadline = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < deadline) {
                processor.next(nextId)?.let { return JSONObject(it).getString("outcome") }
                Thread.sleep(5)
            }
            error("$channel was never answered")
        }

        assertEquals("applied", command("navbar", "swipe_reveal"))
        assertEquals("Swipe reveal", rig.config.navbarMode)
        assertEquals("applied", command("cpu_governor", "performance"))
        assertEquals("performance", rig.metrics.governor)
        assertEquals("applied", command("volume", 40))
        assertEquals(40, rig.audio.percent)
        val led = JSONObject().put("on", true).put("color", JSONObject().put("r", 1).put("g", 2).put("b", 3)).put("effect", "pulse")
        assertEquals("applied", command("led", led))
        assertEquals("1,255,1,2,3", rig.config.lastLed)
        assertEquals("pulse", rig.config.lastLedEffect)
        assertEquals("applied", command("touch_sound", false))
        assertFalse(rig.config.touchSound)
    }

    @Test fun httpConfigValuesHaveTheEffectOfTheSamePanelAssistantCommand() {
        val rig = doorRig()
        assertTrue(rig.config.commitTouchSound(true))
        val authority = LiveSettingAuthority(setOf("navbar_mode", "touch_sound"))
        val server = routeServer(rig.config) { key, normalized ->
            val previous = rig.config.getRaw(requireNotNull(SettingsRegistry.spec(key)))
            authority.applyOrQueueOutcome(key, normalized, previous) { appliedKey, value, before ->
                rig.commands.applySetting(appliedKey, value, before)
            }
        }

        testApplication {
            application {
                routing { route("/api/v1") { with(server) { installDirectConfigPostRoute { Capabilities() } } } }
            }
            val response = client.submitForm(
                url = "/api/v1/config",
                formParameters = Parameters.build {
                    append("navbar_mode", "Swipe reveal")
                    append("touch_sound", "false")
                },
            ) { accept(ContentType.Application.Json) }
            val body = response.bodyAsText()
            assertEquals(body, HttpStatusCode.OK, response.status)
            assertEquals(body, "Swipe reveal", JSONObject(body).getJSONObject("settings").getString("navbar_mode"))
        }

        assertEquals("Swipe reveal", rig.config.navbarMode)
        assertFalse(rig.config.touchSound)
    }

    /** Touch-sound hardware that takes every transition. */
    private class AcceptingTouchSound : TouchSoundHardware {
        override fun capture(): TouchSoundState? = null
        override fun enable() = ControlApplyOutcome.APPLIED
        override fun restore(state: TouchSoundState) = ControlApplyOutcome.APPLIED
        override fun disableConservatively() = ControlApplyOutcome.APPLIED
    }
}
