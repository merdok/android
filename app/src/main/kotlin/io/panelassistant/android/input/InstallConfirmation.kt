package io.panelassistant.android.input

/**
 * Which button on Android's install confirmation the accessibility service may press: the positive button
 * of the installer that shows this app's own update, found by view id because its label is translated.
 */
internal object InstallConfirmation {
    /** AOSP's alert-dialog positive button, and the older installer layout's own Install button. */
    fun buttonIds(installer: String): List<String> = listOf("android:id/button1", "$installer:id/ok_button")

    /**
     * Press only while this app's own update waits in [awaitingInstaller], only in that installer's window,
     * and only an enabled Install button.
     */
    fun shouldPress(awaitingInstaller: String?, windowPackage: String?, viewId: String?, enabled: Boolean): Boolean =
        awaitingInstaller != null && windowPackage == awaitingInstaller && enabled &&
            viewId != null && viewId in buttonIds(awaitingInstaller)
}
