package com.tapscene.clickplan

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The caller owns only delivered images; cancellation at the IO handoff recycles partial results. */
internal suspend fun readClickChainThumbnailBatch(
    gate: Mutex,
    read: suspend (MutableMap<ClickChainThumbnailRequest, Bitmap>) -> Unit,
    stillCurrent: () -> Boolean,
): Map<ClickChainThumbnailRequest, Bitmap> {
    // Keep this holder outside withContext: prompt cancellation can discard its return value.
    val images = linkedMapOf<ClickChainThumbnailRequest, Bitmap>()
    var delivered = false
    try {
        gate.withLock {
            currentCoroutineContext().ensureActive()
            if (!stillCurrent()) return emptyMap()
            withContext(Dispatchers.IO) { read(images) }
        }
        currentCoroutineContext().ensureActive()
        if (!stillCurrent()) return emptyMap()
        val result = images.toMap()
        delivered = true
        return result
    } finally {
        if (!delivered) images.values.toSet().forEach { if (!it.isRecycled) it.recycle() }
    }
}

/** Same bytes never let an unreviewed request borrow reviewed authority, or vice versa. */
internal fun ClickChainCapture.thumbnailSource(draft: ClickChainReviewDraft, key: String): ClickChainThumbnailSource? {
    val candidate = frames[key] ?: return null
    val frame = draft.frames[key]
    if (frame != null && frame.originalPngSha256 != candidate.pngSha256) return null
    return if (frame?.reviewed == true) frame.output?.let { ClickChainThumbnailSource.ReviewedOutput(it.sha256) }
    else ClickChainThumbnailSource.OriginalUnreviewed(candidate.pngSha256)
}
