package com.tapscene.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.EditorFormKind
import com.tapscene.data.EditorPendingForm
import com.tapscene.data.HostProjectFixture
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.ocr.OcrCancellation
import com.tapscene.ocr.OcrResult
import com.tapscene.ocr.OcrWord
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNativeBitmap

/** Real current PNG and native SQLite; controlled recognition completion tests async ownership.
 * OCR accuracy remains covered by the separate production-native synthetic fixture runner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class TextRegionWorkspaceHostTest {
    @Before fun configureSqlite() = HostSqlite.configure()

    @Test fun exactPixelsLateResultsAndRecoveredFormsKeepTheirSourceAndAuthorIntent() = runBlocking {
        withTimeout(120_000) {
            HostProjectFixture().use { fixture ->
                installSafePng(fixture, fixture.a, Color.BLUE)
                installSafePng(fixture, fixture.b, Color.BLUE)
                val calls = Channel<Call>(Channel.UNLIMITED)
                val pending = mutableListOf<Call>()
                val recognize: suspend (Bitmap, OcrCancellation) -> OcrResult = { bitmap, cancellation ->
                    check(Shadow.extract<Any>(bitmap) is ShadowNativeBitmap)
                    check(bitmap.width == 320 && bitmap.height == 640 && bitmap.getPixel(0, 0) == Color.BLUE)
                    val call = Call(cancellation)
                    calls.send(call)
                    // Deliberately return after cancellation to exercise the production generation guard.
                    withContext(NonCancellable) { call.response.await() }
                }
                suspend fun call(): Call = withTimeout(10_000) { calls.receive().also { pending += it } }
                var owner = ViewModelStore()
                var workspace = main { ProjectWorkspace(fixture.app, recognize).also { owner.put("editor", it) } }
                try {
                    settled(workspace)
                    command(workspace) { openProject(fixture.project) }
                    command(workspace) { openStep(fixture.b) }
                    main { workspace.editTitle("Keep my unsaved title") }
                    staged(workspace)
                    main { workspace.recognizeTextRegions() }
                    val old = call()
                    main { workspace.recognizeTextRegions() }
                    val newest = call()
                    check(old.cancellation.isCancelled)
                    old.response.complete(result("旧建议"))
                    newest.response.complete(result("确定"))
                    suggestions(workspace)
                    check(workspace.state.value.textRegions.candidates.single().text == "确定")
                    check(workspace.state.value.stepDraft!!.title == "Keep my unsaved title")

                    // An ordinary pending form wins over an already displayed candidate.
                    val name = EditorPendingForm(EditorFormKind.NAME, title = "Typing untouched")
                    val locked = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val storeLock = ProjectStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(null)
                    val barrier = async(Dispatchers.IO) { synchronized(storeLock) {
                        locked.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    } }
                    check(locked.await(10, TimeUnit.SECONDS))
                    val selection = main {
                        workspace.selectTextRegion(0)
                        val job = field(workspace, "textRegionTask") as Job
                        workspace.editPendingForm(fixture.project, fixture.b, name)
                        workspace.selectTextRegion(0)
                        job
                    }
                    release.countDown(); barrier.await(); selection.join()
                    staged(workspace)
                    check(workspace.state.value.stepDraft!!.pendingForm == name)
                    main { workspace.editPendingForm(fixture.project, fixture.b, null); workspace.recognizeTextRegions() }
                    val departed = call()
                    command(workspace) { openStep(fixture.a) }
                    departed.response.complete(result("返回"))
                    check(departed.cancellation.isCancelled)
                    command(workspace) { openStep(fixture.b) }
                    check(workspace.state.value.textRegions.candidates.isEmpty() && workspace.state.value.stepDraft!!.pendingForm == null)

                    main { workspace.recognizeTextRegions() }
                    call().response.complete(result("确定"))
                    suggestions(workspace)
                    main { workspace.selectTextRegion(0) }
                    withTimeout(10_000) { workspace.state.first { it.stepDraft?.pendingForm?.textRegionSource != null } }
                    staged(workspace)
                    val chosen = checkNotNull(workspace.state.value.stepDraft!!.pendingForm)
                    check(!chosen.endsDemo && chosen.targetStepId == null)
                    check(chosen.label == "确定" && chosen.textRegionSource!!.stepId == fixture.b)
                    val before = fixture.snapshot()
                    command(workspace) { savePendingStepForm() }
                    check(fixture.snapshot() == before && workspace.state.value.stepDraft!!.pendingForm == chosen)
                    main { workspace.editPendingForm(fixture.project, fixture.b, chosen.copy(targetStepId = fixture.a)) }
                    staged(workspace)
                    val routed = workspace.state.value.stepDraft!!.pendingForm!!
                    main { workspace.editPendingForm(fixture.project, fixture.b, routed.copy(label = "Edited name")) }
                    staged(workspace)
                    command(workspace) { undoEditorEdit() }
                    check(workspace.state.value.stepDraft!!.pendingForm == routed)
                    check(fixture.store.readEditorDrafts(fixture.project).getValue(fixture.b).pendingForm == routed)

                    close(fixture, owner, workspace)
                    // Change the current official pixels while the author's old form is at rest.
                    installSafePng(fixture, fixture.b, Color.BLUE, replaceAsset = true)
                    owner = ViewModelStore()
                    workspace = main { ProjectWorkspace(fixture.app, recognize).also { owner.put("editor", it) } }
                    settled(workspace)
                    command(workspace) { openProject(fixture.project) }
                    command(workspace) { openStep(fixture.b) }
                    val recovered = workspace.state.value.stepDraft!!
                    check(recovered.pendingForm == routed && recovered.title == "Keep my unsaved title")
                    check(recovered.conflicts.isEmpty() && !workspace.state.value.canUndoEdit)
                    val changed = fixture.snapshot()
                    command(workspace) { savePendingStepForm() }
                    check(fixture.snapshot() == changed)
                    check(workspace.state.value.stepDraft!!.pendingForm == routed)
                    check(workspace.state.value.message?.contains("画面已更新") == true)

                    // An explicit cancellation enables a fresh selection; no implicit source clearing.
                    main { workspace.editPendingForm(fixture.project, fixture.b, null); workspace.recognizeTextRegions() }
                    call().response.complete(result("返回"))
                    suggestions(workspace)
                    main { workspace.selectTextRegion(0) }
                    withTimeout(10_000) { workspace.state.first { it.stepDraft?.pendingForm?.textRegionSource != null } }
                    staged(workspace)
                    val fresh = workspace.state.value.stepDraft!!.pendingForm!!
                    check(fresh.textRegionSource != routed.textRegionSource)
                    main { workspace.editPendingForm(fixture.project, fixture.b, fresh.copy(targetStepId = fixture.a)) }
                    staged(workspace)
                    fixture.store.deleteStep(fixture.project, fixture.a)
                    command(workspace) { reload() }
                    val missingTarget = workspace.state.value.stepDraft!!.pendingForm!!
                    check(missingTarget.targetStepId == null && !missingTarget.endsDemo && missingTarget.textRegionSource == fresh.textRegionSource)
                    val noTarget = fixture.snapshot()
                    command(workspace) { savePendingStepForm() }
                    check(fixture.snapshot() == noTarget)
                    main { workspace.editPendingForm(fixture.project, fixture.b, missingTarget.copy(endsDemo = true)) }
                    staged(workspace)
                    command(workspace) { savePendingStepForm() }
                    check(workspace.state.value.stepDraft!!.pendingForm == null && !workspace.state.value.stepDraft!!.dirty)
                    check(fixture.snapshot().steps.single().let { it.title == "Keep my unsaved title" && it.hotspots.single().endLabel == "演示结束" })
                    println("HOST_TEXT_REGION current native PNG; late/retry/navigation; intent; undo; recovery/image change; removed target; save PASS")
                } finally {
                    pending.forEach { it.response.complete(result("取消")) }
                    close(fixture, owner, workspace)
                }
            }
        }
    }

    private class Call(val cancellation: OcrCancellation, val response: CompletableDeferred<OcrResult> = CompletableDeferred())
    private fun result(text: String) = OcrResult(320, 640, listOf(OcrWord(text, 120, 400, 160, 420, 95f, 1, 1)), false)
    private suspend fun suggestions(workspace: ProjectWorkspace) {
        withTimeout(10_000) { workspace.state.first { !it.textRegions.working && it.textRegions.candidates.isNotEmpty() } }
    }
    private suspend fun staged(workspace: ProjectWorkspace) {
        withTimeout(10_000) { workspace.state.first { it.stepDraft?.recoveryStatus == DraftRecoveryStatus.STAGED } }
        settled(workspace)
    }
    private suspend fun command(workspace: ProjectWorkspace, block: ProjectWorkspace.() -> Unit) {
        main { workspace.block() }; settled(workspace); check(!workspace.state.value.loadFailed)
    }
    private suspend fun settled(workspace: ProjectWorkspace) {
        withTimeout(10_000) {
            workspace.state.first { !it.busy && it.stepDraft?.recoveryStatus != DraftRecoveryStatus.STAGING }
            main { listOfNotNull(field(workspace, "task") as Job?, field(workspace, "stagingTask") as Job?) }.forEach { it.join() }
        }
    }
    private suspend fun close(fixture: HostProjectFixture, owner: ViewModelStore, workspace: ProjectWorkspace) {
        withContext(NonCancellable) {
            val jobs = main {
                val active = listOfNotNull(field(workspace, "task") as Job?, field(workspace, "stagingTask") as Job?, field(workspace, "textRegionTask") as Job?)
                owner.clear(); active
            }
            withTimeout(10_000) { jobs.forEach { it.join() } }
            fixture.closeStore(field(workspace, "store") as ProjectStore)
        }
    }
    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main.immediate) {
        check(Looper.myLooper() == Looper.getMainLooper()); block()
    }
    private fun field(workspace: ProjectWorkspace, name: String): Any? = ProjectWorkspace::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(workspace)

    private fun installSafePng(fixture: HostProjectFixture, stepId: String, color: Int, replaceAsset: Boolean = false) {
        val step = fixture.snapshot().steps.single { it.id == stepId }
        val assetId = if (replaceAsset) fixture.id() else step.asset.id
        val path = "project-assets/${fixture.project}/$assetId.png"
        val file = File(fixture.root, path).apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        fixture.database {
            if (replaceAsset) execSQL("INSERT INTO local_assets VALUES(?,?,?,?,?,?,?)", arrayOf(assetId, fixture.project, path, sha, file.length(), 320, 640))
            else execSQL("UPDATE local_assets SET sha256=?,byte_length=?,width=320,height=640 WHERE asset_id=?", arrayOf(sha, file.length(), assetId))
            execSQL("UPDATE states SET input_asset_id=?,base_width=320,base_height=640 WHERE state_id=?", arrayOf(assetId, stepId))
            if (replaceAsset) execSQL("UPDATE projects SET draft_revision=draft_revision+1 WHERE project_id=?", arrayOf(fixture.project))
        }
    }
}
