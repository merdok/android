package io.panelassistant.android.dashboard

import android.content.SharedPreferences
import io.panelassistant.android.Config
import io.panelassistant.android.http.HaAreaProtocol
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

class EntityLearningAreaWriteTest {
    @Test fun blankRepairPreservesAnInterveningRealAreaAndClearsTheStaleLiteral() = runBlocking {
        val currentArea = AtomicReference("bad-area")
        val updates = ConcurrentLinkedQueue<JSONObject>()
        val commands = ConcurrentLinkedQueue<String>()
        val deviceUid = "a".repeat(32)
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                // The learned connection proves its instance before a token leaves.
                get("/api/panel_assistant/instance") { call.respondText("""{"instance_id":"${"c".repeat(32)}"}""") }
                webSocket("/api/websocket") {
                    send(Frame.Text("""{"type":"auth_required"}"""))
                    assertEquals("auth", JSONObject((incoming.receive() as Frame.Text).readText()).getString("type"))
                    send(Frame.Text("""{"type":"auth_ok"}"""))
                    for (frame in incoming) {
                        val command = JSONObject((frame as Frame.Text).readText())
                        commands.add(command.getString("type"))
                        val result: Any = when (command.getString("type")) {
                            "auth/current_user" -> JSONObject().put("is_admin", true)
                            "config/auth/list" -> JSONArray()
                            "config/area_registry/list" -> JSONArray()
                                .put(JSONObject().put("area_id", "bad-area").put("name", "null"))
                                .put(JSONObject().put("area_id", "study").put("name", "Study"))
                            "config/device_registry/list" -> JSONArray().put(
                                JSONObject().put("id", "panel-device")
                                    .put("identifiers", JSONArray().put(JSONArray().put("panel_assistant").put("entry-test")))
                                    .put("area_id", currentArea.get()),
                            )
                            "config/device_registry/update" -> {
                                updates.add(command)
                                currentArea.set(if (command.isNull("area_id")) "" else command.getString("area_id"))
                                JSONObject().put("id", "panel-device")
                            }
                            else -> error("unexpected HA command: ${command.getString("type")}")
                        }
                        send(Frame.Text(JSONObject().put("id", command.getInt("id"))
                            .put("type", "result").put("success", true).put("result", result).toString()))
                    }
                }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            // Only the learned connection record is written: it carries the entry the hello named.
            val stored = java.util.concurrent.ConcurrentHashMap<String, Any>()
            val prefs = Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "contains" -> stored.containsKey(args!![0])
                    "getLong" -> stored[args!![0]] ?: args[1]
                    "edit" -> editor(stored)
                    "getString" -> stored[args!![0]] ?: when (args[0]) {
                        "ha_url" -> "http://127.0.0.1:$port"
                        "ha_token" -> "test-token"
                        Config.DEVICE_UID_PREF -> deviceUid
                        else -> ""
                    }
                    "getAll" -> mapOf("ha_url" to "http://127.0.0.1:$port", "ha_token" to "test-token")
                    else -> error("unexpected preference read: ${method.name}")
                }
            } as SharedPreferences
            val config = Config(prefs)
            assertTrue(io.panelassistant.android.HaConnectionRoutes.learn(
                config, io.panelassistant.android.HaConnectionRoutes.current(config),
                io.panelassistant.android.HaConnectionAdvertisement("c".repeat(32), "d".repeat(32), emptyList()), "entry-test",
            ))
            val stale = haAreaCatalog(config)
            assertTrue(stale.queried)
            assertTrue(HaAreaProtocol.hasLiteralNullAssignment(stale.device, stale.areas))
            val readsBeforeRepair = commands.count { it == "config/device_registry/list" }

            currentArea.set("study") // Another HA owner moves the device after the catalog read.
            assertFalse(applyRequestedArea(config, "", stale.ownerKey))
            assertEquals(readsBeforeRepair + 1, commands.count { it == "config/device_registry/list" })
            assertEquals("study", currentArea.get())
            assertTrue(updates.isEmpty())

            currentArea.set("bad-area")
            assertTrue(applyRequestedArea(config, "", stale.ownerKey))
            assertEquals(readsBeforeRepair + 2, commands.count { it == "config/device_registry/list" })
            assertEquals("", currentArea.get())
            assertEquals(1, updates.size)
            assertTrue(updates.single().isNull("area_id"))
        } finally {
            server.stop(100, 1_000)
        }
    }

    private fun editor(stored: MutableMap<String, Any>): SharedPreferences.Editor {
        val pending = mutableMapOf<String, Any>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString", "putLong" -> editor.also { pending[args!![0] as String] = args[1] as Any }
                "commit" -> { stored.putAll(pending); true }
                else -> error("unexpected preference write: ${method.name}")
            }
        } as SharedPreferences.Editor
        return editor
    }
}
