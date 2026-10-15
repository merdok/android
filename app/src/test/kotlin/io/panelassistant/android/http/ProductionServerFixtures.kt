package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.PowerRiskLevel
import io.panelassistant.android.control.PowerSafetyAssessment
import io.panelassistant.android.control.PowerSafetyObservation
import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.persistence.SqliteStatePreferences
import io.panelassistant.android.persistence.StateMutation
import io.panelassistant.android.persistence.StateNamespacePersistence
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.panelassistant.android.sensors.SensorReporter
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import org.junit.Assert.assertTrue
import sun.misc.Unsafe

// A production route server over a real SQLite-backed Config, and the full root mount with its collaborators,
// shared by the Configure route tests and the socket-and-HTTP parity matrix.

internal fun withFullReadServer(block: (Config, PaneldServerHttpFixture) -> Unit) =
    withRouteConfig { config, _, server, _ ->
        Config::class.java.getDeclaredField("contentResolver").apply {
            isAccessible = true
            set(config, object : android.content.ContentResolver(null) {})
        }
        PaneldServerHttpFixture().use { fixture ->
            // Reuse the existing real Config/projection collaborators inside the complete root mount.
            for (name in listOf(
                "config", "system", "sensors", "pendingLiveSettings", "stalledLiveSettings",
                "configLiveValues", "rendererPreparation", "tameReconciliation", "revisions",
                "managementObservations", "powerSafety", "stopping", "haArea", "pageHealth",
                "directConfigMutationLock", "autoSleepHttpApi", "autoBrightnessHttpApi", "applySetting", "onReconfigure",
            )) {
                val value = PaneldServer::class.java.getDeclaredField(name).run {
                    isAccessible = true
                    get(server)
                }
                fixtureSetField(fixture.server, name, value)
            }
            // Source-text reason: the shipped catalogue is runtime input, not a source-shape assertion.
            fixtureSetField(fixture.server, "catalogueLoader\$delegate", lazyOf(
                io.panelassistant.android.i18n.CatalogueLoader { File("src/main/assets", it).readText() },
            ))
            val profileType = io.panelassistant.android.device.DeviceProfile::class.java
            fixtureSetField(fixture.server, "profile", java.lang.reflect.Proxy.newProxyInstance(
                profileType.classLoader, arrayOf(profileType),
            ) { _, method, _ ->
                when (method.name) {
                    "getManufacturer" -> "Profile maker"
                    "getModel" -> "Profile model"
                    else -> error("Unexpected profile access: ${method.name}")
                }
            })
            val learning = fixtureAllocate(io.panelassistant.android.dashboard.EntityLearningManager::class.java)
            fixtureSetField(learning, "config", config)
            fixtureSetField(fixture.server, "entityLearning", learning)
            block(config, fixture)
        }
    }



internal fun withRouteConfig(block: (Config, JdbcStatePersistence, PaneldServer, MutableList<String>) -> Unit) {
    val directory = Files.createTempDirectory("config-post-admission").toFile()
    val writer = Executors.newSingleThreadExecutor()
    try {
        val persistence = JdbcStatePersistence(File(directory, "ha-paneld.db"))
        val config = Config(SqliteStatePreferences(persistence, writer))
        assertTrue(config.applyBatch {
            config.setPanelId("contract-panel")
            config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
            config.setRaw(requireNotNull(SettingsRegistry.spec("manufacturer")), "Contract manufacturer")
            config.setRaw(requireNotNull(SettingsRegistry.spec("model")), "Contract model")
            config.setDashboardPackage("com.example.dashboard")
        })
        val live = mutableListOf<String>()
        val server = routeServer(config) { key, _ ->
            live += key
            LiveSettingRequestOutcome.APPLIED
        }
        fixtureSetField(server, "onReconfigure", { _: Set<String> -> live += "reconfigure" })
        block(config, persistence, server, live)
    } finally {
        writer.shutdownNow()
        directory.deleteRecursively()
    }
}


internal fun routeServer(
    config: Config,
    applySetting: (String, String) -> LiveSettingRequestOutcome,
): PaneldServer {
    val server = fixtureAllocate(PaneldServer::class.java)
    val sensors = fixtureAllocate(SensorReporter::class.java)
    val renderer = RendererPreparationCoordinator(
        builtinPackage = "builtin",
        state = { RendererPreparationState("com.example.dashboard", "") },
        borrow = { null },
        persist = { true },
    )
    val privilege = PrivilegedRouteObservation(
        directSuReady = true,
        helperRootReady = false,
    )
    val snap = ManagementSnapshot(
        emptyMap(), emptyMap(), Capabilities(), emptyList(), privilege,
        null, null, 1.0f, false,
    )
    val observations = ManagementObservations(
        object : android.content.ContextWrapper(null) {},
        io.panelassistant.android.control.DensityController(canSu = false),
        { error("Stopped fixture must not probe") },
        { _, _ -> error("Unexpected diagnostics") },
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        { true },
    )
    observations.snapCache.set(snap)

    fixtureSetField(server, "managementObservations", observations)
    fixtureSetField(server, "manageTable", io.panelassistant.android.ManageTable())
    fixtureSetField(server, "pageHealth", PageHealth(object : android.content.ContextWrapper(null) {}, config))
    fixtureSetField(server, "config", config)
    fixtureSetField(server, "system", SystemController(object : SystemEnv {
        override val ownPackage = "io.github.maxlyth.hapaneld"
        override fun isInstalled(pkg: String) = pkg == "com.example.dashboard"
        override fun launchComponent(pkg: String): String? = null
        override fun homeActivities(): List<ActivityRef> = emptyList()
        override fun defaultHome(): ActivityRef? = null
        override fun directStart(component: String) = true
    }))
    fixtureSetField(server, "sensors", sensors)
    fixtureSetField(server, "applySetting", applySetting)
    fixtureSetField(server, "pendingLiveSettings", { emptyMap<String, String>() })
    fixtureSetField(server, "stalledLiveSettings", { emptySet<String>() })
    fixtureSetField(server, "configLiveValues", { emptyMap<String, String>() })
    fixtureSetField(server, "onReconfigure", { _: Set<String> -> })
    fixtureSetField(server, "rendererPreparation", renderer)
    fixtureSetField(server, "autoSleepHttpApi", AutoSleepHttpApi.UNAVAILABLE)
    fixtureSetField(server, "autoBrightnessHttpApi", AutoBrightnessHttpApi.UNAVAILABLE)
    fixtureSetField(server, "tameReconciliation", TameReconcileAuthority(
        readDesired = { emptySet() },
        reconcile = { _, _ -> error("Stopped route fixture must not actuate packages") },
        stopping = { true },
    ).also { check(it.closeAndJoin(1_000)) })
    val mutationLock = Any()
    fixtureSetField(server, "directConfigMutationLock", mutationLock)
    fixtureSetField(server, "haArea", HaAreaRuntime(
        config,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().apply { cancel() }),
        mutationLock,
    ) { true })
    fixtureSetField(server, "revisions", RevisionStore(Files.createTempDirectory("config-route-revisions").toFile()))
    fixtureSetField(server, "powerSafety", { safePowerAssessment() })
    fixtureSetField(server, "stopping", true)
    return server
}


internal fun safePowerAssessment(): PowerSafetyAssessment = PowerSafetyAssessment(
    level = PowerRiskLevel.SAFE,
    observation = PowerSafetyObservation(
        keepAwakeConfigured = true,
        wakeLockHeld = true,
        wifiLockRequired = false,
        wifiLockHeld = false,
        preventIdleDimConfigured = true,
        screenOffTimeoutMs = 30_000,
        interactive = true,
        pluggedMask = 1,
        stayOnWhilePluggedIn = 1,
        deviceIdleMode = false,
        ignoringBatteryOptimizations = true,
        screenOffMechanism = "test",
    ),
    reasonCodes = emptyList(),
    summary = "safe",
    action = "none",
)


internal class JdbcStatePersistence(private val database: File) : StateNamespacePersistence {
    var failWrites = false
    /** Every key each durable commit wrote, in order. */
    val written = mutableListOf<Set<String>>()
    init {
        connection().use { connection ->
            connection.createStatement().use {
                it.execute(
                    "CREATE TABLE IF NOT EXISTS app_state(" +
                        "state_key TEXT PRIMARY KEY,value_type TEXT NOT NULL,value_text TEXT NOT NULL)",
                )
            }
        }
    }

    override fun initialize(): Map<String, Any> = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT state_key,value_type,value_text FROM app_state").use { rows ->
                buildMap {
                    while (rows.next()) put(rows.getString(1), decode(rows.getString(2), rows.getString(3)))
                }
            }
        }
    }

    override fun persist(mutation: StateMutation): Boolean = transaction { connection ->
        written += mutation.changes.keys
        if (mutation.clear) connection.createStatement().use { it.executeUpdate("DELETE FROM app_state") }
        mutation.changes.forEach { (key, value) ->
            if (value == null) {
                connection.prepareStatement("DELETE FROM app_state WHERE state_key=?").use {
                    it.setString(1, key)
                    it.executeUpdate()
                }
            } else upsert(connection, key, value)
        }
    }

    override fun replace(snapshot: Map<String, Any>): Boolean = transaction { connection ->
        connection.createStatement().use { it.executeUpdate("DELETE FROM app_state") }
        snapshot.forEach { (key, value) -> upsert(connection, key, value) }
    }

    private fun upsert(connection: Connection, key: String, value: Any) {
        val (type, text) = encode(value)
        connection.prepareStatement(
            "INSERT INTO app_state(state_key,value_type,value_text) VALUES(?,?,?) " +
                "ON CONFLICT(state_key) DO UPDATE SET value_type=excluded.value_type,value_text=excluded.value_text",
        ).use {
            it.setString(1, key)
            it.setString(2, type)
            it.setString(3, text)
            it.executeUpdate()
        }
    }

    private fun transaction(block: (Connection) -> Unit): Boolean = !failWrites && runCatching {
        connection().use { connection ->
            connection.autoCommit = false
            block(connection)
            connection.commit()
        }
    }.isSuccess

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}")

    private fun encode(value: Any): Pair<String, String> = when (value) {
        is Boolean -> "boolean" to value.toString()
        is Int -> "int" to value.toString()
        is Long -> "long" to value.toString()
        is Float -> "float" to value.toString()
        is String -> "string" to value
        is Set<*> -> "string_set" to value.filterIsInstance<String>().sorted().joinToString("\u0000")
        else -> error("unsupported SQLite state value ${value::class.java.name}")
    }

    private fun decode(type: String, value: String): Any = when (type) {
        "boolean" -> value.toBooleanStrict()
        "int" -> value.toInt()
        "long" -> value.toLong()
        "float" -> value.toFloat()
        "string" -> value
        "string_set" -> value.split('\u0000').filter(String::isNotEmpty).toSet()
        else -> error("unsupported SQLite state type $type")
    }
}

private val fixtureUnsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
    isAccessible = true
    get(null) as Unsafe
}

@Suppress("UNCHECKED_CAST")
private fun <T> fixtureAllocate(type: Class<T>): T = fixtureUnsafe.allocateInstance(type) as T

private fun fixtureSetField(target: Any, name: String, value: Any?) {
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
}
