package com.tapscene.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Compact toolbar; the host owns window insets and the surrounding Scaffold. */
@Composable
fun ShellTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = if (onBack == null) 16.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp).semantics { contentDescription = "返回" }) {
                    Canvas(Modifier.size(22.dp)) {
                        val color = ShellColors.Ink
                        drawLine(color, Offset(size.width * .65f, size.height * .18f), Offset(size.width * .3f, size.height * .5f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(color, Offset(size.width * .3f, size.height * .5f), Offset(size.width * .65f, size.height * .82f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            Text(
                title,
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
        ShellDivider()
    }
}

@Composable
fun ShellDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = ShellColors.Divider)
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
    }
}

@Composable
fun ScreenEmpty(title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmptyFrames()
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
        if (actionLabel != null) {
            Button(onClick = { onAction?.invoke() }, enabled = onAction != null, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel)
            }
        }
    }
}

/** Abstract empty slots are decoration, never project thumbnails or fake content. */
@Composable
private fun EmptyFrames() {
    Canvas(Modifier.width(132.dp).height(54.dp)) {
        val gap = 8.dp.toPx()
        val frameWidth = (size.width - gap * 2) / 3
        repeat(3) { index ->
            val x = index * (frameWidth + gap)
            drawRect(ShellColors.Surface, Offset(x, 0f), Size(frameWidth, size.height))
            drawRect(ShellColors.Divider, Offset(x, 0f), Size(frameWidth, size.height), style = Stroke(1.dp.toPx()))
            drawLine(ShellColors.Divider, Offset(x + 7.dp.toPx(), size.height - 11.dp.toPx()), Offset(x + frameWidth - 7.dp.toPx(), size.height - 11.dp.toPx()), 1.dp.toPx())
            if (index == 0) drawRect(ShellColors.Accent, Offset(x, 0f), Size(3.dp.toPx(), 17.dp.toPx()))
        }
    }
}

@Composable
fun UnavailableAction(label: String, reason: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
        Text(reason, style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
    }
}

@Composable
fun StatusNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(ShellColors.Quiet).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.padding(top = 7.dp).size(5.dp).background(ShellColors.Muted))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
    }
}

/** A single stable row rhythm for settings, package choices, and workflow destinations. */
@Composable
fun ShellActionRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val clickable = onClick != null && enabled
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) ShellColors.Ink else ShellColors.Muted)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ShellColors.Muted)
        }
        if (value != null) Text(value, style = MaterialTheme.typography.labelLarge, color = ShellColors.Muted)
        if (clickable) Text("›", style = MaterialTheme.typography.titleLarge, color = ShellColors.Muted)
    }
}

@Composable
fun ShellLabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = ShellColors.Muted)
        Text(value, modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
    }
}
