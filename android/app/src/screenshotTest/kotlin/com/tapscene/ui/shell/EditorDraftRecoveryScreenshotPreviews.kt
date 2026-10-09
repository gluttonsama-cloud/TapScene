package com.tapscene.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.EditorFormKind
import com.tapscene.data.EditorPendingForm
import com.tapscene.ui.DraftRecoveryStatus

/** Test-only inputs rendered by the exact production form body, without the platform modal window. */
@PreviewTest
@Preview(name = "30_editor_recovered_form", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun EditorRecoveredFormPreview() {
    EditorRecoverySurface(
        EditorPendingForm(EditorFormKind.NAME, title = "核对报名信息",
            description = "上次尚未应用的讲解：核对活动日期与参与人数，然后继续报名。"),
        DraftRecoveryStatus.STAGED,
    )
}

@PreviewTest
@Preview(name = "31_editor_staging_failure_long_text", widthDp = 320, heightDp = 640, locale = "zh-rCN", showBackground = true)
@Composable
fun EditorStagingFailureLongTextPreview() {
    EditorRecoverySurface(
        EditorPendingForm(EditorFormKind.NAME,
            title = "核对报名信息、联系方式与活动日期，再确认下一步的报名结果",
            description = "请核对活动名称、日期、人数以及页面中已经填写的示例信息。" +
                "如果内容需要修改，可以返回上一步调整；确认无误后再继续。\n\n" +
                "这是尚未应用到步骤的长讲解。本机暂存失败时，输入仍应完整留在面板中，" +
                "重试入口应清楚可见。请先完成暂存，再离开应用；关闭面板则明确取消这些未应用的内容。\n\n" +
                "屏幕较窄时可以上下滚动，检查完整文字，并找到下方的取消与保存本步按钮。"),
        DraftRecoveryStatus.FAILED,
    )
}

@Composable
private fun EditorRecoverySurface(form: EditorPendingForm, status: DraftRecoveryStatus) {
    var pending by remember { mutableStateOf(form) }
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Text(if (status == DraftRecoveryStatus.STAGED) "已恢复上次未保存的编辑，可以继续修改" else "本机暂存失败，输入仍保留",
                    Modifier.fillMaxWidth().background(ShellColors.AccentSoft).padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 20.dp)) {
                    StepNameFormContent(pending, true, status, {}, { pending = it }, {}, { _, _ -> })
                }
                Text("布局样例 · 生产表单内容，独立窗口行为需设备检查", Modifier.fillMaxWidth().background(ShellColors.AccentSoft)
                    .padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall,
                    color = ShellColors.Accent)
            }
        }
    }
}
