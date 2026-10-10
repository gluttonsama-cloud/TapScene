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
            val ready = ReleaseUiState(releases = listOf(release), lastSealedId = release.id, pendingShare = share)
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
                state.value = ready.copy(releases = listOf(release.copy(origin = "imported")))
                check(workspace.beginShareChooser(release.id) == null)
                state.value = ready
                workspace.beginShareChooser(release.id)
                workspace.finishShareChooser(failed = true)
                check(workspace.beginShareChooser(release.id) == null && state.value.message!!.contains("无法打开"))
            }
            println("HOST_OFFLINE_SHARE_UI one-shot/route/version/expiry/failure-return PASS; no system chooser launched")
        } finally { withContext(Dispatchers.Main) { owner.clear() } }
    }
}
