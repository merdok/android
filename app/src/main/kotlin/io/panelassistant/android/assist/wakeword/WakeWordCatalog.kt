package io.panelassistant.android.assist.wakeword

import android.content.Context
import io.panelassistant.android.util.AppInstaller
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Every wake word this panel can listen for: the bundled Apache-2.0 microWakeWord models, then the ones
 * a user imported or downloaded. An import is a microWakeWord manifest (`.json`) and its model
 * (`.tflite`), validated by the native engine before anything can use it; a rejected import leaves the
 * last good model of that id exactly as it was. A download is an import whose files come from the app's
 * pinned list of community models ([DownloadableWakeWord]) and match their recorded SHA-256.
 */
class WakeWordCatalog(
    private val bundled: BundledModels,
    /** Where imported models live, one directory per id. */
    private val importDir: File,
    /** Whether the native engine accepts a model; injected so the file handling is testable. */
    private val accepts: (ByteBuffer, MicroWakeWordModelConfig) -> Boolean = { buffer, config ->
        NativeMicroWakeWord.create(buffer, config)?.let { it.close(); true } ?: false
    },
    /** Fetch an HTTPS URL into a file, at most so many bytes; true when it arrived whole. */
    private val fetch: (url: String, dest: File, maxBytes: Long) -> Boolean = { url, dest, maxBytes ->
        AppInstaller.download(url, dest, maxBytes) == AppInstaller.DownloadResult.Succeeded
    },
) {
    constructor(context: Context) : this(AssetModels(context), File(context.filesDir, IMPORT_DIR))

    /** The bundled models, read from wherever the APK keeps them. */
    interface BundledModels {
        fun ids(): List<String>

        @Throws(IOException::class)
        fun manifest(id: String): String

        @Throws(IOException::class)
        fun model(file: String): ByteArray

        /** The list of models this panel may download, as JSON; none by default. */
        @Throws(IOException::class)
        fun downloads(): String = "{}"
    }

    private class AssetModels(private val context: Context) : BundledModels {
        override fun ids(): List<String> = context.assets.list(MicroWakeWordModelConfig.ASSET_DIR).orEmpty()
            .filter { it.endsWith(JSON) }.map { it.removeSuffix(JSON) }.sorted()

        override fun manifest(id: String): String =
            context.assets.open("${MicroWakeWordModelConfig.ASSET_DIR}/$id$JSON").bufferedReader().use { it.readText() }

        override fun model(file: String): ByteArray =
            context.assets.open("${MicroWakeWordModelConfig.ASSET_DIR}/$file").use { it.readBytes() }

        override fun downloads(): String =
            context.assets.open(DOWNLOADS_ASSET).bufferedReader().use { it.readText() }
    }

    sealed interface ImportResult {
        data class Imported(val config: MicroWakeWordModelConfig) : ImportResult
        /** [incomplete]: the files never arrived whole, so trying again later may succeed. */
        data class Refused(val reason: String, val incomplete: Boolean = false) : ImportResult
    }

    /** Whether [id] names a model shipped with the app rather than one a user imported. */
    fun isBundled(id: String): Boolean = id in bundled.ids()

    /** Every usable model's manifest, bundled first; one that no longer parses is left out. */
    @Synchronized
    fun available(): List<MicroWakeWordModelConfig> {
        recover()
        val bundledIds = bundled.ids()
        val found = bundledIds.mapNotNull { id -> runCatching { MicroWakeWordModelConfig.parse(id, bundled.manifest(id)) }.getOrNull() }
        val imported = importDir.listFiles { file -> file.isDirectory && ID.matches(file.name) }.orEmpty()
            .map { it.name }.filter { it !in bundledIds }.sorted()
            .mapNotNull { id -> runCatching { importedConfig(id) }.getOrNull() }
            .filter { unfit(it) == null }
        return (found + imported).take(MAX_WAKE_WORDS)
    }

    /** Load a model by id with the native engine; null when the id is unknown or the engine refuses it. */
    fun load(id: String): LoadedWakeWordModel? {
        if (!ID.matches(id)) return null
        synchronized(this) { recover() }
        val (config, bytes) = try {
            if (id in bundled.ids()) {
                val config = MicroWakeWordModelConfig.parse(id, bundled.manifest(id))
                config to bundled.model(config.modelFile)
            } else {
                val config = importedConfig(id)
                config to File(File(importDir, id), config.modelFile).readBytes()
            }
        } catch (_: Exception) {
            return null
        }
        val scorer = NativeMicroWakeWord.create(direct(bytes), config) ?: return null
        return LoadedWakeWordModel(config, scorer)
    }

    /**
     * Import a user-trained model. [name] names the wake word's id, reduced to lower case letters, digits
     * and underscores. The files are staged beside the catalogue, checked by the engine, then swapped in
     * whole; a failure at any step leaves the previous model of that id untouched.
     */
    @Synchronized
    fun import(name: String, manifest: ByteArray, model: ByteArray): ImportResult {
        val id = idFor(name) ?: return ImportResult.Refused("the wake word needs a name made of letters or digits")
        if (id in bundled.ids()) return ImportResult.Refused("\"$id\" is a bundled wake word")
        if (manifest.size > MAX_MANIFEST_BYTES) return ImportResult.Refused("the .json file is too large")
        if (model.isEmpty() || model.size > MAX_MODEL_BYTES) return ImportResult.Refused("the .tflite file is empty or too large")
        val config = try {
            MicroWakeWordModelConfig.parse(id, manifest.toString(Charsets.UTF_8))
        } catch (invalid: IllegalArgumentException) {
            return ImportResult.Refused(invalid.message ?: "the .json file is not a microWakeWord manifest")
        }
        unfit(config)?.let { return ImportResult.Refused(it) }
        if (config.modelFile == "$id$JSON") return ImportResult.Refused("the model file cannot share the manifest's name")
        if (!File(importDir, id).isDirectory && available().size >= MAX_WAKE_WORDS) {
            return ImportResult.Refused("a panel holds at most $MAX_WAKE_WORDS wake words")
        }
        if (!accepts(direct(model), config)) {
            return ImportResult.Refused("the model was not accepted by the wake-word engine")
        }
        importDir.mkdirs()
        val staging = File(importDir, ".staging-$id")
        staging.deleteRecursively()
        try {
            if (!staging.mkdirs()) throw IOException("cannot stage $id")
            // Flushed to the disk before the swap, so a power cut after it cannot leave an empty model.
            writeSynced(File(staging, "$id$JSON"), manifest)
            writeSynced(File(staging, config.modelFile), model)
            val target = File(importDir, id)
            val previous = File(importDir, ".previous-$id")
            previous.deleteRecursively()
            if (target.exists() && !target.renameTo(previous)) throw IOException("cannot set aside $id")
            if (!staging.renameTo(target)) {
                previous.renameTo(target)
                throw IOException("cannot install $id")
            }
            previous.deleteRecursively()
        } catch (failed: IOException) {
            staging.deleteRecursively()
            return ImportResult.Refused(failed.message ?: "the model could not be saved")
        }
        return ImportResult.Imported(config)
    }

    /** The models this panel may download, except any that would take a bundled wake word's id. */
    fun downloadable(): List<DownloadableWakeWord> {
        val entries = runCatching { DownloadableWakeWord.parseAll(bundled.downloads()) }.getOrDefault(emptyList())
        val bundledIds = bundled.ids()
        return entries.filter { it.id !in bundledIds }
    }

    /** Whether the model downloaded as [entry] is the one this panel holds under its id now. */
    fun holds(entry: DownloadableWakeWord): Boolean = runCatching {
        val config = synchronized(this) { recover(); importedConfig(entry.id) }
        sha256(File(File(importDir, entry.id), config.modelFile).readBytes()) == entry.modelSha256
    }.getOrDefault(false)

    private val downloadLock = ReentrantLock()

    /**
     * Download the model [id] names in [downloadable] and import it. The manifest and model are fetched
     * into this catalogue's own directory and must match the SHA-256 the list pins; a mismatch is refused
     * outright, never retried, and leaves any model already held under that id untouched. A model the
     * owner imported under the same id is never replaced. The network is never awaited while the catalogue
     * is locked; downloads run one at a time, and each first clears what an interrupted one left.
     */
    fun download(id: String): ImportResult {
        val entry = downloadable().firstOrNull { it.id == id }
            ?: return ImportResult.Refused("\"$id\" is not a wake word this panel can download")
        if (File(importDir, id).isDirectory && !holds(entry)) {
            return ImportResult.Refused("a wake word you imported is already named \"$id\"")
        }
        downloadLock.withLock {
            importDir.mkdirs()
            clearDownloads()
            val manifestFile = File(importDir, "$DOWNLOADING$id$JSON")
            val modelFile = File(importDir, "$DOWNLOADING$id.tflite")
            try {
                val manifest = fetchPinned(entry.manifestUrl, manifestFile, MAX_MANIFEST_BYTES.toLong(), entry.manifestSha256)
                    .getOrElse { return ImportResult.Refused(it.message!!, incomplete = it is Incomplete) }
                val config = try {
                    MicroWakeWordModelConfig.parse(id, manifest.toString(Charsets.UTF_8))
                } catch (invalid: IllegalArgumentException) {
                    return ImportResult.Refused(invalid.message ?: "the downloaded manifest is not a microWakeWord manifest")
                }
                val modelUrl = runCatching { URL(URL(entry.manifestUrl), config.modelFile).toString() }.getOrNull()
                    ?: return ImportResult.Refused("the manifest names a model that cannot be downloaded")
                val model = fetchPinned(modelUrl, modelFile, entry.modelBytes, entry.modelSha256)
                    .getOrElse { return ImportResult.Refused(it.message!!, incomplete = it is Incomplete) }
                return import(id, manifest, model)
            } finally {
                manifestFile.delete()
                modelFile.delete()
            }
        }
    }

    /**
     * The bytes at [url] when they arrived whole and match [sha256]. Bytes that arrived but differ are an
     * integrity failure, told apart from a download that did not complete.
     */
    private fun fetchPinned(url: String, dest: File, maxBytes: Long, sha256: String): Result<ByteArray> {
        dest.delete()
        if (!fetch(url, dest, maxBytes)) return Result.failure(Incomplete())
        val bytes = dest.readBytes()
        if (sha256(bytes) != sha256) return Result.failure(IOException("the download does not match its pinned SHA-256"))
        return Result.success(bytes)
    }

    private class Incomplete : IOException("the download did not complete")

    private fun clearDownloads() {
        importDir.listFiles { file -> file.name.startsWith(DOWNLOADING) }.orEmpty().forEach { it.delete() }
    }

    /** One imported wake word's files, as a backup carries them and [import] takes them back. */
    class ImportedFiles(val id: String, val manifest: ByteArray, val model: ByteArray)

    /**
     * The files of every imported wake word [available] lists, for a backup. Throws [IOException] when one
     * cannot be read: a backup that silently left it out could never restore it.
     */
    @Synchronized
    @Throws(IOException::class)
    fun exportImported(): List<ImportedFiles> {
        recover()
        val bundledIds = bundled.ids()
        // Every imported directory, not only the ones [available] can list: a word whose manifest no longer
        // reads must refuse the backup, never drop out of it while the settings still select it.
        return importDir.listFiles { file -> file.isDirectory && ID.matches(file.name) }.orEmpty()
            .map { it.name }.filter { it !in bundledIds }.sorted()
            .map { id ->
                val dir = File(importDir, id)
                try {
                    val manifest = File(dir, "$id$JSON").readBytes()
                    val config = MicroWakeWordModelConfig.parse(id, manifest.toString(Charsets.UTF_8))
                    ImportedFiles(id, manifest, File(dir, config.modelFile).readBytes())
                } catch (unreadable: Exception) {
                    throw IOException("imported wake word $id could not be read", unreadable)
                }
            }
    }

    /**
     * Finish an import the process did not live to complete. A crash between setting the working model
     * aside and moving its replacement in leaves only `.previous-<id>`: that model is put back. A crash
     * after the swap leaves both, and the new one stands. Staging directories are never in use outside
     * [import], which holds this object's lock, so any found here are abandoned. A download's files are
     * abandoned too unless a download is running, which is the only time they are in use.
     */
    private fun recover() {
        if (downloadLock.tryLock()) {
            try {
                clearDownloads()
            } finally {
                downloadLock.unlock()
            }
        }
        val leftovers = importDir.listFiles { file -> file.name.startsWith(".") }.orEmpty()
        for (dir in leftovers) {
            val name = dir.name
            when {
                name.startsWith(".previous-") -> {
                    val id = name.removePrefix(".previous-")
                    val target = File(importDir, id)
                    if (ID.matches(id) && !target.exists()) dir.renameTo(target) else dir.deleteRecursively()
                }
                name.startsWith(".staging-") -> dir.deleteRecursively()
            }
        }
    }

    private fun importedConfig(id: String): MicroWakeWordModelConfig =
        MicroWakeWordModelConfig.parse(id, File(File(importDir, id), "$id$JSON").readText())

    companion object {
        const val IMPORT_DIR = "wakeword"
        const val DOWNLOADS_ASSET = "wakeword-downloads.json"
        private const val DOWNLOADING = ".download-"

        /** Home Assistant's own bounds on a satellite's wake words; one outside them refuses them all. */
        const val MAX_WAKE_WORDS = 32
        private const val MAX_PHRASE = 64
        private const val MAX_LANGUAGES = 16
        private const val MAX_LANGUAGE = 16
        private val CONTROL = Regex("[\\x00-\\x1f\\x7f]")

        /** Why Home Assistant would refuse to list [config], or null when it fits. */
        internal fun unfit(config: MicroWakeWordModelConfig): String? = when {
            config.wakeWord.length > MAX_PHRASE || CONTROL.containsMatchIn(config.wakeWord) ->
                "the wake word phrase must be at most $MAX_PHRASE plain characters"
            config.trainedLanguages.size > MAX_LANGUAGES ||
                config.trainedLanguages.any { it.length > MAX_LANGUAGE || CONTROL.containsMatchIn(it) } ->
                "the manifest lists too many trained languages, or one that is too long"
            else -> null
        }
        private const val JSON = ".json"
        private const val MAX_MANIFEST_BYTES = 16 * 1024
        internal const val MAX_MODEL_BYTES = 2 * 1024 * 1024
        /** A wake-word id: a bundled model's or an imported one's directory name. */
        internal val ID = Regex("^[a-z][a-z0-9_]{0,63}$")

        /** The id an imported wake word gets from its name, or null when nothing usable remains. */
        fun idFor(name: String): String? {
            val reduced = name.substringBeforeLast('.').lowercase()
                .replace(Regex("[^a-z0-9]+"), "_").trim('_').take(64)
            return reduced.takeIf(ID::matches)
        }

        private fun writeSynced(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
        }

        internal fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun direct(bytes: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
    }
}
