package com.tapscene.data

import com.tapscene.packageformat.AiDraftImportPolicy
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerScene

/** A review lease on one immutable private input and one explicitly selected comparison. */
data class AiDraftImportPreview(
    val sessionId: String,
    val previewDigest: String,
    val newProjectId: String,
    val scene: ViewerScene,
    val plan: RenderPlan,
    val baselineReleaseId: String?,
    val baselineTitle: String?,
    val issues: List<AiDraftImportPolicy.Issue>,
    val differences: List<AiDraftImportPolicy.Difference>,
    val summary: String,
    val trustNotice: String,
    val expandedByteLength: Long,
)

data class AiDraftImportResult(val sessionId: String, val status: String, val projectId: String?, val projectExists: Boolean)
internal data class AiImportSession(val id: String, val status: String, val projectId: String,
    val inputSha: String?, val previewDigest: String?, val preparedJson: String?)
internal data class AiImportAssetCopy(val ownerId: String, val assetId: String, val sourceAssetId: String, val region: Boolean)
