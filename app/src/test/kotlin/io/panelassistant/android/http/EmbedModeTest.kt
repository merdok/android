package io.panelassistant.android.http

import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.i18n.sourceHash
import io.ktor.http.HttpHeaders
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class EmbedModeTest {
    // Source-text reason: loads the shipped embed test vectors as input data.
    private val vectors = JSONObject(File("src/main/assets/panel_assistant_embed_v1.json").readText())
    private val embed = vectors.getJSONObject("embed")

    @Test fun `shared vectors name the tab set the parser admits`() {
        assertEquals(EmbedMode.MAX_BYTES, embed.getInt("max_bytes"))
        assertEquals(EmbedMode.TABS, strings(embed.getJSONArray("tabs")).toSet())
    }

    @Test fun `every valid grammar vector parses to its expected presentation`() {
        val valid = embed.getJSONArray("valid")
        for (i in 0 until valid.length()) {
            val vector = valid.getJSONObject(i)
            val header = vector.getString("header")
            val parsed = EmbedMode.parse(header)
            assertNotNull(header, parsed)
            assertEquals(header, vector.optString("lang").takeUnless { vector.isNull("lang") }, parsed!!.lang)
            assertEquals(header, vector.optString("theme").takeUnless { vector.isNull("theme") }, parsed.theme)
            assertEquals(header, strings(vector.getJSONArray("hide")).toSet(), parsed.hiddenTabs)
        }
    }

    @Test fun `every invalid grammar vector is ignored whole`() {
        val invalid = embed.getJSONArray("invalid")
        for (i in 0 until invalid.length()) {
            val header = invalid.getJSONObject(i).getString("header")
            assertNull(header, EmbedMode.parse(header))
        }
    }

    @Test fun `embedded language stands in for Automatic and never overrides a chosen language`() {
        val loader = catalogueLoader()
        // A page as the sidebar relay asks for it: the query's signals and the presentation in `args`.
        fun locale(path: String, embed: String?, persisted: String = "de"): String {
            val args = JSONObject()
            java.net.URI("http://panel$path").rawQuery?.split('&')?.forEach { pair -> args.put(pair.substringBefore('='), pair.substringAfter('=')) }
            embed?.let { args.put("embed", it) }
            val origin = io.panelassistant.android.CommandOrigin.user("0123456789abcdef0123456789abcdef")
                .withHeaders(mapOf(HttpHeaders.AcceptLanguage to "es"))
            val call = ManageOpCall(io.panelassistant.android.ManageRequest("page.info", args, origin, null), io.ktor.http.HttpMethod.Get, "/")
            return resolvedRequestStrings(call, persistedLanguage = persisted, deviceLanguageTag = "fr", allowPseudo = false, catalogueLoader = loader)
                .requestedLocale
        }

        // Outside embedded mode the persisted panel choice wins, as on the LAN.
        assertEquals("de", locale("/?ha_lang=it", null))
        // A chosen Interface language wins in the sidebar too: choosing one must change the page.
        assertEquals("de", locale("/", "v=1;lang=it;theme=blue"))
        assertEquals("de", locale("/", "v=1;lang=it"))
        // On Automatic the Home Assistant user's language outranks every automatic signal.
        assertEquals("it", locale("/?ha_lang=nl", "v=1;lang=it", persisted = "auto"))
        assertEquals("zh-Hans", locale("/", "v=1;lang=zh-Hans", persisted = "auto"))
        assertEquals("cs", locale("/", "v=1;lang=cs", persisted = "auto"))
        assertEquals("pt-BR", locale("/", "v=1;lang=pt-BR", persisted = "auto"))
        // A language without a catalogue is English, not the panel's own choice.
        assertEquals("en", locale("/", "v=1;lang=pt-PT", persisted = "auto"))
        // An explicit choice made in the page still wins.
        assertEquals("fr", locale("/?lang=fr", "v=1;lang=it"))
        assertEquals("fr", locale("/?lang=fr", "v=1;lang=it", persisted = "auto"))
        // A switch without a language leaves the LAN precedence alone.
        assertEquals("de", locale("/", "v=1;hide=api"))
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)

    private fun catalogueLoader(): CatalogueLoader {
        val revision = "a".repeat(40)
        val english = "Label"
        return CatalogueLoader { path ->
            val locale = path.removePrefix("i18n/").removeSuffix(".json")
            if (locale == "en") {
                """{"schema":1,"locale":"en","sourceRevision":"$revision","strings":{"settings.example.label":{
                  "text":"$english","sourceHash":"${sourceHash(english)}","surface":"settings","context":"Label",
                  "risk":"ordinary","siblings":[],"placeholders":[],"frozen":[],"softMaxChars":40,"hardMaxChars":80}}}"""
            } else {
                """{"schema":1,"locale":"$locale","sourceRevision":"$revision","strings":{"settings.example.label":{
                  "text":"$locale","sourceHash":"${sourceHash(english)}","state":"machine-cross-checked"}}}"""
            }
        }
    }
}
