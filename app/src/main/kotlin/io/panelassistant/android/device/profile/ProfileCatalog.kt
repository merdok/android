package io.panelassistant.android.device.profile

import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import java.io.File

internal const val BUNDLED_PROFILE_DIR = "device-profiles"

internal data class StoredProfile(
    val ref: ProfileRef,
    val origin: ProfileOrigin,
    val rawYaml: String,
    val sourceBytes: Int,
    val document: ProfileDocument?,
    val issues: List<ProfileIssue> = emptyList(),
    val rollbackOnly: Boolean = false,
    val importedAtEpochMs: Long? = null,
) {
    val compatible: Boolean get() = document != null && issues.none { it.severity == ProfileIssueSeverity.ERROR }
}

/** Presentation is additive: oversized opaque evidence must never turn a compatibility issue into a throw. */
internal fun profileIssue(
    severity: ProfileIssueSeverity,
    path: String,
    message: String,
    presentationCode: String,
    presentationParams: Map<String, String> = emptyMap(),
) = ProfileIssue(
    severity,
    path,
    message,
    runCatching { ProfilePresentation(presentationCode, presentationParams) }.getOrNull(),
)

internal fun StoredProfile.isBundledFallback(): Boolean =
    origin == ProfileOrigin.BUNDLED && !rollbackOnly && compatible && document?.match?.fallback == true

/**
 * Every profile revision this process knows: the bundled assets, the retained rollback snapshots and the
 * imported revisions, each parsed, validated and admitted under the catalog quotas. It publishes its
 * result as one atomic swap of [entries] and [catalogIssues]; deciding which entry a panel runs is
 * [RuntimeProfileRegistry]'s concern. It holds no lock: the registry calls it from its synchronized methods.
 */
internal class ProfileCatalog(
    private val store: ProfileRevisionStore,
    private val bundledLoader: () -> Map<String, String>,
    private val coreVersion: String,
) {
    var entries: Map<ProfileRef, StoredProfile> = emptyMap()
        private set
    var catalogIssues: List<ProfileIssue> = emptyList()
        private set
    var importedDiskCount: Int = 0
        private set
    var importedDiskBytes: Long = 0
        private set
    var importedDiskCountById: Map<String, Int> = emptyMap()
        private set
    var fullyHydrated = false
        private set

    /**
     * Shared catalog-load scaffold: bounded cost accounting, the bundled-asset pass, the missing-generic
     * fallback guard, and the atomic `entries`/`catalogIssues` publish. [loadExtras] contributes the
     * store-specific revisions (a startup subset or the full imported/rollback catalog) into the same
     * working map before it is published; [markHydrated] flags a full reload so the registry's `ensureHydrated` no
     * longer re-reads the imported catalog.
     */
    private fun loadCatalog(
        markHydrated: Boolean,
        loadExtras: (loaded: MutableMap<ProfileRef, StoredProfile>, issues: MutableList<ProfileIssue>) -> Unit,
    ) {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_CATALOG_LOAD)
        val loaded = linkedMapOf<ProfileRef, StoredProfile>()
        val issues = mutableListOf<ProfileIssue>()
        try {
            bundledLoader().forEach { (name, raw) ->
                loadEntry(raw, ProfileOrigin.BUNDLED, "assets/$BUNDLED_PROFILE_DIR/$name", loaded, issues)
            }
            loadExtras(loaded, issues)
            entries = loaded
            if (markHydrated) fullyHydrated = true
            if (loaded.values.none { it.isBundledFallback() }) {
                issues += ProfileIssue(
                    ProfileIssueSeverity.ERROR,
                    "catalog",
                    "Bundled generic fallback is missing or invalid; the capability-empty emergency profile will be used.",
                    ProfilePresentation("catalog-fallback-invalid-emergency-used"),
                )
            }
            catalogIssues = issues
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            val bytes = loaded.values.fold(0L) { total, entry ->
                val size = entry.sourceBytes.toLong()
                if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size
            }
            cost.work(units = loaded.size.toLong(), bytes = bytes).close()
        }
    }

    /**
     * Foreground startup needs every bundled asset for automatic matching, but imported revisions are
     * inert unless an administrator pins them. Read only pinned revisions that can participate in the
     * current activation or its recovery; the rest of the imported catalog is hydrated on first admin use.
     */
    fun loadStartup(requiredRefs: () -> Set<ProfileRef>) = loadCatalog(markHydrated = false) { loaded, issues ->
        val required = requiredRefs()
        required.forEach { ref ->
            if (ref in loaded) return@forEach
            val imported = store.importedFile(ref)
            val rollback = store.rollbackFile(ref)
            when {
                // Full hydration admits retained bundled rollback snapshots before imports. Preserve
                // that precedence if an unexpected duplicate exists in both stores.
                rollback.isFile -> loadStartupFile(
                    rollback,
                    ref,
                    ProfileOrigin.BUNDLED,
                    rollbackOnly = true,
                    loaded = loaded,
                    issues = issues,
                )
                imported.isFile -> loadStartupFile(
                    imported,
                    ref,
                    ProfileOrigin.IMPORTED,
                    rollbackOnly = false,
                    loaded = loaded,
                    issues = issues,
                )
            }
        }
    }

    private fun loadStartupFile(
        file: File,
        expected: ProfileRef,
        origin: ProfileOrigin,
        rollbackOnly: Boolean,
        loaded: MutableMap<ProfileRef, StoredProfile>,
        issues: MutableList<ProfileIssue>,
    ) {
        val raw = runCatching { store.readCatalogFile(file) }.getOrElse {
            issues += ProfileIssue(
                ProfileIssueSeverity.ERROR,
                "catalog[${file.path}]",
                "Could not read required profile revision.",
                ProfilePresentation("required-profile-read-failed"),
            )
            return
        }
        loadEntry(
            raw,
            origin,
            file.path,
            loaded,
            issues,
            expectedRef = expected,
            rollbackOnly = rollbackOnly,
            importedAtEpochMs = if (origin == ProfileOrigin.IMPORTED) importedAt(file) else null,
        )
    }

    fun reload(protectedRefs: () -> Set<ProfileRef>) = loadCatalog(markHydrated = true) { loaded, issues ->
        store.rollbackFiles().forEach { file ->
            val expected = store.expectedRef(file) ?: return@forEach
            if (expected in loaded) return@forEach
            val raw = runCatching { store.readCatalogFile(file) }.getOrNull() ?: return@forEach
            loadEntry(raw, ProfileOrigin.BUNDLED, file.path, loaded, issues, expectedRef = expected, rollbackOnly = true)
        }
        val importedFiles = store.importedFiles()
        importedDiskCount = importedFiles.size
        importedDiskBytes = importedFiles.fold(0L) { total, file ->
            val length = file.length().coerceAtLeast(0)
            if (Long.MAX_VALUE - total < length) Long.MAX_VALUE else total + length
        }
        importedDiskCountById = importedFiles.groupingBy { it.parentFile?.name.orEmpty() }.eachCount()
        val protected = protectedRefs()
        val orderedFiles = importedFiles.sortedWith(
            compareBy<File> { file -> store.expectedRef(file) !in protected }
                .thenBy { it.parentFile?.name.orEmpty() }
                .thenBy { it.name },
        )
        var admittedCount = 0
        var admittedBytes = 0L
        val admittedById = mutableMapOf<String, Int>()
        orderedFiles.forEach { file ->
            val expected = store.expectedRef(file)
            if (expected == null) {
                issues += profileIssue(ProfileIssueSeverity.ERROR, "catalog[${file.path}]", "Imported revision path is not canonical <id>/<sha256>.yaml.", "imported-path-noncanonical")
                return@forEach
            }
            val length = file.length()
            val quotaIssue = when {
                length < 0 || length > ProfileMetadata.MAX_BYTES -> "Imported revision exceeds the per-file size limit." to "imported-file-size-limit"
                admittedCount >= ProfileMetadata.MAX_IMPORTED_REVISIONS -> "Imported revision skipped: catalog count quota exceeded." to "imported-catalog-count-quota"
                admittedById.getOrDefault(expected.id, 0) >= ProfileMetadata.MAX_IMPORTED_REVISIONS_PER_ID -> "Imported revision skipped: per-profile revision quota exceeded." to "imported-profile-count-quota"
                admittedBytes + length > ProfileMetadata.MAX_IMPORTED_BYTES -> "Imported revision skipped: aggregate byte quota exceeded." to "imported-catalog-byte-quota"
                else -> null
            }
            if (quotaIssue != null) {
                issues += profileIssue(ProfileIssueSeverity.ERROR, "catalog[${file.path}]", quotaIssue.first, quotaIssue.second)
                return@forEach
            }
            admittedCount++
            admittedBytes += length
            admittedById[expected.id] = admittedById.getOrDefault(expected.id, 0) + 1
            val raw = runCatching { store.readCatalogFile(file) }.getOrElse {
                issues += profileIssue(ProfileIssueSeverity.ERROR, "catalog[${file.path}]", "Could not read imported profile.", "imported-profile-read-failed")
                return@forEach
            }
            loadEntry(raw, ProfileOrigin.IMPORTED, file.path, loaded, issues, expectedRef = expected, importedAtEpochMs = importedAt(file))
        }
    }

    private fun loadEntry(
        raw: String,
        origin: ProfileOrigin,
        source: String,
        loaded: MutableMap<ProfileRef, StoredProfile>,
        issues: MutableList<ProfileIssue>,
        expectedRef: ProfileRef? = null,
        rollbackOnly: Boolean = false,
        importedAtEpochMs: Long? = null,
    ) {
        val parsed = measuredParse(raw)
        val document = parsed.document
        val validation = document?.let { measuredValidate(it, bundled = origin == ProfileOrigin.BUNDLED) }.orEmpty()
        val allIssues = parsed.issues + validation
        val computedRef = document?.let { ProfileRef(it.id, parsed.contentSha256) }
        if (origin == ProfileOrigin.BUNDLED && (document == null || allIssues.any { it.severity == ProfileIssueSeverity.ERROR })) {
            issues += allIssues.map { it.copy(path = "catalog[$source].${it.path}") }
            return
        }
        val ref = expectedRef ?: computedRef ?: return
        if (expectedRef != null && parsed.contentSha256 != expectedRef.revision) {
            issues += profileIssue(ProfileIssueSeverity.ERROR, "catalog[$source]", "Imported filename does not match the content SHA-256; revision ignored.", "imported-filename-hash-mismatch")
            return
        }
        val identityIssue = if (expectedRef != null && document != null && document.id != expectedRef.id) {
            profileIssue(
                ProfileIssueSeverity.ERROR,
                "id",
                "Document id '${document.id}' does not match storage id '${expectedRef.id}'.",
                "imported-document-id-mismatch",
                mapOf("document_id" to document.id, "storage_id" to expectedRef.id),
            )
        } else null
        val storedIssues = allIssues + listOfNotNull(identityIssue)
        if (storedIssues.any { it.severity == ProfileIssueSeverity.ERROR }) {
            issues += storedIssues.map { it.copy(path = "catalog[$source].${it.path}") }
        }
        val storedDocument = document?.takeIf { identityIssue == null }
        val previous = loaded.putIfAbsent(
            ref,
            StoredProfile(ref, origin, raw, parsed.sourceBytes, storedDocument, storedIssues, rollbackOnly, importedAtEpochMs),
        )
        if (previous != null) issues += profileIssue(ProfileIssueSeverity.WARNING, "catalog[$source]", "Duplicate immutable revision ignored.", "duplicate-revision-ignored")
    }

    fun measuredParse(raw: String): ProfileParseResult {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_YAML_PARSE)
        return try {
            ProfileYaml.parse(raw).also { parsed ->
                cost.work(units = 1L, bytes = parsed.sourceBytes.toLong())
            }
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            cost.close()
        }
    }

    fun measuredValidate(document: ProfileDocument, bundled: Boolean): List<ProfileIssue> {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.PROFILE_VALIDATE).work(units = 1L)
        return try {
            ProfileValidator.validate(document, coreVersion, bundled)
        } catch (error: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            cost.close()
        }
    }

    /**
     * The import event's time, read from the immutable revision file rather than stored beside it,
     * because the file is written once at import and never rewritten. A filesystem that reports no
     * modification time answers 0, which is indistinguishable from the epoch, so both become null
     * and the caller falls back to its stable tiebreak rather than inventing an order.
     */
    private fun importedAt(file: File): Long? = runCatching { file.lastModified() }.getOrNull()?.takeIf { it > 0L }
}
