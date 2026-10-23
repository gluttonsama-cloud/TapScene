package com.tapscene.clickplan

import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.data.HostFileSyncShadow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    @Test fun reviewedThumbnailRequiresExactOutputAndBoundsDecodeWithoutChangingReview() = runBlocking {
        val store = ClickChainReviewStore(RuntimeEnvironment.getApplication())
        val run = UUID.randomUUID().toString()
        val initial = store.open(run, "a".repeat(64), 1)
        val (working, folder) = store.beginOutput(initial)
        check(folder.mkdir())
        val file = File(folder, "candidate-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val bytes = file.readBytes()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val output = store.output(working, file, sha, 1080, 1920)
        val frame = ClickChainFrameDraft("$run:1", "b".repeat(64), "画面", output = output, reviewed = true)
        val saved = store.save(working.copy(serial = working.serial + 1, frames = mapOf(frame.key to frame)))
        val thumbnail = readClickChainThumbnail(store.outputFile(run, output), output)
        check(thumbnail.width in 1..1080 && thumbnail.height in 1..1920)
        check(thumbnail.width.toLong() * thumbnail.height <= 220_000)
        check(thumbnail.getPixel(0, 0) == Color.BLACK)
        thumbnail.recycle()
        check(store.readExisting(run) == saved)
        check(runCatching { readClickChainThumbnail(file, output.copy(sha256 = "0".repeat(64))) }.isFailure)
        check(runCatching { readClickChainThumbnail(file, output.copy(width = 1081)) }.isFailure)
        check(runCatching { readClickChainThumbnail(file, output.copy(width = Int.MAX_VALUE)) }.isFailure)
        check(runCatching { readClickChainThumbnail(file, output.copy(height = 0)) }.isFailure)
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        check(runCatching { readClickChainThumbnail(store.outputFile(run, output), output) }.isFailure)
        file.writeBytes(bytes)
        var cancelled = false
        val job = launch {
            cancel()
            try { readClickChainThumbnail(file, output) }
            catch (_: CancellationException) { cancelled = true }
        }
        job.join()
        check(cancelled && file.readBytes().contentEquals(bytes) && store.readExisting(run) == saved)
    }

    @Test fun anotherSegmentRotatesOwnerAndRetainsExactReviewedOutputOwnership() {
        val store = ClickChainReviewStore(RuntimeEnvironment.getApplication())
        val run = UUID.randomUUID().toString()
        val initial = store.open(run, "a".repeat(64), 4)
        val (working, folder) = store.beginOutput(initial)
        check(folder.mkdir())
        val file = File(folder, "candidate-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        try { FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val output = store.output(working, file, sha, 4, 4)
        val frame = ClickChainFrameDraft("$run:1", "b".repeat(64), "已复核画面", output = output, reviewed = true)
        val committed = store.save(working.copy(serial = working.serial + 1, lastAction = "2",
            frames = mapOf(frame.key to frame), operationId = UUID.randomUUID().toString(),
            framesOnly = true, selectedFrameKeys = setOf(frame.key), markLastTerminal = true))
        val next = store.startAnotherSegment(committed, committed.sourceSha256, 4)
        check(next.owner != committed.owner && next.serial > committed.serial && next.operationId == null)
        check(next.firstAction == "3" && next.lastAction == "4" && !next.framesOnly && !next.markLastTerminal)
        check(next.selectedFrameKeys == null && next.frames == committed.frames && next.actions == committed.actions)
        check(next.outputJobs == committed.outputJobs && store.outputFile(run, output) == file)
        check(runCatching { store.startAnotherSegment(committed, committed.sourceSha256, 4) }.isFailure)
        check(runCatching { store.save(committed.copy(serial = next.serial + 100)) }.isFailure)
        store.finishOutput(committed, folder)
        check(store.readExisting(run) == next && file.isFile)
        check(store.open(run, committed.sourceSha256, 4).frames == next.frames)
    }

    @Test fun anotherSegmentRejectsUnfrozenChangedCancelledAndStaleInputsWithoutMutation() {
        val store = ClickChainReviewStore(RuntimeEnvironment.getApplication())
        val initial = store.open(UUID.randomUUID().toString(), "a".repeat(64), 2)
        check(runCatching { store.startAnotherSegment(initial, initial.sourceSha256, 2) }.isFailure)
        val committed = store.save(initial.copy(serial = initial.serial + 1,
            operationId = UUID.randomUUID().toString()))
        check(runCatching { store.startAnotherSegment(committed, "b".repeat(64), 2) }.isFailure)
        check(runCatching { store.startAnotherSegment(committed, committed.sourceSha256, 2) { false } }.isFailure)
        check(store.readExisting(initial.runId) == committed)
        val newer = store.open(initial.runId, initial.sourceSha256, 2)
        check(runCatching { store.startAnotherSegment(committed, committed.sourceSha256, 2) }.isFailure)
        check(store.readExisting(initial.runId) == newer)
        val restarted = store.startAnotherSegment(newer, newer.sourceSha256, 2)
        check(restarted.firstAction == "1" && restarted.lastAction == "2")
    }

    @Test fun uncertainRestartIsRecoveredFromActualJournalAndCannotReplayCompletedWriter() {
        val store = ClickChainReviewStore(RuntimeEnvironment.getApplication())
        val initial = store.open(UUID.randomUUID().toString(), "a".repeat(64), 2)
        val committed = store.save(initial.copy(serial = initial.serial + 1, lastAction = "1",
            operationId = UUID.randomUUID().toString()))
        HostFileSyncShadow.failNextDirectorySyncAfterWrite(File(RuntimeEnvironment.getApplication().noBackupFilesDir,
            "click-chain-reviews/${initial.runId}"))
        check(runCatching { store.startAnotherSegment(committed, committed.sourceSha256, 2) }.isFailure)
        val actual = checkNotNull(store.readExisting(initial.runId))
        check(actual.owner != committed.owner && actual.operationId == null && actual.firstAction == "2")
        check(runCatching { store.save(committed.copy(serial = actual.serial + 10)) }.isFailure)
        check(runCatching { store.startAnotherSegment(committed, committed.sourceSha256, 2) }.isFailure)
        val reopened = store.open(initial.runId, initial.sourceSha256, 2)
        check(reopened.operationId == null && reopened.firstAction == "2" && reopened.lastAction == "2")
    }

}
