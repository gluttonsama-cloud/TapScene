package com.tapscene.data

/** Durable local operation identity. A committed receipt survives deletion of either project. */
data class ProjectCopyReceipt(
    val operationId: String,
    val sourceProjectId: String,
    val sourceRevision: Long,
    val projectId: String,
    val status: String,
    val missingRawSourceCount: Int,
)
