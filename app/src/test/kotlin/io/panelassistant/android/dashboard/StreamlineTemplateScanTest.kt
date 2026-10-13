package io.panelassistant.android.dashboard

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** Entities a Streamline Card template names are found before the first filtered paint. */
class StreamlineTemplateScanTest {
    // Trimmed from a real remote template file (streamline-card 0.2.2): string, list-form and default
    // variables, a nested invocation, and JavaScript left as text.
    private val remoteYaml = """
        room_thermostat_template_v3:
          default:
            - room: habitat
          card:
            type: custom:bubble-card
            card_type: climate
            entity: 'climate.[[room]]_thermostat'
            name: '[[room]]'
            sub_button:
              - name: Openings
                entity: binary_sensor.[[room]]_window_group
                visibility:
                  - condition: template
                    value_template: "{{ states('binary_sensor.[[room]]_window_group') == 'on' }}"
            styles: |-
              ${'$'}{hass.states['binary_sensor.[[room]]_occupancy_group']?.state}
        camera_v3:
          default:
            - angle: north
            - stream: uld
          card:
            type: custom:webrtc-camera
            streams:
              - url: '[[angle]][[stream]]'
            shortcuts:
              - name: Camera Light
                service: light.toggle
                service_data:
                  entity_id: light.camera_[[angle]]_light
        grid_template:
          card:
            type: grid
            columns: '[[columns]]'
            cards:
              - type: custom:streamline-card
                template: all_lights_template
        all_lights_template:
          card:
            type: custom:mushroom-light-card
            entity: light.downstairs_lights_group
        loop_template:
          card:
            type: custom:streamline-card
            template: loop_template
    """.trimIndent()

    private fun scan(cards: String, root: String = "", remote: String? = remoteYaml) =
        EntityLearningProtocol.scanDashboard(
            """{$root"views":[{"cards":[$cards]}]}""",
            remote?.let { requireNotNull(StreamlineTemplates.parse(it)) },
        )

    @Test fun stringVariablesNameTheEntitiesTheTemplateBuilds() {
        val ids = scan("""{"type":"custom:streamline-card","template":"room_thermostat_template_v3","variables":{"room":"office"}}""").entityIds
        assertTrue(ids.containsAll(listOf(
            "climate.office_thermostat", "binary_sensor.office_window_group", "binary_sensor.office_occupancy_group",
        )))
        assertFalse("the card's variable beats the template default", "climate.habitat_thermostat" in ids)
    }

    @Test fun listFormVariablesOverrideOnlyTheirOwnDefaults() {
        val ids = scan("""{"type":"custom:streamline-card","template":"camera_v3","variables":[{"angle":"south"}]}""").entityIds
        assertTrue("light.camera_south_light" in ids)
        assertFalse("light.camera_north_light" in ids)
    }

    @Test fun defaultsApplyWhenTheCardHasNoVariables() {
        val ids = scan("""{"type":"custom:streamline-card","template":"room_thermostat_template_v3"}""").entityIds
        assertTrue("climate.habitat_thermostat" in ids)
    }

    @Test fun nestedInvocationsAreExpandedToo() {
        val ids = scan("""{"type":"custom:streamline-card","template":"grid_template","variables":{"columns":3}}""").entityIds
        assertTrue("light.downstairs_lights_group" in ids)
    }

    @Test fun aTemplateKeptInTheDashboardWinsOverTheRemoteFile() {
        val ids = scan(
            """{"type":"custom:streamline-card","template":"all_lights_template"}""",
            root = """"streamline_templates":{"all_lights_template":{"card":{"type":"tile","entity":"light.inline_group"}}},""",
        ).entityIds
        assertTrue("light.inline_group" in ids)
        assertFalse("light.downstairs_lights_group" in ids)
    }

    @Test fun dashboardTemplatesExpandWithoutTheRemoteFile() {
        val ids = scan(
            """{"type":"custom:streamline-card","template":"room","variables":{"room":"study"}}""",
            root = """"streamline_templates":{"room":{"card":{"type":"tile","entity":"climate.[[room]]_thermostat"}}},""",
            remote = null,
        ).entityIds
        assertTrue("climate.study_thermostat" in ids)
    }

    @Test fun anUnknownOrSelfReferencingTemplateAddsNothingAndStaysUnresolved() {
        val unknown = scan("""{"type":"custom:streamline-card","template":"missing","variables":{"room":"x"}}""")
        assertTrue(unknown.entityIds.isEmpty())
        assertTrue(unknown.unresolved.any { it.endsWith(":custom:streamline-card") })
        assertTrue(scan("""{"type":"custom:streamline-card","template":"loop_template"}""").entityIds.isEmpty())
    }

    @Test fun aSelfReferencingTemplateIsExpandedOnceNotUntilTheDepthBound() {
        val root = org.json.JSONObject("""{"views":[{"cards":[{"type":"custom:streamline-card","template":"loop_template"}]}]}""")
        assertEquals(1, StreamlineTemplates.expandedCards(root, StreamlineTemplates.parse(remoteYaml)).length())
    }

    @Test fun expansionAddsDependenciesButNotTemplateDiagnostics() {
        val card = """{"type":"custom:streamline-card","template":"room_thermostat_template_v3","variables":{"room":"office"}}"""
        val without = scan(card, remote = null)
        val with = scan(card)
        assertEquals(without.unresolved, with.unresolved)
        assertEquals(without.dynamicExpressions, with.dynamicExpressions)
        assertTrue(with.entityIds.size > without.entityIds.size)
    }

    @Test fun bubbleCardsDependOnTheirModuleRegistry() {
        val ids = scan("""{"type":"custom:streamline-card","template":"room_thermostat_template_v3"}""").entityIds
        assertTrue("sensor.bubble_card_modules" in ids)
        assertFalse("sensor.bubble_card_modules" in scan("""{"type":"tile","entity":"light.a"}""").entityIds)
    }

    @Test fun fetchFollowsTheCardsPathOrderWithoutCredentials() {
        val requests = ConcurrentLinkedQueue<String>()
        val authorization = ConcurrentLinkedQueue<String>()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("{path...}") {
                    requests.add(call.request.path())
                    call.request.header("Authorization")?.let(authorization::add)
                    when (call.request.path()) {
                        "/local/streamline-card/streamline_templates.yaml" ->
                            call.respondText("- not a template map", ContentType.Text.Plain)
                        "/local/community/streamline-card/streamline_templates.yaml" ->
                            call.respondText(remoteYaml, ContentType.Text.Plain)
                        else -> call.respondText("", status = HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)
        try {
            val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().single().port }
            var guarded = 0
            val templates = StreamlineTemplates.fetch("http://127.0.0.1:$port/", 5_000) { guarded++ }
            assertNotNull(templates?.optJSONObject("camera_v3"))
            assertEquals(StreamlineTemplates.REMOTE_PATHS, requests.toList())
            assertEquals(3, guarded)
            assertTrue(authorization.isEmpty())
        } finally {
            server.stop(0, 0)
        }
    }

    @Test fun nestedFanOutStopsAtTheExpansionBound() {
        // Each level invokes the next ten times: unbounded, four levels expand to over 11,000 cards.
        val yaml = (0..4).joinToString("\n") { level ->
            val child = if (level < 4) "{type: custom:streamline-card, template: t${level + 1}}" else "{type: tile, entity: light.leaf}"
            "t$level:\n  card:\n    type: grid\n    cards: [${List(10) { child }.joinToString(", ")}]"
        }
        val root = org.json.JSONObject("""{"views":[{"cards":[{"type":"custom:streamline-card","template":"t0"}]}]}""")
        val cards = StreamlineTemplates.expandedCards(root, requireNotNull(StreamlineTemplates.parse(yaml)))
        assertTrue(cards.length() in 1..1_000)
    }

    @Test fun aMalformedBaseIsNoTemplatesRatherThanAFailedSync() {
        assertNull(StreamlineTemplates.fetch("not a url", 1_000))
    }

    @Test fun fetchGivesUpQuietlyWhenNoPathAnswers() {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing { get("{path...}") { call.respondText("", status = HttpStatusCode.NotFound) } }
        }.start(wait = false)
        try {
            val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().single().port }
            assertNull(StreamlineTemplates.fetch("http://127.0.0.1:$port", 5_000))
        } finally {
            server.stop(0, 0)
        }
    }
}
