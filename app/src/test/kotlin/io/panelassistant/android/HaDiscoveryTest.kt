package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract that keeps discovery honest: every "cannot work here" verdict must be earned, and anything
 * unjudgeable must degrade to "found nothing" rather than assert a cause. The regression these guard is a
 * cross-subnet panel being left at a blank Home Assistant URL with no explanation.
 */
class HaDiscoveryTest {
    @Test
    fun anIpFormAdvertisedUrlIsRewrittenToTheRecordsOwnHostname() {
        // HA commonly advertises internal_url in IP form; suggestions built from it pinned panels to one
        // IPv4 address (hardware review). The record's server field names the same machine.
        assertEquals(
            "http://homeassistant.local:8123",
            HaDiscovery.preferServerHostname("http://192.0.2.8:8123", "homeassistant.local."),
        )
        // Hostname-form URLs are untouched; a missing/IP-form server changes nothing.
        assertEquals(
            "https://hass.example.net:443",
            HaDiscovery.preferServerHostname("https://hass.example.net:443", "homeassistant.local."),
        )
        assertEquals(
            "http://192.0.2.8:8123",
            HaDiscovery.preferServerHostname("http://192.0.2.8:8123", "198.51.100.5"),
        )
        assertEquals(
            "http://192.0.2.8:8123",
            HaDiscovery.preferServerHostname("http://192.0.2.8:8123", null),
        )
        assertEquals(null, HaDiscovery.preferServerHostname(null, "homeassistant.local."))
        // IPv6-literal advertised hosts count as IP-form too.
        assertEquals(
            "http://homeassistant.local:8123",
            HaDiscovery.preferServerHostname("http://[fd00::8]:8123", "homeassistant.local"),
        )
    }

    @Test fun aSuccessfulDiscoverySettlesItRegardlessOfEveryOtherSignal() {
        val r = HaDiscovery.classify(
            mdnsRunning = false,
            multicastLockHeld = false,
            servicesSeen = 0,
            discoveredUrl = "http://homeassistant.local:8123",
            attemptedAtMs = 7L,
            failed = true,
        )
        assertEquals(DiscoveryOutcome.FOUND, r.outcome)
        assertEquals("http://homeassistant.local:8123", r.value)
        assertEquals(DiscoveryReason.NONE, r.reason)
        assertFalse(r.hopeless)
    }

    @Test fun notListeningIsReportedAsAPanelFact() {
        val r = HaDiscovery.classify(
            mdnsRunning = false, multicastLockHeld = true,
            servicesSeen = SERVICES_SEEN_UNKNOWN, discoveredUrl = "", attemptedAtMs = 1L,
        )
        assertEquals(DiscoveryReason.MDNS_NOT_RUNNING, r.reason)
        assertTrue(r.hopeless)
    }

    @Test fun aMissingMulticastLockIsReportedAsAPanelFact() {
        val r = HaDiscovery.classify(
            mdnsRunning = true, multicastLockHeld = false,
            servicesSeen = SERVICES_SEEN_UNKNOWN, discoveredUrl = "", attemptedAtMs = 1L,
        )
        assertEquals(DiscoveryReason.NO_MULTICAST_LOCK, r.reason)
        assertEquals(DiscoveryOutcome.UNAVAILABLE, r.outcome)
    }

    @Test fun silenceIsTheWeakestInferenceAndIsSkippedWhenUncounted() {
        val silent = HaDiscovery.classify(
            mdnsRunning = true, multicastLockHeld = true,
            servicesSeen = 0, discoveredUrl = "", attemptedAtMs = 1L,
        )
        assertEquals(DiscoveryReason.NO_RESPONSES_AT_ALL, silent.reason)
        val uncounted = HaDiscovery.classify(
            mdnsRunning = true, multicastLockHeld = true,
            servicesSeen = SERVICES_SEEN_UNKNOWN, discoveredUrl = "", attemptedAtMs = 1L,
        )
        assertEquals(DiscoveryOutcome.NONE_FOUND, uncounted.outcome)
    }

    @Test fun explanationsExistForEveryUnavailableReasonAndNoneOtherwise() {
        assertNull(HaDiscovery.unavailableExplanation(DiscoveryResult()))
        assertNull(HaDiscovery.unavailableExplanation(DiscoveryResult(DiscoveryOutcome.NONE_FOUND)))
        assertNull(
            HaDiscovery.unavailableExplanation(DiscoveryResult(DiscoveryOutcome.FOUND, "http://ha:8123")),
        )
        DiscoveryReason.entries.filter { it != DiscoveryReason.NONE }.forEach { reason ->
            val text = HaDiscovery.unavailableExplanation(
                DiscoveryResult(DiscoveryOutcome.UNAVAILABLE, reason = reason),
            )
            assertNotNull("no explanation for $reason", text)
            // Each explanation is embedded mid-sentence by the caller, so it must not be pre-punctuated.
            // (It may still begin with a capital when it opens on a proper noun such as Home Assistant.)
            assertTrue("explanation for $reason should not be blank", text!!.isNotBlank())
            assertFalse("explanation for $reason must compose mid-sentence", text.trimEnd().endsWith("."))
        }
    }

    @Test fun aGuessIsPhrasedAsOne() {
        val guess = HaDiscovery.unavailableExplanation(
            DiscoveryResult(DiscoveryOutcome.UNAVAILABLE, reason = DiscoveryReason.NO_RESPONSES_AT_ALL),
        )!!
        // Overclaiming here is what makes a diagnosis actively misleading rather than merely unhelpful.
        assertTrue(guess.contains("may"))
    }
}
