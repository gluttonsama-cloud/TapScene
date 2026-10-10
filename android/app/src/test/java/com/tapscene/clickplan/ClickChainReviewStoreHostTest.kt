package com.tapscene.clickplan

import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.data.HostFileSyncShadow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real private JSON, native PNG and exact output ownership; no real recording or author review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35],shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClickChainReviewStoreHostTest {
    @Before fun start() = HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
    @After fun close() = HostFileSyncShadow.reset()

    @Test fun authorInputsReviewedPixelsAndOutputOwnershipSurviveReopenWhileOldWritersFail() {
        val app = RuntimeEnvironment.getApplication()
        val store = ClickChainReviewStore(app)
        val run = UUID.randomUUID().toString();val sha = "a".repeat(64);val key = "$run:1"
        var draft = store.open(run,sha,2)
        draft = store.save(draft.copy(serial = draft.serial + 1,title = "保留名称",firstAction = "",lastAction = "2"))
        val (working,folder) = store.beginOutput(draft);draft = working
        check(folder.mkdir())
        val file = File(folder,"candidate-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it));it.fd.sync() };bitmap.recycle()
        val actualSha = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        HostFileSyncShadow.beginProductionEvidence()
        val output = store.output(draft,file,actualSha,4,4)
        HostFileSyncShadow.assertDirectorySyncEvidence()
        draft = store.save(draft.copy(serial = draft.serial + 1,frames = mapOf(key to ClickChainFrameDraft(key,"b".repeat(64),"已核对画面",output = output,reviewed = true))))
        draft = store.finishOutput(draft,folder)
        val old = draft
        val reopened = store.open(run,sha,2)
        check(reopened.owner != old.owner && reopened.title == "保留名称" && reopened.firstAction == "")
        check(reopened.frames[key]?.reviewed == true && store.outputFile(run,output).readBytes().contentEquals(file.readBytes()))
        check(runCatching { store.save(old.copy(serial = old.serial + 100,title = "迟到覆盖")) }.isFailure)
        check(store.readExisting(run)?.title == "保留名称")
        check(runCatching { store.open(run,"c".repeat(64),2) }.isFailure)
        // Deliberate reload uses the new owner; a rejected old writer cannot trap future editing.
        check(runCatching { store.open(run,sha,2) { false } }.isFailure)
        check(store.readExisting(run)?.owner == reopened.owner)
        val reloaded = store.open(run,sha,2)
        val resumed = store.save(reloaded.copy(serial = reloaded.serial + 1,title = "重读后继续"))
        check(resumed.title == "重读后继续" && resumed.frames[key]?.reviewed == true)
        val (pending,abandoned) = store.beginOutput(resumed)
        check(abandoned.mkdir());File(abandoned,"candidate-${UUID.randomUUID()}.png").writeBytes(byteArrayOf(1,2,3))
        store.releaseOutput(run,abandoned)
        val after = store.open(run,sha,2)
        check(!abandoned.exists() && file.exists() && after.frames[key]?.output == output)
        check(pending.outputJobs.isNotEmpty())
        check(runCatching { store.outputFile(UUID.randomUUID().toString(),output) }.isFailure)
    }
}
