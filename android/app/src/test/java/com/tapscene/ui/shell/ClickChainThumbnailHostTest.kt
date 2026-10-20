package com.tapscene.ui.shell

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.clickplan.ClickChainActionEditor
import com.tapscene.clickplan.ClickChainFrameEditor
import com.tapscene.clickplan.ClickChainFrameRow
import com.tapscene.clickplan.ClickChainReviewUiState
import com.tapscene.clickplan.ClickChainStageChoice
import com.tapscene.clickplan.ClickChainStageRow
import com.tapscene.clickplan.ClickChainThumbnailRequest
import com.tapscene.clickplan.ClickChainThumbnailSource
import com.tapscene.clickplan.INITIAL_SNAPSHOT_NOTICE
import com.tapscene.clickplan.MAX_VISIBLE_CLICK_CHAIN_THUMBNAILS
import com.tapscene.clickplan.visibleThumbnailRequests
import com.tapscene.data.HostSqlite
import com.tapscene.media.OpaqueMask
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Production Compose with synthetic pixels and controlled loaders; no private files or review writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClickChainThumbnailHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() { resetFactory(); HostSqlite.configure() }
        override fun after() { resetFactory() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun visibleRequestsExcludeOffscreenDeduplicateRevisitsAndCapEight() {
        val frames = List(12) { frame("frame-$it") } + ClickChainFrameRow("no-source", "无缩略图来源", false)
        val stages = listOf(stage(0, frames[0]), stage(1, frames[0])) +
            frames.drop(1).mapIndexed { index, frame -> stage(index + 2, frame) }
        val state = fixture(frames).copy(framesOnly = false, stages = stages)
        assertEquals(listOf(request(frames[0]), request(frames[2])),
            state.visibleThumbnailRequests(setOf("stage:0", "stage:1", "stage:3", "frame:frame-1")))
        assertEquals(frames.take(MAX_VISIBLE_CLICK_CHAIN_THUMBNAILS).map(::request),
            state.visibleThumbnailRequests(stages.map { "stage:${it.index}" }.toSet()))
        assertEquals(listOf(request(frames[2]), request(frames[4])), state.copy(framesOnly = true)
            .visibleThumbnailRequests(setOf("frame:frame-2", "frame:frame-4", "frame:no-source", "stage:0")))
        assertTrue(state.visibleThumbnailRequests(emptySet()).isEmpty())
        assertNotEquals(ClickChainThumbnailSource.OriginalUnreviewed("same"), ClickChainThumbnailSource.ReviewedOutput("same"))
    }

    @Test fun thumbnailLabelsAndAccessibilityDistinguishOriginalFromReviewedWithSameDigest() {
        val initial = frame("one", digest = "same").copy(selectionNotice = INITIAL_SNAPSHOT_NOTICE)
        val state = mutableStateOf(fixture(listOf(initial)))
        val requested = mutableListOf<List<ClickChainThumbnailRequest>>()
        var reviews = 0
        var creates = 0
        show(state, ClickChainReviewCallbacks(onReviewFrame = { reviews++ }, onCreate = { creates++ },
            loadThumbnails = { requests -> requested += requests; requests.associateWith { bitmap() } }))
        compose.onNodeWithText("原始画面 · 未复核").assertIsDisplayed()
        compose.onNodeWithContentDescription(description(initial)).assertIsDisplayed()
        compose.onNodeWithText(INITIAL_SNAPSHOT_NOTICE).assertIsDisplayed()
        compose.onNodeWithText("画面 0/1 · 仅加入画面").assertIsDisplayed()

        val reviewed = frame("one", reviewed = true, digest = "same")
        compose.runOnIdle { state.value = fixture(listOf(reviewed)) }
        compose.onNodeWithContentDescription(description(initial)).assertDoesNotExist()
        compose.onNodeWithText("已复核画面").assertIsDisplayed()
        compose.onNodeWithContentDescription(description(reviewed)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(listOf(request(initial)), listOf(request(reviewed))), requested)
            assertEquals(0, reviews)
            assertEquals(0, creates)
        }
    }

    @Test fun thumbnailFailuresKeepReviewControlsAndNeverMasqueradeAsMissingEvidence() {
        val original = frame("original")
        val reviewed = frame("reviewed", reviewed = true)
        val state = mutableStateOf(fixture(listOf(original, reviewed)))
        val opened = mutableListOf<String>()
        val requested = mutableListOf<ClickChainThumbnailRequest>()
        show(state, ClickChainReviewCallbacks(onReviewFrame = { opened += it },
            loadThumbnails = { requests -> requested += requests; emptyMap() }))
        compose.onNodeWithText("待复核\n打开画面").assertIsDisplayed()
        compose.onNodeWithText("已复核\n查看画面").assertIsDisplayed()
        compose.onNodeWithText("待补画面").assertDoesNotExist()
        compose.onNodeWithText("缺少画面证据").assertDoesNotExist()
        compose.onNodeWithText("复核画面").assertIsEnabled().performClick()
        compose.onNodeWithText("查看画面").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(original.key, reviewed.key), opened)
            assertTrue(requested.containsAll(listOf(request(original), request(reviewed))))
            assertFalse(requested.any { it.frameKey == reviewed.key && it.source is ClickChainThumbnailSource.OriginalUnreviewed })
            state.value = fixture(emptyList()).copy(framesOnly = false,
                stages = listOf(ClickChainStageRow(0, "缺失合成画面", emptyList(), null, false)))
        }
        compose.onNodeWithText("待补画面").assertIsDisplayed()
        compose.onNodeWithText("缺少画面证据").assertIsDisplayed()
        compose.onNodeWithText("复核画面").assertIsNotEnabled()
    }

    @Test fun lateRunSourceKindAndOutputResultsCannotPaintCurrentState() {
        val original = frame("one", digest = "same")
        val state = mutableStateOf(fixture(listOf(original)))
        val loader = ControlledLoader()
        show(state, ClickChainReviewCallbacks(loadThumbnails = loader::load))
        val oldRun = pending(loader, 0)
        compose.runOnIdle { state.value = state.value.copy(runId = "run-b") }
        val oldOriginal = pending(loader, 1)
        val reviewed = frame("one", reviewed = true, digest = "same")
        compose.runOnIdle { state.value = fixture(listOf(reviewed), "run-b") }
        val oldOutput = pending(loader, 2)
        val current = frame("one", reviewed = true, digest = "new-output")
        compose.runOnIdle { state.value = fixture(listOf(current), "run-b") }
        val latest = pending(loader, 3)
        listOf(oldRun, oldOriginal, oldOutput).forEach { cancelled ->
            val stale = bitmap()
            compose.runOnIdle { cancelled.deliver(stale) }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(description(current)).assertDoesNotExist()
            compose.onNodeWithContentDescription(description(original)).assertDoesNotExist()
            assertTrue("An unpublished cancelled result must release its pixels", stale.isRecycled)
        }
        compose.runOnIdle { latest.deliver(bitmap()) }
        compose.onNodeWithContentDescription(description(current)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(4, loader.calls.size) }
    }

    @Test fun leavingVisibleRowsCancelsTheOldBatchAndRequestsOnlyTheNewViewport() {
        val state = mutableStateOf(fixture(List(25) { frame("frame-$it") }))
        val started = mutableListOf<List<ClickChainThumbnailRequest>>()
        val cancelled = mutableListOf<List<ClickChainThumbnailRequest>>()
        show(state, ClickChainReviewCallbacks(loadThumbnails = { requests ->
            started += requests
            try { awaitCancellation() } finally { cancelled += requests }
        }))
        lateinit var first: List<ClickChainThumbnailRequest>
        compose.runOnIdle { first = started.last(); assertTrue(first.isNotEmpty()) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(16)
        compose.runOnIdle {
            assertTrue(cancelled.contains(first))
            assertTrue(started.last().isNotEmpty())
            assertTrue(started.last().none { it in first })
            assertTrue(started.all { it.size <= MAX_VISIBLE_CLICK_CHAIN_THUMBNAILS })
            assertTrue(started.flatten().map { it.frameKey }.toSet().size < state.value.frames.size)
        }
    }

    @Test fun replacingWorkbenchWithEitherEditorCancelsItsThumbnailRequest() {
        val only = frame("one")
        val state = mutableStateOf(fixture(listOf(only)))
        var started = 0
        var cancelled = 0
        show(state, ClickChainReviewCallbacks(
            onReviewFrame = { key -> state.value = state.value.copy(frameEditor = frameEditor(key)) },
            onCloseEditor = { state.value = state.value.copy(frameEditor = null, actionEditor = null) },
            loadThumbnails = {
                started++
                try { awaitCancellation() } finally { cancelled++ }
            }))
        compose.onNodeWithText("复核画面").performClick()
        compose.runOnIdle { assertEquals(1, started); assertEquals(1, cancelled) }
        compose.onNodeWithContentDescription(description(only)).assertDoesNotExist()
        compose.onNodeWithText("返回整理").performClick()
        compose.runOnIdle {
            assertEquals(2, started)
            state.value = state.value.copy(actionEditor = ClickChainActionEditor("action", "合成点击",
                OpaqueMask(.1f, .1f, .3f, .3f), null, null, "合成后继", .2f to .2f, false, 1))
        }
        compose.onNodeWithText("确认点击区域").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, cancelled) }
        compose.onNodeWithContentDescription(description(only)).assertDoesNotExist()
    }

    @Test fun revisitsShareOneBitmapAndReplacingOutputDropsButDoesNotRecycleDisplayedPixels() {
        val only = frame("one")
        val state = mutableStateOf(fixture(listOf(only)).copy(framesOnly = false,
            stages = listOf(stage(0, only), stage(1, only))))
        val loader = ControlledLoader()
        show(state, ClickChainReviewCallbacks(loadThumbnails = loader::load))
        val first = pending(loader, 0)
        val displayed = bitmap()
        compose.runOnIdle { assertEquals(listOf(request(only)), first.requests); first.deliver(displayed) }
        compose.onAllNodesWithContentDescription(description(only)).assertCountEquals(2)
        val revised = frame("one", digest = "replacement")
        compose.runOnIdle { state.value = state.value.copy(frames = listOf(revised)) }
        val replacement = pending(loader, 1)
        compose.onNodeWithContentDescription(description(only)).assertDoesNotExist()
        assertFalse("Rendered revisit pixels must not be recycled while a draw can still own them", displayed.isRecycled)
        val newPixels = bitmap()
        compose.runOnIdle { replacement.deliver(newPixels) }
        compose.onAllNodesWithContentDescription(description(revised)).assertCountEquals(2)
        compose.runOnIdle { state.value = state.value.copy(frameEditor = frameEditor(only.key)) }
        compose.onNodeWithContentDescription(description(revised)).assertDoesNotExist()
        assertFalse(newPixels.isRecycled)
        compose.runOnIdle { assertEquals(2, loader.calls.size) }
    }

    private fun show(state: MutableState<ClickChainReviewUiState>, callbacks: ClickChainReviewCallbacks) {
        compose.runOnUiThread {
            compose.activity.setContent { TapSceneTheme { ClickChainReviewContent(state.value, callbacks) } }
        }
        compose.waitForIdle()
    }

    private fun pending(loader: ControlledLoader, index: Int): PendingBatch {
        compose.waitForIdle()
        lateinit var result: PendingBatch
        compose.runOnIdle { assertEquals(index + 1, loader.calls.size); result = loader.calls[index] }
        return result
    }

    private class ControlledLoader {
        val calls = mutableListOf<PendingBatch>()
        // Deliberately non-cooperative: production must reject a return after cancellation.
        suspend fun load(requests: List<ClickChainThumbnailRequest>): Map<ClickChainThumbnailRequest, Bitmap> =
            suspendCoroutine { calls += PendingBatch(requests, it) }
    }

    private class PendingBatch(val requests: List<ClickChainThumbnailRequest>,
        private val continuation: Continuation<Map<ClickChainThumbnailRequest, Bitmap>>) {
        fun deliver(bitmap: Bitmap) { continuation.resume(requests.associateWith { bitmap }) }
    }

    private fun fixture(frames: List<ClickChainFrameRow>, runId: String = "run-a") = ClickChainReviewUiState(
        runId = runId, title = "合成演示", totalActions = 1, framesOnly = true, frames = frames,
        frameCount = frames.size, reviewedFrameCount = frames.count { it.reviewed })
    private fun frame(key: String, reviewed: Boolean = false, digest: String = key) = ClickChainFrameRow(
        key, "合成画面 $key", reviewed, thumbnailSource = if (reviewed) ClickChainThumbnailSource.ReviewedOutput(digest)
        else ClickChainThumbnailSource.OriginalUnreviewed(digest))
    private fun stage(index: Int, frame: ClickChainFrameRow) = ClickChainStageRow(index, frame.title,
        listOf(ClickChainStageChoice(frame.key, "代表画面")), frame.key, frame.reviewed)
    private fun request(frame: ClickChainFrameRow) = ClickChainThumbnailRequest(frame.key, requireNotNull(frame.thumbnailSource))
    private fun description(frame: ClickChainFrameRow) = "${frame.title}，${if (frame.reviewed) "已复核画面" else "原始画面 · 未复核"}缩略图"
    private fun frameEditor(key: String) = ClickChainFrameEditor(key, "合成画面", null, emptyList(), null, null, false)
    private fun bitmap() = Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
    private fun resetFactory() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
