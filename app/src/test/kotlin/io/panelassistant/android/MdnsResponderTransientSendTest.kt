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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.impl.DNSOutgoing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production advertiser, started on the loopback IPv4 address and read back by an independent
 * JmDNS browser. One test per class, so the slow responder tests spread across test forks.
 */
class MdnsResponderTransientSendTest {
    @Test fun oneFailedResponseSendKeepsTheOriginalAdvertisementOnWire() {
        val failNext = AtomicBoolean(false)
        val sendFailed = CountDownLatch(1)
        val sendRetried = CountDownLatch(1)
        val failedPacket = AtomicReference<DNSOutgoing?>()
        val advertiser = MdnsAdvertiser(
            context = ContextWrapper(null),
            config = Config(readOnlyPreferences()),
            runtimePanelId = "transient-send-panel-$RUN",
            runtimeFriendlyName = "Transient Send Panel",
            runtimeHttpPort = 8888,
            acquireMulticastLock = { {} },
            discoveryId = { "transient-send-did" },
            refreshIntervalMs = 100,
            createDns = { address, name ->
                object : MdnsSendRetryDns(address, name) {
                    override fun sendPacket(outgoing: DNSOutgoing) {
                        if (outgoing === failedPacket.get()) sendRetried.countDown()
                        if (isResponderReply(outgoing) && failNext.compareAndSet(true, false)) {
                            failedPacket.set(outgoing)
                            sendFailed.countDown()
                            throw SocketException("sendto failed: EPERM (Operation not permitted)")
                        }
                        super.sendPacket(outgoing)
                    }
                }
            },
        )
        try {
            advertiser.start(LOOPBACK)
            val original = JmDNS.create(InetAddress.getByName(LOOPBACK), "initial-send-browser").use {
                browse(it, "transient-send-panel-$RUN")
            }
            assertNotNull("the original service never appeared", original)
            val token = original!!.getPropertyString("probe")

            failNext.set(true)
            val afterFault = JmDNS.create(InetAddress.getByName(LOOPBACK), "fault-send-browser").use {
                browse(it, "transient-send-panel-$RUN")
            }
            assertTrue("the browser never triggered a response send", sendFailed.await(5, TimeUnit.SECONDS))
            assertNotNull("one failed send withdrew the real advertisement", afterFault)
            assertEquals("the responder was replaced after one failed send", token, afterFault!!.getPropertyString("probe"))
            assertTrue("the failed response was not retried", sendRetried.await(5, TimeUnit.SECONDS))
            assertEquals(0, advertiser.health().liveness.recoveryAttempts)
        } finally {
            advertiser.stop()
        }
    }

}
