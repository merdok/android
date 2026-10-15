package io.panelassistant.android.persistence

import io.panelassistant.android.DashboardEntityBackupState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardIssueBackupTest {
    private val first = DashboardIgnoredIssue("1020304050607080", "/lovelace", "aabbccddeeff0011", 123L)

    private fun archive(rows: List<DashboardIgnoredIssue>) =
        JSONObject("{\"kind\":\"ha-paneld-backup\"${DashboardIssueBackup.manifestFragment(rows)}}")

    private fun owner(instance: String = first.instance) = DashboardEntityBackupState(
        instanceKey = instance,
        instanceOrigin = "https://ha.example",
        instanceUuid = "ha-instance",
        dashboardPath = first.path,
        filterIds = "",
        filterEnabled = false,
        filterOwner = "",
        learningApplied = false,
        appliedOwner = "",
        overrides = "",
        overrideOwner = "",
        initialActivationPending = false,
    )

    private fun refused(block: () -> Unit) {
        try {
            block()
            throw AssertionError("malformed or overflowing decisions were accepted")
        } catch (_: IllegalArgumentException) {
            // An invalid present section must refuse restore, never look like an absent section.
        }
    }

    @Test fun exactDecisionsRoundTripWithoutDashboardDocuments() {
        val rows = listOf(first, first.copy(path = "/dashboard-kitchen", ignoredAt = Long.MAX_VALUE))
        val written = archive(rows)

        assertEquals(rows, DashboardIssueBackup.decode(written))
        assertEquals(setOf("kind", DashboardIssueBackup.SECTION_KEY), written.keys().asSequence().toSet())
        assertFalse(written.toString().contains("config_json"))
    }

    @Test fun olderArchivesAndEmptyCapturesNeedNoSection() {
        assertEquals("", DashboardIssueBackup.manifestFragment(emptyList()))
        assertTrue(DashboardIssueBackup.decode(JSONObject("{}" )).isEmpty())
    }

    @Test fun admissionKeepsAllDashboardsOfTheRestoredHaOwnerOnly() {
        val otherDashboard = first.copy(path = "/dashboard-kitchen")
        val otherHa = first.copy(instance = "1122334455667788")
        val rows = listOf(first, otherDashboard, otherHa)

        assertEquals(listOf(first, otherDashboard), DashboardIssueBackup.admitted(rows, owner()))
        assertTrue(DashboardIssueBackup.admitted(rows, owner("")).isEmpty())
        assertTrue(DashboardIssueBackup.admitted(rows, owner("unconfigured")).isEmpty())
        assertTrue(DashboardIssueBackup.admitted(rows, null).isEmpty())
    }

    @Test fun malformedRowsRefuseTheWholeSectionIncludingAValidNeighbour() {
        val changes = listOf<Pair<String, Any>>(
            "instance" to "", "instance" to "unconfigured", "instance" to "a/b",
            "instance" to "a".repeat(257), "instance" to "bad\nowner",
            "path" to "/", "path" to "lovelace", "path" to "/lovelace/view",
            "path" to "/lovelace?query", "path" to "//lovelace", "path" to "/lovelace/",
            "path" to "/${"a".repeat(2_048)}", "path" to "/%2e%2e",
            "fingerprint" to "AABBCCDDEEFF0011", "fingerprint" to "aabb", "fingerprint" to 1,
            "ignored_at" to -1L, "ignored_at" to 1.5, "ignored_at" to "123", "ignored_at" to JSONObject.NULL,
        )
        changes.forEach { (key, value) ->
            val written = archive(listOf(first))
            val section = written.getJSONArray(DashboardIssueBackup.SECTION_KEY)
            section.put(JSONObject(section.getJSONObject(0).toString()).put(key, value))
            refused { DashboardIssueBackup.decode(written) }
        }
    }

    @Test fun malformedSectionTypesAndDuplicateDecisionsAreRejected() {
        listOf<Any>(JSONObject(), JSONObject.NULL, "[]", 1, JSONArray().put(true)).forEach { section ->
            refused { DashboardIssueBackup.decode(JSONObject().put(DashboardIssueBackup.SECTION_KEY, section)) }
        }
        refused { DashboardIssueBackup.manifestFragment(listOf(first, first.copy(ignoredAt = 124))) }
        val written = archive(listOf(first))
        written.getJSONArray(DashboardIssueBackup.SECTION_KEY).put(JSONObject())
        refused { DashboardIssueBackup.decode(written) }
    }

    @Test fun captureAndReadBothRefuseOverflowRatherThanDroppingDecisions() {
        val full = (0 until DashboardIssueBackup.MAX_ROWS).map { index ->
            first.copy(fingerprint = "%016x".format(index))
        }
        assertEquals(full, DashboardIssueBackup.decode(archive(full)))
        refused { DashboardIssueBackup.manifestFragment(full + first) }
        val written = archive(full)
        written.getJSONArray(DashboardIssueBackup.SECTION_KEY).put(JSONObject())
        refused { DashboardIssueBackup.decode(written) }

        val tooLarge = full.map { it.copy(path = "/${"a".repeat(1_000)}") }
        refused { DashboardIssueBackup.manifestFragment(tooLarge) }
        val oversizedSection = JSONArray().apply {
            tooLarge.forEach { row -> put(JSONObject().put("instance", row.instance).put("path", row.path)
                .put("fingerprint", row.fingerprint).put("ignored_at", row.ignoredAt)) }
        }
        refused { DashboardIssueBackup.decode(JSONObject().put(DashboardIssueBackup.SECTION_KEY, oversizedSection)) }
    }

    @Test fun programmaticRowsAreValidatedBeforeCustodyCanReceiveThem() {
        listOf(
            first.copy(instance = "unconfigured"), first.copy(path = "/lovelace/view"),
            first.copy(fingerprint = "bad"), first.copy(ignoredAt = -1),
        ).forEach { row -> refused { DashboardIssueBackup.admitted(listOf(row), owner()) } }
    }
}
