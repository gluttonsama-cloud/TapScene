package com.tapscene.ui.shell

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tapscene.MainActivity
import com.tapscene.data.HostProjectFixture
import com.tapscene.data.HostSqlite
import com.tapscene.data.ReleaseSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Production Compose with synthetic summaries/callbacks. No system picker or device behavior is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DeliveryProductPagesHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() { HostSqlite.configure() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val release = ReleaseSummary("selected-fixed-release-0123456789", "活动报名：选择活动并核对报名结果", "0".repeat(64),
        1_791_504_000_000L, 3, 455_000L, "local")

    @Test fun noSelectionKeepsReviewAndOtherVersionAiReachableWithoutDeadExportButtons() {
        var review = 0
        var ai = 0
        var back = 0
        content { DeliveryOptionsScreen({ back++ }, { review++ }, { ai++ }, {}, {}) }
        compose.onNodeWithText("当前未选择封存版本").assertIsDisplayed()
        compose.onNodeWithText("尚未生成").assertDoesNotExist()
        compose.onNodeWithText("保存到文件").assertDoesNotExist()
        compose.onNodeWithText("系统分享").assertDoesNotExist()
        compose.onNodeWithText("继续成品复核").performClick()
        compose.onNodeWithText("AI 数据包").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, review)
        assertEquals(1, ai)
        assertEquals(1, back)
        compose.onNodeWithText("开发托管账号").assertDoesNotExist()
        compose.onNodeWithText("开发托管版本").assertDoesNotExist()
        compose.onAllNodesWithText("托管链接尚未接入。").assertCountEquals(1)
    }

    @Test fun selectedVersionDetailsAndBusyStatePreserveExportAndShareCallbacks() {
        val selected = mutableStateOf(release)
        val busy = mutableStateOf(false)
        val exported = mutableListOf<String>()
        val shared = mutableListOf<String>()
        val viewed = mutableListOf<String>()
        content {
            val item = selected.value
            DeliveryOptionsScreen({}, {}, {}, {}, {}, sealedSummary = item,
                onExport = { exported += item.id; busy.value = true },
                onShare = { shared += item.id; busy.value = true }, busy = busy.value,
                onOpenSealed = { viewed += item.id })
        }
        compose.onNodeWithText(release.title).assertIsDisplayed()
        compose.onNodeWithText("返回成品复核").assertDoesNotExist()
        compose.onNodeWithText("查看封存成品").performScrollTo().performClick()
        assertEquals(listOf(release.id), viewed)
        compose.onNodeWithText(release.id).assertDoesNotExist()
        compose.onNodeWithText("版本详情").performClick()
        compose.onNodeWithText(release.id).assertIsDisplayed()
        compose.onNodeWithText("版本详情").performClick()
        compose.onNodeWithText(release.id).assertDoesNotExist()
        compose.onNodeWithText("保存到文件").performScrollTo().performClick()
        compose.onNodeWithText("正在准备").assertIsNotEnabled().performClick()
        compose.onNodeWithText("系统分享").assertIsNotEnabled().performClick()
        assertEquals(listOf(release.id), exported)
        assertEquals(emptyList<String>(), shared)
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithText("系统分享").performClick()
        compose.onNodeWithText("系统分享").assertIsNotEnabled().performClick()
        assertEquals(listOf(release.id), shared)
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithText("版本详情").performScrollTo().performClick()
        compose.runOnIdle { selected.value = release.copy(id = "another-fixed-release") }
        compose.onNodeWithText("版本标识").assertDoesNotExist()
        compose.onNodeWithText("版本详情").performClick()
        compose.onNodeWithText("another-fixed-release").assertIsDisplayed()
    }

    @Test fun importedVersionCannotShareAndDeveloperHostingRetainsAllDestinations() {
        var hosting = 0
        var account = 0
        var versions = 0
        content {
            DeliveryOptionsScreen({}, {}, {}, { account++ }, { versions++ },
                sealedSummary = release.copy(origin = "imported"), onExport = {}, onShare = {}, onHosting = { hosting++ })
        }
        compose.onNodeWithText("导入于", substring = true).assertIsDisplayed()
        compose.onNodeWithText("保存到文件").assertIsEnabled()
        compose.onNodeWithText("系统分享").assertIsNotEnabled()
        compose.onNodeWithText("选择本机封存版").performScrollTo().performClick()
        compose.onNodeWithText("开发托管账号").performScrollTo().performClick()
        compose.onNodeWithText("开发托管版本").performScrollTo().performClick()
        assertEquals(1, hosting)
        assertEquals(1, account)
        assertEquals(1, versions)
        compose.onNodeWithText("托管链接尚未接入。").assertDoesNotExist()
    }

    @Test fun settingsOnlyShowsRealStorageAndExpandableAccurateHelp() {
        var manage = 0
        content { SettingsStorageScreen(2, 42_000_000L, {}, {}, {}, { manage++ }, hostingAvailable = false) }
        compose.onNodeWithText("40.1 MiB").assertIsDisplayed()
        compose.onNodeWithText("2 项").assertIsDisplayed()
        compose.onNodeWithText("以上仅统计当前读取到的原素材，不代表应用全部占用。").assertIsDisplayed()
        compose.onNodeWithText("临时缓存").assertDoesNotExist()
        compose.onNodeWithText("交付协议").assertDoesNotExist()
        compose.onNodeWithText("管理本机保留素材").performClick()
        assertEquals(1, manage)
        compose.onNodeWithText("支持格式与导入说明").performScrollTo().performClick()
        compose.onNodeWithText("或单张 PNG / JPEG 截图", substring = true).assertExists()
        compose.onNodeWithText("支持格式与导入说明").performClick()
        compose.onNodeWithText("或单张 PNG / JPEG 截图", substring = true).assertDoesNotExist()
        compose.onNodeWithText("本机内容与设备丢失").performScrollTo().performClick()
        compose.onNodeWithText("观看包不包含原片或可继续编辑的草稿。", substring = true).assertExists()
        compose.onNodeWithText("本机内容与设备丢失").performClick()
        compose.onNodeWithText("观看包不包含原片或可继续编辑的草稿。", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Apache-2.0").performScrollTo().assertIsDisplayed()
    }

    @Test fun failedProjectReadBlocksGenerationWithoutShowingProgress() {
        HostProjectFixture().use { fixture ->
            val snapshot = fixture.snapshot()
            val failed = mutableStateOf(true)
            val busy = mutableStateOf(true)
            var builds = 0
            content { DeliveryCheckScreen(snapshot, emptyList(), {}, {}, {}, {}, {},
                onBuildCandidate = { builds++ }, busy = busy.value, loadFailed = failed.value) }
            compose.onNodeWithText("请先重试读取项目，再确认交付检查。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("正在生成固定候选，请稍候。").assertDoesNotExist()
            compose.onNodeWithText("当前检查未返回待处理问题。").assertDoesNotExist()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
            compose.onNodeWithText("生成待复核成品").assertIsNotEnabled().performClick()
            assertEquals(0, builds)
            compose.runOnIdle { busy.value = false }
            compose.onNodeWithText("生成待复核成品").assertIsNotEnabled().performClick()
            assertEquals(0, builds)
            compose.runOnIdle { failed.value = false }
            compose.onNodeWithText("请先重试读取项目，再确认交付检查。").assertDoesNotExist()
            compose.onNodeWithText("生成待复核成品").assertIsEnabled().performClick()
            assertEquals(1, builds)
        }
    }

    private fun content(block: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { TapSceneTheme(content = block) } }
        compose.waitForIdle()
    }
}
