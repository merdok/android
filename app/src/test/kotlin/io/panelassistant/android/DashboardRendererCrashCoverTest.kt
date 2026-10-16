package io.panelassistant.android

import android.os.Handler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import io.panelassistant.android.control.BuiltinDashboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * A renderer crash while the Reconnecting cover is up. The crash is delivered to the dashboard
 * WebView's own [WebViewClient], built by [DashboardActivity.createWebView], on an activity whose state
 * is exactly what [DashboardActivity.showReconnecting] leaves behind: the reconnecting phase and the
 * cover standing over the current WebView's container.
 *
 * The rebuild that follows creates a new container with `setContentView`. If the crash leaves the phase
 * reconnecting, the new WebView's first network error reaches `showReconnecting`, which returns on that
 * stale phase, so nothing covers Chromium's own error page; and the old cover stays in the detached
 * container. A teardown ends the phase on every rebuild path; the crash path must as well.
 *
 * No Robolectric: the activity is allocated without its constructor (which needs a main Looper) and
 * only the fields the crash path touches are populated; framework calls return mockable-jar defaults.
 */
class DashboardRendererCrashCoverTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
    }

    private fun set(target: Any, name: String, value: Any?) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            runCatching { c.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                it.set(target, value)
                return
            }
            c = c.superclass
        }
        error("no field $name")
    }

    private fun get(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private class Detail : RenderProcessGoneDetail() {
        override fun didCrash() = true
        override fun rendererPriorityAtExit() = 0
    }

    @Test fun aRendererCrashUnderTheReconnectingCoverClearsItSoTheRebuiltWebViewCanShowItAgain() {
        val activity = allocate(DashboardActivity::class.java)
        val main = allocate(Handler::class.java)
        val gate = RendererGenerationGate()
        val generation = gate.open()
        val oldWeb = allocate(WebView::class.java)
        val host = Proxy.newProxyInstance(
            DashboardFrontendBridge.Host::class.java.classLoader,
            arrayOf(DashboardFrontendBridge.Host::class.java),
        ) { _, m, _ -> error("bridge host ${m.name} not expected on a crash") } as DashboardFrontendBridge.Host
        val cover = DashboardWebCover(activity, main)
        // The cover showReconnecting put up over the crashed WebView's container.
        val shownCover = allocate(StatusSurface::class.java)
        set(cover, "cover", shownCover)

        set(activity, "main", main)
        set(activity, "rendererGate", gate)
        set(activity, "bridge", DashboardFrontendBridge(main, CoroutineScope(Job()), host))
        set(activity, "shownPage", ShownPageTracker().apply { onCommitVisible("data:text/html,reconnecting") })
        set(activity, "webCover\$delegate", lazyOf(cover))
        set(activity, "activityOwner", BuiltinDashboard.acquireActivityOwner())
        set(activity, "web", oldWeb)
        set(activity, "phase", RendererPhase.RECONNECTING) // showReconnecting ran for the first network error
        set(activity, "reconnectingSinceMs", 1L)
        set(activity, "pacer", DashboardActivity::class.java.getDeclaredMethod("createPacer").apply { isAccessible = true }.invoke(activity))

        // The client is an anonymous class whose compiler ordinal shifts as createWebView changes.
        val clientClass = (1..9).mapNotNull {
            runCatching { Class.forName("io.panelassistant.android.DashboardActivity\$createWebView\$$it\$1") }.getOrNull()
        }.single { WebViewClient::class.java.isAssignableFrom(it) }
        val ctor = clientClass.declaredConstructors.single().apply { isAccessible = true }
        val args = ctor.parameterTypes.map { type ->
            when (type) {
                DashboardActivity::class.java -> activity
                java.lang.Long.TYPE -> generation
                else -> null
            }
        }
        val client = ctor.newInstance(*args.toTypedArray()) as WebViewClient

        assertTrue(client.onRenderProcessGone(oldWeb, Detail()))

        // The owned-crash branch ran: the generation is retired and the dead WebView released.
        assertFalse("crash did not retire the renderer generation", gate.owns(generation))
        assertNull("crash did not release the dead WebView", get(activity, "web"))

        // What the rebuilt WebView's first main-frame network error needs: showReconnecting returns
        // early while the phase is still reconnecting, and the old cover must not stay behind in the
        // detached container.
        assertEquals(
            "after the renderer-crash rebuild: [still reconnecting (showReconnecting returns early), " +
                "old cover still held in the crashed WebView's container]",
            listOf(false, false),
            listOf(get(activity, "phase") == RendererPhase.RECONNECTING, get(cover, "cover") != null),
        )
    }
}
