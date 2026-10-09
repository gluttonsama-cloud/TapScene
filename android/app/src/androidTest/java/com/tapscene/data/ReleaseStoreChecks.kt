package com.tapscene.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** Real SQLite/PNG/filesystem checks. Compile separately from actually running on a device. */
object ReleaseStoreChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "release-checks-${id()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        var failure: Throwable? = null
        try { checkLifecycle(isolated, status) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching {
                check(root.canonicalFile.parentFile == parent)
                check(root.deleteRecursively() && !root.exists())
            }.exceptionOrNull()
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    private suspend fun checkLifecycle(context: Context, status: (String) -> Unit) {
        val projects = ProjectStore(context)
        var store = ReleaseStore(context)
        val input = fixture(context)
        val project = projects.createProject("固定成品", "两种实际出口")
        val p = project.project.id
        val a = projects.addReviewedStep(p, input.copy(captureId = "release-a"), "起点", "检查实际图片和文字").steps.single()
        val b = projects.addReviewedStep(p, input.copy(captureId = "release-b"), "终点").steps.last()
        projects.saveHotspot(p, a.id, label = "提前结束", rect = OpaqueMask(0.1f, 0.2f, 0.4f, 0.5f), endLabel = "已取消")
        var draft = checkNotNull(projects.readProject(p))
        draft = projects.connectStepsInOrder(p, listOf(a.id, b.id), true, draft.project.revision)
        val initialDraft = draft
        rejected { store.createCandidate(p, draft.project.revision - 1) }
        check(store.listCandidates().isEmpty() && store.listReleases().isEmpty())
        var candidate = store.createCandidate(p, draft.project.revision)
        val originalScene = ViewerPackageCodec.writeScene(candidate.scene)
        check(candidate.projectRevision == draft.project.revision && candidate.reviewedStateIds.isEmpty())
        check(!candidate.completedPath && candidate.visitedEdgeIds.isEmpty())
        check(store.createCandidate(p, draft.project.revision).id == candidate.id)
        rejected { store.seal(candidate.id, candidate.contentDigest) }
        rejected { store.reviewState(candidate.id, "0".repeat(64), a.id) }
        rejected { store.reviewState(candidate.id, candidate.contentDigest, id()) }
        val next = candidate.scene.edges.single { it.trigger == "continue" }
        val end = candidate.scene.edges.single { it.toStateId == null }
        rejected { store.recordTraversal(candidate.id, candidate.contentDigest, next.id, true) }
        rejected { store.recordTraversal(candidate.id, candidate.contentDigest, null, true) }
        check(!checkNotNull(store.readCandidate(p)).completedPath)
        status("PASS release preflight: stale draft, missing review, foreign state/digest and forged completion do not create a sealed version")

        draft = projects.renameProject(p, "后改草稿", "新目标")
        projects.updateStep(p, a.id, "后改步骤", "新说明")
        check(ViewerPackageCodec.writeScene(checkNotNull(store.readCandidate(p)).scene).contentEquals(originalScene))
        rejected { store.createCandidate(p, draft.project.revision) }
        check(store.listCandidates().single().id == candidate.id)
        candidate = store.reviewState(candidate.id, candidate.contentDigest, a.id)
        store = ReleaseStore(context) // Reconstruct store to exercise persisted review recovery.
        candidate = checkNotNull(store.readCandidate(p))
        check(candidate.reviewedStateIds == setOf(a.id))
        check(candidate.projectRevision == initialDraft.project.revision)
        candidate = store.reviewState(candidate.id, candidate.contentDigest, b.id)
        candidate = store.reviewSummary(candidate.id, candidate.contentDigest)
        candidate = store.reviewFileList(candidate.id, candidate.contentDigest)
        rejected { store.seal(candidate.id, candidate.contentDigest) }
        store.candidateAssetFile(candidate.id, a.id)
        candidate = store.recordTraversal(candidate.id, candidate.contentDigest, null, false)
        store.candidateAssetFile(candidate.id, b.id)
        candidate = store.recordTraversal(candidate.id, candidate.contentDigest, next.id, false)
        check(candidate.completedPath && candidate.traversalEnded && candidate.visitedEdgeIds == setOf(next.id))
        rejected { store.recordTraversal(candidate.id, candidate.contentDigest, end.id, true) }
        rejected { store.seal(candidate.id, candidate.contentDigest) }
        candidate = store.rewindTraversal(candidate.id, candidate.contentDigest)
        check(candidate.traversalStateId == a.id && !candidate.traversalEnded && candidate.visitedEdgeIds == setOf(next.id))
        candidate = store.recordTraversal(candidate.id, candidate.contentDigest, end.id, false)
        check(candidate.traversalEnded && candidate.traversalEndEdgeId == end.id)
        candidate = store.rewindTraversal(candidate.id, candidate.contentDigest)
        check(candidate.traversalStateId == a.id && candidate.traversalHistory == listOf(a.id) && !candidate.traversalEnded)
        check(candidate.visitedEdgeIds == setOf(next.id, end.id))
        status("PASS release review recovery: actual fixed text/media, persisted confirmations, contiguous edge coverage and historical backtracking")

        // Actual bytes are rechecked after confirmations, including immediately before sealing.
        val actual = store.candidateAssetFile(candidate.id, a.id)
        val bytes = actual.readBytes()
        actual.writeBytes(bytes + byteArrayOf(1))
        rejected { store.seal(candidate.id, candidate.contentDigest) }
        actual.writeBytes(bytes)
        val sealed = store.seal(candidate.id, candidate.contentDigest)
        check(sealed.origin == "local" && sealed.stepCount == 2 && sealed.title == "固定成品")
        check(store.readCandidate(p) == null && store.listCandidates().isEmpty())
        check(store.seal(candidate.id, candidate.contentDigest) == sealed)
        check(ViewerPackageCodec.writeScene(store.loadRelease(sealed.id)).contentEquals(originalScene))
        val exported = store.exportRelease(sealed.id)
        val packageBytes = exported.readBytes()
        check(packageBytes.size <= 50 * 1024 * 1024)
        check(store.importPackage(ByteArrayInputStream(packageBytes)) == sealed)
        check(store.listReleases() == listOf(sealed))
        status("PASS release sealing: altered PNG blocks seal; restored bytes seal atomically; retry and identical import are idempotent")

        // A structurally valid package with the same release ID but different content must not win.
        val scene = store.loadRelease(sealed.id)
        val different = ViewerScene(scene.releaseId, "冲突内容", scene.goal, scene.createdAt,
            scene.startStateId, scene.states, scene.edges, scene.hotspots, scene.assets)
        val conflict = File(context.noBackupFilesDir, "conflict.tapscene")
        ViewerPackageCodec.writePackage(different,
            File(context.noBackupFilesDir, "release-store/releases/${sealed.id}/package"), conflict,
            ViewerPackageCodec.CancelCheck {})
        rejected { conflict.inputStream().use { store.importPackage(it) } }
        rejected { store.importPackage(ByteArrayInputStream(packageBytes.copyOf(packageBytes.size / 2))) }
        rejected { store.importPackage(ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        check(store.listReleases() == listOf(sealed))
        check(File(context.noBackupFilesDir, "release-store/staging").listFiles().orEmpty().isEmpty())
        check(ViewerPackageCodec.writeScene(store.loadRelease(sealed.id)).contentEquals(originalScene))
        val transparent = transparentPackage(context)
        rejected { transparent.inputStream().use { store.importPackage(it) } }
        check(store.listReleases() == listOf(sealed))
        status("PASS release import isolation: conflicting ID, incomplete ZIP, invalid archive and actually transparent PNG never register or replace a library entry")

        val stageRoot = File(context.noBackupFilesDir, "release-store/staging")
        val beforeMarker = File(stageRoot, id()).also { check(it.mkdir()) }
        val partialMarker = File(stageRoot, id()).also { check(it.mkdir()) }
        File(partialMarker, ".owner").writeText("tapscene-")
        val ownedOrphan = File(stageRoot, id()).also { check(it.mkdir()) }
        File(ownedOrphan, ".owner").writeText("tapscene-release-v1:${ownedOrphan.name}")
        File(ownedOrphan, "incoming.zip").writeBytes(byteArrayOf(1, 2))
        val savedRoot = File(context.noBackupFilesDir, "release-store/releases/${sealed.id}")
        val interruptedMetadata = File(savedRoot, ".summary.json-${id()}.part").apply { writeText("{") }
        val unrelated = File(savedRoot, "unrelated.part").apply { writeText("Keep") }
        store = ReleaseStore(context)
        check(store.listReleases() == listOf(sealed))
        check(!beforeMarker.exists() && !partialMarker.exists() && !ownedOrphan.exists() && !interruptedMetadata.exists())
        check(unrelated.readText() == "Keep")
        check(ViewerPackageCodec.writeScene(store.loadRelease(sealed.id)).contentEquals(originalScene))
        status("PASS release interruption recovery: exact pre-marker/partial-marker/owned stages and metadata parts are cleaned; installed content and unrelated files survive")

        // A new candidate remains independent even after deleting its draft; explicit discard
        // removes only that candidate, while a sealed release retains all original asset bytes.
        val latest = checkNotNull(projects.readProject(p))
        var survivor = store.createCandidate(p, latest.project.revision)
        val oldSurvivor = survivor
        val revised = projects.renameProject(p, "第三版草稿")
        survivor = store.createCandidate(p, revised.project.revision, replaceExisting = true)
        check(survivor.id != oldSurvivor.id && store.listCandidates().single().id == survivor.id)
        projects.deleteProject(p)
        check(projects.readProject(p) == null)
        check(store.listCandidates().single().id == survivor.id)
        store.candidateAssetFile(survivor.id, a.id)
        store.releaseAssetFile(sealed.id, a.id)
        check(ViewerPackageCodec.writeScene(store.loadRelease(sealed.id)).contentEquals(originalScene))
        store.discardCandidate(survivor.id)
        check(store.listCandidates().isEmpty() && store.listReleases() == listOf(sealed))
        check(File(context.noBackupFilesDir, input.source.privateRelativePath).isFile && input.file.isFile)
        store.deleteRelease(sealed.id)
        check(store.listReleases().isEmpty())
        val imported = store.importPackage(ByteArrayInputStream(packageBytes))
        check(imported.id == sealed.id && imported.origin == "imported" && imported.contentDigest == sealed.contentDigest)
        check(ViewerPackageCodec.writeScene(store.loadRelease(imported.id)).contentEquals(originalScene))
        status("PASS release ownership: draft edit/delete, explicit candidate replacement/discard and library deletion are isolated; exported bytes restore independently")
    }

    private fun transparentPackage(context: Context): File {
        val directory = File(context.noBackupFilesDir, "transparent-package").also { check(it.mkdir()) }
        val assets = File(directory, "assets").also { check(it.mkdir()) }
        val assetId = id()
        val stateId = id()
        val png = File(assets, "$assetId.png")
        val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        try { png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val asset = ViewerScene.Asset(assetId, "assets/$assetId.png", "image/png", png.length(),
            ViewerPackageCodec.sha256(png), 48, 64)
        val scene = ViewerScene(id(), "Opaque verification", "", System.currentTimeMillis(), stateId,
            listOf(ViewerScene.State(stateId, assetId, 48, 64, "Transparent fixture", "", "authored", true)),
            emptyList(), emptyList(), listOf(asset))
        return File(context.noBackupFilesDir, "transparent.tapscene").also {
            ViewerPackageCodec.writePackage(scene, directory, it, ViewerPackageCodec.CancelCheck {})
        }
    }

    private suspend fun fixture(context: Context): ReviewedStepInput {
        val sourceId = id()
        val sources = File(context.noBackupFilesDir, "sources").also { check(it.mkdir()) }
        val source = File(sources, "$sourceId.mp4").apply { writeText("Synthetic ownership fixture; not a video") }
        val imported = ImportedSource(sourceId, "sources/$sourceId.mp4", "synthetic.mp4",
            SourceMetadata("video/mp4", source.length(), ViewerPackageCodec.sha256(source), 48, 64, 0, 1_000_000))
        val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        try {
            val generated = SafeMediaWriter(context).writePng(bitmap, emptyList(),
                File(context.noBackupFilesDir, "candidates/${id()}"))
            return ReviewedStepInput(generated.file, generated.sha256, generated.width, generated.height,
                imported, 100_000, 1_000, emptyList())
        } finally { bitmap.recycle() }
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        check(runCatching { action() }.isFailure) { "Expected the invalid release action to be rejected" }
    }
    private fun id() = UUID.randomUUID().toString()
}
