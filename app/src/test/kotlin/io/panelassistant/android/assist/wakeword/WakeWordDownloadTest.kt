package io.panelassistant.android.assist.wakeword

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WakeWordDownloadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val base = "https://raw.githubusercontent.com/someone/models/0123456789abcdef0123456789abcdef01234567/models"
    private val manifest = manifest("Nocturna", "nocturna.tflite").toByteArray()
    private val model = byteArrayOf(4, 5, 6, 7)

    /** What the network serves, by URL; a URL missing here is a download that does not complete. */
    private val served = mutableMapOf("$base/nocturna.json" to manifest, "$base/nocturna.tflite" to model)
    private val fetched = mutableListOf<String>()
    private var list = listOf(entry("nocturna"))
    /** Runs while the model is being fetched, to act from another thread mid-download. */
    private var duringModelFetch: () -> Unit = {}

    private fun catalog() = WakeWordCatalog(
        bundled = object : WakeWordCatalog.BundledModels {
            override fun ids() = listOf("okay_nabu")
            override fun manifest(id: String) = manifest("Okay Nabu", "okay_nabu.tflite")
            override fun model(file: String) = byteArrayOf(1)
            override fun downloads() = JSONObject().put("models", JSONArray(list)).toString()
        },
        importDir = folder.root,
        accepts = { _, _ -> true },
        fetch = { url, dest, maxBytes ->
            fetched += url
            if (url.endsWith(".tflite")) { dest.writeBytes(byteArrayOf(0)); duringModelFetch() }
            val bytes = served[url]
            if (bytes == null || bytes.size > maxBytes) false else { dest.writeBytes(bytes); true }
        },
    )

    @Test fun `a download matching its pinned digests is imported under its listed id and is then held`() {
        val catalog = catalog()
        assertFalse(catalog.holds(catalog.downloadable().single()))

        val result = catalog.download("nocturna")

        assertEquals("nocturna", (result as WakeWordCatalog.ImportResult.Imported).config.id)
        assertEquals(listOf("$base/nocturna.json", "$base/nocturna.tflite"), fetched)
        assertEquals(listOf("okay_nabu", "nocturna"), catalog.available().map { it.id })
        assertArrayEquals(model, File(folder.root, "nocturna/nocturna.tflite").readBytes())
        assertTrue(catalog.holds(catalog.downloadable().single()))
        assertEquals(listOf("nocturna"), folder.root.list()!!.toList())
    }

    @Test fun `a model that does not match its pinned digest is refused and the model already held stays`() {
        catalog().download("nocturna")
        served["$base/nocturna.tflite"] = byteArrayOf(9, 9, 9, 9)

        val result = catalog().download("nocturna")

        assertEquals(WakeWordCatalog.ImportResult.Refused("the download does not match its pinned SHA-256"), result)
        assertArrayEquals(model, File(folder.root, "nocturna/nocturna.tflite").readBytes())
        assertEquals(listOf("nocturna"), folder.root.list()!!.toList())
    }

    @Test fun `a manifest that does not match its pinned digest is refused before the model is fetched`() {
        served["$base/nocturna.json"] = manifest("Something else", "nocturna.tflite").toByteArray()

        val result = catalog().download("nocturna")

        assertEquals("the download does not match its pinned SHA-256", (result as WakeWordCatalog.ImportResult.Refused).reason)
        assertEquals(listOf("$base/nocturna.json"), fetched)
        assertEquals(listOf("okay_nabu"), catalog().available().map { it.id })
    }

    @Test fun `a download that does not complete is refused as incomplete and leaves nothing behind`() {
        served.remove("$base/nocturna.tflite")

        val result = catalog().download("nocturna")

        assertEquals(WakeWordCatalog.ImportResult.Refused("the download did not complete", incomplete = true), result)
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test fun `a model larger than its pinned size is never read past that size`() {
        served["$base/nocturna.tflite"] = model + byteArrayOf(0)

        val result = catalog().download("nocturna")

        assertEquals("the download did not complete", (result as WakeWordCatalog.ImportResult.Refused).reason)
    }

    @Test fun `the next download clears what an interrupted one left`() {
        File(folder.root, ".download-nocturna.tflite").writeBytes(byteArrayOf(1))
        File(folder.root, ".download-other.json").writeBytes(byteArrayOf(1))

        catalog().download("nocturna")

        assertEquals(listOf("nocturna"), folder.root.list()!!.toList())
    }

    @Test fun `a model the owner imported under a listed id is never replaced by a download`() {
        val own = byteArrayOf(8, 8)
        catalog().import("nocturna", manifest("My Nocturna", "nocturna.tflite").toByteArray(), own)

        val result = catalog().download("nocturna")

        assertEquals("a wake word you imported is already named \"nocturna\"", (result as WakeWordCatalog.ImportResult.Refused).reason)
        assertEquals(emptyList<String>(), fetched)
        assertArrayEquals(own, File(folder.root, "nocturna/nocturna.tflite").readBytes())
        assertEquals("My Nocturna", catalog().available().single { it.id == "nocturna" }.wakeWord)
    }

    @Test fun `a list entry whose author is not its manifest's is refused`() {
        list = listOf(entry("nocturna").put("author", "Someone famous"))

        val result = catalog().download("nocturna")

        assertEquals("the downloaded manifest does not match the list entry", (result as WakeWordCatalog.ImportResult.Refused).reason)
        assertEquals(listOf("okay_nabu"), catalog().available().map { it.id })
    }

    @Test fun `a newer pin replaces the copy an earlier download left`() {
        catalog().download("nocturna")
        val newer = byteArrayOf(1, 1, 2, 3, 5)
        served["$base/nocturna.tflite"] = newer
        list = listOf(entry("nocturna").put("model_sha256", WakeWordCatalog.sha256(newer)).put("model_bytes", newer.size))

        val result = catalog().download("nocturna")

        assertTrue(result is WakeWordCatalog.ImportResult.Imported)
        assertArrayEquals(newer, File(folder.root, "nocturna/nocturna.tflite").readBytes())
    }

    @Test fun `the catalogue used from another thread mid-download leaves the download's files alone`() {
        val catalog = catalog()
        duringModelFetch = {
            val other = Thread { catalog.available() }
            other.start()
            other.join()
            assertTrue(File(folder.root, ".download-nocturna.tflite").exists())
        }

        assertTrue(catalog.download("nocturna") is WakeWordCatalog.ImportResult.Imported)
    }

    @Test fun `files a killed download left are cleared at next use while no download runs`() {
        File(folder.root, ".download-nocturna.tflite").writeBytes(byteArrayOf(1))

        catalog().available()

        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test fun `only listed ids can be downloaded and none may take a bundled wake word's id`() {
        list = listOf(entry("nocturna"), entry("okay_nabu"), entry("broken").put("license", ""))

        assertEquals(listOf("nocturna"), catalog().downloadable().map { it.id })
        val refused = catalog().download("okay_nabu")
        assertTrue(refused is WakeWordCatalog.ImportResult.Refused)
        assertTrue(catalog().download("broken") is WakeWordCatalog.ImportResult.Refused)
        assertEquals(emptyList<String>(), fetched)
    }

    @Test fun `downloading the same model twice converges on one held copy`() {
        catalog().download("nocturna")
        val again = catalog().download("nocturna")

        assertTrue(again is WakeWordCatalog.ImportResult.Imported)
        assertEquals(listOf("okay_nabu", "nocturna"), catalog().available().map { it.id })
        assertEquals(listOf("nocturna"), folder.root.list()!!.toList())
    }

    private fun entry(id: String) = JSONObject()
        .put("id", id)
        .put("wake_word", "Nocturna")
        .put("author", "me")
        .put("license", "Apache-2.0")
        .put("license_url", "https://github.com/someone/models/blob/0123456789abcdef0123456789abcdef01234567/LICENSE")
        .put("source", "https://github.com/someone/models")
        .put("manifest_url", "$base/nocturna.json")
        .put("manifest_sha256", WakeWordCatalog.sha256(manifest))
        .put("model_sha256", WakeWordCatalog.sha256(model))
        .put("model_bytes", model.size)

    private fun manifest(phrase: String, model: String) = """
        {"type":"micro","wake_word":"$phrase","author":"me","model":"$model","trained_languages":["en"],"version":2,
         "micro":{"probability_cutoff":0.97,"feature_step_size":10,"sliding_window_size":5,"tensor_arena_size":26080}}
    """.trimIndent()
}
