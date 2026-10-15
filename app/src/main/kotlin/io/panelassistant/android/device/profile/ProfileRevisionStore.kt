package io.panelassistant.android.device.profile

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

internal interface ProfileRevisionPersistence {
    fun createDirectory(directory: File): Boolean
    fun writeAndSync(file: File, bytes: ByteArray)
    fun atomicRename(source: File, target: File): Boolean
    fun delete(file: File): Boolean
    fun syncDirectory(directory: File)
}

internal object FileProfileRevisionPersistence : ProfileRevisionPersistence {
    override fun createDirectory(directory: File): Boolean = directory.isDirectory || directory.mkdir()

    override fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    override fun atomicRename(source: File, target: File): Boolean = source.renameTo(target)

    override fun delete(file: File): Boolean = file.delete()

    override fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}

/**
 * The on-disk home of immutable profile revisions: imported revisions under `device-profiles/imported/<id>/<sha256>.yaml`
 * and retained bundled rollback snapshots under `device-profiles/rollback/`. It owns the layout and the
 * durability of every write, delete and bounded read; what a revision means is [ProfileCatalog]'s concern.
 * It holds no lock: [RuntimeProfileRegistry] calls it from its synchronized methods.
 */
internal class ProfileRevisionStore(
    filesDir: File,
    private val revisionPersistence: ProfileRevisionPersistence,
    private val catalogFileReader: ((File) -> String)?,
) {
    private val importedDir = File(filesDir, "device-profiles/imported")
    private val rollbackDir = File(filesDir, "device-profiles/rollback")

    fun hasImportedId(id: String): Boolean = File(importedDir, id).exists()

    fun importedFiles(): List<File> = revisionFiles(importedDir)

    fun rollbackFiles(): List<File> = revisionFiles(rollbackDir)

    private fun revisionFiles(root: File): List<File> = root.listFiles().orEmpty().filter { it.isDirectory }.flatMap { directory ->
        directory.listFiles().orEmpty().filter { it.isFile && it.extension == "yaml" }
    }

    fun writeImported(ref: ProfileRef, raw: String): Boolean = writeImmutable(importedFile(ref), raw)

    fun writeRollback(ref: ProfileRef, raw: String): Boolean = writeImmutable(rollbackFile(ref), raw)

    private fun readBounded(file: File): String {
        if (file.length() > ProfileMetadata.MAX_BYTES) error("profile is too large")
        val bytes = ByteArrayOutputStream(minOf(file.length().coerceAtLeast(0).toInt(), ProfileMetadata.MAX_BYTES))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > ProfileMetadata.MAX_BYTES) error("profile is too large")
                bytes.write(buffer, 0, read)
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    fun readCatalogFile(file: File): String = catalogFileReader?.invoke(file) ?: readBounded(file)

    fun importedFile(ref: ProfileRef) = File(File(importedDir, ref.id), "${ref.revision}.yaml")

    fun rollbackFile(ref: ProfileRef) = File(File(rollbackDir, ref.id), "${ref.revision}.yaml")

    private fun writeImmutable(target: File, raw: String): Boolean = runCatching {
        val parent = target.parentFile ?: error("revision has no parent directory")
        if (!createDirectoriesDurably(parent)) error("could not create revision directory")
        if (target.exists()) {
            if (readBounded(target) != raw) return@runCatching false
            revisionPersistence.syncDirectory(parent)
            return@runCatching true
        }
        val staging = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            val bytes = raw.toByteArray(Charsets.UTF_8)
            require(bytes.size <= ProfileMetadata.MAX_BYTES)
            revisionPersistence.writeAndSync(staging, bytes)
            if (!revisionPersistence.atomicRename(staging, target)) error("atomic rename failed")
            revisionPersistence.syncDirectory(parent)
        } finally {
            if (staging.exists()) revisionPersistence.delete(staging)
        }
        true
    }.getOrDefault(false)

    private fun createDirectoriesDurably(directory: File): Boolean {
        if (directory.isDirectory) return true
        val missing = generateSequence(directory) { it.parentFile }
            .takeWhile { !it.exists() }
            .toList()
        missing.asReversed().forEach { candidate ->
            val parent = candidate.parentFile ?: return false
            if (!revisionPersistence.createDirectory(candidate)) return false
            revisionPersistence.syncDirectory(parent)
        }
        return directory.isDirectory
    }

    fun deleteImmutable(file: File): Boolean = runCatching {
        val parent = file.parentFile ?: error("revision has no parent directory")
        if (!file.isFile || !revisionPersistence.delete(file)) error("revision unlink failed")
        revisionPersistence.syncDirectory(parent)
        if (parent.list().isNullOrEmpty() && revisionPersistence.delete(parent)) {
            parent.parentFile?.let(revisionPersistence::syncDirectory)
        }
        true
    }.getOrDefault(false)

    fun expectedRef(file: File): ProfileRef? {
        val id = file.parentFile?.name ?: return null
        val revision = file.name.removeSuffix(".yaml")
        if (!Regex("^[a-z0-9](?:[a-z0-9.-]{0,126}[a-z0-9])?$").matches(id) || ".." in id) return null
        if (!Regex("^[0-9a-f]{64}$").matches(revision)) return null
        return ProfileRef(id, revision)
    }
}
