package io.panelassistant.android

import android.content.ContextWrapper
import android.os.Handler
import android.webkit.ValueCallback
import android.webkit.WebView
import io.panelassistant.android.control.BuiltinDashboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.lang.reflect.Proxy

/**
 * A [DashboardActivity] on the plain JVM, for tests that drive its own renderer entry points (the
 * watchdog fire, the periodic check, a teardown) and observe what reaches the WebView, the cover and the
 * process-global renderer state.
 *
 * No Robolectric: the activity, its Handler and the cover's surface are allocated without constructors, and
 * only the collaborators those entry points use are filled in, from production classes. Framework calls
 * return mockable-jar defaults, so Handler posts are not observable; what the WebView is asked to do is.
 */
internal class DashboardRendererRig(prefs: Map<String, Any?> = SIGNED_IN) : AutoCloseable {
    val activity: DashboardActivity = allocate(DashboardActivity::class.java)
    private val main: Handler = allocate(Handler::class.java)
    private val gate = RendererGenerationGate()
    val owner = BuiltinDashboard.acquireActivityOwner()
    val cover = DashboardWebCover(activity, main)
    private val bridge = DashboardFrontendBridge(main, CoroutineScope(Job()), proxy(DashboardFrontendBridge.Host::class.java))

    init {
        set("main", main)
        set("rendererGate", gate)
        set("rendererGeneration", gate.open())
        set("bridge", bridge)
        set("shownPage", ShownPageTracker())
        set("webCover\$delegate", lazyOf(cover))
        set("activityOwner", owner)
        set("activityConfig", Config(fakePreferences(prefs).instance))
        set("retryPolicy", DashboardRetryPolicy())
        set("wakeMediaRecovery", WakeMediaRecoveryGate())
        set("screenAwake", true)
    }

    /** Make [view] the activity's WebView, its listeners already installed (as after its first load). */
    fun show(view: RecordingWebView, committed: String? = view.url) {
        set("web", view)
        DashboardFrontendBridge::class.java.getDeclaredField("v2ListenerView").apply { isAccessible = true }.set(bridge, view)
        committed?.let { (get("shownPage") as ShownPageTracker).onCommitVisible(it) }
    }

    /** Stand the reconnecting or latch cover over the page, as showReconnecting or the latch page leaves it. */
    fun coverShown() {
        DashboardWebCover::class.java.getDeclaredField("cover").apply { isAccessible = true }
            .set(cover, allocate(StatusSurface::class.java))
    }

    val coverUp: Boolean get() = DashboardWebCover::class.java.getDeclaredField("cover").apply { isAccessible = true }.get(cover) != null

    var phase: RendererPhase
        get() = get("phase") as RendererPhase
        set(value) = set("phase", value)

    fun call(name: String) {
        DashboardActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }

    fun set(name: String, value: Any?) {
        DashboardActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    fun get(name: String): Any? =
        DashboardActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    override fun close() = BuiltinDashboard.releaseActivityOwner(owner)

    companion object {
        const val HA = "http://ha.local:8123"
        val SIGNED_IN: Map<String, Any?> = mapOf("ha_url" to HA, "ha_token" to "token")

        @Suppress("UNCHECKED_CAST")
        fun <T> allocate(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        } as T
    }
}

/** A WebView that records what the renderer asks of it. */
internal class RecordingWebView(private val pageUrl: String?) : WebView(ContextWrapper(null)) {
    val calls = mutableListOf<String>()
    override fun getUrl(): String? = pageUrl
    override fun loadUrl(url: String) { calls += "loadUrl $url" }
    override fun reload() { calls += "reload" }
    override fun onPause() { calls += "onPause" }
    override fun onResume() { calls += "onResume" }
    override fun pauseTimers() { calls += "pauseTimers" }
    override fun resumeTimers() { calls += "resumeTimers" }
    override fun destroy() { calls += "destroy" }
    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) { calls += "evaluateJavascript" }
    override fun loadDataWithBaseURL(baseUrl: String?, data: String, mimeType: String?, encoding: String?, historyUrl: String?) {
        calls += "loadData"
    }
}
