package com.tapscene.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.tapscene.media.ImportedSource
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.SourceMetadata
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext

/** Platform-only real PNG/SQLite checks. Synthetic inputs are not a human privacy review. */
object RegionStoreChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "region-checks-${id()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        var failure: Throwable? = null
        try { checkLifecycle(isolated, status) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            val cleanup = runCatching { check(root.canonicalFile.parentFile == parent); check(root.deleteRecursively() && !root.exists()) }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private suspend fun checkLifecycle(context: Context, status: (String) -> Unit) {
        val coordinateExample = RegionBox(48, 655, 384, 93)
        check(com.tapscene.ui.shell.regionPixelBox(com.tapscene.ui.shell.regionNormalizedBox(coordinateExample, 480, 840), 480, 840) == coordinateExample)
        check(com.tapscene.ui.shell.parseRegionBox("2147483647", "0", "2", "1", 480, 840) == null)
        val projects = ProjectStore(context)
        val releases = ReleaseStore(context)
        val sourceId = id()
        val sourceFile = File(context.noBackupFilesDir, "sources/$sourceId.mp4")
        check(sourceFile.parentFile!!.mkdir()); sourceFile.writeText("Private source marker: never export this")
        val source = ImportedSource(sourceId, "sources/$sourceId.mp4", "PRIVATE-SOURCE.mp4",
            SourceMetadata("video/mp4", sourceFile.length(), ViewerPackageCodec.sha256(sourceFile), 32, 48, 0, 1_000_000))
        val input = fixture(context, source, 0)
        val p = projects.createProject("Visible regions").project.id
        val stepId = projects.addReviewedStep(p, input, "Safe base").steps.single().id
        var draft = projects.setTerminal(p, stepId, true)
        val base = draft.steps.single().asset
        val bounds = RegionBox(3, 4, 10, 12)
        fun region() = draft.steps.single().regions.single()
        draft = projects.saveRegion(p, stepId, null, "  Header  ", " Group ", bounds, -3, .25, .75, draft.project.revision)
        val r = region().id
        check(region().name == "Header" && region().group == "Group" && region().stale && region().reviewedAt == null)
        check(region().matchesBase(base) && region().bbox == bounds)
        rejected { projects.regionFile(p, r) }
        rejected { releases.createCandidate(p, draft.project.revision) }
        val saved = draft
        rejected { projects.saveRegion(p, stepId, r, "No", null, bounds, 0, Double.NaN, .5, saved.project.revision) }
        rejected { projects.saveRegion(p, stepId, r, "No", null, RegionBox(Int.MAX_VALUE, 0, 1, 1), 0, .5, .5, saved.project.revision) }
        rejected { projects.saveRegion(p, stepId, r, "No", null, bounds, 10001, .5, .5, saved.project.revision) }
        rejected { projects.saveRegion(p, stepId, r, "No", null, bounds, 0, .5, .5, saved.project.revision - 1) }
        check(projects.readProject(p) == saved)
        coroutineScope {
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                projects.generateRegion(p, r, saved.project.revision)
            }
            check(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)
        }
        check(projects.readProject(p) == saved)
        draft = projects.generateRegion(p, r, draft.project.revision)
        val generated = requireNotNull(region().asset)
        check(!region().stale && region().reviewedAt == null && generated.width == 10 && generated.height == 12)
        val actual = projects.regionFile(p, r)
        val bitmap = checkNotNull(BitmapFactory.decodeFile(actual.path))
        val baseBitmap = checkNotNull(BitmapFactory.decodeFile(projects.resolveAsset(p, stepId).path))
        try {
            for (y in 0 until 12) for (x in 0 until 10) check(bitmap.getPixel(x, y) == baseBitmap.getPixel(x + 3, y + 4))
        } finally { bitmap.recycle(); baseBitmap.recycle() }
        rejected { releases.createCandidate(p, draft.project.revision) }
        rejected { projects.reviewRegion(p, r, draft.project.revision, "0".repeat(64)) }
        val bytes = actual.readBytes(); actual.writeBytes(bytes + byteArrayOf(1))
        rejected { projects.reviewRegion(p, r, draft.project.revision, generated.sha256) }
        actual.writeBytes(bytes)
        draft = projects.reviewRegion(p, r, draft.project.revision, generated.sha256)
        check(region().reviewedAt != null && ProjectStore(context).readProject(p) == draft)
        status("PASS regions: actual PNG crop pixels match safe base; metadata, bounds, stale revision, cancellation, tamper and missing review fail closed")
        var candidate = releases.createCandidate(p, draft.project.revision)
        check(candidate.scene.schemaVersion == 3 && candidate.scene.policyVersion == "scene-regions-3")
        check(candidate.scene.regions.single().bbox.x == 3 && candidate.scene.assets.size == 2)
        val canonical = ViewerPackageCodec.writeScene(candidate.scene)
        check(!canonical.toString(Charsets.UTF_8).contains("PRIVATE-SOURCE"))
        candidate = releases.reviewState(candidate.id, candidate.contentDigest, stepId)
        candidate = releases.reviewSummary(candidate.id, candidate.contentDigest)
        candidate = releases.reviewFileList(candidate.id, candidate.contentDigest)
        releases.candidateAssetFile(candidate.id, stepId)
        candidate = releases.recordTraversal(candidate.id, candidate.contentDigest, null, false)
        rejected { releases.seal(candidate.id, candidate.contentDigest) }
        releases.candidateRegionFile(candidate.id, r)
        candidate = releases.reviewRegion(candidate.id, candidate.contentDigest, r)
        val sealed = releases.seal(candidate.id, candidate.contentDigest)
        val archive = releases.exportRelease(sealed.id).readBytes()
        val visits = listOf(RenderPlan.Visit(id(), stepId, null, 90))
        val portrait = RenderPlan.build(candidate.scene, 1080, 1920, visits, emptyList())
        check(releases.exportAiRelease(sealed.id, portrait).isFile)
        check(requireNotNull(releases.readAiPlan(sealed.id)).toBytes().contentEquals(portrait.toBytes()))
        val landscape = RenderPlan.build(candidate.scene, 1920, 1080, visits, emptyList())
        check(releases.exportAiRelease(sealed.id, landscape).isFile)
        check(requireNotNull(releases.readAiPlan(sealed.id)).toBytes().contentEquals(landscape.toBytes()))
        check(ViewerPackageCodec.writeScene(releases.loadRelease(sealed.id)).contentEquals(canonical))
        check(ViewerPackageCodec.contentDigest(releases.loadRelease(sealed.id)) == sealed.contentDigest)
        status("PASS AI store: persisted portrait/landscape plans export separately and leave sealed scene identity unchanged")
        val replacement = fixture(context, source, 1)
        draft = projects.replaceReviewedStep(p, stepId, draft.project.revision, replacement)
        check(region().asset == null && region().reviewedAt == null && !region().matchesBase(draft.steps.single().asset))
        check(region().bbox == bounds && region().id == r)
        rejected { projects.regionFile(p, r) }
        rejected { releases.createCandidate(p, draft.project.revision) }
        check(ViewerPackageCodec.writeScene(releases.loadRelease(sealed.id)).contentEquals(canonical))
        check(releases.exportRelease(sealed.id).readBytes().contentEquals(archive))
        draft = projects.generateRegion(p, r, draft.project.revision)
        check(region().matchesBase(draft.steps.single().asset) && region().reviewedAt == null)
        rejected { releases.createCandidate(p, draft.project.revision) }
        draft = projects.reviewRegion(p, r, draft.project.revision, requireNotNull(region().asset).sha256)
        val cropPath = projects.regionFile(p, r)
        draft = projects.saveRegion(p, stepId, r, "Renamed", null, bounds, 0, .5, .5, draft.project.revision)
        check(region().asset == null && region().reviewedAt == null)
        projects.readProject(p) // Pending exact-file cleanup runs on next operation.
        check(!cropPath.exists())
        draft = projects.generateRegion(p, r, draft.project.revision)
        val deletionCrop = projects.regionFile(p, r)
        projects.deleteStep(p, stepId)
        projects.readProject(p)
        check(!deletionCrop.exists() && projects.readProject(p)?.steps?.isEmpty() == true)
        check(sourceFile.isFile && input.file.isFile && replacement.file.isFile)
        check(releases.loadRelease(sealed.id).regions.single().id == r)
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "projects.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            check(db.version == 4)
            db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
            db.rawQuery("SELECT COUNT(*) FROM asset_imports", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
        }
        status("PASS regions: replaced base invalidates crop/review; regeneration needs fresh review; sealed bytes unchanged; metadata edits and deletion clean only exact crop files")
    }

    private suspend fun fixture(context: Context, source: ImportedSource, offset: Int): ReviewedStepInput {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        for (y in 0 until 48) for (x in 0 until 32) bitmap.setPixel(x, y, Color.rgb((x * 7 + offset) % 256, y * 5, offset * 100))
        return try {
            val output = SafeMediaWriter(context).writePng(bitmap, emptyList(), File(context.noBackupFilesDir, "candidate-${id()}"))
            ReviewedStepInput(output.file, output.sha256, output.width, output.height, source, 0, 1000, emptyList(), "region-$offset")
        } finally { bitmap.recycle() }
    }
    private suspend fun rejected(action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        check(error is IllegalArgumentException || error is IllegalStateException) { "Invalid region action did not fail closed: $error" }
    }
    private fun id() = UUID.randomUUID().toString()
}
