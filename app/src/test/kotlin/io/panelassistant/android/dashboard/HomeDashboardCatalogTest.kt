package io.panelassistant.android.dashboard

import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.HaAuthSnapshot
import io.panelassistant.android.stableOwner
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import io.panelassistant.android.liveHomeDashboardResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardCatalogTest {
    @Test fun `catalog read is scan independent and keeps system fallback after stale user default`() = runTest {
        val commands = mutableListOf<String>()
        val catalog = readHomeDashboardCatalog { command ->
            val type = command.getString("type")
            commands += type
            when (type) {
                "auth/current_user" -> result(JSONObject().put("is_admin", false))
                "get_panels" -> result(JSONObject().put(
                    "home", JSONObject().put("title", "Home").put("icon", "mdi:home"),
                ))
                "lovelace/dashboards/list" -> result(JSONArray().put(
                    JSONObject().put("url_path", "alpha").put("title", "Office"),
                ))
                "frontend/get_user_data" -> result(JSONObject().put("value", JSONObject().put(
                    "default_panel", "deleted-user-dashboard",
                )))
                "frontend/get_system_data" -> result(JSONObject().put("value", JSONObject().put(
                    "default_panel", "alpha",
                )))
                else -> error("unexpected command $type")
            }
        }

        assertEquals(
            listOf(
                "auth/current_user",
                "get_panels",
                "lovelace/dashboards/list",
                "frontend/get_user_data",
                "frontend/get_system_data",
            ),
            commands,
        )
        assertTrue(catalog.queried)
        assertEquals(listOf("/home", "/alpha"), catalog.items.map { it.path })
        assertEquals(
            EntityLearningProtocol.HomeDashboardDefault(explicit = true, path = "/alpha"),
            catalog.default,
        )
        assertEquals(
            "/home",
            EntityLearningProtocol.resolveHomeDashboard("", null, null, catalog.items).path,
        )
        assertTrue(commands.none { it == "lovelace/config" || it == "get_states" || "registry" in it })
    }

    @Test fun `queried empty catalog is distinct from a failed lookup`() = runTest {
        val catalog = readHomeDashboardCatalog { command ->
            when (command.getString("type")) {
                "auth/current_user" -> result(JSONObject().put("is_admin", false))
                "get_panels" -> result(JSONObject())
                "lovelace/dashboards/list" -> result(JSONArray())
                "frontend/get_user_data", "frontend/get_system_data" ->
                    result(JSONObject().put("value", JSONObject()))
                else -> error("unexpected command")
            }
        }

        assertTrue(catalog.queried)
        assertEquals(emptyList<EntityLearningProtocol.HomeDashboardChoice>(), catalog.items)
        assertEquals(
            EntityLearningProtocol.HomeDashboardResolution(),
            EntityLearningProtocol.resolveHomeDashboard(
                "/out-of-list", catalog.userDefault, catalog.systemDefault, catalog.items,
            ),
        )
        assertEquals(false, HomeDashboardCatalog().queried)
    }

    @Test fun `missing either authenticated list shape cannot claim a complete zero catalog`() = runTest {
        for (missing in listOf("get_panels", "lovelace/dashboards/list")) {
            var failed = false
            try {
                readHomeDashboardCatalog { command ->
                    when (val type = command.getString("type")) {
                        "auth/current_user" -> result(JSONObject().put("is_admin", false))
                        "get_panels" -> if (type == missing) JSONObject().put("success", true)
                            else result(JSONObject())
                        "lovelace/dashboards/list" -> if (type == missing) JSONObject().put("success", true)
                            else result(JSONArray())
                        "frontend/get_user_data", "frontend/get_system_data" ->
                            result(JSONObject().put("value", JSONObject()))
                        else -> error("unexpected command")
                    }
                }
            } catch (_: IllegalStateException) {
                failed = true
            }
            assertTrue("$missing without a typed result must fail closed", failed)
        }
    }

    @Test fun `failed default command makes the whole catalog unavailable`() = runTest {
        for (failedCommand in listOf("frontend/get_user_data", "frontend/get_system_data")) {
            var failed = false
            try {
                readHomeDashboardCatalog { command ->
                    when (val type = command.getString("type")) {
                        "auth/current_user" -> result(JSONObject().put("is_admin", false))
                        "get_panels" -> result(JSONObject().put("home", JSONObject()))
                        "lovelace/dashboards/list" -> result(JSONArray())
                        "frontend/get_user_data", "frontend/get_system_data" ->
                            if (type == failedCommand) error("transient rejection")
                            else result(JSONObject().put("value", JSONObject()))
                        else -> error("unexpected command")
                    }
                }
            } catch (_: IllegalStateException) {
                failed = true
            }
            assertTrue("$failedCommand failure must retry instead of choosing a fallback", failed)
        }
    }

    @Test fun `legal explicit dashboard does not invoke lower priority defaults`() = runTest {
        val commands = mutableListOf<String>()
        val resolution = readHomeDashboardResolution({ command ->
            val type = command.getString("type")
            commands += type
            when (type) {
                "auth/current_user" -> result(JSONObject().put("is_admin", false))
                "get_panels" -> result(JSONObject())
                "lovelace/dashboards/list" -> result(JSONArray().put(
                    JSONObject().put("url_path", "alpha").put("title", "Office"),
                ))
                else -> error("lower-priority command must not run: $type")
            }
        }, "/alpha/view?kiosk=1")

        assertEquals(EntityLearningProtocol.HomeDashboardSource.EXPLICIT, resolution.source)
        assertEquals("/alpha/view?kiosk=1", resolution.path)
        assertEquals(
            listOf("auth/current_user", "get_panels", "lovelace/dashboards/list"),
            commands,
        )
    }

    @Test fun `legal user default does not invoke system default`() = runTest {
        val commands = mutableListOf<String>()
        val resolution = readHomeDashboardResolution({ command ->
            val type = command.getString("type")
            commands += type
            when (type) {
                "auth/current_user" -> result(JSONObject().put("is_admin", false))
                "get_panels" -> result(JSONObject())
                "lovelace/dashboards/list" -> result(JSONArray().put(
                    JSONObject().put("url_path", "alpha").put("title", "Office"),
                ))
                "frontend/get_user_data" -> result(JSONObject().put(
                    "value", JSONObject().put("default_panel", "alpha"),
                ))
                else -> error("lower-priority command must not run: $type")
            }
        }, "")

        assertEquals(EntityLearningProtocol.HomeDashboardSource.USER_DEFAULT, resolution.source)
        assertEquals("/alpha", resolution.path)
        assertEquals(
            listOf("auth/current_user", "get_panels", "lovelace/dashboards/list", "frontend/get_user_data"),
            commands,
        )
    }

    @Test fun `invalid explicit and out of list user default continue to system then first legal`() = runTest {
        val commands = mutableListOf<String>()
        suspend fun resolve(systemDefault: String?) = readHomeDashboardResolution({ command ->
            val type = command.getString("type")
            commands += type
            when (type) {
                "auth/current_user" -> result(JSONObject().put("is_admin", false))
                "get_panels" -> result(JSONObject())
                "lovelace/dashboards/list" -> result(JSONArray()
                    .put(JSONObject().put("url_path", "first").put("title", "First"))
                    .put(JSONObject().put("url_path", "system").put("title", "System")))
                "frontend/get_user_data" -> result(JSONObject().put(
                    "value", JSONObject().put("default_panel", "deleted"),
                ))
                "frontend/get_system_data" -> result(JSONObject().put(
                    "value", JSONObject().apply { systemDefault?.let { put("default_panel", it) } },
                ))
                else -> error("unexpected command $type")
            }
        }, "https://invalid.example/dashboard")

        val system = resolve("system")
        assertEquals(EntityLearningProtocol.HomeDashboardSource.SYSTEM_DEFAULT, system.source)
        assertEquals("/system", system.path)
        val first = resolve("missing")
        assertEquals(EntityLearningProtocol.HomeDashboardSource.FIRST_LEGAL, first.source)
        assertEquals("/first", first.path)
        assertEquals(2, commands.count { it == "frontend/get_system_data" })
    }

    @Test fun `renderer and scanner share one resolution until authenticated authority changes`() = runTest {
        val authority = HomeDashboardResolutionAuthority()
        val ownerA = HaAuthOwner("https://ha", "refresh-a", "client", "")
        val ownerB = ownerA.copy(refreshToken = "refresh-b")
        val keyA = HomeDashboardResolutionAuthority.Key("https://ha", ownerA, "")
        val office = EntityLearningProtocol.HomeDashboardResolution(
            "/alpha", EntityLearningProtocol.HomeDashboardSource.USER_DEFAULT,
        )
        val kitchen = EntityLearningProtocol.HomeDashboardResolution(
            "/beta", EntityLearningProtocol.HomeDashboardSource.USER_DEFAULT,
        )
        var reads = 0

        assertEquals(office, authority.resolve(keyA, { true }) { reads++; office })
        assertEquals(office, authority.resolve(keyA, { true }) { reads++; kitchen })
        assertEquals(1, reads)
        assertEquals(
            kitchen,
            authority.resolve(keyA.copy(authOwner = ownerB), { true }) { reads++; kitchen },
        )
        assertEquals(2, reads)
    }

    @Test fun `zero dashboard result is reread after the user creates a dashboard`() = runTest {
        val authority = HomeDashboardResolutionAuthority()
        val key = HomeDashboardResolutionAuthority.Key(
            "https://ha", HaAuthOwner("https://ha", "refresh", "client", ""), "",
        )
        val none = EntityLearningProtocol.HomeDashboardResolution()
        val office = EntityLearningProtocol.HomeDashboardResolution(
            "/alpha", EntityLearningProtocol.HomeDashboardSource.FIRST_LEGAL,
        )
        var reads = 0

        assertEquals(none, authority.resolve(key, { true }) { reads++; none })
        assertEquals(office, authority.resolve(key, { true }) { reads++; office })
        assertEquals(2, reads)
    }

    @Test fun `a panel moved to a Home Assistant without its dashboard shows the default and finds the dashboard once created`() = runTest {
        // A panel set to /wall-panel, signed in to a Home Assistant that has only `map`: the scanner
        // and the renderer resolve through the same authority, with the scanner's live-read rule.
        val configured = "/wall-panel"
        val dashboards = mutableListOf("map")
        var reads = 0
        suspend fun read() = readHomeDashboardResolution({ command ->
            when (val type = command.getString("type")) {
                "auth/current_user" -> { reads++; result(JSONObject().put("is_admin", true)) }
                "get_panels" -> result(JSONObject())
                "lovelace/dashboards/list" -> result(JSONArray().apply {
                    dashboards.forEach { put(JSONObject().put("url_path", it).put("title", it)) }
                })
                "frontend/get_user_data", "frontend/get_system_data" -> result(JSONObject().put("value", JSONObject()))
                else -> error("unexpected command $type")
            }
        }, configured)
        val authority = HomeDashboardResolutionAuthority()
        val key = HomeDashboardResolutionAuthority.Key("https://dev", HaAuthOwner("https://dev", "refresh", "client", ""), configured)
        suspend fun resolve() = authority.resolve(key, { true }, homeDashboardResolutionMustBeLive(configured)) { read() }

        val standIn = checkNotNull(resolve())
        assertEquals("/map", standIn.path)
        assertTrue(homeDashboardIsSubstitute(configured, standIn))
        // The scan keeps its configured scope: no rebind, so it commits instead of relaunching forever.
        assertFalse(resolvedScopeRequiresRebind(configured, configured, standIn.path!!))
        assertEquals(DashboardProbeOutcome.UNCHANGED, dashboardProbeOutcome(configured, standIn.path!!, configured, "aa", "aa"))
        // The renderer holds the stand-in provisionally, so its next rebuild asks again.
        assertFalse(liveHomeDashboardResolution(owner(configured), standIn).confirmed)

        // Each pass asks again while the stand-in is shown, so the owner creating the dashboard is seen.
        assertEquals("/map", resolve()?.path)
        dashboards += "wall-panel"
        val found = checkNotNull(resolve())
        assertEquals(EntityLearningProtocol.HomeDashboardSource.EXPLICIT, found.source)
        assertEquals(configured, found.path)
        assertFalse(homeDashboardIsSubstitute(configured, found))
        assertTrue(liveHomeDashboardResolution(owner(configured), found).confirmed)
        assertEquals(3, reads)
        // A real answer is the process answer again.
        assertEquals(configured, resolve()?.path)
        assertEquals(3, reads)
    }

    @Test fun `Auto still rebinds to the dashboard the account default resolves to`() {
        assertTrue(resolvedScopeRequiresRebind("", "/", "/map"))
        assertEquals(DashboardProbeOutcome.CHANGED, dashboardProbeOutcome("", "/map", "/", "aa", "aa"))
        val userDefault = EntityLearningProtocol.HomeDashboardResolution("/map", EntityLearningProtocol.HomeDashboardSource.USER_DEFAULT)
        assertFalse("Auto showing its default is no stand-in", homeDashboardIsSubstitute("", userDefault))
        assertTrue(liveHomeDashboardResolution(owner(""), userDefault).confirmed)
    }

    private fun owner(path: String) = io.panelassistant.android.HomeDashboardResolutionOwner(
        HaAuthOwner("https://dev", "refresh", "client", ""), path,
    )

    @Test fun `authenticated token cannot be paired with a replacement credential owner`() {
        val before = HaAuthSnapshot("https://ha", "access-a", "refresh-a", 10, "client")
        val replacement = HaAuthSnapshot("https://ha", "access-b", "refresh-b", 20, "client")
        assertEquals(
            null,
            ownedAuthenticatedHomeDashboardAuthority(
                "https://ha", "", before.stableOwner(), replacement, "access-a",
            ),
        )

        val refreshed = before.copy(accessToken = "access-a2", tokenExpiry = 30)
        val retained = ownedAuthenticatedHomeDashboardAuthority(
            "https://ha", "", before.stableOwner(), refreshed, "access-a2",
        )
        assertEquals(before.stableOwner(), retained?.key?.authOwner)
        assertEquals("", retained?.key?.configuredPath)
    }

    @Test fun `fresh account resolving to a built-in panel dashboard reads a config Home Assistant serves`() = runTest {
        // A fresh account: no default, no stored Lovelace config, built-in panels registered as Core does.
        val ha: suspend (JSONObject) -> JSONObject = { command ->
            when (command.getString("type")) {
                "auth/current_user" -> result(JSONObject().put("is_admin", true))
                "get_panels" -> result(JSONObject()
                    .put("home", JSONObject().put("component_name", "home").put("config", JSONObject.NULL))
                    .put("light", JSONObject().put("component_name", "light").put("config", JSONObject.NULL)))
                "lovelace/dashboards/list" -> result(JSONArray())
                "frontend/get_user_data", "frontend/get_system_data" -> result(JSONObject().put("value", JSONObject.NULL))
                "lovelace/config" -> {
                    val urlPath = command.optString("url_path")
                    require(urlPath.isNotBlank() && '-' in urlPath) {
                        if (urlPath.isBlank()) "No config found." else "Unknown config specified: $urlPath"
                    }
                    result(JSONObject().put("views", JSONArray()))
                }
                else -> error("unexpected command ${command.getString("type")}")
            }
        }
        val catalog = readHomeDashboardCatalog(ha)
        val resolved = EntityLearningProtocol.resolveHomeDashboard("", null, null, catalog.items).path!!
        assertEquals("/home", resolved)

        val config = JSONObject(checkNotNull(readDashboardConfig(ha, resolved)))

        assertEquals("home", config.getJSONObject("strategy").getString("type"))
    }

    private fun result(value: Any): JSONObject = JSONObject()
        .put("success", true)
        .put("result", value)
}
