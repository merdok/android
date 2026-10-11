package io.panelassistant.android.dashboard

import io.panelassistant.android.EntityHoldKind
import io.panelassistant.android.entityHoldKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityLearningBootstrapProblemTest {
    @Test fun `authentication failures are classified without exposing their detail`() {
        assertEquals(
            EntityBootstrapProblem.AUTHENTICATION,
            classifyEntityBootstrapProblem("degraded", "states request failed: HTTP 401"),
        )
        assertEquals(
            EntityBootstrapProblem.AUTHENTICATION,
            classifyEntityBootstrapProblem("degraded", "Home Assistant credential rejected"),
        )
    }

    @Test fun `a scan that found no session yet keeps the panel on its progress screen`() {
        // First-run sign-in replaces the credential under a running scan, which then reports no token.
        // The panel's screen must read that as setup still running, never as a refused sign-in.
        val kind = entityHoldKind(
            blockingIssues = 0,
            filterHold = false,
            problem = classifyEntityBootstrapProblem("degraded", TOKEN_UNAVAILABLE),
            canIgnoreBlockingIssues = false,
        )
        assertEquals(EntityHoldKind.LEARNING, kind)
    }

    @Test fun `other failed scans get generic retry guidance`() {
        assertEquals(
            EntityBootstrapProblem.SYNCHRONIZATION,
            classifyEntityBootstrapProblem("degraded", "connection timed out for private-host.example"),
        )
    }

    @Test fun `success target change and empty errors clear the problem`() {
        assertNull(classifyEntityBootstrapProblem("active", ""))
        assertNull(classifyEntityBootstrapProblem("active", "states request failed: HTTP 401"))
        assertNull(classifyEntityBootstrapProblem("learning", ""))
        assertNull(classifyEntityBootstrapProblem("degraded", ""))
    }
}
