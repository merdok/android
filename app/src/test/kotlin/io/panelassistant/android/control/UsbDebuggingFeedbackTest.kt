package io.panelassistant.android.control

import android.database.ContentObserver
import org.junit.Assert.*
import org.junit.Test

class UsbDebuggingFeedbackTest {
    private class Harness(initial: String? = "0", sdk: Int = 29) {
        var raw = initial
        var stopping = false
        var reads = 0
        var registrations = 0
        var unregistrations = 0
        var registered: ContentObserver? = null
        var visible: Boolean? = null
        val events = mutableListOf<String>()
        val reader = UsbDebuggingObserver(sdkInt = { sdk }, readSetting = { reads++; raw })

        fun observe(profile: String = "meta-portal-10-gen2", duringRegistration: () -> Unit = {}): UsbDebuggingFeedback? =
            UsbDebuggingFeedback.observe(
                profile, handler = null, readState = reader::state, isStopping = { stopping },
                register = { registrations++; registered = it; duringRegistration() },
                unregister = { assertSame(registered, it); unregistrations++ },
                show = { enabled ->
                    events += "show:$enabled"
                    visible = enabled
                    val cancel: () -> Unit = { events += "cancel:$enabled"; visible = null }
                    cancel
                },
            )

        fun change(value: String?) {
            raw = value
            checkNotNull(registered).onChange(false)
        }
    }

    @Test fun bothPortalProfilesSeedSilentlyAndIgnoreDuplicateCallbacks() {
        for (profile in listOf("meta-portal", "meta-portal-10-gen2")) {
            for (initial in listOf("0", "1", null)) {
                val h = Harness(initial)
                val observer = h.observe(profile)
                assertNotNull(observer)
                assertEquals(1, h.registrations)
                h.change(initial)
                h.change(initial)
                assertTrue("$profile initial=$initial", h.events.isEmpty())
                observer!!.close()
                assertEquals(1, h.unregistrations)
            }
        }
    }

    @Test fun realSettingCallbacksReplaceTheVisibleStateInBothDirections() {
        val h = Harness("0")
        h.observe()
        h.change("1")
        assertEquals(true, h.visible)
        h.change("1")
        assertEquals(listOf("show:true"), h.events)
        h.change("0")
        assertEquals(false, h.visible)
        assertEquals(listOf("show:true", "cancel:true", "show:false"), h.events)
    }

    @Test fun unknownClearsOldFeedbackAndKnownRecoveryIsASilentBaseline() {
        val h = Harness("0")
        h.observe()
        h.change("1")
        h.change(null)
        assertNull(h.visible)
        h.change("0")
        h.change("2")
        h.change("1")
        assertEquals(listOf("show:true", "cancel:true"), h.events)
        h.change("0")
        assertEquals(listOf("show:true", "cancel:true", "show:false"), h.events)
    }

    @Test fun closeCancelsUnregistersOnceAndQueuedCallbacksCannotReadOrShow() {
        val h = Harness("0")
        val observer = checkNotNull(h.observe())
        h.change("1")
        observer.close()
        observer.close()
        val reads = h.reads
        h.change("0")
        assertNull(h.visible)
        assertEquals(1, h.unregistrations)
        assertEquals(reads, h.reads)
        assertEquals(listOf("show:true", "cancel:true"), h.events)
    }

    @Test fun stoppingBeforeRegistrationOrDuringRegistrationLeavesNoObserver() {
        val before = Harness()
        before.stopping = true
        assertNull(before.observe())
        assertEquals(0, before.registrations)
        assertEquals(0, before.reads)
        val during = Harness()
        assertNull(during.observe(duringRegistration = { during.stopping = true }))
        assertEquals(1, during.unregistrations)
        during.change("1")
        assertTrue(during.events.isEmpty())
    }

    @Test fun aQueuedChangeAfterStoppingCancelsAndUnregistersWithoutAnotherRead() {
        val h = Harness()
        h.observe()
        h.change("1")
        val reads = h.reads
        h.stopping = true
        h.change("0")
        assertNull(h.visible)
        assertEquals(reads, h.reads)
        assertEquals(1, h.unregistrations)
        assertEquals(listOf("show:true", "cancel:true"), h.events)
    }

    @Test fun stoppingDuringASettingReadNeverShowsTheNewValue() {
        var stopping = false
        var stopInRead = false
        var value = false
        val shown = mutableListOf<Boolean>()
        var registered: ContentObserver? = null
        var unregistered = 0
        UsbDebuggingFeedback.observe(
            "meta-portal", null,
            readState = { if (stopInRead) stopping = true; value },
            isStopping = { stopping }, register = { registered = it },
            unregister = { unregistered++ }, show = { shown += it; null },
        )
        value = true
        stopInRead = true
        registered!!.onChange(false)
        assertTrue(shown.isEmpty())
        assertEquals(1, unregistered)
    }

    @Test fun otherProfilesNeverRegisterOrReadAndRedactedValuesNeverBecomeOff() {
        val other = Harness()
        assertNull(other.observe("generic"))
        assertEquals(0, other.registrations)
        assertEquals(0, other.reads)
        val redacted = Harness("0", sdk = 37)
        redacted.observe()
        redacted.change("1")
        redacted.change("0")
        assertEquals(0, redacted.reads)
        assertTrue(redacted.events.isEmpty())
    }
}
