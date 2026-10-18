package com.tapscene.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tapscene.data.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Production cancellation coordinator with controlled durable receipts; no device or crash claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class AiImportCancellationHostTest {
    @Test fun cancellingUnknownReceiptRetryAlwaysReadsActualCommitBeforeCleanup() = runBlocking {
        withTimeout(30_000) {
            HostSqlite.configure()
            for (status in listOf("committed", "ready")) {
                val access = ReceiptAccess(status)
                val owner = ViewModelStore()
                val app = RuntimeEnvironment.getApplication()
                val model = withContext(Dispatchers.Main) {
                    ViewModelProvider(owner, object : ViewModelProvider.Factory {
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            @Suppress("UNCHECKED_CAST")
                            return AiDraftWorkspace(app, SavedStateHandle(mapOf(AiDraftWorkspace.SESSION to access.id)))
                                .also { it.importAccess = access } as T
                        }
                    })[AiDraftWorkspace::class.java]
                }
                try {
                    withContext(Dispatchers.Main) { model.openImport() }
                    model.importState.first { !it.busy }
                    check(model.importState.value.outcomeUnknown)
                    access.failRead = false
                    access.blockNextRead = true
                    withContext(Dispatchers.Main) { model.readImportResult() }
                    access.readEntered.await()
                    check(model.importState.value.busy && model.importState.value.outcomeUnknown)
                    withContext(Dispatchers.Main) { model.cancelImport() }
                    model.importState.first { !it.busy }
                    check(!model.importState.value.outcomeUnknown)
                    check(access.cancelCalls == if (status == "committed") 0 else 1)
                    check(model.importState.value.result?.status == if (status == "committed") "committed" else "cancelled")
                    check(model.importState.value.result?.projectExists == (status == "committed"))
                } finally { withContext(Dispatchers.Main) { owner.clear() } }
            }
        }
    }

    private class ReceiptAccess(status: String) : AiDraftImportAccess {
        val id = UUID.randomUUID().toString()
        var receipt = AiDraftImportResult(id, status, if (status == "committed") UUID.randomUUID().toString() else null, status == "committed")
        var failRead = true
        var blockNextRead = false
        var cancelCalls = 0
        val readEntered = CompletableDeferred<Unit>()
        override suspend fun result(sessionId: String): AiDraftImportResult {
            check(sessionId == id)
            if (failRead) throw IOException("Receipt unavailable")
            if (blockNextRead) { blockNextRead = false; readEntered.complete(Unit); awaitCancellation() }
            return receipt
        }
        override suspend fun cancel(sessionId: String): AiDraftImportResult {
            check(sessionId == id && receipt.status != "committed")
            cancelCalls++
            receipt = receipt.copy(status = "cancelled")
            return receipt
        }
        override suspend fun pending() = if (receipt.status == "ready") listOf(id) else emptyList()
        override suspend fun baselines() = emptyList<ReleaseSummary>()
        override suspend fun prepare(input: InputStream): AiDraftImportPreview = error("Must not re-import")
        override suspend fun preview(sessionId: String, baselineReleaseId: String?): AiDraftImportPreview = error("Must not replace preview")
        override suspend fun readPrepared(sessionId: String): AiDraftImportPreview? = error("Cancelled read cannot load preview")
        override suspend fun commit(sessionId: String, digest: String): AiDraftImportResult = error("Must not recommit")
        override suspend fun image(sessionId: String, assetId: String): File = error("No image request")
    }
}
