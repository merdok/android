package io.panelassistant.android

import android.content.ContextWrapper
import io.panelassistant.android.MdnsResponderTestSupport.LOOPBACK
import io.panelassistant.android.MdnsResponderTestSupport.RUN
import io.panelassistant.android.MdnsResponderTestSupport.SECONDARY_LOOPBACK
import io.panelassistant.android.MdnsResponderTestSupport.browse
import io.panelassistant.android.MdnsResponderTestSupport.hasAddress
import io.panelassistant.android.MdnsResponderTestSupport.readOnlyPreferences
import io.panelassistant.android.util.RetirableMutationGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production advertiser, started on the loopback IPv4 address and read back by an independent
 * JmDNS browser. One test per class, so the slow responder tests spread across test forks.
 */
class MdnsSecondaryRepairRemovedAddressTest {
    @Test fun queuedSecondaryRepairCannotRestoreRemovedNetworkAddress() {
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = Config(readOnlyPreferences()),
            runtimePanelId = "removed-secondary-test-panel-$RUN",
            runtimeFriendlyName = "Removed Secondary Test Panel",
            acquireMulticastLock = { {} },
            discoveryId = { "removed-secondary-test-did" },
            refreshIntervalMs = 100,
        )
        val gate = MdnsAdvertiser::class.java.getDeclaredField("ownerGate").apply {
            isAccessible = true
        }.get(advertiser) as RetirableMutationGate
        val topology = MdnsAdvertiser::class.java.getDeclaredField("topology").apply {
            isAccessible = true
        }.get(advertiser) as MdnsTopology
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        var holder: Thread? = null
        try {
            advertiser.start(LOOPBACK, SECONDARY_LOOPBACK)
            // The secondary responder announces asynchronously; a loaded runner can take longer than one browse.
            assertTrue(hasAddress(SECONDARY_LOOPBACK, "removed-secondary-test-panel-$RUN", SECONDARY_LOOPBACK, timeoutMs = 15_000))
            holder = Thread {
                gate.runExclusive {
                    entered.countDown()
                    release.await()
                }
            }.apply { start() }
            assertTrue("owner gate was not held", entered.await(2, TimeUnit.SECONDS))
            val secondary = MdnsAdvertiser::class.java.getDeclaredField("secondaryDns").apply {
                isAccessible = true
            }.get(advertiser) as JmDNS
            secondary.close()

            // A network callback updates topology before entering the owner gate. Hold that gate
            // until the real refresh worker has queued repair and is waiting to execute it.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var recoveryWorker: Thread? = null
            while (System.nanoTime() < deadline && recoveryWorker == null) {
                recoveryWorker = Thread.getAllStackTraces().keys.firstOrNull { thread ->
                    thread.name == "ha-paneld-mdns-recovery" &&
                        thread.stackTrace.any { it.className.endsWith("RetirableMutationGate") }
                }
                if (recoveryWorker == null) Thread.sleep(20)
            }
            assertNotNull("secondary repair was never queued", recoveryWorker)
            topology.request(LOOPBACK, null)
            release.countDown()
            holder.join(2_000)
            while (System.nanoTime() < deadline &&
                recoveryWorker!!.stackTrace.any { it.className.endsWith("RetirableMutationGate") }
            ) Thread.sleep(20)
            assertTrue("queued repair did not finish", recoveryWorker!!.stackTrace.none {
                it.className.endsWith("RetirableMutationGate")
            })
            assertTrue("primary vanished", hasAddress(LOOPBACK, "removed-secondary-test-panel-$RUN", LOOPBACK))
            assertTrue(
                "queued repair restored an address removed by the network callback",
                !hasAddress(SECONDARY_LOOPBACK, "removed-secondary-test-panel-$RUN", SECONDARY_LOOPBACK),
            )
        } finally {
            release.countDown()
            holder?.join(2_000)
            advertiser.stop()
        }
    }
}
