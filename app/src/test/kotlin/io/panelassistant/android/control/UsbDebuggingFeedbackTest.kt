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
    @Test fun delayedPreparationCannotShowAnOlderStateAfterReplacementOrClose() {
        var value = false
        var observer: ContentObserver? = null
        val prepared = mutableListOf<(String) -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val feedback = checkNotNull(UsbDebuggingFeedback.observe(
            "meta-portal", null, { value }, { false }, { observer = it }, {},
            show = { enabled ->
                UsbDebuggingFeedback.showWhenPrepared(
                    isStopping = { false },
                    prepare = { ready -> prepared += ready; { events += "cancel-prepare:$enabled" } },
                    post = { main.addLast(it) },
                    show = { text ->
                        events += "show:$text"
                        val cancel: () -> Unit = { events += "cancel-toast:$text" }
                        cancel
                    },
                )
            },
        ))
        value = true
        observer!!.onChange(false)
        prepared[0]("USB debugging: On")
        value = false
        observer!!.onChange(false)
        main.removeFirst().invoke()
        assertEquals(listOf("cancel-prepare:true"), events)
        prepared[1]("USB debugging: Off")
        main.removeFirst().invoke()
        assertEquals(listOf("cancel-prepare:true", "show:USB debugging: Off"), events)
        feedback.close()
        assertEquals(listOf("cancel-prepare:true", "show:USB debugging: Off", "cancel-prepare:false", "cancel-toast:USB debugging: Off"), events)
        prepared[0]("late On")
        main.removeFirst().invoke()
        assertEquals(4, events.size)
    }

    @Test fun aPreparedMessageIsDroppedWhenStoppingBeforeMainDelivery() {
        var stopping = false
        var ready: ((String) -> Unit)? = null
        var posted: (() -> Unit)? = null
        val shown = mutableListOf<String>()
        val cancel = UsbDebuggingFeedback.showWhenPrepared(
            { stopping }, prepare = { ready = it; {} }, post = { posted = it },
            show = { shown += it; null },
        )
        ready!!("USB debugging: On")
        stopping = true
        posted!!.invoke()
        assertTrue(shown.isEmpty())
        cancel()
    }

    private class Presentation {
        var permission = true
        var attachFails = false
        var now = 0L
        val visible = mutableSetOf<String>()
        val timers = mutableMapOf<() -> Unit, Long>()
        val removals = mutableListOf<String>()

        fun show(text: String): () -> Unit = UsbDebuggingFeedback.presentStatus(
            canOverlay = { permission },
            attachOverlay = {
                if (attachFails) error("permission revoked")
                attach("banner:$text")
            },
            showToast = { attach("toast:$text") },
            schedule = { delay, expire ->
                timers[expire] = now + delay
                val cancel: () -> Unit = { timers.remove(expire) }
                cancel
            },
        )

        private fun attach(label: String): () -> Unit {
            visible += label
            return { visible -= label; removals += label }
        }

        fun advance(time: Long) {
            now += time
            timers.filterValues { it <= now }.keys.toList().forEach { it() }
        }
    }

    @Test fun statusBannerRemainsForEightSecondsThenRemovesItsWindowAndTimerOnce() {
        val h = Presentation()
        val cancel = h.show("USB debugging: On")
        h.advance(7_999)
        assertEquals(setOf("banner:USB debugging: On"), h.visible)
        h.advance(1)
        assertTrue(h.visible.isEmpty())
        assertTrue(h.timers.isEmpty())
        cancel()
        assertEquals(listOf("banner:USB debugging: On"), h.removals)
    }

    @Test fun missingOrRevokedOverlayPermissionUsesCancellableToastFallback() {
        for (revoked in listOf(false, true)) {
            val h = Presentation().apply { permission = revoked; attachFails = revoked }
            val cancel = h.show("USB debugging: Off")
            assertEquals(setOf("toast:USB debugging: Off"), h.visible)
            cancel()
            h.advance(8_000)
            assertTrue(h.visible.isEmpty())
            assertTrue(h.timers.isEmpty())
            assertEquals(listOf("toast:USB debugging: Off"), h.removals)
        }
    }

    @Test fun settingReplacementUnknownAndTeardownReleasePresentationAndItsTimer() {
        val h = Presentation()
        var state: Boolean? = false
        var observer: ContentObserver? = null
        var unregistered = 0
        val feedback = checkNotNull(UsbDebuggingFeedback.observe(
            "meta-portal", null, { state }, { false }, { observer = it }, { unregistered++ },
            show = { h.show("USB debugging: ${if (it) "On" else "Off"}") },
        ))
        fun change(value: Boolean?) { state = value; observer!!.onChange(false) }
        change(true)
        h.advance(4_000)
        change(false)
        assertEquals(setOf("banner:USB debugging: Off"), h.visible)
        assertEquals(1, h.timers.size)
        h.advance(4_000)
        assertEquals(setOf("banner:USB debugging: Off"), h.visible)
        change(null)
        assertTrue(h.visible.isEmpty())
        assertTrue(h.timers.isEmpty())
        change(false)
        change(true)
        feedback.close()
        assertTrue(h.visible.isEmpty())
        assertTrue(h.timers.isEmpty())
        assertEquals(1, unregistered)
        assertEquals(listOf("banner:USB debugging: On", "banner:USB debugging: Off", "banner:USB debugging: On"), h.removals)
    }

}
