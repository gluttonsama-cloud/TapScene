package com.tapscene.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.media.OpaqueMask

@PreviewTest
@Preview(name = "click_chain_precision_small", widthDp = 320, heightDp = 568, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainActionPrecisionSmallPreview() = ClickChainPrecisionPreview()

@PreviewTest
@Preview(name = "click_chain_precision_large_font", widthDp = 320, heightDp = 800, fontScale = 1.5f,
    locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainActionPrecisionLargeFontPreview() = ClickChainPrecisionPreview(invalid = true)

@PreviewTest
@Preview(name = "click_chain_precision_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun ClickChainActionPrecisionLandscapePreview() = ClickChainPrecisionPreview(invalid = true)

/** Production modal content with synthetic geometry; not a device dialog or keyboard capture. */
@Composable
private fun ClickChainPrecisionPreview(invalid: Boolean = false) {
    val rect = OpaqueMask(.12345679f, .2345679f, .8765432f, .9876543f)
    val form = ClickChainRectPercentages.from(rect).let { if (invalid) it.copy(left = "90") else it }
    TapSceneTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("范围精调 · 合成布局 · 非设备弹窗", style = MaterialTheme.typography.labelSmall)
            Surface(Modifier.widthIn(max = 560.dp), shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                ClickChainActionPrecisionContent(rect, form, true, {}, {}, {})
            }
        }
    } }
}
