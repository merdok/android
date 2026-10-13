package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject

/** Keeps derived issue diagnostics small even when a dashboard or parser produces hostile input. */
internal object EntityCatalogIssuePersistence {
    const val MAX_ISSUE_GROUPS = 64
    const val MAX_SOURCES_PER_GROUP = 8
    /**
     * The prior 64 KiB bound plus room for one additive presentation code and typed synthetic-title
     * markers on every retained issue. Empty presentation parameters are deliberately absent and
     * require a fresh budget decision.
     */
    const val MAX_PAYLOAD_BYTES = 72 * 1024
    const val MAX_PRESENTATION_CODE_BYTES = 32
    const val MAX_VIEW_TITLE_INDEX = 10_000
    private val PRESENTATION_CODE = Regex("[a-z0-9][a-z0-9_-]{0,31}")
    private const val MAX_ARRAY_ITEMS = 16
    private const val MAX_OBJECT_KEYS = 24
    private const val MAX_KEY_CHARS = 100
    private const val MAX_STRING_CHARS = 500
    private const val MAX_DEPTH = 4

    fun boundedJson(issues: List<JSONObject>): String {
        val out = JSONArray()
        val buckets = Array(4) { ArrayList<JSONObject>(MAX_ISSUE_GROUPS) }
        for (issue in issues) {
            val bucket = buckets[issuePriority(issue)]
            if (bucket.size < MAX_ISSUE_GROUPS) bucket += issue
        }
        for (bucket in buckets) {
            for (issue in bucket) {
                if (out.length() >= MAX_ISSUE_GROUPS) return out.toString()
                val sanitized = sanitizeObject(issue, 0)
                out.put(sanitized)
                if (out.toString().toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
                    out.remove(out.length() - 1)
                }
            }
        }
        return out.toString()
    }

    fun boundExistingJson(raw: String): String = runCatching {
        val array = JSONArray(raw)
        boundedJson(buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add)
        })
    }.getOrDefault("[]")

    fun counts(raw: String): Pair<Int, Int> = runCatching {
        val array = JSONArray(boundExistingJson(raw))
        var blocking = 0
        for (index in 0 until array.length()) {
            if (array.optJSONObject(index)?.optBoolean("blocking", false) == true) blocking++
        }
        array.length() to blocking
    }.getOrDefault(0 to 0)

    fun ignoredCount(raw: String): Int = runCatching {
        val array = JSONArray(boundExistingJson(raw))
        (0 until array.length()).count { array.optJSONObject(it)?.optBoolean("ignored", false) == true }
    }.getOrDefault(0)

    fun canIgnore(issue: JSONObject): Boolean {
        val wouldBlock = issue.optBoolean("would_block", issue.optBoolean("blocking", false))
        return wouldBlock && issue.optBoolean("ignorable", true)
    }

    fun applyIgnores(issues: JSONArray, ignoredFingerprints: Set<String>): String {
        val effective = buildList {
            for (index in 0 until issues.length()) issues.optJSONObject(index)?.let { source ->
                val issue = JSONObject(source.toString())
                val wouldBlock = issue.optBoolean("would_block", issue.optBoolean("blocking", false))
                val ignored = canIgnore(issue) && issue.optString("fingerprint") in ignoredFingerprints
                issue.put("ignored", ignored)
                issue.put("would_block", wouldBlock)
                issue.put("blocking", wouldBlock && !ignored)
                if (wouldBlock) issue.put("severity", if (ignored) "warning" else "error")
                add(issue)
            }
        }
        return boundedJson(effective)
    }

    private fun issuePriority(issue: JSONObject): Int = when {
        issue.optString("type") == "diagnostic_limit" -> 0
        issue.optBoolean("blocking", false) -> 1
        issue.optBoolean("would_block", false) -> 2
        else -> 3
    }

    private fun sanitizeObject(input: JSONObject, depth: Int): JSONObject = JSONObject().apply {
        if (depth >= MAX_DEPTH) return@apply
        input.keys().asSequence().toList().sorted()
            .filterNot { it == "presentation_params" }
            .take(MAX_OBJECT_KEYS)
            .forEach { rawKey ->
            val key = rawKey.take(MAX_KEY_CHARS)
            sanitizeValue(input.opt(rawKey), depth + 1, key)?.let { put(key, it) }
        }
    }

    private fun sanitizeValue(value: Any?, depth: Int, key: String): Any? = when {
        value == null || value === JSONObject.NULL -> JSONObject.NULL
        value is String && key == "presentation_code" -> value.takeIf(::isValidPresentationCode)
        key == "presentation_code" -> null
        key == "view_title_index" -> (value as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it % 1.0 == 0.0 && it in 1.0..MAX_VIEW_TITLE_INDEX.toDouble()
        }?.toInt()
        key == "card_title_hacs_kiosk" -> true.takeIf { value == true }
        value is String -> value.take(MAX_STRING_CHARS)
        value is Boolean || value is Number -> value
        depth >= MAX_DEPTH -> null
        value is JSONObject -> sanitizeObject(value, depth)
        value is JSONArray -> JSONArray().apply {
            val limit = if (key == "sources" || key == "source_locations") MAX_SOURCES_PER_GROUP else MAX_ARRAY_ITEMS
            for (index in 0 until minOf(value.length(), limit)) {
                sanitizeValue(value.opt(index), depth + 1, key)?.let { put(it) }
            }
        }
        else -> value.toString().take(MAX_STRING_CHARS)
    }

    internal fun isValidPresentationCode(value: String): Boolean = PRESENTATION_CODE.matches(value)
}
