package io.panelassistant.android.testsupport

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import io.panelassistant.android.Config
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.AutoBrightnessController
import io.panelassistant.android.control.BootChimeController
import io.panelassistant.android.control.BootChimeHardware
import io.panelassistant.android.control.BootChimeState
import io.panelassistant.android.control.BootChimeStateStore
import io.panelassistant.android.control.BrightnessController
import io.panelassistant.android.control.ControlApplyOutcome
import io.panelassistant.android.control.CpuController
import io.panelassistant.android.control.FakeBacklight
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeScreenPower
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.FakeWakeTap
import io.panelassistant.android.control.LedEffectController
import io.panelassistant.android.control.NavbarController
import io.panelassistant.android.control.NavigateController
import io.panelassistant.android.control.RelayController
import io.panelassistant.android.control.ScreenController
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.control.TouchSoundController
import io.panelassistant.android.control.VolumeController
import io.panelassistant.android.control.WatchdogController
import io.panelassistant.android.control.WifiOutageCounts
import io.panelassistant.android.control.ZigbeeController
import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.hardware.LedController
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.setAutoSleep
import io.panelassistant.android.state.SoftwareTarget
import io.panelassistant.android.state.SoftwareUpdateSources
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.storage.StorageQuickCheck
import io.panelassistant.android.util.MonotonicDeadline
import org.junit.Assert.assertTrue
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The panel's real state core and command dispatcher on fake hardware, wired as the service wires one
 * network-runtime generation, for tests that observe native state and commands through those owners.
 */
internal abstract class PanelRuntimeRig {

    protected fun submitNative(rig: Rig, channel: String, payload: String): PanelAssistantCommandResult {
        val done = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<PanelAssistantCommandResult>()
        rig.commands.submit(PanelAssistantCommand(channel, payload, admit = { null })) {
            result.set(it)
            done.countDown()
        }
        assertTrue("$channel native command finished", done.await(20, TimeUnit.SECONDS))
        return result.get()
    }

    /** Waits until the ordered command worker has run everything queued before it. */
    protected fun barrier(rig: Rig, busy: String) {
        val done = CountDownLatch(1)
        val key = if (busy == "navigate") "home_dashboard" else "navigate"
        rig.commands.submit(
            PanelAssistantCommand(key, "", admit = { PanelAssistantCommandResult.Refused("barrier") }),
        ) { done.countDown() }
        assertTrue("command worker drained", done.await(20, TimeUnit.SECONDS))
    }

    /** Every channel a panel with every capability registers once those capabilities are observed. */
    protected fun everyChannel(): Set<String> {
        val rig = rig(hasCamera = true, hasCht8305 = true)
        try {
            rig.core.ensureCapabilityChannels(
                Capabilities(zigbeePresent = true, cpuGovernors = true, networkAdb = true, relays = 2, buttonLeds = 1),
            )
            return rig.core.stateChannelKeys()
        } finally {
            rig.close()
        }
    }

    // ---- rig ----

    protected class Rig(
        val tmp: File,
        val config: Config,
        val sysfs: SysfsRootShell,
        val core: io.panelassistant.android.PanelStateCore,
        val commands: io.panelassistant.android.PanelCommandDispatcher,
        val storage: java.util.concurrent.atomic.AtomicReference<StorageHealthSnapshot>,
        val storageReads: AtomicInteger,
        val updateSources: java.util.concurrent.atomic.AtomicReference<SoftwareUpdateSources>,
        val autoSleepConfigChanges: AtomicInteger,
        /** The music stream volume the panel reads; a test changes it as the volume keys would. */
        val audio: android.media.ScriptedAudioManager,
        /** The CPU governor source; a test changes its governor as a thermal daemon would. */
        val metrics: io.panelassistant.android.control.FakeMetricSource,
    ) {
        /** The service's start order: the state core's startup step. */
        fun start() {
            core.start()
        }

        fun close() {
            runCatching { commands.stop(MonotonicDeadline(1_000)) }
            runCatching { core.stop(MonotonicDeadline(1_000)) }
            tmp.deleteRecursively()
        }
    }

    protected fun rig(
        hasTemperature: Boolean = true,
        buttonKeys: io.panelassistant.android.device.PanelButtonKeys = io.panelassistant.android.device.PanelButtonKeys.UNKNOWN,
        hasHumidity: Boolean = true,
        hasCht8305: Boolean = false,
        hasCamera: Boolean = false,
        cameraAvailability: () -> Boolean? = { hasCamera },
        learnedProximityState: () -> Boolean? = { null },
        lightChannel: () -> Boolean? = { true },
        led: LedController = object : LedController {
            override fun available() = true
            override fun colorCapable() = true
            override fun setRgb(r: Int, g: Int, b: Int) = true
            override fun off() = true
        },
        companionHomeReturns: MutableList<String>? = null,
        // A speaker panel by default, as every bundled profile is; null is a profile declaring none.
        media: io.panelassistant.android.media.PanelMediaPlayer? = io.panelassistant.android.media.PanelMediaPlayer(
            streams = { _, _, _ -> error("this rig plays no media") },
            post = { it() },
            announce = { _, _ -> false },
            cancelAnnouncement = {},
            muted = { false },
            setMuted = {},
        ),
        autoSleepActivity: () -> io.panelassistant.android.AutoSleepActivitySnapshot = { io.panelassistant.android.AutoSleepActivitySnapshot() },
        helperSend: ((String) -> String?)? = null,
        /** A panel whose CPU governors are readable, starting on this governor; null offers none. */
        cpuGovernor: String? = null,
        wifiOutages: () -> WifiOutageCounts? = { WifiOutageCounts(last24h = 3) },
        configure: (Config) -> Unit = {},
    ): Rig {
        val tmp = Files.createTempDirectory("panel-runtime-rig").toFile()
        val prefs = MemoryPreferences()
        val context = FakeContext(tmp, prefs)
        val config = newConfig(prefs, context.contentResolver)
        config.setRaw(requireNotNull(SettingsRegistry.spec("manufacturer")), "Golden Manufacturing")
        config.setRaw(requireNotNull(SettingsRegistry.spec("model")), "Golden Panel")
        config.setAutoSleep(true)
        // Most config entities are opt-in. Expose a representative set. Host-metric diagnostics stay
        // hidden: they read /proc.
        listOf(
            "diag_wifi_outages_24h", "wake_on_wave", "auto_sleep",
            "auto_sleep_activity", "touch_sound", "kiosk_lock", "auto_brightness", "navbar_mode",
        ).forEach { config.setHaExposed(it, true) }
        configure(config)

        val sysfs = SysfsRootShell()
        val relay = RelayController(
            fakeProfile(relayBase = RELAY_BASE, buttonLedGpioBase = LED_GPIO_BASE),
            sysfs,
        )
        val brightness = BrightnessController(context, FakeRootShell(), FakeDaemon())
        val screen = ScreenController(
            FakeBacklight(160), FakeScreenPower(), FakeRootShell(), FakeDaemon(), FakeWakeTap(),
            ScreenOff.BRIGHTNESS_ZERO, nap = {},
        )
        val companion = "io.homeassistant.companion.android.minimal"
        val system = SystemController(
            FakeSystemEnv(), FakeRootShell(), FakeDaemon(replies = mapOf("RELOAD $companion" to "OK")),
            builtinForeground = { false }, homeDashboard = { config.homeDashboard },
            onCompanionHome = { pkg, path -> companionHomeReturns?.add("$pkg:$path") },
        )
        val bootChime = BootChimeController(
            configured = { false },
            setConfigured = {},
            stateStore = object : BootChimeStateStore {
                override fun load(): BootChimeState? = null
                override fun save(state: BootChimeState) = true
                override fun clear() = true
            },
            hardware = object : BootChimeHardware {
                override fun capture(): BootChimeState? = null
                override fun silence() = ControlApplyOutcome.APPLIED
                override fun restore(state: BootChimeState) = ControlApplyOutcome.APPLIED
            },
        )
        val capabilities = Capabilities(
            hasProximity = true,
            hasLearnedProximity = true,
            hasLight = true,
            hasTemperature = true,
            hasHumidity = true,
            hasButtonBacklight = true,
            microphone = io.panelassistant.android.audio.MicrophonePresence.PROVEN,
            hasCamera = hasCamera,
            relays = 2,
            buttonLeds = 1,
            canInstallVerifiedApps = true,
            hasWifi = true,
            cpuGovernors = cpuGovernor != null,
        )
        val storage = java.util.concurrent.atomic.AtomicReference(storageSnapshot(StorageHealthSeverity.HEALTHY, walBytes = 4_096))
        val storageReads = AtomicInteger()
        val updateSources = java.util.concurrent.atomic.AtomicReference(updateSources())
        val autoSleepConfigChanges = AtomicInteger()

        val ledEffect = LedEffectController(led)
        val zigbee = ZigbeeController(fakeProfile(), FakeRootShell())
        val metrics = io.panelassistant.android.control.FakeMetricSource(
            governor = cpuGovernor,
            availableGovernors = cpuGovernor?.let { "performance powersave schedutil" },
        )
        val cpu = CpuController(
            fakeProfile(), FakeRootShell(), FakeDaemon(), io.panelassistant.android.metrics.PanelMetrics(metrics),
        )
        // Only its best-effort reconnect reassertion runs, on a worker that logs and discards failure.
        val adb = allocate(AdbController::class.java)
        val volume = VolumeController(context)
        val core = io.panelassistant.android.PanelStateCore(
            config = config,
            brightness = brightness,
            screen = screen,
            led = led,
            ledEffect = ledEffect,
            volume = volume,
            system = system,
            bootChime = bootChime,
            zigbee = zigbee,
            relay = relay,
            cpu = cpu,
            adb = adb,
            buttonsEnabled = true,
            buttonKeys = buttonKeys,
            capabilities = { capabilities },
            hasProximity = true,
            hasTemperature = hasTemperature,
            hasHumidity = hasHumidity,
            hasCht8305 = hasCht8305,
            hasButtonBacklight = true,
            media = media,
            hasCamera = cameraAvailability,
            lightChannel = lightChannel,
            autoSleepActivity = autoSleepActivity,
            softwareUpdateSources = { updateSources.get() },
            storageHealth = { storageReads.incrementAndGet(); storage.get() },
            helperSend = helperSend ?: io.panelassistant.android.util.HelperClient::send,
            panelId = PANEL,
            friendlyName = "Golden panel",
            wifiOutages = wifiOutages,
            learnedProximityEligibility = { true },
            learnedProximityState = learnedProximityState,
        )
        val commands = io.panelassistant.android.PanelCommandDispatcher(
            config = config,
            core = core,
            brightness = brightness,
            screen = screen,
            navigate = NavigateController(context),
            volume = volume,
            system = system,
            // Never reached: no navbar command reaches the controller in these tests.
            navbar = allocate(NavbarController::class.java),
            watchdog = WatchdogController(system, config),
            // Never reached: touch_sound needs an Android audio stack, so no touch_sound command is sent.
            touchSound = allocate(TouchSoundController::class.java),
            bootChime = bootChime,
            zigbee = zigbee,
            relay = relay,
            cpu = cpu,
            adb = adb,
            hasProximity = true,
            hasButtonBacklight = true,
            hasMicrophone = true,
            media = media,
            hasCamera = cameraAvailability,
            // Never reached: no screen brightness or auto-brightness command is sent.
            autoBright = allocate(AutoBrightnessController::class.java),
            onDirectKioskSetting = { true },
            onAutoSleepConfigChanged = { autoSleepConfigChanges.incrementAndGet() },
            helperSend = helperSend ?: io.panelassistant.android.util.HelperClient::send,
        )
        return Rig(
            tmp, config, sysfs, core, commands, storage, storageReads, updateSources, autoSleepConfigChanges,
            context.audio, metrics,
        )
    }

    // ---- fakes ----

    /** Relay and button-LED sysfs nodes whose reads reflect writes; one write can be held on a latch. */
    protected class SysfsRootShell : RootShell {
        private val nodes = ConcurrentHashMap(
            mapOf(
                "$RELAY_BASE/relay1" to "0",
                "$RELAY_BASE/relay2" to "1",
                "/sys/class/gpio/gpio$LED_GPIO_BASE/value" to "0",
            ),
        )
        val writes = CopyOnWriteArrayList<String>()
        @Volatile private var blockPath: String? = null
        val blockEntered = CountDownLatch(1)
        val blockRelease = CountDownLatch(1)

        fun blockNextWrite(path: String) {
            blockPath = path
        }

        override fun available() = true
        override fun run(cmd: String) = true
        override fun runOutput(cmd: String): String? = null
        override fun runBytes(cmd: String): ByteArray? = null
        override fun fireAndForget(cmd: String) = true
        override fun listSysfs(path: String): String? = if (path == RELAY_BASE) "relay1 relay2" else null
        override fun readSysfs(path: String): String? = nodes[path]
        override fun prepareOutputGpio(gpio: Int) = true
        override fun writeSysfs(path: String, value: String): Boolean {
            if (path == blockPath) {
                blockPath = null
                blockEntered.countDown()
                check(blockRelease.await(30, TimeUnit.SECONDS)) { "blocked write never released" }
            }
            writes += "$path=$value"
            nodes[path] = value
            return true
        }
    }

    protected class FakeContext(
        private val files: File,
        private val prefs: SharedPreferences,
    ) : ContextWrapper(null) {
        val audio = android.media.ScriptedAudioManager()
        private val resolver = object : ContentResolver(null) {}
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = if (name == Context.AUDIO_SERVICE) audio else null
        override fun getContentResolver(): ContentResolver = resolver
        override fun getNoBackupFilesDir(): File = files
        override fun getFilesDir(): File = files
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getPackageName(): String = "io.panelassistant.android"
    }

    /** Thread-safe in-memory preferences whose commits always succeed. */
    protected class MemoryPreferences : SharedPreferences by proxy(ConcurrentHashMap())

    protected companion object {
        fun drainStatePump() {
            val drained = CountDownLatch(1)
            StateConverger.dispatch { drained.countDown() }
            assertTrue("convergence pump drained", drained.await(5, TimeUnit.SECONDS))
        }

        const val PANEL = "golden"

        const val RELAY_BASE = "/sys/class/strelay"
        const val LED_GPIO_BASE = 147
        fun awaitCondition(condition: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!condition()) {
                if (System.nanoTime() >= deadline) return false
                Thread.sleep(10)
            }
            return true
        }

        fun updateSources(paneldTarget: SoftwareTarget? = SoftwareTarget("1.2.4", "v1.2.4", "https://example.invalid/releases/v1.2.4")) =
            SoftwareUpdateSources(
                paneldVersion = "1.2.3",
                paneldChannel = "stable",
                paneldTarget = paneldTarget,
                companionMinimalVersion = "2026.1.1-minimal",
                companionFullVersion = null,
                companionChannel = "stable",
                companionCap = null,
                companionTarget = null,
                runningOperation = null,
            )

        fun storageSnapshot(severity: StorageHealthSeverity, walBytes: Long) = StorageHealthSnapshot(
            severity = severity,
            pressureSeverity = severity,
            checkedAtMillis = 1_700_000_000_000L,
            usableBytes = 6_000_000_000L,
            totalBytes = 8_000_000_000L,
            usedPercent = 25.0,
            mainDatabaseBytes = 1_048_576L,
            walBytes = walBytes,
            sidecarBytes = 32_768L,
            pageSizeBytes = 4_096L,
            pageCount = 256L,
            freelistCount = 3L,
            schemaVersion = 7,
            quickCheck = StorageQuickCheck.OK,
        )

        fun newConfig(prefs: SharedPreferences, resolver: ContentResolver): Config {
            // The production constructor opens SQLite; the internal JVM seam takes the stores directly.
            val constructor = Config::class.java.declaredConstructors.single {
                it.parameterTypes.contentEquals(
                    arrayOf(
                        SharedPreferences::class.java,
                        ContentResolver::class.java,
                        SharedPreferences::class.java,
                        SharedPreferences::class.java,
                        Resources::class.java,
                    ),
                )
            }
            constructor.isAccessible = true
            return constructor.newInstance(prefs, resolver, prefs, prefs, null) as Config
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> allocate(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
        }

        fun proxy(
            values: MutableMap<String, Any>,
            listeners: CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener> = CopyOnWriteArrayList(),
        ): SharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { instance, method, args ->
                when (method.name) {
                    "getAll" -> synchronized(values) { values.toMap() }
                    "getString", "getInt", "getLong", "getFloat", "getBoolean", "getStringSet" ->
                        values[args!![0] as String] ?: args[1]
                    "contains" -> values.containsKey(args!![0] as String)
                    "edit" -> editor(values) { key ->
                        listeners.forEach { it.onSharedPreferenceChanged(instance as SharedPreferences, key) }
                    }
                    "registerOnSharedPreferenceChangeListener" -> {
                        listeners.addIfAbsent(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener)
                        null
                    }
                    "unregisterOnSharedPreferenceChangeListener" -> {
                        listeners.remove(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener)
                        null
                    }
                    "toString" -> "MemoryPreferences"
                    "hashCode" -> System.identityHashCode(values)
                    "equals" -> false
                    else -> error("unexpected SharedPreferences call: ${method.name}")
                }
            } as SharedPreferences

        fun editor(values: MutableMap<String, Any>, onChange: (String) -> Unit = {}): SharedPreferences.Editor {
            val writes = LinkedHashMap<String, Any?>()
            val removals = LinkedHashSet<String>()
            var clear = false
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet" -> editor.also {
                        writes[args!![0] as String] = args[1]
                        removals.remove(args[0] as String)
                    }
                    "remove" -> editor.also {
                        writes.remove(args!![0] as String)
                        removals.add(args[0] as String)
                    }
                    "clear" -> editor.also { clear = true }
                    "commit", "apply" -> {
                        synchronized(values) {
                            if (clear) values.clear()
                            removals.forEach { values.remove(it) }
                            writes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                        }
                        (removals + writes.keys).forEach(onChange)
                        if (method.name == "commit") true else null
                    }
                    "toString" -> "MemoryPreferencesEditor"
                    else -> error("unexpected Editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
