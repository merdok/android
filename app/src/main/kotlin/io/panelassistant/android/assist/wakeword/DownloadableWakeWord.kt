package io.panelassistant.android.assist.wakeword

import org.json.JSONObject
import java.net.URL

/**
 * A community microWakeWord model the panel can download, as the app's own list pins it. microWakeWord
 * manifests carry no licence, so the licence shown before a download is the one of the upstream
 * repository, recorded here with the commit the URLs are pinned to. The manifest is fetched as published;
 * its `model` names the `.tflite` beside it. Both are refused unless their SHA-256 matches.
 */
data class DownloadableWakeWord(
    val id: String,
    val wakeWord: String,
    val author: String,
    val license: String,
    val licenseUrl: String,
    val source: String,
    val manifestUrl: String,
    val manifestSha256: String,
    val modelSha256: String,
    val modelBytes: Long,
) {
    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** Every well-formed entry of the list; one with a field missing or out of shape is left out. */
        fun parseAll(json: String): List<DownloadableWakeWord> {
            val models = JSONObject(json).optJSONArray("models") ?: return emptyList()
            return (0 until models.length()).mapNotNull { index ->
                runCatching { parse(models.getJSONObject(index)) }.getOrNull()
            }.distinctBy { it.id }
        }

        private fun parse(entry: JSONObject): DownloadableWakeWord {
            fun text(key: String) = entry.getString(key).also { require(it.isNotBlank()) { key } }
            fun https(key: String) = text(key).also { require(URL(it).protocol == "https") { key } }
            fun digest(key: String) = text(key).also { require(SHA256.matches(it)) { key } }
            return DownloadableWakeWord(
                id = text("id").also { require(WakeWordCatalog.ID.matches(it)) { "id" } },
                wakeWord = text("wake_word"),
                author = text("author"),
                license = text("license"),
                licenseUrl = https("license_url"),
                source = https("source"),
                manifestUrl = https("manifest_url"),
                manifestSha256 = digest("manifest_sha256"),
                modelSha256 = digest("model_sha256"),
                modelBytes = entry.getLong("model_bytes").also { require(it > 0) { "model_bytes" } },
            )
        }
    }
}
