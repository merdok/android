package io.panelassistant.android.platform

import io.panelassistant.android.platform.AndroidDeviceName.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** A rename is reported as written only when Android reads the new name back. */
class AndroidDeviceNameTest {
    private var name: String? = "Jenna"
    private val writes = mutableListOf<String>()

    private fun follower(granted: Boolean = true, sticks: Boolean = true) = AndroidDeviceName(
        granted = { granted },
        read = { name },
        write = { writes += it; if (sticks) name = it; true },
    )

    @Test fun withoutTheGrantNothingIsWrittenAndThePanelSaysSo() {
        assertEquals(Outcome.NOT_GRANTED, follower(granted = false).follow("Garden room"))
        assertEquals(emptyList<String>(), writes)
        assertEquals("secure_settings=false device_name=\"Jenna\"", follower(granted = false).diagnostic())
    }

    @Test fun aWriteAndroidDoesNotKeepIsAFailureNotASuccess() {
        assertEquals(Outcome.FAILED, follower(sticks = false).follow("Garden room"))
        assertEquals("Jenna", name)
    }

    /** A cleared friendly name leaves Android's name alone, so the model never becomes the name. */
    @Test fun withNoChosenNameAndroidsNameIsLeftAlone() {
        assertEquals(Outcome.NOT_CHOSEN, follower().follow(null))
        assertEquals(Outcome.NOT_CHOSEN, follower().follow(" "))
        assertEquals(emptyList<String>(), writes)
    }

    @Test fun aChosenNameIsWrittenOnce() {
        assertEquals(Outcome.WRITTEN, follower().follow("Garden room"))
        assertEquals(Outcome.UNCHANGED, follower().follow("Garden room"))
        assertEquals(listOf("Garden room"), writes)
    }
}
