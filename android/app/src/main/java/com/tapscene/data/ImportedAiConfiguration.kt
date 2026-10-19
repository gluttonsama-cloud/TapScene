package com.tapscene.data

import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerScene

/** One locked read of the current release and its separate, optional local configuration. */
data class AiConfigurationOpen(
    val summary: ReleaseSummary,
    val scene: ViewerScene,
    val plan: RenderPlan?,
    val fromDraft: Boolean,
    val importedSnapshot: ImportedAiConfigurationSnapshot?,
)

/** Fingerprint of actual bytes, including invalid/empty JSON. Both null means proven absence. */
data class ImportedAiConfigurationSnapshot(val byteLength: Long?, val sha256: String?) {
    init {
        require((byteLength == null) == (sha256 == null))
        require(byteLength == null || byteLength in 0..RenderPlan.MAX_BYTES.toLong())
        require(sha256 == null || sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Current disk equality only: MATCHES_TARGET does not establish who committed those bytes. */
enum class ImportedAiConfigurationState { MATCHES_TARGET, PREVIOUS_UNCHANGED, CHANGED_OR_REPLACED }
