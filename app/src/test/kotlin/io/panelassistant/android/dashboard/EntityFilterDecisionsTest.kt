package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityFilterDecisionsTest {
    @Test fun bulkIssueIgnorePreflightsNonIgnorableFenceBeforeAnyMutation() {
        val ordinary = JSONObject()
            .put("type", "unbounded_selector")
            .put("blocking", true)
            .put("ignorable", true)
            .put("fingerprint", "0123456789abcdef")
        val fence = JSONObject()
            .put("type", "diagnostic_limit")
            .put("blocking", true)
            .put("ignorable", false)
            .put("fingerprint", "fedcba9876543210")

        val ordinaryOnly = blockingIssueSelection(JSONArray().put(ordinary).toString())
        assertTrue(ordinaryOnly.allIgnorable)
        assertEquals(listOf("0123456789abcdef"), ordinaryOnly.fingerprints)
        val fenced = blockingIssueSelection(JSONArray().put(ordinary).put(fence).toString())
        assertFalse(fenced.allIgnorable)
        assertEquals(listOf("0123456789abcdef"), fenced.fingerprints)
    }
}
