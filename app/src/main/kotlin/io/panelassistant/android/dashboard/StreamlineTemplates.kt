package io.panelassistant.android.dashboard

import android.util.Log
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.closeBody
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.schema.CoreSchema

/**
 * Streamline Card's template expansion, reproduced only far enough for the static entity scan.
 *
 * The card (0.2.x) reads its templates from a YAML file Home Assistant serves without authentication,
 * overlaid by the dashboard's own root `streamline_templates`, and substitutes `[[name]]` variables by
 * plain string replacement over the template's JSON. Expanding the same way before the first filtered
 * paint lets the scan see the entities a template names instead of learning them one runtime promotion
 * (a visible WebView rebuild) at a time. JavaScript keys are never evaluated: their text is scanned as
 * literal configuration, as for any other card.
 */
internal object StreamlineTemplates {
    const val CARD_TYPE = "custom:streamline-card"
    private const val FILE = "streamline-card/streamline_templates.yaml"

    /** The card's own lookup order; the first file that answers and parses wins. */
    val REMOTE_PATHS = listOf("/hacsfiles/$FILE", "/local/$FILE", "/local/community/$FILE")
    const val MAX_BYTES = 1L * 1024 * 1024
    private const val MAX_DEPTH = 8
    // Nesting fans out multiplicatively; a real dashboard expands to tens of cards.
    private const val MAX_EXPANSIONS = 1_000
    private const val TAG = "EntityLearning"

    // Built into the card and overridable by either source, exactly as the card merges it.
    private val BUILT_IN = """{"example_tile":{"card":{"entity":"[[entity]]","features_position":"bottom","type":"tile","vertical":false}}}"""

    private val load = Load(
        LoadSettings.builder()
            .setLabel("streamline templates")
            .setSchema(CoreSchema())
            .setAllowDuplicateKeys(false)
            .setAllowRecursiveKeys(false)
            .setMaxAliasesForCollections(50)
            .setCodePointLimit(MAX_BYTES.toInt())
            .build(),
    )

    fun usedBy(configJson: String): Boolean = configJson.contains(CARD_TYPE)

    /**
     * The remote template file, fetched the way the card fetches it: without credentials (Home Assistant
     * serves it as a static file), first path that answers with a YAML mapping wins.
     */
    fun fetch(base: String, timeoutMs: Int, beforeRequest: () -> Unit = {}): JSONObject? {
        for (path in REMOTE_PATHS) {
            beforeRequest()
            var c: HttpURLConnection? = null
            try {
                c = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                }
                if (c.responseCode !in 200..299) {
                    c.closeBody()
                    continue
                }
                val templates = c.inputStream.use {
                    parse(String(BoundedStreams.readBytes(it, MAX_BYTES), Charsets.UTF_8))
                } ?: continue
                Log.i(TAG, "streamline templates: ${templates.length()} from $path")
                return templates
            } catch (failure: IOException) {
                Log.w(TAG, "streamline templates at $path unreadable: ${failure.message}")
            } catch (failure: ByteLimitExceeded) {
                Log.w(TAG, "streamline templates at $path unreadable: ${failure.message}")
            } finally {
                c?.disconnect()
            }
        }
        Log.w(TAG, "streamline templates unavailable; their entities are learned at runtime")
        return null
    }

    /** The template map from the remote file, or null when it is not a YAML mapping. */
    fun parse(yaml: String): JSONObject? = try {
        toJson(load.loadFromString(yaml)) as? JSONObject
    } catch (_: RuntimeException) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    /** Every streamline card in [root] expanded with its variables, nested invocations included. */
    fun expandedCards(root: JSONObject, remote: JSONObject?): JSONArray {
        val templates = JSONObject(BUILT_IN)
        remote?.let { merge(templates, it) }
        root.optJSONObject("streamline_templates")?.let { merge(templates, it) }
        val out = JSONArray()

        fun visit(value: Any?, stack: List<String>) {
            when (value) {
                is JSONObject -> {
                    val name = value.opt("template") as? String
                    if (value.optString("type") == CARD_TYPE && name != null &&
                        name !in stack && stack.size < MAX_DEPTH && out.length() < MAX_EXPANSIONS
                    ) {
                        templates.optJSONObject(name)?.let { expand(it, value.opt("variables")) }?.let { card ->
                            out.put(card)
                            visit(card, stack + name)
                        }
                    }
                    value.keys().forEach { visit(value.opt(it), stack) }
                }
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i), stack)
            }
        }
        root.keys().asSequence().filterNot { it == "streamline_templates" }.forEach { visit(root.opt(it), emptyList()) }
        return out
    }

    /** The card's `evaluateVariables`: template defaults, then the card's variables, by string replacement. */
    fun expand(template: JSONObject, variables: Any?): Any? {
        val card = template.opt("card") ?: template.opt("element") ?: return null
        if ((variables == null || variables == JSONObject.NULL) && !template.has("default")) return card
        val values = LinkedHashMap<String, Any?>()
        flatten(template.opt("default"), values)
        flatten(variables, values)
        var text = card.toString()
        for ((key, value) in values) {
            val k = Regex.escape("[[$key]]")
            text = when (value) {
                is Number, is Boolean -> text.replace(Regex("[\"'`]?$k[\"'`]?"), Regex.escapeReplacement(value.toString()))
                is JSONObject, is JSONArray, JSONObject.NULL, null -> {
                    val json = (value ?: JSONObject.NULL).toString()
                    text.replace(Regex("\"$k\""), Regex.escapeReplacement(json))
                        .replace(Regex("['`]$k['`]"), Regex.escapeReplacement(json.replace("\"", "\\\"")))
                }
                else -> text.replace(Regex(k), Regex.escapeReplacement(value.toString()))
            }
        }
        return try {
            JSONTokener(text).nextValue()
        } catch (_: JSONException) {
            null
        }
    }

    // The card's formatVariables: a list of one-key maps flattens to one map; a map is used as is.
    private fun flatten(variables: Any?, into: MutableMap<String, Any?>) {
        when (variables) {
            is JSONArray -> for (i in 0 until variables.length()) flatten(variables.optJSONObject(i), into)
            is JSONObject -> variables.keys().forEach { into[it] = variables.opt(it) }
        }
    }

    private fun merge(into: JSONObject, from: JSONObject) = from.keys().forEach { into.put(it, from.opt(it)) }

    private fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj -> value.forEach { (k, v) -> obj.put(k.toString(), toJson(v)) } }
        is List<*> -> JSONArray().also { array -> value.forEach { array.put(toJson(it)) } }
        is String, is Boolean, is Int, is Long, is Double -> value
        is Number -> value.toDouble()
        else -> value.toString()
    }
}
