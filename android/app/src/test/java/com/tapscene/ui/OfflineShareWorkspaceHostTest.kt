package com.tapscene.ui

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.ReleaseSummary
import com.tapscene.sharing.OfflineShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercises the real one-shot launch boundary without starting a chooser or granting another app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class OfflineShareWorkspaceHostTest {
    @Test fun chooserRequestIsConsumedOnceAndNeverRestoredAfterExit() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val owner = ViewModelStore()
        val workspace = withContext(Dispatchers.Main) {
            ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[ReleaseWorkspace::class.java]
        }
        (ReleaseWorkspace::class.java.getDeclaredField("task").apply { isAccessible = true }.get(workspace) as Job).join()
        try {
            @Suppress("UNCHECKED_CAST") val state = ReleaseWorkspace::class.java.getDeclaredField("mutableState")
                .apply { isAccessible = true }.get(workspace) as MutableStateFlow<ReleaseUiState>
            val release = ReleaseSummary("selected", "Synthetic sealed release", "0".repeat(64), 1, 1, 100, "local")
            val share = OfflineShare("one-shot", release.id, Uri.parse("content://synthetic/offline"), System.currentTimeMillis() + 60_000)
            val ready = ReleaseUiState(releases = listOf(release), lastSealedId = "unrelated-newer-seal", deliverySelection = DeliverySelection(release.id, release.contentDigest, "source-project"), pendingShare = share)
            withContext(Dispatchers.Main) {
                state.value = ready
                check(workspace.beginShareChooser(null) == null && state.value.pendingShare == null)
                state.value = ready
                check(workspace.beginShareChooser("another-release") == null && state.value.pendingShare == null)
                state.value = ready
                check(workspace.beginShareChooser(release.id) == share)
                check(workspace.beginShareChooser(release.id) == null && state.value.shareChooserOpen)
                workspace.shareChooserLaunched()
                workspace.finishShareChooser()
                check(!state.value.shareChooserOpen && state.value.pendingShare == null)
                check(workspace.beginShareChooser(release.id) == null)
                check(state.value.message!!.contains("返回不代表已发送"))
                state.value = ready
                workspace.leaveSharePage()
                check(workspace.beginShareChooser(release.id) == null)
                state.value = ready.copy(pendingShare = share.copy(expiresAt = 1))
                check(workspace.beginShareChooser(release.id) == null && state.value.pendingShare == null)
                state.value = ready.copy(deliverySelection = ready.deliverySelection!!.copy(contentDigest = "1".repeat(64)))
                check(workspace.beginShareChooser(release.id) == null)
                state.value = ready.copy(releases = listOf(release.copy(origin = "imported")))
                check(workspace.beginShareChooser(release.id) == null)
                state.value = ready
                workspace.beginShareChooser(release.id)
                workspace.finishShareChooser(failed = true)
                check(workspace.beginShareChooser(release.id) == null && state.value.message!!.contains("无法打开"))
                // Completion may publish the file while execute still re-reads the library in its finally.
                val prepared = java.io.File(app.cacheDir, "delivery-ready.tapscene").apply { writeText("synthetic") }
                fun readyExport(busy: Boolean) {
                    state.value = ready.copy(pendingShare = null, exportFile = prepared, busy = busy)
                    ReleaseWorkspace::class.java.getDeclaredField("exportDeliverySelection").apply { isAccessible = true }
                        .set(workspace, ready.deliverySelection)
                    ReleaseWorkspace::class.java.getDeclaredField("exportDigest").apply { isAccessible = true }
                        .set(workspace, "0".repeat(64))
                }
                readyExport(busy = true)
                workspace.cancel()
                check(state.value.exportFile == null && prepared.isFile)
                readyExport(busy = false)
                check(!workspace.beginExportPicker(deliveryPageVisible = false))
                check(state.value.exportFile == null && prepared.isFile)
                readyExport(busy = false)
                state.value = state.value.copy(deliverySelection = ready.deliverySelection!!.copy(contentDigest = "1".repeat(64)))
                check(!workspace.beginExportPicker() && state.value.exportFile == null)
                readyExport(busy = false)
                check(workspace.beginExportPicker())
                workspace.leaveDeliveryPage()
                check(ReleaseWorkspace::class.java.getDeclaredField("pendingExportFile").apply { isAccessible = true }.get(workspace) == prepared)
                check(!workspace.selectDeliveryRelease("another-release")) // The original picker retains its owner.
                val writing = Job()
                ReleaseWorkspace::class.java.getDeclaredField("task").apply { isAccessible = true }.set(workspace, writing)
                state.value = state.value.copy(busy = true)
                workspace.leaveDeliveryPage()
                check(writing.isActive && prepared.isFile)
                writing.cancel()
                workspace.cancelExportPicker()
            }
            println("HOST_OFFLINE_SHARE_UI one-shot/route/version/expiry/failure-return PASS; no system chooser launched")
        } finally { withContext(Dispatchers.Main) { owner.clear() } }
    }
}
