package io.panelassistant.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdaterAssetMatchTest {
    @Test fun onlyThePrefixedApkIsThisAppsAsset() {
        assertTrue(SelfUpdater.isOwnAsset("panel-assistant-v0.9.8-manual-setup-required.apk"))
        assertFalse(SelfUpdater.isOwnAsset("ha-paneld-v0.9.8-manual-setup-required.apk"))
        assertFalse(SelfUpdater.isOwnAsset("panel-assistant-v0.9.8.apk.idsig"))
        assertFalse(SelfUpdater.isOwnAsset("x-panel-assistant-v0.9.8.apk"))
    }
}
