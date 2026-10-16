package io.panelassistant.android.util

import io.panelassistant.android.AppIdentity
import io.panelassistant.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The app's own pin: exactly this package under the release signer. */
class AppInstallerPinTest {
    private val signer = AppInstaller.HA_PANELD.certSha256

    @Test fun theAppPinsItsOwnPackageUnderTheReleaseSigner() {
        assertEquals(BuildConfig.APPLICATION_ID, AppInstaller.HA_PANELD.pkg)
        assertEquals("ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339", signer)
        assertNull(AppInstaller.pinRefusal(AppIdentity.OWN, listOf(signer), AppInstaller.HA_PANELD))
    }

    @Test fun anotherPackageUnderTheReleaseSignerNeverSatisfiesThePin() {
        for (other in listOf(AppIdentity.LEGACY, "com.example.other")) {
            assertEquals(
                "package $other not allowlisted",
                AppInstaller.pinRefusal(other, listOf(signer), AppInstaller.HA_PANELD),
            )
        }
    }

    @Test fun theOwnPackageUnderAnotherSignerIsRefused() {
        assertEquals(
            "signer mismatch",
            AppInstaller.pinRefusal(AppIdentity.OWN, listOf("0".repeat(64)), AppInstaller.HA_PANELD),
        )
        assertEquals(
            "no signature",
            AppInstaller.pinRefusal(AppIdentity.OWN, emptyList(), AppInstaller.HA_PANELD),
        )
    }

    @Test fun pinnedSignerMatchesWithoutRegardToCase() {
        assertNull(AppInstaller.pinRefusal(AppIdentity.OWN, listOf(signer.uppercase()), AppInstaller.HA_PANELD))
    }
}
