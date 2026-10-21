package com.tapscene.clickplan

/** Original evidence is never interchangeable with reviewed output, even when bytes match. */
internal sealed interface ClickChainThumbnailSource {
    val digest: String
    data class OriginalUnreviewed(override val digest: String) : ClickChainThumbnailSource
    data class ReviewedOutput(override val digest: String) : ClickChainThumbnailSource
}

internal data class ClickChainThumbnailRequest(val frameKey: String, val source: ClickChainThumbnailSource)
internal const val MAX_VISIBLE_CLICK_CHAIN_THUMBNAILS = 8

/** Visible visits share one image; composed but offscreen rows never request original pixels. */
internal fun ClickChainReviewUiState.visibleThumbnailRequests(visibleItemKeys: Set<Any>): List<ClickChainThumbnailRequest> {
    val byKey = frames.associateBy { it.key }
    val visibleFrames = if (framesOnly) frames.filter { "frame:${it.key}" in visibleItemKeys }
    else stages.filter { "stage:${it.index}" in visibleItemKeys }.mapNotNull { byKey[it.selectedKey] }
    return visibleFrames.mapNotNull { frame ->
        frame.thumbnailSource?.let { ClickChainThumbnailRequest(frame.key, it) }
    }.distinct().take(MAX_VISIBLE_CLICK_CHAIN_THUMBNAILS)
}
