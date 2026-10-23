package com.tapscene.ui.shell

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tapscene.MainActivity
import com.tapscene.data.HostProjectFixture
import com.tapscene.data.HostSqlite
import com.tapscene.data.ReleaseSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private var previousRequestedSize: String? = null

    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            HostSqlite.configure()
            previousRequestedSize = System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        }
        override fun after() {
            val key = "robolectric.windowManager.useRequestedSize"
            previousRequestedSize?.let { System.setProperty(key, it) } ?: System.clearProperty(key)
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val release = ReleaseSummary("selected-fixed-release-0123456789", "活动报名：选择活动并核对报名结果", "0".repeat(64),
        1_791_504_000_000L, 3, 455_000L, "local")

    @Test fun noSelectionKeepsReviewAndOtherVersionAiReachableWithoutDeadExportButtons() {
        var review = 0
        var ai = 0
        var back = 0
        content { DeliveryOptionsScreen({ back++ }, { review++ }, { ai++ }, {}, {},
            sealedRevision = 7L, currentDraftRevision = 8L, onBackToChecks = {}) }
        compose.onNodeWithText("当前未选择封存版本").assertIsDisplayed()
        compose.onNodeWithText("封存修订 7").assertDoesNotExist()
        compose.onNodeWithText("草稿已更新", substring = true).assertDoesNotExist()
        compose.onNodeWithText("返回项目检查").assertDoesNotExist()
        compose.onNodeWithText("尚未生成").assertDoesNotExist()
        compose.onNodeWithText("保存到文件").assertDoesNotExist()
        compose.onNodeWithText("系统分享").assertDoesNotExist()
        compose.onNodeWithText("播放此版本").assertDoesNotExist()
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
        val draftRevision = mutableStateOf(7L)
        val busy = mutableStateOf(false)
        val exported = mutableListOf<String>()
        val shared = mutableListOf<String>()
        val viewed = mutableListOf<String>()
        var checks = 0
        content {
            val item = selected.value
            DeliveryOptionsScreen({}, {}, {}, {}, {}, sealedSummary = item,
                onExport = { exported += item.id; busy.value = true },
                onShare = { shared += item.id; busy.value = true }, busy = busy.value,
                onOpenSealed = { viewed += item.id }, sealedRevision = 7L,
                currentDraftRevision = draftRevision.value, onBackToChecks = { checks++ })
        }
        compose.onNodeWithText(release.title).assertIsDisplayed()
        compose.onNodeWithText("封存修订 7").assertDoesNotExist()
        compose.onNodeWithText("封存于", substring = true).assertDoesNotExist()
        compose.runOnIdle { draftRevision.value = 8L }
        compose.onNodeWithText("草稿已更新至修订 8，可能包含此版本未收录的后续修改。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("封存修订 8").assertDoesNotExist()
        compose.onNodeWithText("返回项目检查").performScrollTo().performClick()
        assertEquals(1, checks)
        assertEquals(release.id, selected.value.id)
        assertEquals(emptyList<String>(), exported)
        assertEquals(emptyList<String>(), shared)
        compose.onNodeWithText("返回成品复核").assertDoesNotExist()
        compose.onNodeWithText("播放此版本").performScrollTo().performClick()
        assertEquals(listOf(release.id), viewed)
        compose.onNodeWithText(release.id).assertDoesNotExist()
        compose.onNodeWithText(release.contentDigest).assertDoesNotExist()
        compose.onNodeWithText("版本详情").performScrollTo().performClick()
        compose.onNodeWithText("封存修订 7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("封存于", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(release.id).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("内容摘要").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(release.contentDigest).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("版本详情").performScrollTo().performClick()
        compose.onNodeWithText(release.id).assertDoesNotExist()
        compose.onNodeWithText(release.contentDigest).assertDoesNotExist()
        compose.onNodeWithText("封存修订 7").assertDoesNotExist()
        compose.onNodeWithText("封存于", substring = true).assertDoesNotExist()
        compose.onNodeWithText("保存到文件").performScrollTo().performClick()
        compose.onNodeWithText("正在准备").assertIsNotEnabled().performClick()
        compose.onNodeWithText("系统分享").assertIsNotEnabled().performClick()
        compose.onNodeWithText("播放此版本").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(listOf(release.id), viewed)
        compose.onNodeWithText("返回项目检查").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(1, checks)
        assertEquals(listOf(release.id), exported)
        assertEquals(emptyList<String>(), shared)
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithText("系统分享").performScrollTo().performClick()
        compose.onNodeWithText("系统分享").assertIsNotEnabled().performClick()
        compose.onNodeWithText("播放此版本").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(listOf(release.id), viewed)
        assertEquals(listOf(release.id), shared)
        assertEquals(release.id, selected.value.id)
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithText("版本详情").performScrollTo().performClick()
        compose.runOnIdle { selected.value = release.copy(id = "another-fixed-release") }
        compose.onNodeWithText("版本标识").assertDoesNotExist()
        compose.onNodeWithText("内容摘要").assertDoesNotExist()
        compose.onNodeWithText("版本详情").performScrollTo().performClick()
        compose.onNodeWithText("another-fixed-release").performScrollTo().assertIsDisplayed()
    }

    @Test fun matchingAndUnknownRevisionsDoNotSuggestUnverifiedDraftChanges() {
        val sealedRevision = mutableStateOf<Long?>(7L)
        val draftRevision = mutableStateOf<Long?>(7L)
        content {
            DeliveryOptionsScreen({}, {}, {}, {}, {}, sealedSummary = release,
                onExport = {}, onShare = {}, sealedRevision = sealedRevision.value,
                currentDraftRevision = draftRevision.value, onBackToChecks = {})
        }
        compose.onNodeWithText("此版本内容已固定，不会随项目草稿更新。").assertIsDisplayed()
        compose.onNodeWithText("封存修订 7").assertDoesNotExist()
        compose.onNodeWithText("版本详情").performScrollTo().performClick()
        compose.onNodeWithText("封存修订 7").performScrollTo().assertIsDisplayed()
        assertNoDraftComparison()
        compose.runOnIdle { draftRevision.value = null }
        compose.onNodeWithText("封存修订 7").performScrollTo().assertIsDisplayed()
        assertNoDraftComparison()
        compose.runOnIdle { sealedRevision.value = null; draftRevision.value = 8L }
        compose.onNodeWithText("封存修订", substring = true).assertDoesNotExist()
        assertNoDraftComparison()
        compose.onNodeWithText("保存到文件").assertIsEnabled()
        compose.onNodeWithText("系统分享").assertIsEnabled()
    }

    @Test fun lowerDraftRevisionUsesNeutralCopyAndMissingRouteDoesNotInventChecksAction() {
        val draftRevision = mutableStateOf(6L)
        content {
            DeliveryOptionsScreen({}, {}, {}, {}, {}, sealedSummary = release,
                onExport = {}, onShare = {}, sealedRevision = 7L, currentDraftRevision = draftRevision.value)
        }
        compose.onNodeWithText("当前草稿为修订 6，与封存修订 7 不同。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("草稿已更新", substring = true).assertDoesNotExist()
        compose.onNodeWithText("后续修改", substring = true).assertDoesNotExist()
        compose.onNodeWithText("返回项目检查").assertDoesNotExist()
        compose.onNodeWithText("保存到文件").assertIsEnabled()
        compose.onNodeWithText("系统分享").assertIsEnabled()
        compose.runOnIdle { draftRevision.value = 8L }
        compose.onNodeWithText("草稿已更新至修订 8，可能包含此版本未收录的后续修改。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("当前草稿为修订", substring = true).assertDoesNotExist()
        compose.onNodeWithText("返回项目检查").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-xhdpi")
    fun shortLandscapeKeepsVersionContextDetailsAndExportReachable() {
        var exports = 0
        var checks = 0
        content {
            DeliveryOptionsScreen({}, {}, {}, {}, {},
                sealedSummary = release.copy(title = "活动报名：选择活动、核对报名信息并确认结果与后续安排"),
                onExport = { exports++ }, onShare = {}, sealedRevision = 7L, currentDraftRevision = 8L,
                onBackToChecks = { checks++ })
        }
        compose.onNodeWithText("版本详情").performScrollTo().performClick()
        compose.onNodeWithText("封存修订 7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("版本详情").performScrollTo().performClick()
        compose.onNodeWithText("草稿已更新至修订 8，可能包含此版本未收录的后续修改。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("返回项目检查").performScrollTo().performClick()
        compose.onNodeWithText("版本详情").performScrollTo().performClick()
        compose.onNodeWithText(release.contentDigest).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("保存到文件").performScrollTo().performClick()
        assertEquals(1, checks)
        assertEquals(1, exports)
    }

    @Test fun importedVersionCannotShareAndDeveloperHostingRetainsAllDestinations() {
        var hosting = 0
        var account = 0
        var versions = 0
        content {
            DeliveryOptionsScreen({}, {}, {}, { account++ }, { versions++ },
                sealedSummary = release.copy(origin = "imported"), onExport = {}, onShare = {}, onHosting = { hosting++ })
        }
        compose.onNodeWithText("导入于", substring = true).assertDoesNotExist()
        compose.onNodeWithText("版本详情").performScrollTo().performClick()
        compose.onNodeWithText("导入于", substring = true).performScrollTo().assertIsDisplayed()
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

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun smallDeliveryShowsBoundPlaybackBeforeScrollingAndBlocksUnavailableActions() {
        val selected = mutableStateOf<ReleaseSummary?>(release)
        val busy = mutableStateOf(false)
        val playbackAvailable = mutableStateOf(true)
        val played = mutableListOf<String>()
        var exports = 0
        var shares = 0
        content(fontScale = 1.2f) {
            val item = selected.value
            DeliveryOptionsScreen({}, {}, {}, {}, {}, sealedSummary = item,
                onExport = { exports++ }, onShare = { shares++ }, busy = busy.value,
                onOpenSealed = if (playbackAvailable.value) ({ played += checkNotNull(item).id; busy.value = true }) else null,
                sealedRevision = 7L, currentDraftRevision = 8L, onBackToChecks = {})
        }
        compose.onNodeWithTag("delivery-surface").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(640.dp)
        compose.onAllNodesWithText("播放此版本").assertCountEquals(1)
        val play = compose.onNode(hasText("播放此版本") and hasClickAction()).assertIsEnabled()
        val viewport = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
            .fetchSemanticsNode().boundsInRoot
        val visible = play.fetchSemanticsNode().boundsInRoot.intersect(viewport)
        val full = play.getUnclippedBoundsInRoot()
        // Before ANY scroll: a visible label/sliver does not prove a usable playback action.
        with(compose.density) {
            assertTrue(visible.width >= 48.dp.toPx() && visible.height >= 48.dp.toPx())
            assertEquals(full.left.toPx(), visible.left, 1f)
            assertEquals(full.top.toPx(), visible.top, 1f)
            assertEquals(full.right.toPx(), visible.right, 1f)
            assertEquals(full.bottom.toPx(), visible.bottom, 1f)
        }
        val warning = compose.onNodeWithText("草稿已更新至修订 8，可能包含此版本未收录的后续修改。")
        assertTrue(play.getUnclippedBoundsInRoot().bottom <= warning.getUnclippedBoundsInRoot().top)
        assertTrue(warning.getUnclippedBoundsInRoot().bottom <=
            compose.onNodeWithText("保存到文件").getUnclippedBoundsInRoot().top)
        compose.onNodeWithText("封存修订 7").assertDoesNotExist()
        compose.onNodeWithText("封存于", substring = true).assertDoesNotExist()
        play.performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf(release.id), played)
        play.assertIsNotEnabled().performTouchInput { click(center) }
        assertEquals(listOf(release.id), played)

        // The same visible title does not permit retaining the previous version's callback.
        val other = release.copy(id = "other-selected-fixed-version", contentDigest = "1".repeat(64))
        compose.runOnIdle { selected.value = other; busy.value = false }
        play.assertIsEnabled().performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(listOf(release.id, other.id), played)
        assertEquals(0, exports)
        assertEquals(0, shares)
        compose.runOnIdle { playbackAvailable.value = false; busy.value = false }
        compose.onNodeWithText("播放此版本").assertDoesNotExist()
        compose.runOnIdle { playbackAvailable.value = true; selected.value = null }
        compose.onNodeWithText("播放此版本").assertDoesNotExist()
        compose.onNodeWithText("当前未选择封存版本").assertIsDisplayed()
        assertEquals(listOf(release.id, other.id), played)
    }

    private fun content(fontScale: Float? = null, block: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                    TapSceneTheme {
                        Box(Modifier.fillMaxSize().testTag("delivery-surface")) { block() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertNoDraftComparison() {
        compose.onNodeWithText("草稿已更新", substring = true).assertDoesNotExist()
        compose.onNodeWithText("当前草稿为修订", substring = true).assertDoesNotExist()
        compose.onNodeWithText("返回项目检查").assertDoesNotExist()
    }
}
