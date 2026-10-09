package com.tapscene.recording

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Platform filesystem/recovery seam checks, isolated from the author's sources. Synthetic bytes
 * below are deliberately NOT recordings. These checks do not exercise consent, MediaProjection,
 * MediaRecorder, phone rotation, lock screen, notification taps or a real process kill.
 */
object RecordingRecoveryTest {
    suspend fun run(context: Context, status: (String) -> Unit) {
        check(!RecordingCoordinator.state.value.isBusy) { "Do not run recording recovery checks during capture" }
        val base = context.noBackupFilesDir.canonicalFile
        val root = File(base, "recording-checks-${UUID.randomUUID()}")
        check(root.mkdir() && root.canonicalFile.parentFile == base)
        val unexpectedServiceStarts = AtomicInteger(0)
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
            override fun startForegroundService(service: Intent): ComponentName? {
                unexpectedServiceStarts.incrementAndGet()
                error("Recovery checks must never launch a real capture service")
            }
        }
        try {
            checkLayouts()
            status("PASS recording layout: portrait, landscape and long-screen fit; no callback-to-PTS mapping")
            val store = RecordingJournalStore(isolated)
            val projectId = UUID.randomUUID().toString()
            var order = System.currentTimeMillis()
            fun journal(phase: JournalPhase) = RecordingJournal(
                UUID.randomUUID().toString(), projectId, UUID.randomUUID().toString(), phase, ++order,
                elapsedMs = 2_000, width = 1080, height = 2400, fps = 30, bitrate = 4_000_000,
                latestContentLayout = RecordingContentLayout.fit(1080, 2400, 1440, 3200),
            )

            val interrupted = journal(JournalPhase.Recording)
            store.create(interrupted)
            store.part(interrupted.sessionId).writeBytes(byteArrayOf(1, 2, 3))
            RecordingCoordinator.recover(isolated)
            val recovered = await(interrupted.sessionId) { it.phase == RecordingPhase.Interrupted }
            check(recovered.stopReason == RecordingStopReason.ProcessInterrupted && !recovered.canRetry)
            check(recovered.elapsedMs == 2_000L)
            check(!store.part(interrupted.sessionId).exists() && !store.sealed(interrupted.sessionId).exists())
            check(store.readAll().single().phase == JournalPhase.Interrupted)
            status("PASS recording recovery: unsealed interrupted session is never registered/resumed and raw is cleaned")

            val sealed = journal(JournalPhase.Stopping)
            store.create(sealed)
            val bytes = byteArrayOf(8, 9, 10, 11)
            store.part(sealed.sessionId).writeBytes(bytes)
            val sealedFile = store.seal(sealed.sessionId)
            check(sealedFile.readBytes().contentEquals(bytes) && !store.part(sealed.sessionId).exists())
            store.save(sealed.copy(phase = JournalPhase.Sealed))
            check(runCatching { store.seal(sealed.sessionId) }.isFailure)
            val reloaded = store.readAll().single { it.sessionId == sealed.sessionId }
            check(reloaded.sourceId == sealed.sourceId && reloaded.latestContentLayout == sealed.latestContentLayout)
            // Simulate an early tap while the UI still shows the previous non-retryable session.
            // The coordinator must discover sealed work before accepting this new fake grant.
            val countBeforeStart = store.readAll().size
            RecordingCoordinator.start(isolated, UUID.randomUUID().toString(), Activity.RESULT_OK, Intent())
            withTimeout(10_000) { RecordingCoordinator.awaitCommandBoundary() }
            check(unexpectedServiceStarts.get() == 0)
            check(RecordingCoordinator.state.value.sessionId == sealed.sessionId && RecordingCoordinator.state.value.canRetry)
            check(store.readAll().size == countBeforeStart)
            status("PASS recording start race: persisted sealed work blocks a stale-UI start without consuming consent or launching a service")
            RecordingCoordinator.recover(isolated)
            val retryable = await(sealed.sessionId) { it.phase == RecordingPhase.Interrupted && it.canRetry }
            check(retryable.sourceId == sealed.sourceId && sealedFile.exists())
            check(retryable.mapping == RecordingMapping.Unknown)
            RecordingCoordinator.discardSealed(isolated)
            await(sealed.sessionId) { it.phase == RecordingPhase.Interrupted && !it.canRetry }
            check(!sealedFile.exists())
            check(store.readAll().single { it.sessionId == sealed.sessionId }.phase == JournalPhase.Discarded)
            // Duplicate cleanup is harmless; it cannot adopt another session or source.
            RecordingCoordinator.discardSealed(isolated)
            status("PASS recording recovery: stable source ID, sealed-only retry eligibility, explicit discard and duplicate cleanup")

            val registered = journal(JournalPhase.Registered)
            store.create(registered)
            store.part(registered.sessionId).writeBytes(bytes)
            val ownedRaw = store.seal(registered.sessionId)
            val sources = File(root, "sources").apply { check(mkdir()) }
            val repositorySource = File(sources, "${registered.sourceId}.mp4").apply { writeBytes(bytes) }
            RecordingCoordinator.recover(isolated)
            withTimeout(10_000) {
                RecordingCoordinator.state.first { it.phase == RecordingPhase.Idle && it.sessionId == null }
            }
            check(!ownedRaw.exists() && repositorySource.readBytes().contentEquals(bytes))
            status("PASS recording recovery: registered history returns Idle, never claims an old source still exists, and cleanup preserves sources")

            val corruptId = UUID.randomUUID().toString()
            val corruptDirectory = File(File(root, "recordings"), corruptId).apply { check(mkdir()) }
            File(corruptDirectory, "session.json").writeText("{broken")
            val orphan = File(corruptDirectory, "sealed.mp4").apply { writeBytes(bytes) }
            check(corruptId in store.orphanSessionIds(store.readAll().map { it.sessionId }.toSet()))
            RecordingCoordinator.recover(isolated)
            // State can equal the previous Idle snapshot; wait on a fresh downstream call
            // after recovery using a uniquely newer journal, rather than infer completion from it.
            val checkpoint = journal(JournalPhase.Failed)
            store.create(checkpoint)
            RecordingCoordinator.recover(isolated)
            await(checkpoint.sessionId) { it.phase == RecordingPhase.Interrupted }
            check(!orphan.exists() && repositorySource.exists())
            check(runCatching { store.part("../sources") }.isFailure)
            val linkId = UUID.randomUUID().toString()
            val link = File(File(root, "recordings"), linkId)
            Os.symlink(sources.absolutePath, link.absolutePath)
            check(runCatching { store.part(linkId) }.isFailure)
            check(link.delete())
            status("PASS recording storage: corrupt journal cannot authorize adoption; traversal and symlink are rejected")
        } finally {
            check(root.canonicalFile.parentFile == base)
            check(root.deleteRecursively() && !root.exists()) { "Recording checks cleanup failed" }
        }
    }

    private suspend fun await(sessionId: String, predicate: (RecordingUiState) -> Boolean): RecordingUiState =
        withTimeout(10_000) {
            RecordingCoordinator.state.first { it.sessionId == sessionId && predicate(it) }
        }

    private fun checkLayouts() {
        val portrait = RecordingContentLayout.fit(1080, 2400, 2400, 1080)
        check(close(portrait.left, 0.0) && close(portrait.right, 1080.0))
        check(close(portrait.top, 957.0) && close(portrait.bottom, 1443.0))
        val landscape = RecordingContentLayout.fit(2400, 1080, 1080, 2400)
        check(close(landscape.left, 957.0) && close(landscape.right, 1443.0))
        check(close(landscape.top, 0.0) && close(landscape.bottom, 1080.0))
        val longScreen = RecordingContentLayout.fit(1080, 2400, 1440, 3200)
        check(close(longScreen.left, 0.0) && close(longScreen.top, 0.0))
        check(close(longScreen.right, 1080.0) && close(longScreen.bottom, 2400.0))
        check(runCatching { RecordingContentLayout.fit(0, 2400, 1080, 2400) }.isFailure)
    }

    private fun close(a: Double, b: Double) = abs(a - b) < 0.000_001
}
