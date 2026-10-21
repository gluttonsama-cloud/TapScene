package com.tapscene.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
internal fun ClickPlanCreatedProjectExitDialog(error: String?, geometryMatches: Boolean, message: String?,
    saving: Boolean, saveEnabled: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, onDiscard: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.widthIn(max = 560.dp), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            ClickPlanCreatedProjectExitContent(error, geometryMatches, message, saving, saveEnabled,
                onDismiss, onSave, onDiscard)
        }
    }
}

/** Shared production content; feedback starts the scrolling details and all decisions stay fixed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClickPlanCreatedProjectExitContent(error: String?, geometryMatches: Boolean, message: String?,
    saving: Boolean, saveEnabled: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, onDiscard: () -> Unit) {
    val feedback = message?.takeIf { it != error }
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("保留当前修改？", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (!geometryMatches) Text("请恢复原方向及全屏，或返回点击计划按当前屏幕重新编排。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text("点击计划有尚未保存的修改。可保存后打开新项目，或放弃本次修改。已生成的新项目不会改变。",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("暂不打开")
            }
            TextButton(onClick = onDiscard, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("放弃修改并打开")
            }
            TextButton(onClick = onSave, enabled = saveEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("保存并打开")
            }
        }
    }
}
