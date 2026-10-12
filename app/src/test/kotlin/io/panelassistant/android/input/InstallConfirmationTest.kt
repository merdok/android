package io.panelassistant.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallConfirmationTest {
    @Test fun onlyTheEnabledInstallButtonOfTheWaitingInstallerIsPressed() {
        val installer = "com.android.packageinstaller"
        val cases = listOf(
            // The app's own update is waiting in this installer: its positive button, either layout.
            listOf(installer, installer, "android:id/button1", true) to true,
            listOf(installer, installer, "$installer:id/ok_button", true) to true,
            // Still disabled just after the window opened: wait for a later event.
            listOf(installer, installer, "android:id/button1", false) to false,
            // Cancel, or any other button.
            listOf(installer, installer, "android:id/button2", true) to false,
            listOf(installer, installer, null, true) to false,
            // Another app's window, even one with a positive button.
            listOf(installer, "com.example.other", "android:id/button1", true) to false,
            // Nothing of this app's is waiting: press nothing, whatever is on screen.
            listOf(null, installer, "android:id/button1", true) to false,
        )
        for ((case, expected) in cases) {
            val (awaiting, window, id, enabled) = case
            assertEquals(case.toString(), expected, InstallConfirmation.shouldPress(awaiting as String?, window as String?, id as String?, enabled as Boolean))
        }
    }
}
