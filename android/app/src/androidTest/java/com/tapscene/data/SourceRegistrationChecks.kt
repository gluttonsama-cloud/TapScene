package com.tapscene.data

import android.content.Context
import android.content.ContextWrapper
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SourceMetadata
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Platform metadata/atomic-file checks with two real WorkspaceStore instances and deliberately
 * stale UI snapshots. Synthetic source bytes are not valid video; no picker, decoder, recorder
 * or consent success is claimed. Each run owns a separate noBackup UUID directory.
 */
object SourceRegistrationChecks {
    fun run(context: Context, status: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "source-registration-checks-${UUID.randomUUID()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        var failure: Throwable? = null
        try {
            checkInterleavedDrafts(isolated, status)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanup = runCatching {
                check(root.canonicalFile.parentFile == parent)
                check(root.deleteRecursively() && !root.exists()) { "Source registration checks cleanup failed" }
            }.exceptionOrNull()
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private fun checkInterleavedDrafts(context: Context, status: (String) -> Unit) {
        val projectId = UUID.randomUUID().toString()
        val editor = WorkspaceStore(context, projectId)
        val recorder = WorkspaceStore(context, projectId)
        val a = syntheticSource(context.noBackupFilesDir, "A")
        val b = syntheticSource(context.noBackupFilesDir, "B")
        val originalA = File(context.noBackupFilesDir, a.privateRelativePath).readBytes()
        val originalB = File(context.noBackupFilesDir, b.privateRelativePath).readBytes()
        check(editor.append(a) == a)
        val staleSnapshot = editor.read()
        val staleA = staleSnapshot.single()
        check(staleA.frameTimeUs == 0L && staleA.masks.isEmpty())

        // A recording registers B after the editor captured its older A-only snapshot.
        check(recorder.append(b) == b)
        val bMask = OpaqueMask(0.10f, 0.15f, 0.50f, 0.60f)
        recorder.updateSource(b.sourceId) { it.copy(frameTimeUs = 250_000L, masks = listOf(bMask)) }
        val retainedB = recorder.read().single { it.source.sourceId == b.sourceId }

        var saved = editor.updateSource(staleA.source.sourceId) { current -> current.copy(frameTimeUs = 123_000L) }
        check(saved.size == 2 && saved.single { it.source.sourceId == b.sourceId } == retainedB)
        check(saved.single { it.source.sourceId == a.sourceId }.frameTimeUs == 123_000L)

        val firstMask = OpaqueMask(0.20f, 0.25f, 0.60f, 0.70f)
        val selectedMasks = staleA.masks + firstMask
        saved = editor.updateSource(staleA.source.sourceId) { current -> current.copy(masks = selectedMasks) }
        check(saved.single { it.source.sourceId == a.sourceId }.let { it.frameTimeUs == 123_000L && it.masks == selectedMasks })
        check(saved.single { it.source.sourceId == b.sourceId } == retainedB)

        // Retry uses only the captured pending mask edit, never that stale whole workspace or
        // its older frame value. Meanwhile the latest saved frame has advanced independently.
        val pending = staleA.copy(masks = listOf(firstMask, OpaqueMask(0.05f, 0.05f, 0.20f, 0.20f)))
        recorder.updateSource(a.sourceId) { it.copy(frameTimeUs = 456_000L) }
        saved = editor.updateSource(pending.source.sourceId) { current -> current.copy(masks = pending.masks) }
        val editedA = saved.single { it.source.sourceId == a.sourceId }
        check(editedA.frameTimeUs == 456_000L && editedA.masks == pending.masks)
        check(saved.single { it.source.sourceId == b.sourceId } == retainedB)
        check(WorkspaceStore(context, projectId).read() == saved)
        status("PASS source edits: stale A-only snapshot, concurrent B append, frame/mask edits and mask retry all preserve B and latest frame")

        // Repeated registration with the same source is idempotent and confirms directory
        // durability without resetting frame choice or privacy masks.
        check(recorder.append(a) == a)
        check(recorder.confirmRegistration(a) == a)
        check(editor.read() == saved && editor.read().single { it.source.sourceId == a.sourceId } == editedA)
        check(runCatching { recorder.append(a.copy(metadata = a.metadata.copy(sha256 = "f".repeat(64)))) }.isFailure)
        check(editor.read() == saved) { "An ID conflict changed existing source metadata or masks" }
        status("PASS source registration retry: same ID preserves frame/masks, fresh-store reload matches, different content with same ID is rejected")

        // Remove only A from the CURRENT list, even though the caller originally knew only A.
        val afterRemoval = editor.update { current -> current.filterNot { it.source.sourceId == staleA.source.sourceId } }
        check(afterRemoval == listOf(retainedB) && recorder.read() == listOf(retainedB))
        check(runCatching { editor.updateSource(a.sourceId) { it.copy(masks = emptyList()) } }.isFailure)
        check(recorder.read() == listOf(retainedB))
        check(File(context.noBackupFilesDir, a.privateRelativePath).readBytes().contentEquals(originalA))
        check(File(context.noBackupFilesDir, b.privateRelativePath).readBytes().contentEquals(originalB))
        status("PASS source removal: stale caller removes only A, retains B and rejects later edits to A; source bytes remain unchanged")
    }

    private fun syntheticSource(root: File, label: String): ImportedSource {
        val id = UUID.randomUUID().toString()
        val relativePath = "sources/$id.mp4"
        val file = File(root, relativePath)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdir())
        check(file.createNewFile())
        val bytes = "synthetic source $label".toByteArray(Charsets.UTF_8)
        file.writeBytes(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return ImportedSource(id, relativePath, "$label.mp4", SourceMetadata("video/avc", bytes.size.toLong(), digest,
            32, 32, 0, 1_000_000L))
    }
}
