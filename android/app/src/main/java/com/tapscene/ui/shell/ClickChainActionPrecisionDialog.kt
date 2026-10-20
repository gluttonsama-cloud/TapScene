package com.tapscene.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tapscene.media.OpaqueMask

@Composable
internal fun ClickChainActionPrecisionDialog(original: OpaqueMask, form: ClickChainRectPercentages, enabled: Boolean,
    onChange: (ClickChainRectPercentages) -> Unit, onDismiss: () -> Unit, onApply: (OpaqueMask) -> Unit) {
    // Dismissal owns only staged text, including system Back; it never returns from the editor.
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.widthIn(max = 560.dp), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ClickChainActionPrecisionContent(original, form, enabled, onChange, onDismiss, onApply)
        }
    }
}

/** The same bounded content is rendered by the real dialog and layout-only previews. */
@Composable
internal fun ClickChainActionPrecisionContent(original: OpaqueMask, form: ClickChainRectPercentages, enabled: Boolean,
    onChange: (ClickChainRectPercentages) -> Unit, onDismiss: () -> Unit, onApply: (OpaqueMask) -> Unit) {
    val rectangle = form.rectangle(original)
    val largeText = LocalDensity.current.fontScale > 1.2f
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("范围精调", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("按当前画面的 0–100% 输入，可用小数。应用后仍需确认点击区域与后继。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (largeText) {
                EditorPercentageField("左边 %", form.left, { onChange(form.copy(left = it)) }, enabled, Modifier.fillMaxWidth())
                EditorPercentageField("顶部 %", form.top, { onChange(form.copy(top = it)) }, enabled, Modifier.fillMaxWidth())
                EditorPercentageField("右边 %", form.right, { onChange(form.copy(right = it)) }, enabled, Modifier.fillMaxWidth())
                EditorPercentageField("底部 %", form.bottom, { onChange(form.copy(bottom = it)) }, enabled, Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorPercentageField("左边 %", form.left, { onChange(form.copy(left = it)) }, enabled, Modifier.weight(1f))
                    EditorPercentageField("顶部 %", form.top, { onChange(form.copy(top = it)) }, enabled, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorPercentageField("右边 %", form.right, { onChange(form.copy(right = it)) }, enabled, Modifier.weight(1f))
                    EditorPercentageField("底部 %", form.bottom, { onChange(form.copy(bottom = it)) }, enabled, Modifier.weight(1f))
                }
            }
            if (rectangle == null) Text("范围须在 0–100% 内，右边大于左边，底部大于顶部。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("取消") }
            Button({ if (enabled && rectangle != null) onApply(rectangle) }, Modifier.heightIn(min = 48.dp),
                enabled = enabled && rectangle != null) { Text("应用范围") }
        }
    }
}
