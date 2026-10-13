package io.panelassistant.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallConfirmationTest {
    private val installer = "com.android.packageinstaller"
    private val label = "Panel Assistant"

    private fun press(
        awaiting: String? = installer,
        window: String? = installer,
        id: String? = "android:id/button1",
        enabled: Boolean = true,
        texts: List<CharSequence?> = listOf("Panel Assistant", "Do you want to install an update to this existing app?"),
    ) = InstallConfirmation.shouldPress(awaiting, window, id, enabled, texts, label)

    @Test fun onlyTheEnabledInstallButtonOfThisAppsOwnConfirmationIsPressed() {
        val cases = listOf(
            // This app's own update waits in this installer, and the window names this app: either layout.
            press() to true,
            press(id = "$installer:id/ok_button") to true,
            // Still disabled just after the window opened: wait for a later event.
            press(enabled = false) to false,
            // Cancel, or any other button.
            press(id = "android:id/button2") to false,
            press(id = null) to false,
            // Another app's window, even one with a positive button.
            press(window = "com.example.other") to false,
            // Nothing of this app's is waiting: press nothing, whatever is on screen.
            press(awaiting = null) to false,
            // The same installer confirming another app while this app's update waits.
            press(texts = listOf("Other App", "Do you want to install this app?")) to false,
            // A name that only contains this app's, and a window that names no app.
            press(texts = listOf("Panel Assistant Pro")) to false,
            press(texts = emptyList()) to false,
        )
        cases.forEachIndexed { index, (actual, expected) -> assertEquals("case $index", expected, actual) }
    }
}
