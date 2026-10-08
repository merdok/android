package io.panelassistant.android.state

import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.panelassistant.PanelAssistantShadowReporter
import io.panelassistant.android.requestLocalObservation
import io.panelassistant.android.util.ServiceRuntimeOwner
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

internal class NativeHelloTest : PanelRuntimeRig() {
    @Test(timeout = 90_000)
    fun acceptedHelloReportsEveryKnowableDescribedChannel() = runBlocking {
        // The state core alone produces every native observation.
        val rig = rig()
        val owner = ServiceRuntimeOwner(rig, "native-hello")
        try {
            assertTrue(owner.start { it.start() }.get(5, TimeUnit.SECONDS))
            var checks = 0
            assertTrue("a replaced runtime cannot complete a native snapshot", !rig.core.observeForNativeHello {
                ++checks == 1
            })
            assertEquals("ownership was checked after observation", 2, checks)

            // An ordinary local pass establishes which channels this hardware can actually read.
            // Bind a fresh reporter afterwards: its accepted hello must produce those observations
            // even though no periodic tick has supplied its initial cache yet.
            val reference = PanelAssistantShadowReporter(log = {})
            rig.core.addStateSink(reference.bindShape(rig.core::nativeChannelShape))
            requestLocalObservation(owner) { it.core }
            drainStatePump()
            val referenceOffer = reference.offer()
            reference.open(referenceOffer.descriptors)
            val knowable = channels(JSONObject(requireNotNull(reference.next(1, "reference", 0L))))
            assertTrue("fixture must expose a meaningful state surface: $knowable", knowable.size >= 20)
            assertTrue(knowable.containsAll(listOf("screen", "navigate", "home_dashboard", "storage_health", "relay1")))

            val native = PanelAssistantShadowReporter(log = {})
            rig.core.addStateSink(native.bindShape(rig.core::nativeChannelShape))
            val hello = native.offer()
            val described = hello.descriptors.map { it.channel }.toSet()
            assertTrue("expected channels must be described", described.containsAll(knowable))

            // The production accepted-hello path samples the current runtime before opening reporting.
            assertTrue(rig.core.observeForNativeHello { owner.observe()?.let(owner::isCurrent) == true })
            native.open(hello.descriptors)
            val begin = JSONObject(requireNotNull(native.next(2, "native-session", 0L)))
            assertEquals("full_begin", begin.getString("sync"))
            val reported = channels(begin)
            assertTrue("full sync missed ${knowable - reported.toSet()}", reported.containsAll(knowable))
            native.onResult(io.panelassistant.android.panelassistant.PanelAssistantReportResult.Acknowledged(2, emptyMap()), 0L)
            val end = JSONObject(requireNotNull(native.next(3, "native-session", 0L)))
            assertEquals("full_end", end.getString("sync"))
            native.onResult(io.panelassistant.android.panelassistant.PanelAssistantReportResult.Acknowledged(3, emptyMap()), 0L)
            assertTrue(native.fullSyncComplete())
        } finally {
            owner.shutdown(1_000) {}
            rig.close()
        }
    }

    private fun channels(report: JSONObject): Set<String> = report.getJSONArray("observations").let { observations ->
        (0 until observations.length()).mapTo(linkedSetOf()) { observations.getJSONObject(it).getString("channel") }
    }
}
