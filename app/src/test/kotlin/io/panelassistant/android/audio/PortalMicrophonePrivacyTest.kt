package io.panelassistant.android.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalMicrophonePrivacyTest {
    @Test fun `construction is inert and an ON startup observation is the first publication`() {
        var reads = 0
        val published = mutableListOf<Pair<Boolean, Boolean>>()
        val privacy = PortalMicrophonePrivacy({ true }, { reads++; false }) { muted, initial ->
            published += muted to initial
        }
        assertEquals(0, reads)
        assertEquals(emptyList<Pair<Boolean, Boolean>>(), published)
        privacy.observe(1.toByte())
        privacy.refresh()
        privacy.observe(1.toByte()) // Duplicate initial-sticky delivery.
        privacy.refresh()
        assertEquals(listOf(true to true), published)
        privacy.observe(0.toByte())
        privacy.refresh()
        assertEquals(listOf(true to true, false to false), published)
    }

    @Test fun `hardware OFF never clears Android mute and either source can keep the microphone muted`() {
        var androidMuted = true
        val published = mutableListOf<Pair<Boolean, Boolean>>()
        val privacy = PortalMicrophonePrivacy({ true }, { androidMuted }) { muted, initial ->
            published += muted to initial
        }
        privacy.observe(0.toByte())
        privacy.refresh()
        androidMuted = false
        privacy.refresh()
        privacy.observe(1.toByte())
        privacy.refresh()
        androidMuted = true
        privacy.observe(0.toByte())
        privacy.refresh()
        assertEquals(listOf(true to true, false to false, true to false), published)
        androidMuted = false
        privacy.refresh()
        assertEquals(listOf(true to true, false to false, true to false, false to false), published)
    }

    @Test fun `unknown transitional missing and malformed events retain the last stable hardware observation`() {
        val published = mutableListOf<Boolean>()
        val privacy = PortalMicrophonePrivacy({ true }, { false }) { muted, _ -> published += muted }
        privacy.observe(1.toByte())
        privacy.refresh()
        listOf(null, 0, 1, false, "0", 2.toByte(), 3.toByte(), (-1).toByte(), 4.toByte()).forEach {
            privacy.observe(it)
            privacy.refresh()
        }
        assertEquals(listOf(true), published)
        privacy.observe(0.toByte())
        privacy.refresh()
        privacy.observe(3.toByte())
        privacy.refresh()
        assertEquals(listOf(true, false), published)
    }

    @Test fun `an absent microphone is never reported muted by either source`() {
        var offered = false
        val published = mutableListOf<Boolean>()
        val privacy = PortalMicrophonePrivacy({ offered }, { true }) { muted, _ -> published += muted }
        privacy.observe(1.toByte())
        privacy.refresh()
        offered = true
        privacy.refresh()
        offered = false
        privacy.refresh()
        assertEquals(listOf(false, true, false), published)
    }

    @Test fun `Android-only observations retain their existing initial and change publications`() {
        var androidMuted = false
        val published = mutableListOf<Pair<Boolean, Boolean>>()
        val privacy = PortalMicrophonePrivacy({ true }, { androidMuted }) { muted, initial ->
            published += muted to initial
        }
        privacy.refresh()
        privacy.refresh()
        androidMuted = true
        privacy.refresh()
        androidMuted = false
        privacy.refresh()
        assertEquals(listOf(false to true, true to false, false to false), published)
    }

    @Test fun `queued observations and refreshes publish nothing after close`() {
        val published = mutableListOf<Boolean>()
        val privacy = PortalMicrophonePrivacy({ true }, { false }) { muted, _ -> published += muted }
        privacy.observe(1.toByte())
        privacy.refresh()
        privacy.close()
        privacy.close()
        privacy.observe(0.toByte())
        privacy.refresh()
        assertEquals(listOf(true), published)
        assertEquals(true, privacy.mute.muted)
    }
}
