package com.tapscene.ui

import com.tapscene.data.SafeImageBinding
import com.tapscene.ocr.OcrError
import com.tapscene.ocr.TextRegionSuggestion

/** Session-only OCR output. Only a deliberately selected form may persist its label and bounds. */
data class TextRegionSuggestions(
    val binding: SafeImageBinding? = null,
    val candidates: List<TextRegionSuggestion> = emptyList(),
    val working: Boolean = false,
    val message: String? = null,
)

internal fun textRegionError(code: OcrError): String = when (code) {
    OcrError.INPUT_TOO_LARGE -> "画面超过本机识别限额，请手动画热点。"
    OcrError.MODEL_UNAVAILABLE, OcrError.ENGINE_UNAVAILABLE -> "本机文字识别暂不可用，请手动画热点。"
    OcrError.ENGINE_BUSY -> "文字识别正在使用中，稍后重试或手动画热点。"
    OcrError.RESOURCE_LIMIT -> "本机可用资源不足，请稍后重试或手动画热点。"
    OcrError.TIMEOUT -> "识别超时，请重试或手动画热点。"
    OcrError.INVALID_INPUT, OcrError.RECOGNITION_FAILED -> "未能识别当前画面，请重试或手动画热点。"
}
