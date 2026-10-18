package com.tapscene.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.AiDraftImportPreview
import com.tapscene.packageformat.AiDraftImportPolicy
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerScene
import java.util.UUID

/** Actual production comparison panel; in-memory graph metadata, no image/device claim. */
@PreviewTest
@Preview(name = "ai_import_difference_small_large_font", widthDp = 320, heightDp = 568, fontScale = 1.3f, locale = "zh-rCN", showBackground = true)
@Preview(name = "ai_import_difference_landscape", widthDp = 740, heightDp = 360, locale = "zh-rCN", showBackground = true)
@Composable
fun AiImportDifferenceIdentityPreview() {
    fun id(value: String) = UUID.nameUUIDFromBytes("import-difference-layout-$value".toByteArray()).toString()
    val asset = ViewerScene.Asset(id("image"), "assets/${id("image")}.png", "image/png", 100, "0".repeat(64), 32, 48)
    fun step(key: String, title: String, terminal: Boolean) = ViewerScene.State(id(key), asset.id, 32, 48, title, "", "authored", terminal)
    val start = step("start", "核对报名", false)
    val oldTarget = step("old-target", "付款确认与参与者完整信息", true)
    val alternate = step("alternate", "活动说明与预约结果", true)
    val hotspot = ViewerScene.Hotspot(id("hotspot"), start.id, "查看另一项", ViewerScene.Rect(.1, .1, .4, .2))
    fun edges(target: String, other: String) = listOf(
        ViewerScene.Edge(id("next"), start.id, target, null, null, "继续", "continue", "authored"),
        ViewerScene.Edge(id("branch"), start.id, other, null, hotspot.id, hotspot.label, "tap", "authored"),
    )
    val before = ViewerScene(id("before"), "报名流程", "", 1, start.id, listOf(start, oldTarget, alternate),
        edges(oldTarget.id, alternate.id), listOf(hotspot), listOf(asset))
    val renamed = step("old-target", "资料填写", true)
    val after = ViewerScene(id("after"), "报名流程", "", 2, start.id, listOf(start, alternate, renamed),
        edges(alternate.id, renamed.id), listOf(hotspot), listOf(asset))
    val plan = RenderPlan.build(after, 1080, 1920, listOf(
        RenderPlan.Visit(id("v1"), start.id, id("next"), 90), RenderPlan.Visit(id("v2"), alternate.id, null, 90)), emptyList())
    val policy = AiDraftImportPolicy.review(after, plan, before)
    val preview = AiDraftImportPreview(id("session"), "a".repeat(64), id("project"), after, plan, before.releaseId,
        before.title, policy.issues, policy.differences, policy.summary, policy.trustNotice, 100, before)
    val difference = policy.differences.single { it.subjectId == id("next") && it.field == "toStateId" }
    TapSceneTheme {
        Column(Modifier.fillMaxSize().background(ShellColors.Background).verticalScroll(rememberScrollState()).padding(16.dp)) {
            AiImportDifferencePanel(preview, difference, 0)
        }
    }
}
