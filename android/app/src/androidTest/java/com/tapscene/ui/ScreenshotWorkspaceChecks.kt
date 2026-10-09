package com.tapscene.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.ProjectStore
import com.tapscene.media.OpaqueMask
import com.tapscene.media.ScreenshotImporter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Real ViewModel operations with a file reader seam; system picker/touch/process kill are separate. */
object ScreenshotWorkspaceChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val root=File(context.noBackupFilesDir,"screenshot-workspace-${UUID.randomUUID()}")
        check(root.mkdir())
        val app=object : Application() {
            init { attachBaseContext(context.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val owner=ViewModelStore()
        val workspace=withContext(Dispatchers.Main.immediate) {
            ViewModelProvider(owner,ViewModelProvider.AndroidViewModelFactory(app))[ScreenshotWorkspace::class.java]
        }
        try {
            val store=ProjectStore(app)
            val importer=ScreenshotImporter(app)
            val source=File(root,"selected.png")
            val pixels=Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            try { source.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { pixels.recycle() }
            // Picker cancellation never calls startImport, so constructing/closing creates no project.
            withContext(Dispatchers.Main.immediate) { check(workspace.close()) }
            check(store.listProjects().isEmpty())
            val entered=CompletableDeferred<Unit>()
            val release=CompletableDeferred<Unit>()
            withContext(Dispatchers.Main.immediate) {
                check(workspace.startImport(null) { entered.complete(Unit); release.await(); importer.importScreenshot(source) })
            }
            entered.await()
            withContext(Dispatchers.Main.immediate) {
                check(!workspace.close())
                check(!workspace.startImport(null) { error("Duplicate import executed") })
                workspace.cancel()
            }
            idle(workspace)
            check(workspace.state.value.draft?.bitmap==null && store.listProjects().isEmpty())
            withContext(Dispatchers.Main.immediate) { check(workspace.close()) }
            withContext(Dispatchers.Main.immediate) { check(workspace.startImport(null) { importer.importScreenshot(source) }) }
            idle(workspace)
            val raw=checkNotNull(workspace.state.value.draft?.bitmap)
            check(!workspace.state.value.canConfirm("a".repeat(64)))
            withContext(Dispatchers.Main.immediate) { workspace.confirm("a".repeat(64)); workspace.makeImage() }
            idle(workspace)
            val first=checkNotNull(workspace.state.value.media.candidate)
            check(workspace.state.value.canConfirm(first.sha256))
            check(!workspace.state.value.canConfirm("0".repeat(64)))
            withContext(Dispatchers.Main.immediate) { workspace.addMask(OpaqueMask(.1f,.1f,.3f,.3f)) }
            check(workspace.state.value.media.candidate==null && !workspace.state.value.canConfirm(first.sha256))
            withContext(Dispatchers.Main.immediate) { workspace.confirm(first.sha256); workspace.makeImage() }
            idle(workspace)
            val output=checkNotNull(workspace.state.value.media.candidate)
            val actual=checkNotNull(workspace.state.value.media.candidateImage)
            check(actual.getPixel(6,9)==Color.BLACK && actual.getPixel(31,47)==Color.CYAN)
            check(raw.getPixel(6,9)==Color.CYAN && store.listProjects().isEmpty())
            withContext(Dispatchers.Main.immediate) { workspace.confirm(output.sha256); workspace.confirm(output.sha256) }
            idle(workspace)
            val saved=checkNotNull(workspace.state.value.completed)
            check(saved.steps.size==1 && store.listProjects().size==1 && store.screenshotCount(saved.project.id)==1)
            // Model the exact result-unknown state after a committed return was interrupted and
            // the immediate reread failed. Editing must freeze; the same output can reconcile.
            val originalState = workspace.state.value
            val input = com.tapscene.data.ReviewedStepInput(output.file,output.sha256,32,48,
                saved.steps.single().origin,checkNotNull(originalState.draft).masks,"screenshot-${originalState.sessionId}")
            check(ScreenshotWorkspace.matchesScreenshotCommit(saved.steps.single(),input))
            check(!ScreenshotWorkspace.matchesScreenshotCommit(saved.steps.single(),input.copy(sha256="0".repeat(64))))
            check(!ScreenshotWorkspace.matchesScreenshotCommit(saved.steps.single(),input.copy(masks=emptyList())))
            @Suppress("UNCHECKED_CAST")
            val flow=ScreenshotWorkspace::class.java.getDeclaredField("mutableState").apply { isAccessible=true }
                .get(workspace) as kotlinx.coroutines.flow.MutableStateFlow<ScreenshotUiState>
            withContext(Dispatchers.Main.immediate) {
                flow.value=originalState.copy(completed=null,saveOutcomeUnknown=true,draft=originalState.draft?.copy(reviewLocked=true))
                workspace.addMask(OpaqueMask(.6f,.6f,.9f,.9f)); workspace.undoMask(); workspace.makeImage()
                check(workspace.state.value.draft?.masks==originalState.draft.masks && workspace.state.value.media.candidate==output)
                workspace.confirm(output.sha256)
            }
            idle(workspace)
            check(workspace.state.value.completed==saved && !workspace.state.value.saveOutcomeUnknown)
            withContext(Dispatchers.Main.immediate) { check(workspace.close()) }
            check(!raw.isRecycled && !actual.isRecycled) // Published pixels must not be recycled under a finishing Compose frame.
            // Rapid reselection cannot be swept by the preceding session's async cleanup.
            withContext(Dispatchers.Main.immediate) { check(workspace.startImport(saved.project.id) { importer.importScreenshot(source) }) }
            idle(workspace)
            withContext(Dispatchers.Main.immediate) { workspace.makeImage() }
            idle(workspace)
            val second=checkNotNull(workspace.state.value.media.candidate)
            delay(50)
            check(second.file.isFile && workspace.state.value.canConfirm(second.sha256))
            withContext(Dispatchers.Main.immediate) { check(workspace.close()) }
            check(store.readProject(saved.project.id)==saved)
            withTimeout(5_000) {
                while (File(root,"image-import-staging").walkTopDown().any { it.isFile } ||
                    File(root,"screenshot-outputs").walkTopDown().any { it.isFile }) delay(10)
            }
            check(File(root,(saved.steps.single().origin as com.tapscene.data.StepOrigin.ImportedImage).source.privateRelativePath).isFile)
            status("PASS screenshot workspace: cancel/busy/duplicate-read gates; no empty project; actual-output digest invalidated on mask edit; exactly-once confirmation; close does not recycle displayed pixels; rapid reselect preserves newer files; private source/output cleanup")
        } finally {
            withContext(NonCancellable+Dispatchers.Main.immediate) { owner.clear() }
            withTimeout(5_000) {
                while (File(root,"image-import-staging").walkTopDown().any { it.isFile } ||
                    File(root,"screenshot-outputs").walkTopDown().any { it.isFile }) delay(10)
            }
            check(root.deleteRecursively())
        }
    }
    private suspend fun idle(workspace: ScreenshotWorkspace) = withTimeout(10_000) {
        workspace.state.first { !it.media.busy }
    }
}
