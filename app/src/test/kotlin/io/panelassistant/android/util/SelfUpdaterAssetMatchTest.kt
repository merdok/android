package io.panelassistant.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdaterAssetMatchTest {
    @Test fun onlyThePrefixedApkBinIsThisAppsAsset() {
        assertTrue(SelfUpdater.isOwnAsset("panel-assistant-v1.0.0-manual-setup-required.apk.bin"))
        assertFalse(SelfUpdater.isOwnAsset("ha-paneld-v0.9.11-manual-setup-required.apk.bin"))
        assertFalse(SelfUpdater.isOwnAsset("panel-assistant-v0.9.8-manual-setup-required.apk"))
        assertFalse(SelfUpdater.isOwnAsset("panel-assistant-v1.0.0-manual-setup-required.apk.bin.idsig"))
        assertFalse(SelfUpdater.isOwnAsset("panel-assistant-v1.0.0-manual-setup-required.apk.bin.sha256"))
        assertFalse(SelfUpdater.isOwnAsset("x-panel-assistant-v1.0.0.apk.bin"))
    }
}
