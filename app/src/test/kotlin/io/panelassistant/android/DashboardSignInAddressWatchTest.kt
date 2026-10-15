package io.panelassistant.android

import android.os.Handler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The phone sign-in address on the sign-in-refused cover and the SIGN_IN_REQUIRED admission screen. Both
 * draw it through [DashboardStatusScreens.provenSignInUrl]; when the panel's address changes while such a
 * surface is up, the surface must be redrawn so its QR and fix line never keep naming the old address.
 *
 * The surface here is the funnel's own contract: each draw asks for the address and passes its redraw.
 * The real surfaces' view drawing is not reachable on the JVM (no Robolectric).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardSignInAddressWatchTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
    }

    private class Rig(test: TestScope) {
        var address: String? = A
        val drawn = mutableListOf<String?>()
        val screens: DashboardStatusScreens

        init {
            val io = StandardTestDispatcher(test.testScheduler)
            val host = Proxy.newProxyInstance(
                DashboardStatusScreens.Host::class.java.classLoader,
                arrayOf(DashboardStatusScreens.Host::class.java),
            ) { _, m, _ -> if (m.name == "getDestroyed") false else error("host ${m.name} not expected") }
                as DashboardStatusScreens.Host
            screens = DashboardStatusScreens(
                allocate(DashboardActivity::class.java), allocate(Handler::class.java), test.backgroundScope,
                AdmissionRetryPolicy(), host, { address }, io,
            )
            val check = SignInPageCheck(test.backgroundScope, now = { test.testScheduler.currentTime }, io = io, answers = { true })
            DashboardStatusScreens::class.java.getDeclaredField("signInPageCheck\$delegate")
                .apply { isAccessible = true }.set(screens, lazyOf(check))
        }

        fun draw() { drawn += screens.provenSignInUrl("/configure") { draw() } }

        private fun <T> allocate(type: Class<T>): T = DashboardSignInAddressWatchTest().allocate(type)
    }

    @Test fun aPanelAddressChangeRedrawsTheSignInSurfaceWithTheNewAddress() = runTest {
        val rig = Rig(this)
        rig.draw(); runCurrent()
        assertEquals("first draw offers nothing until A answers, then A", listOf(null, A), rig.drawn)

        rig.address = B
        advanceTimeBy(3_001); runCurrent()
        assertEquals(
            "after the address moved: the old address is dropped at once and B is offered once proven",
            listOf(null, A, null, B), rig.drawn,
        )
    }

    @Test fun losingEveryScannableAddressOffersNoCodeOrFixLine() = runTest {
        val rig = Rig(this)
        rig.draw(); runCurrent()
        rig.address = null
        advanceTimeBy(3_001); runCurrent()
        assertEquals(listOf(null, A, null), rig.drawn)
    }

    @Test fun aSurfaceThatWentDownIsNotRedrawn() = runTest {
        val rig = Rig(this)
        rig.draw(); runCurrent()
        rig.screens.clearRerender()
        rig.address = B
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(null, A), rig.drawn)
    }

    private companion object {
        const val A = "http://192.168.1.20:8888/configure"
        const val B = "http://192.168.1.31:8888/configure"
    }
}
