package io.panelassistant.android

import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import io.panelassistant.android.input.PanelAccessibilityService
import io.panelassistant.android.input.PortalStartupOverlayDismissal
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real service publication and destruction, with only factory work and the main queue held by the test. */
class PortalStartupOwnerTeardownTest {
    private class Page {
        val backs = AtomicInteger()
        val checks = ConcurrentLinkedQueue<Runnable>()
        val installations = ConcurrentLinkedQueue<Runnable>()
        fun controller(onClose: () -> Unit = {}): PortalStartupOverlayDismissal {
            val closed = AtomicBoolean()
            return PortalStartupOverlayDismissal(
                "meta-portal-10-gen2", 9, { 8 }, { true }, "Aloha Control Center", { 24_000L },
                { PortalStartupOverlayDismissal.Window(
                    41, PortalStartupOverlayDismissal.SETTINGS, listOf("Aloha Control Center"),
                    listOf(PortalStartupOverlayDismissal.Toggle(true, true, true, false)),
                ) },
                { backs.incrementAndGet(); true }, { checks.add(it) }, {
                    checks.remove(it)
                    if (closed.compareAndSet(false, true)) onClose()
                },
            )
        }
        fun install(controller: PortalStartupOverlayDismissal) =
            PanelAccessibilityService.installPortalStartupOverlayDismissal(controller) { installations.add(it) }
        fun show(controller: PortalStartupOverlayDismissal) {
            drain(installations)
            controller.onEvent(TYPE_WINDOW_STATE_CHANGED, PortalStartupOverlayDismissal.SETTINGS,
                PortalStartupOverlayDismissal.ACTIVITY, 41)
            drain(checks)
        }
    }

    @Test fun everyEarlyServiceExitStopsBeforeAConcurrentOwnerCanPublishDuringClose() {
        val outcomes = earlyGenerations.map { generation ->
            val service = service(generation)
            val page = Page()
            val late = page.controller()
            val closing = page.controller {
                // The main thread has already read the old field. Publish the concurrently prepared
                // replacement through the real owner method before that close returns.
                prepare(service, { late }, page::install)
            }
            setField(service, "portalStartupOverlayDismissal", closing)
            try {
                service.onDestroy()
                page.show(late)
                page.backs.get()
            } finally { cancelScope(service) }
        }
        assertEquals(List(earlyGenerations.size) { 0 }, outcomes)
    }

    @Test fun noMainInstallationExistsWhileTheFactoryIsPausedAcrossOwnerDestruction() {
        val outcomes = earlyGenerations.map { generation ->
            val service = service(generation)
            val page = Page()
            val controller = page.controller()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = Executors.newSingleThreadExecutor()
            val preparing = worker.submit {
                prepare(service, {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    controller
                }, page::install)
            }
            try {
                assertTrue("factory must be paused before destroying its owner", entered.await(5, TimeUnit.SECONDS))
                service.onDestroy()
                // Drain main while preparation is STILL blocked. The old factory posted installation
                // before handing ownership back; publication must now wait for the owner stop check.
                val queuedWhilePreparing = page.installations.size
                drain(page.installations)
                drain(page.checks)
                val beforeReturn = page.backs.get()
                release.countDown()
                preparing.get(5, TimeUnit.SECONDS)
                page.show(controller)
                listOf(queuedWhilePreparing, beforeReturn, page.installations.size, page.backs.get())
            } finally {
                release.countDown()
                worker.shutdownNow()
                cancelScope(service)
            }
        }
        assertEquals(List(earlyGenerations.size) { listOf(0, 0, 0, 0) }, outcomes)
    }

    @Test fun stoppedOwnersRejectPreparationAndAlreadyQueuedMainInstallation() {
        val outcomes = earlyGenerations.flatMap { generation ->
            listOf(false, true).map { preparedFirst ->
                val service = service(generation)
                val page = Page()
                val controller = page.controller()
                try {
                    if (preparedFirst) prepare(service, { controller }, page::install)
                    val queuedBeforeStop = page.installations.size
                    service.onDestroy()
                    if (!preparedFirst) prepare(service, { controller }, page::install)
                    page.show(controller)
                    listOf(queuedBeforeStop, page.backs.get())
                } finally { cancelScope(service) }
            }
        }
        assertEquals(earlyGenerations.flatMap { listOf(listOf(0, 0), listOf(1, 0)) }, outcomes)
    }

    @Test fun actualAccessibilityDestructionClosesThePublishedPolicyButAnOldInstanceCannotCloseANewOne() {
        val outcomes = listOf(false, true).map { destroyCurrent ->
            val service = service("uninitialized")
            val page = Page()
            val controller = page.controller()
            val oldConnection = PanelAccessibilityService()
            val currentConnection = PanelAccessibilityService()
            val instance = PanelAccessibilityService::class.java.getDeclaredField("instance").apply { isAccessible = true }
            try {
                prepare(service, { controller }, page::install)
                drain(page.installations)
                instance.set(null, currentConnection)
                (if (destroyCurrent) currentConnection else oldConnection).onDestroy()
                page.show(controller)
                page.backs.get()
            } finally {
                instance.set(null, null)
                service.onDestroy()
                cancelScope(service)
            }
        }
        assertEquals(listOf(1, 0), outcomes)
    }

    private fun service(generation: String): PaneldService = PaneldService().also {
        if (generation != "uninitialized") setField(it, generation, true)
    }

    private fun prepare(
        service: PaneldService,
        factory: () -> PortalStartupOverlayDismissal?,
        install: (PortalStartupOverlayDismissal) -> Unit,
    ) {
        PaneldService::class.java.getDeclaredMethod(
            "preparePortalStartupOverlayDismissal", Function0::class.java, Function1::class.java,
        ).apply { isAccessible = true }.invoke(service, factory, install)
    }

    private fun setField(service: PaneldService, name: String, value: Any?) =
        PaneldService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)

    private fun cancelScope(service: PaneldService) =
        (PaneldService::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(service) as CoroutineScope).cancel()

    private companion object {
        val earlyGenerations = listOf("uninitialized", "guardDbRedirect", "identityMigrationStandby", "standingDown")
        fun drain(queue: ConcurrentLinkedQueue<Runnable>) {
            while (true) (queue.poll() ?: return).run()
        }
    }
}
