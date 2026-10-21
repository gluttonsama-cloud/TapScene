package com.tapscene.ui.shell

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectNextAction
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep
import com.tapscene.data.ProjectSummary
import com.tapscene.data.ProjectTransition
import com.tapscene.data.StepAsset
import com.tapscene.data.TransitionAsset
import com.tapscene.media.ImportedSource
import com.tapscene.media.SourceMetadata
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

/** Production Compose with synthetic metadata/callbacks; no video files, decoding or device claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AuthoredPathImpactHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() { resetApplicationViewModelFactory(); HostSqlite.configure() }
        override fun after() { resetApplicationViewModelFactory() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sameTitleStepsShowOnlyChangedTransitionImpactAndRequireExplicitBuild() {
        val project = fixture()
        val (a, b, c) = project.steps.take(3).map { it.id }
        val selected = listOf(a, c, b)
        val builds = mutableListOf<BuildRequest>()
        val drafts = mutableListOf<Pair<List<String>, Boolean>>()
        content {
            AuthoredPathScreen(project, selected, false, {},
                { ids, terminal, revision -> builds += BuildRequest(ids, terminal, revision) }, {},
                initialMarkLastTerminal = false,
                onDraftChanged = { ids, terminal -> drafts += ids to terminal })
        }

        // All four steps have the same title and a video binding. Only A's A→B becomes A→C:
        // C→B is unchanged, B is last, and D is unselected, so those three bindings are retained.
        compose.onNodeWithText("将移除 1 个原视频过渡；生成后需重新设置。").assertIsDisplayed()
        showText("通路 1 · 步骤 1 同名")
        compose.onNodeWithText("下一步 → 步骤 3 同名").assertIsDisplayed()
        compose.onNodeWithText("原下一步 → 步骤 2 同名").assertIsDisplayed()
        compose.onNodeWithText("原视频过渡将移除，需重新设置。").assertIsDisplayed()
        compose.onAllNodesWithText("原视频过渡将移除，需重新设置。").assertCountEquals(1)
        showText("通路 2 · 步骤 3 同名")
        compose.onNodeWithText("下一步 → 步骤 2 同名").assertIsDisplayed()
        showText("通路 3 · 步骤 2 同名")
        compose.onNodeWithText("末步 · 保留下一步与热点").assertIsDisplayed()
        showText("步骤 4 同名")
        compose.onNodeWithText("将改写 1 个下一步目标；手动热点与项目起点保留。").assertIsDisplayed()
        compose.onNodeWithText("将移除 1 个原视频过渡；生成后需重新设置。").assertIsDisplayed()
        compose.onNodeWithText("按此顺序生成通路").assertIsEnabled()
        assertEquals(selected to false, drafts.last())
        assertTrue(builds.isEmpty())

        clickDescription("上移 步骤 2 同名")
        showText("通路 1 · 步骤 1 同名")
        compose.onNodeWithText("下一步 → 步骤 2 同名").assertIsDisplayed()
        compose.onNodeWithText("原下一步 → 步骤 2 同名").assertDoesNotExist()
        compose.onNodeWithText("原视频过渡将移除，需重新设置。").assertDoesNotExist()
        compose.onNodeWithText("将移除", substring = true).assertDoesNotExist()
        compose.onNodeWithText("按上方顺序连接；手动热点、项目起点与分镜排序保留。").assertIsDisplayed()
        showText("通路 2 · 步骤 2 同名")
        showText("通路 3 · 步骤 3 同名")
        assertEquals(listOf(a, b, c) to false, drafts.last())

        // Selection changes use the original step identity too, without submitting a build.
        clickDescription("移出通路：步骤 3 同名")
        assertEquals(listOf(a, b) to false, drafts.last())
        compose.onNodeWithText("将移除", substring = true).assertDoesNotExist()
        showText("步骤 3 同名")
        compose.onNodeWithText("步骤 3 同名").performClick()
        clickDescription("下移 步骤 2 同名")
        showText("通路 1 · 步骤 1 同名")
        compose.onNodeWithText("下一步 → 步骤 3 同名").assertIsDisplayed()
        compose.onNodeWithText("原下一步 → 步骤 2 同名").assertIsDisplayed()
        compose.onNodeWithText("将移除 1 个原视频过渡；生成后需重新设置。").assertIsDisplayed()
        assertEquals(selected to false, drafts.last())
        assertTrue(builds.isEmpty())

        compose.onNodeWithText("按此顺序生成通路").assertIsEnabled().performClick()
        assertEquals(listOf(BuildRequest(selected, false, project.project.revision)), builds)
        assertEquals(listOf("path-a", "path-b", "path-c", "path-d"), project.steps.map { it.id })
        assertEquals(listOf("path-b", "path-c", "path-b", "path-a"),
            project.steps.map { it.nextAction?.targetStepId })
        assertTrue(project.steps.all { it.nextAction?.transition != null })
    }

    @Config(qualifiers = "w740dp-h480dp-xhdpi")
    @Test fun reorderedSameTitlePathKeepsProjectStartAndRequiresExplicitBuild() {
        val original = fixture()
        val (a, b, c, d) = original.steps
        val project = original.copy(steps = listOf(b, a, c, d).mapIndexed { index, step -> step.copy(sortOrder = index) })
        val selected = listOf(b.id, a.id, c.id)
        val builds = mutableListOf<BuildRequest>()
        val drafts = mutableListOf<Pair<List<String>, Boolean>>()
        val differentStart = "本次通路第一步不是项目起点。要从步骤 1 同名开始，请返回分镜，在该步更多菜单选择“设为起点”。"
        content {
            AuthoredPathScreen(project, selected, false, {},
                { ids, terminal, revision -> builds += BuildRequest(ids, terminal, revision) }, {},
                initialMarkLastTerminal = false,
                onDraftChanged = { ids, terminal -> drafts += ids to terminal })
        }

        // Storyboard order is B,A,C,D, while the persisted start is still A. Same titles
        // must use storyboard numbers, not the original identity order or path position.
        showText("项目起点：步骤 2 同名")
        compose.onNodeWithText("项目起点：步骤 1 同名").assertDoesNotExist()
        showText("生成通路不会更改项目起点。")
        showText(differentStart)
        showText("通路 1 · 步骤 1 同名")
        showText("通路 2 · 步骤 2 同名")
        showText("通路 3 · 步骤 3 同名")
        compose.onNodeWithText("将改写 2 个下一步目标；手动热点与项目起点保留。").assertIsDisplayed()
        assertEquals(selected to false, drafts.last())
        assertEquals(a.id, project.project.startStepId)
        assertTrue(builds.isEmpty())

        clickDescription("上移 步骤 2 同名")
        showText("项目起点：步骤 2 同名")
        showText("生成通路不会更改项目起点。")
        compose.onNodeWithText(differentStart).assertDoesNotExist()
        showText("通路 1 · 步骤 2 同名")
        compose.onNodeWithText("按上方顺序连接；手动热点、项目起点与分镜排序保留。").assertIsDisplayed()
        assertEquals(listOf(a.id, b.id, c.id) to false, drafts.last())
        assertEquals(a.id, project.project.startStepId)
        assertTrue(builds.isEmpty())

        // Choosing a different first step again is only a draft change. Even the explicit
        // build callback receives that order unchanged; it cannot silently choose a start.
        clickDescription("下移 步骤 2 同名")
        showText(differentStart)
        assertEquals(selected to false, drafts.last())
        assertTrue(builds.isEmpty())
        compose.onNodeWithText("按此顺序生成通路").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf(BuildRequest(selected, false, project.project.revision)), builds)
        assertEquals(a.id, project.project.startStepId)
        assertEquals(listOf(b.id, a.id, c.id, d.id), project.steps.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), project.steps.map { it.sortOrder })
        showText("项目起点：步骤 2 同名")
        showText(differentStart)
    }

    @Test fun changedRevisionAndBusyStateBlockBuildCallbacks() {
        val original = fixture()
        val project = mutableStateOf(original)
        val busy = mutableStateOf(true)
        val selected = listOf(original.steps[0].id, original.steps[2].id, original.steps[1].id)
        val builds = mutableListOf<BuildRequest>()
        content {
            AuthoredPathScreen(project.value, selected, busy.value, {},
                { ids, terminal, revision -> builds += BuildRequest(ids, terminal, revision) }, {},
                initialMarkLastTerminal = false)
        }
        compose.onNodeWithText("将移除 1 个原视频过渡；生成后需重新设置。").assertIsDisplayed()
        compose.onNodeWithText("正在生成…").assertIsNotEnabled().performClick()
        assertTrue(builds.isEmpty())
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithText("按此顺序生成通路").assertIsEnabled()
        assertTrue(builds.isEmpty())

        compose.runOnIdle {
            project.value = original.copy(project = original.project.copy(revision = original.project.revision + 1))
        }
        compose.onNodeWithText("项目已有更新，请返回后重新打开。").assertIsDisplayed()
        compose.onNodeWithText("按此顺序生成通路").assertIsNotEnabled().performClick()
        assertTrue(builds.isEmpty())
        // Clearing a later busy period must not make the old revision confirmation usable again.
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithText("正在生成…").assertIsNotEnabled().performClick()
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithText("项目已有更新，请返回后重新打开。").assertIsDisplayed()
        compose.onNodeWithText("按此顺序生成通路").assertIsNotEnabled().performClick()
        assertTrue(builds.isEmpty())
    }

    private fun fixture(): ProjectSnapshot {
        val source = ImportedSource("path-source", "host-metadata-only/no-video.mp4", "合成元数据",
            SourceMetadata("video/avc", 1_024L, "0".repeat(64), 480, 840, 0, 8_000_000L))
        val ids = listOf("path-a", "path-b", "path-c", "path-d")
        val targets = listOf(ids[1], ids[2], ids[1], ids[0])
        return ProjectSnapshot(ProjectSummary("path-project", "同名步骤通路", "", 17L, 0L, 4, ids.first()),
            ids.mapIndexed { index, id ->
                ProjectStep(id, "同名", "", index, false,
                    StepAsset("image-$id", "host-metadata-only/$id.png", "0".repeat(64), 1_024L, 480, 840),
                    source, index * 1_000_000L, 1_000L, emptyList(), emptyList(), "capture-$id",
                    ProjectNextAction("next-$id", "下一步", targets[index], ProjectTransition(
                        TransitionAsset("transition-$id", "host-metadata-only/$id.mp4", "1".repeat(64),
                            1_024L, 480, 840, 1_000_000L),
                        source, 0L, 1_000_000L, emptyList(), "review-$id")))
            })
    }

    private data class BuildRequest(val ids: List<String>, val markLastTerminal: Boolean, val revision: Long)

    private fun showText(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun clickDescription(description: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).assertIsDisplayed().performClick()
    }

    private fun content(block: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { TapSceneTheme(content = block) } }
        compose.waitForIdle()
    }

    private fun resetApplicationViewModelFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
