package io.panelassistant.android

import android.content.ContextWrapper
import io.panelassistant.android.MdnsResponderTestSupport.LOOPBACK
import io.panelassistant.android.MdnsResponderTestSupport.RUN
import io.panelassistant.android.MdnsResponderTestSupport.browse
import io.panelassistant.android.MdnsResponderTestSupport.isResponderReply
import io.panelassistant.android.MdnsResponderTestSupport.readOnlyPreferences
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.impl.DNSOutgoing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production advertiser, started on the loopback IPv4 address and read back by an independent
 * JmDNS browser. One test per class, so the slow responder tests spread across test forks.
 */
class MdnsResponderPersistentSendRecoveryTest {
    @Test fun persistentFailedResponseSendRecoversAfterCompletedTeardown() {
        val remainingFailures = AtomicInteger(0)
        val failedSends = CountDownLatch(2)
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = Config(readOnlyPreferences()),
            runtimePanelId = "responder-recovery-panel-$RUN",
            runtimeFriendlyName = "Responder Recovery Panel",
            runtimeHttpPort = 8888,
            acquireMulticastLock = {
                // Model a close that finishes after the 2-second worker-drain budget.
                { Thread.sleep(2_100) }
            },
            discoveryId = { "responder-recovery-did" },
            refreshIntervalMs = 100,
            createDns = { address, name ->
                object : MdnsSendRetryDns(address, name) {
                    override fun sendPacket(outgoing: DNSOutgoing) {
                        if (isResponderReply(outgoing) && remainingFailures.getAndUpdate { maxOf(0, it - 1) } > 0) {
                            failedSends.countDown()
                            throw SocketException("sendto failed: EPERM (Operation not permitted)")
                        }
                        super.sendPacket(outgoing)
                    }
                }
            },
        )
        val ipv4Browser = JmDNS.create(InetAddress.getByName(LOOPBACK), "recovery-ipv4-browser")
        try {
            advertiser.start(LOOPBACK)
            val original = browse(ipv4Browser, "responder-recovery-panel-$RUN")
            assertNotNull("the primary never advertised", original)
            val oldToken = original!!.getPropertyString("probe")

            // Two failed attempts for one real response still reach JmDNS's self-close path.
            remainingFailures.set(2)
            assertEquals(
                "the production probe must see the withdrawn responder",
                MdnsProbeResult.MISSING,
                probeMdnsService(LOOPBACK, "responder-recovery-panel-$RUN", Config.MDNS_SERVICE_TYPE, oldToken),
            )
            assertTrue("the response did not fail twice", failedSends.await(5, TimeUnit.SECONDS))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var replacement: ServiceInfo? = null
            while (System.nanoTime() < deadline) {
                val state = advertiser.health().liveness
                assertTrue(
                    "closed responders must not be reported as an undrained teardown: $state",
                    state.reasonCode != MdnsReasonCode.TEARDOWN_FAILED,
                )
                JmDNS.create(InetAddress.getByName(LOOPBACK), "recovery-check-browser").use { fresh ->
                    replacement = browse(fresh, "responder-recovery-panel-$RUN")
                }
                val replacementToken = replacement?.getPropertyString("probe")
                if (replacementToken != null && replacementToken != oldToken) break
            }
            assertNotNull("three missing IPv4 probes must recreate the responder", replacement)
            assertNotEquals(oldToken, replacement!!.getPropertyString("probe"))
        } finally {
            advertiser.stop()
            ipv4Browser.close()
        }
    }

}
