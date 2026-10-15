package io.panelassistant.android.persistence

import android.content.Context
import io.panelassistant.android.DashboardEntityBackupState
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.dashboard.readThenClose
import io.panelassistant.android.util.DashboardPath
import io.panelassistant.android.util.Json
import org.json.JSONArray
import org.json.JSONObject

internal data class DashboardIgnoredIssue(
    val instance: String,
    val path: String,
    val fingerprint: String,
    val ignoredAt: Long,
)

/** The catalog is Android-owned; callers can substitute this existing backup seam on the JVM. */
internal interface DashboardIssueCustody {
    fun export(): List<DashboardIgnoredIssue>

    /** Returns how many requested decisions are durably present, including an identical earlier restore. */
    fun restore(rows: List<DashboardIgnoredIssue>): Int
}

internal class CatalogDashboardIssueCustody(private val context: Context) : DashboardIssueCustody {
    override fun export() = readThenClose(EntityCatalogStore(context), { it.close() }) {
        it.exportIgnoredDashboardIssues()
    }

    override fun restore(rows: List<DashboardIgnoredIssue>) =
        readThenClose(EntityCatalogStore(context), { it.close() }) { it.restoreIgnoredDashboardIssues(rows) }
}

/** Explicit diagnostic decisions only: neither dashboard documents nor scans belong in a backup. */
internal object DashboardIssueBackup {
    const val SECTION_KEY = "ignored_dashboard_issues"
    const val MAX_ROWS = 128
    const val MAX_BYTES = 64 * 1024
    private val INSTANCE = Regex("[A-Za-z0-9_.:-]{1,256}")
    private val FINGERPRINT = Regex("[a-f0-9]{16}")

    fun manifestFragment(rows: List<DashboardIgnoredIssue>): String {
        validate(rows)
        return if (rows.isEmpty()) "" else ",\"$SECTION_KEY\":" + encode(rows)
    }

    /** Missing means an older archive; a present malformed section refuses the whole restore. */
    fun decode(manifest: JSONObject): List<DashboardIgnoredIssue> {
        if (!manifest.has(SECTION_KEY)) return emptyList()
        val section = manifest.opt(SECTION_KEY) as? JSONArray
            ?: throw IllegalArgumentException("invalid $SECTION_KEY section")
        require(section.length() <= MAX_ROWS && section.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "$SECTION_KEY exceeds its backup limit"
        }
        val rows = (0 until section.length()).map { index ->
            val row = section.opt(index) as? JSONObject
                ?: throw IllegalArgumentException("invalid ignored dashboard issue")
            fun string(key: String) = row.opt(key) as? String
                ?: throw IllegalArgumentException("invalid ignored dashboard issue $key")
            val timestamp = row.opt("ignored_at")
            require(timestamp is Long || timestamp is Int) { "invalid ignored dashboard issue timestamp" }
            DashboardIgnoredIssue(string("instance"), string("path"), string("fingerprint"), (timestamp as Number).toLong())
        }
        validate(rows)
        return rows
    }

    /** Other dashboards of this HA owner keep their decisions; another HA namespace inherits none. */
    fun admitted(rows: List<DashboardIgnoredIssue>, entityState: DashboardEntityBackupState?): List<DashboardIgnoredIssue> {
        validate(rows)
        val owner = entityState?.instanceKey ?: return emptyList()
        if (!validInstance(owner)) return emptyList()
        return rows.filter { it.instance == owner }
    }

    /** Checked at capture and again before the catalog opens a write transaction. Never truncate. */
    fun validate(rows: List<DashboardIgnoredIssue>) {
        require(rows.size <= MAX_ROWS) { "$SECTION_KEY exceeds its row limit" }
        val keys = HashSet<Triple<String, String, String>>()
        rows.forEach { row ->
            require(validInstance(row.instance)) { "invalid ignored dashboard issue instance" }
            require(row.path.length <= 2_048 && DashboardPath.root(row.path) == row.path) {
                "ignored dashboard issue path must be a dashboard root"
            }
            require(FINGERPRINT.matches(row.fingerprint) && row.ignoredAt >= 0L) {
                "invalid ignored dashboard issue fingerprint or timestamp"
            }
            require(keys.add(Triple(row.instance, row.path, row.fingerprint))) { "duplicate ignored dashboard issue" }
        }
        require(encode(rows).toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "$SECTION_KEY exceeds its byte limit" }
    }

    private fun validInstance(value: String) = value != "unconfigured" && INSTANCE.matches(value)

    private fun encode(rows: List<DashboardIgnoredIssue>): String = rows.joinToString(",", "[", "]") { row ->
        "{\"instance\":${Json.str(row.instance)},\"path\":${Json.str(row.path)}," +
            "\"fingerprint\":${Json.str(row.fingerprint)},\"ignored_at\":${row.ignoredAt}}"
    }
}
