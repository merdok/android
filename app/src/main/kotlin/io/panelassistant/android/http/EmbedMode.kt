package io.panelassistant.android.http

import java.util.Locale

/**
 * The presentation Panel Assistant asks for when it shows a page in its Home Assistant sidebar: `args.embed` of
 * `page.<name>` (protocol section 20.3), in the grammar the retired `X-Panel-Assistant-Embed` header carried. It
 * changes how pages render and nothing else: no guard, approval or persisted setting reads it. Its value is never
 * logged.
 *
 * Grammar, version 1 (quoted literals match case-insensitively, as in RFC 5234):
 *
 * ```
 * embed = "v=1" *( ";" param )
 * param = "lang=" tag / "theme=" ( "light" / "dark" ) / "hide=" tab *( "," tab )
 * tag   = 1*8ALPHA *( "-" 1*8ALPHANUM )     ; at most 35 characters
 * ```
 *
 * A `hide` item must be token-shaped; a well-formed name that is not a tab is ignored. Anything else that
 * fails the grammar, or a value over 256 bytes, makes the whole value invalid.
 * A repeated `lang` or `theme` takes its last value; repeated `hide` lists are combined.
 */
internal data class EmbedMode(
    val lang: String?,
    val theme: String?,
    val hiddenTabs: Set<String>,
) {
    companion object {
        const val MAX_BYTES = 256
        val TABS = setOf("setup", "dashboard", "configure", "profiles", "entities", "install", "logs", "api")

        private val LANG_TAG = Regex("[A-Za-z]{1,8}(?:-[A-Za-z0-9]{1,8})*")
        private val TAB_TOKEN = Regex("[A-Za-z0-9_-]{1,32}")

        fun parse(raw: String?): EmbedMode? {
            if (raw == null || raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
            val parts = raw.trim(' ', '\t').split(';')
            if (!parts[0].equals("v=1", ignoreCase = true)) return null
            var lang: String? = null
            var theme: String? = null
            val hidden = linkedSetOf<String>()
            for (part in parts.drop(1)) {
                val eq = part.indexOf('=')
                if (eq <= 0) return null
                val value = part.substring(eq + 1)
                when (part.substring(0, eq).lowercase(Locale.ROOT)) {
                    "lang" -> {
                        if (value.length > 35 || !LANG_TAG.matches(value)) return null
                        lang = value
                    }
                    "theme" -> theme = value.lowercase(Locale.ROOT).takeIf { it == "light" || it == "dark" } ?: return null
                    "hide" -> value.split(',').forEach { item ->
                        if (!TAB_TOKEN.matches(item)) return null
                        item.lowercase(Locale.ROOT).takeIf { it in TABS }?.let(hidden::add)
                    }
                    else -> return null
                }
            }
            return EmbedMode(lang, theme, hidden)
        }
    }
}
