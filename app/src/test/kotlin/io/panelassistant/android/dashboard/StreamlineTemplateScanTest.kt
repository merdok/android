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
import org.junit.Assert.assertThrows
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

    /** Serves [files] by path; every other path is 404. Returns the templates [StreamlineTemplates.fetch] took and the paths asked for. */
    private fun fetchFrom(files: Map<String, String>): Pair<org.json.JSONObject?, List<String>> {
        val requests = ConcurrentLinkedQueue<String>()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("{path...}") {
                    requests.add(call.request.path())
                    val body = files[call.request.path()]
                    if (body != null) call.respondText(body, ContentType.Text.Plain)
                    else call.respondText("", status = HttpStatusCode.NotFound)
                }
            }
        }.start(wait = false)
        try {
            val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().single().port }
            return StreamlineTemplates.fetch("http://127.0.0.1:$port", 5_000) to requests.toList()
        } finally {
            server.stop(0, 0)
        }
    }

    // streamline-card 0.2.2 (src/evaluateYaml.js) parses with a custom `!include` tag and replaces the
    // tagged value with the included file, resolved relative to the template file's own URL.
    @Test fun aRemoteFileUsingIncludeStillExpandsItsInlineTemplates() {
        val (templates, requests) = fetchFrom(mapOf(
            "/hacsfiles/streamline-card/streamline_templates.yaml" to """
                included_tile: !include included_tile.yaml
                inline_tile:
                  card:
                    type: tile
                    entity: light.[[room]]_inline
            """.trimIndent(),
            "/hacsfiles/streamline-card/included_tile.yaml" to "card:\n  type: tile\n  entity: light.[[room]]_included\n",
        ))
        assertEquals("the first path answered, so no other template path is tried",
            "/hacsfiles/streamline-card/streamline_templates.yaml", requests.first())
        assertFalse("the first path's file was not discarded", "/local/streamline-card/streamline_templates.yaml" in requests)
        val ids = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:streamline-card","template":"inline_tile","variables":{"room":"study"}}]}]}""",
            templates,
        ).entityIds
        assertTrue("light.study_inline" in ids)
    }

    /** Serves whatever [body] answers for a path (404 for null) on its own port; [block] gets the port and the paths asked for. */
    private fun <T> serving(body: (String) -> String?, block: (Int, ConcurrentLinkedQueue<String>) -> T): T {
        val requests = ConcurrentLinkedQueue<String>()
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("{path...}") {
                    requests.add(call.request.path())
                    val text = body(call.request.path())
                    if (text != null) call.respondText(text, ContentType.Text.Plain)
                    else call.respondText("", status = HttpStatusCode.NotFound)
                }
            }
        }.start(wait = false)
        try {
            return block(kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().single().port }, requests)
        } finally {
            server.stop(0, 0)
        }
    }

    private fun idsOf(templates: org.json.JSONObject?, template: String) = EntityLearningProtocol.scanDashboard(
        """{"views":[{"cards":[{"type":"custom:streamline-card","template":"$template","variables":{"room":"hall"}}]}]}""",
        templates,
    ).entityIds.toSet()

    private val dir = "/hacsfiles/streamline-card/"
    private val includedTile = "card:\n  type: tile\n  entity: light.[[room]]_included\n"

    // The card resolves an include relative to the file that names it, nested includes relative to their own file.
    @Test fun anIncludedTemplatesEntitiesAreScannedThroughTheRouteGuard() {
        val files = mapOf(
            "${dir}streamline_templates.yaml" to "included_tile: !include parts/included_tile.yaml\n",
            "${dir}parts/included_tile.yaml" to "card: !include ../cards/tile.yaml\n",
            "${dir}cards/tile.yaml" to "type: tile\nentity: light.[[room]]_nested\n",
        )
        serving(files::get) { port, requests ->
            var guarded = 0
            val templates = StreamlineTemplates.fetch("http://127.0.0.1:$port", 5_000) { guarded++ }
            assertEquals(setOf("light.hall_nested"), idsOf(templates, "included_tile"))
            assertEquals("every request, includes too, passes the route guard", requests.size, guarded)
        }
        // On the last template path no later request repeats the guard, so only the include's own check can end the sync.
        val last = "/local/community/streamline-card/"
        serving(files.mapKeys { (path, _) -> path.replace(dir, last) }::get) { port, _ ->
            var calls = 0
            assertThrows(IllegalStateException::class.java) {
                StreamlineTemplates.fetch("http://127.0.0.1:$port", 5_000) { check(++calls < 4) { "route changed" } }
            }
            assertEquals("the guard stopped the include before its request", 4, calls)
        }
    }

    // An include the card would read but the panel refuses reads as an empty template, learned at runtime.
    @Test fun anIncludeFromAnotherOriginIsRefusedAndTheRestOfTheFileKept() {
        serving({ "card:\n  type: tile\n  entity: light.elsewhere\n" }) { other, elsewhere ->
            val files = mapOf(
                "${dir}streamline_templates.yaml" to """
                    included_tile: !include included_tile.yaml
                    absolute: !include http://127.0.0.1:$other/absolute.yaml
                    scheme_relative: !include //127.0.0.1:$other/scheme_relative.yaml
                """.trimIndent(),
                "${dir}included_tile.yaml" to includedTile,
            )
            serving(files::get) { port, _ ->
                val templates = StreamlineTemplates.fetch("http://127.0.0.1:$port", 5_000)
                assertEquals(setOf("light.hall_included"), idsOf(templates, "included_tile"))
                assertEquals(emptySet<String>(), idsOf(templates, "absolute") + idsOf(templates, "scheme_relative"))
            }
            assertTrue("nothing reached the other origin: $elsewhere", elsewhere.isEmpty())
        }
    }

    @Test fun anIncludeWithAnotherSchemeIsRefusedWithoutFailingTheFetch() {
        val files = mapOf(
            "${dir}streamline_templates.yaml" to """
                included_tile: !include included_tile.yaml
                local_file: !include file:///etc/hostname
                jar: !include jar:file:/tmp/x.jar!/t.yaml
                secure: !include https://127.0.0.1:PORT${dir}included_tile.yaml
            """.trimIndent(),
            "${dir}included_tile.yaml" to includedTile,
        )
        var served = 0
        serving({ files[it]?.replace("PORT", "$served") }) { port, _ ->
            served = port
            val templates = StreamlineTemplates.fetch("http://127.0.0.1:$port", 5_000)
            assertEquals(setOf("light.hall_included"), idsOf(templates, "included_tile"))
            assertEquals(emptySet<String>(), idsOf(templates, "local_file") + idsOf(templates, "jar") + idsOf(templates, "secure"))
        }
    }

    // As in the card, an include that does not answer loses its whole file; the next template path is tried.
    @Test fun aMissingIncludeDiscardsItsFileLikeTheCard() {
        val files = mapOf(
            "${dir}streamline_templates.yaml" to "missing: !include missing.yaml\ninline_tile:\n  card: {type: tile, entity: light.hacs}\n",
            "/local/streamline-card/streamline_templates.yaml" to "inline_tile:\n  card: {type: tile, entity: light.local}\n",
        )
        serving(files::get) { port, _ ->
            assertEquals(setOf("light.local"), idsOf(StreamlineTemplates.fetch("http://127.0.0.1:$port", 5_000), "inline_tile"))
        }
    }

    @Test fun anIncludeCycleReadsEachFileOnce() {
        val files = mapOf(
            "${dir}streamline_templates.yaml" to "a: !include a.yaml\nself: !include streamline_templates.yaml\n",
            "${dir}a.yaml" to "card:\n  type: grid\n  cards:\n    - {type: tile, entity: light.a}\n    - !include b.yaml\n",
            "${dir}b.yaml" to "type: grid\ncards:\n  - {type: tile, entity: light.b}\n  - !include a.yaml\n",
        )
        serving(files::get) { port, requests ->
            val templates = StreamlineTemplates.fetch("http://127.0.0.1:$port", 5_000)
            assertEquals(setOf("light.a", "light.b"), idsOf(templates, "a"))
            assertEquals(requests.toList(), requests.distinct())
        }
    }

    @Test fun includeFanOutStopsAtTheFileCap() {
        // Every file includes two new ones: unbounded, twelve levels are 8,191 requests.
        val node = Regex("${dir}n(\\d*)\\.yaml")
        serving({ path ->
            node.matchEntire(path)?.groupValues?.get(1)?.let { id ->
                if (id.length >= 12) "{type: tile, entity: light.leaf}"
                else "type: grid\ncards:\n  - {type: tile, entity: light.n$id}\n  - !include n${id}0.yaml\n  - !include n${id}1.yaml\n"
            } ?: if (path == "${dir}streamline_templates.yaml") "fan:\n  card: !include n.yaml\n" else null
        }) { port, requests ->
            val templates = StreamlineTemplates.fetch("http://127.0.0.1:$port", 5_000)
            assertTrue("light.n" in idsOf(templates, "fan"))
            assertTrue("${requests.size} requests", requests.size <= 32)
        }
    }

    // The card's yaml 2.x parser accepts up to 100 alias resolutions by default (maxAliasCount).
    @Test fun aRemoteFileWithSixtyCollectionAliasesIsKept() {
        val yaml = "shared_style: &style {color: red}\n" + (1..60).joinToString("\n") { i ->
            "t$i:\n  card:\n    type: tile\n    entity: light.t$i\n    style: *style"
        }
        val (templates, requests) = fetchFrom(mapOf("/hacsfiles/streamline-card/streamline_templates.yaml" to yaml))
        assertEquals(listOf("/hacsfiles/streamline-card/streamline_templates.yaml"), requests)
        val ids = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:streamline-card","template":"t60"}]}]}""",
            templates,
        ).entityIds
        assertTrue("light.t60" in ids)
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
