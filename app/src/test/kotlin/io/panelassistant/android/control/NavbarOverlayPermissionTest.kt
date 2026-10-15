package io.panelassistant.android.control

import io.panelassistant.android.testsupport.scratchDirectory

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A panel whose only way to Android Settings is the app's own bar (a Meta Portal) must not lose that
 * way when the overlay permission is missing: the dashboard's bottom-edge swipe opens Android's page
 * for the permission instead of doing nothing, and once the permission is held it no longer does.
 */
class NavbarOverlayPermissionTest {
    private val settingsOpened = AtomicInteger()
    private val tempDir: File = scratchDirectory("navbar-overlay-permission").toFile()
    @Volatile private var overlayHeld = false

    @Before @After fun reset() {
        BuiltinDashboard.setNavbarRevealHandler(null)
    }

    @Test fun aSwipeWithoutTheOverlayOpensAndroidsPermissionPage() {
        val navbar = navbar()

        navbar.applyAsync(NavbarController.MODE_SWIPE).get(10, TimeUnit.SECONDS)

        assertTrue("the swipe stays armed without the overlay", BuiltinDashboard.navbarSwipeEnabled)
        BuiltinDashboard.requestNavbarReveal()
        assertEquals(1, settingsOpened.get())
    }

    @Test fun anAlwaysOnBarWithoutTheOverlayAlsoLeadsToThePermission() {
        val navbar = navbar()

        navbar.applyAsync(NavbarController.MODE_ALWAYS).get(10, TimeUnit.SECONDS)

        BuiltinDashboard.requestNavbarReveal()
        assertEquals(1, settingsOpened.get())
    }

    @Test fun onceTheOverlayIsHeldTheSwipeNoLongerOpensSettings() {
        val navbar = navbar()
        navbar.applyAsync(NavbarController.MODE_SWIPE).get(10, TimeUnit.SECONDS)
        BuiltinDashboard.requestNavbarReveal()

        overlayHeld = true
        BuiltinDashboard.requestNavbarReveal()

        assertEquals(1, settingsOpened.get())
    }

    @Test fun offAndNativeArmNoSwipe() {
        val navbar = navbar()

        navbar.applyAsync(NavbarController.MODE_OFF).get(10, TimeUnit.SECONDS)
        navbar.applyAsync(NavbarController.MODE_NATIVE).get(10, TimeUnit.SECONDS)

        assertFalse(BuiltinDashboard.navbarSwipeEnabled)
        assertEquals(0, settingsOpened.get())
    }

    private fun navbar() = NavbarController(
        context = RecordingContext(),
        system = allocate(SystemController::class.java),
        volume = allocate(VolumeController::class.java),
        brightness = allocate(BrightnessController::class.java),
        launcherPkg = { "" },
        dashboardPkg = { "" },
        appCanSu = false,
        hasRecents = false,
        overlayHeld = { overlayHeld },
    )

    private inner class RecordingContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.WINDOW_SERVICE) {
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WindowManager::class.java)) { _, _, _ -> null }
            } else null

        override fun getNoBackupFilesDir(): File = tempDir

        override fun getPackageName(): String = "io.panelassistant.android"

        override fun startActivity(intent: Intent?) {
            settingsOpened.incrementAndGet()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }
}
