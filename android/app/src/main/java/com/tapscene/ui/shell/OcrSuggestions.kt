package com.tapscene.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tapscene.media.OpaqueMask
import com.tapscene.media.DecodedFrame
import com.tapscene.media.ImportedSource
import com.tapscene.ocr.OcrResult
import com.tapscene.ocr.OcrTitlePolicy
import kotlin.math.max

/** Carries the private result's identity into review without trusting a previously drawn screen. */
data class CandidateTextSuggestions(
    val projectId: String,
    val sourceId: String,
    val sourceSha256: String,
    val candidateId: String,
    val actualTimeUs: Long,
    val timePrecisionUs: Long,
    val result: OcrResult,
) {
    fun matches(project: String?, source: ImportedSource?, reviewId: String?, frame: DecodedFrame?): Boolean =
        project == projectId && source != null && source.sourceId == sourceId && source.metadata.sha256.equals(sourceSha256, ignoreCase = true) &&
            reviewId == candidateId && frame != null && frame.presentationTimeUs == actualTimeUs &&
            frame.timePrecisionUs == timePrecisionUs && !frame.bitmap.isRecycled &&
            frame.bitmap.width == result.width && frame.bitmap.height == result.height
}

/** A source-local text line, never a hotspot or an automatic privacy finding. */
data class OcrTextSuggestion(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val uncertain: Boolean,
    val title: String? = null,
)

/** Invalid boxes are not offered as actions, even if a future engine returns malformed data. */
fun ocrTextSuggestions(result: OcrResult): List<OcrTextSuggestion> {
    if (result.width <= 0 || result.height <= 0) return emptyList()
    val titles = OcrTitlePolicy.suggestions(result).toSet()
    return result.words.filter { word ->
        word.text.isNotBlank() && word.confidence.isFinite() && word.confidence in 0f..100f &&
            word.left >= 0 && word.top >= 0 && word.right <= result.width && word.bottom <= result.height &&
            word.right > word.left && word.bottom > word.top
    }.groupBy { it.blockIndex to it.lineIndex }.values.map { words ->
        val lineText = OcrTitlePolicy.text(words)
        OcrTextSuggestion(
            text = lineText,
            left = words.minOf { it.left }, top = words.minOf { it.top },
            right = words.maxOf { it.right }, bottom = words.maxOf { it.bottom },
            uncertain = words.any { it.confidence < 55f },
            title = lineText.takeIf { it in titles && OcrTitlePolicy.isEligible(words) },
        )
    }
}

/** Normalize against decoded source pixels, not the fitted preview or encoded video dimensions. */
fun ocrSuggestionMask(suggestion: OcrTextSuggestion, width: Int, height: Int): OpaqueMask? {
    if (width <= 0 || height <= 0 || suggestion.left < 0 || suggestion.top < 0 ||
        suggestion.right > width || suggestion.bottom > height ||
        suggestion.right <= suggestion.left || suggestion.bottom <= suggestion.top) return null
    val padding = max(2f, (suggestion.bottom - suggestion.top) * 0.08f)
    return OpaqueMask(
        ((suggestion.left - padding) / width).coerceIn(0f, 1f),
        ((suggestion.top - padding) / height).coerceIn(0f, 1f),
        ((suggestion.right + padding) / width).coerceIn(0f, 1f),
        ((suggestion.bottom + padding) / height).coerceIn(0f, 1f),
    )
}

/** Caller must bind this result to the current source, candidate, actual PTS and decoded size. */
@Composable
fun OcrSuggestions(
    result: OcrResult,
    title: String,
    enabled: Boolean,
    canAddMask: Boolean,
    onUseTitle: (String) -> Unit,
    onMask: (OpaqueMask) -> Unit,
    initiallyExpanded: Boolean = false,
) {
    val suggestions = remember(result) { ocrTextSuggestions(result) }
    var expanded by remember(result) { mutableStateOf(initiallyExpanded) }
    var shown by remember(result) { mutableIntStateOf(6) }
    Column(Modifier.fillMaxWidth().background(ShellColors.Quiet, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("文字建议 · ${suggestions.size} 行", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
        }
        if (expanded) {
            Text("离线识别可能有误；遮挡后仍需检查实际输出。",
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            if (result.truncated) Text("文字较多，仅显示部分识别结果。",
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Accent)
            if (suggestions.isEmpty()) Text("这张画面未识别到可用文字。", Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            suggestions.take(shown).forEach { suggestion ->
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(suggestion.text, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (suggestion.uncertain) Text("可能有误", style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (suggestion.title != null) TextButton(onClick = { if (title.isBlank()) onUseTitle(suggestion.title) },
                            enabled = enabled && title.isBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("用作标题") }
                        else Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                            Text("无标题建议", style = MaterialTheme.typography.labelSmall, color = ShellColors.Muted)
                        }
                        TextButton(onClick = {
                            ocrSuggestionMask(suggestion, result.width, result.height)?.let(onMask)
                        }, enabled = enabled && canAddMask, modifier = Modifier.heightIn(min = 48.dp)) { Text("遮住此处") }
                    }
                    ShellDivider()
                }
            }
            if (suggestions.size > shown) TextButton(onClick = { shown += 6 }) { Text("更多文字（${suggestions.size - shown} 行）") }
            if (title.isNotBlank() && suggestions.isNotEmpty()) Text("已填写标题；文字建议不会替换它。",
                Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
            Spacer(Modifier.height(8.dp))
        }
    }
}
