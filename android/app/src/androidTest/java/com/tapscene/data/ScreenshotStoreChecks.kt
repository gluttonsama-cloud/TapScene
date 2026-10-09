package com.tapscene.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SafeMediaWriter
import com.tapscene.media.ScreenshotImporter
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext

/** Synthetic source/output tests, never claims human privacy review or system-picker validation. */
object ScreenshotStoreChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val root = File(context.noBackupFilesDir, "screenshot-store-checks-${id()}")
        check(root.mkdir())
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        try { checkStore(isolated, status) } finally { check(root.deleteRecursively()) }
    }

    private suspend fun checkStore(context: Context, status: (String) -> Unit) {
        val store = ProjectStore(context)
        val importer = ScreenshotImporter(context)
        val file = File(context.noBackupFilesDir, "selected.png")
        val bitmap = Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
        val originalDigest = ViewerPackageCodec.sha256(file)
        val lease = importer.importScreenshot(file,"selected.fake")
        val masks = listOf(OpaqueMask(.1f,.1f,.3f,.3f))
        val output = SafeMediaWriter(context).writePng(lease.bitmap,masks,File(context.noBackupFilesDir,"outputs"))
        val input = ReviewedStepInput(output.file,output.sha256,32,48,StepOrigin.ImportedImage(lease.source),masks,"capture-image")
        val newProject = id()
        rejected { store.addReviewedScreenshot(newProject,input.copy(sha256="0".repeat(64)),"Created only after review") }
        check(store.listProjects().isEmpty() && store.readProject(newProject)==null)
        coroutineScope {
            val operation = async(start=CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                store.addReviewedScreenshot(newProject,input,"Cancelled")
            }
            check(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
        }
        check(store.readProject(newProject)==null)
        // A perfectly valid output from the wrong pixels must not acquire this source identity.
        val wrongPixels=Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        val wrong=try { SafeMediaWriter(context).writePng(wrongPixels,masks,File(context.noBackupFilesDir,"outputs")) }
            finally { wrongPixels.recycle() }
        rejected { store.addReviewedScreenshot(newProject,input.copy(file=wrong.file,sha256=wrong.sha256),"Wrong pixels") }
        check(store.readProject(newProject)==null && privateRawFiles(context).isEmpty())
        var saved=store.addReviewedScreenshot(newProject,input,"Screenshot test")
        check(saved.steps.size==1 && store.screenshotCount(newProject)==1)
        val step=saved.steps.single()
        val raw=(step.origin as StepOrigin.ImportedImage).source
        check(raw.privateRelativePath.startsWith("image-sources/") && raw.sourceId==lease.source.sourceId)
        check(step.source==null && step.frameTimeUs==null && step.timePrecisionUs==null && step.evidenceKind=="authored")
        check(ViewerPackageCodec.sha256(importer.checkedSourceFile(raw))==originalDigest)
        check(store.resolveAsset(newProject,step.id).path!=importer.checkedSourceFile(raw).path)
        check(store.addReviewedScreenshot(newProject,input,"Retry") == saved)
        // Snapshot retries have the exact same source/token/output, not a duplicate step or quota.
        lease.close()
        check(!File(context.noBackupFilesDir,lease.source.privateRelativePath).exists())
        check(store.readProject(newProject)==saved && store.screenshotCount(newProject)==1)
        saved=store.saveStepDraft(newProject,step.id,step.title,step.description,true,emptyList(),saved.project.revision)
        val releases=ReleaseStore(context)
        val candidate=releases.createCandidate(newProject,saved.project.revision)
        check(candidate.scene.states.single().sourceKind=="authored")
        val json=String(ViewerPackageCodec.writeScene(candidate.scene),Charsets.UTF_8)
        check(!json.contains("image-sources") && !json.contains("image-import-staging") && !json.contains(raw.sourceId))
        check(candidate.scene.assets.single().mime=="image/png")
        releases.reviewState(candidate.id,candidate.contentDigest,step.id)
        releases.reviewSummary(candidate.id,candidate.contentDigest)
        releases.reviewFileList(candidate.id,candidate.contentDigest)
        releases.recordTraversal(candidate.id,candidate.contentDigest,null,false)
        val sealed=releases.seal(candidate.id,candidate.contentDigest)
        val sealedFile=releases.releaseAssetFile(sealed.id,step.id)
        val sealedDigest=ViewerPackageCodec.sha256(sealedFile)
        val latest=saved.steps.single().safeImageBinding(saved.project)
        val base=store.readSafeImageBase(latest)
        val extra=listOf(OpaqueMask(.6f,.6f,.9f,.9f))
        val redacted=try { SafeMediaWriter(context).writePng(base,extra,File(context.noBackupFilesDir,"outputs")) } finally { base.recycle() }
        saved=store.replaceReviewedStep(newProject,step.id,saved.project.revision,
            ReviewedStepInput(redacted.file,redacted.sha256,32,48,StepOrigin.Image(latest),extra))
        check(saved.steps.single().evidenceKind=="authored" && saved.steps.single().origin is StepOrigin.Image)
        check(store.screenshotCount(newProject)==0 && !File(context.noBackupFilesDir,raw.privateRelativePath).exists())
        check(ViewerPackageCodec.sha256(sealedFile)==sealedDigest)
        check(releases.loadRelease(sealed.id).states.single().sourceKind=="authored")
        check(ViewerPackageCodec.sha256(file)==originalDigest) // System-selected input was never modified.
        status("PASS screenshot transaction: no empty project on cancel/failure, wrong pixels rejected, exactly-once commit, raw/safe domains separate, authored evidence survives safe append, raw cleanup cannot alter sealed output")

        val quota=store.createProject("Quota").project.id
        repeat(ProjectLimits.MAX_SCREENSHOTS) { index ->
            importer.importScreenshot(file).use { image ->
                val png=SafeMediaWriter(context).writePng(image.bitmap,emptyList(),File(context.noBackupFilesDir,"outputs"))
                store.addReviewedScreenshot(quota,ReviewedStepInput(png.file,png.sha256,32,48,StepOrigin.ImportedImage(image.source),emptyList(),"quota-$index"))
            }
        }
        val full=checkNotNull(store.readProject(quota))
        check(store.screenshotCount(quota)==20 && full.steps.size==20)
        importer.importScreenshot(file).use { image ->
            val png=SafeMediaWriter(context).writePng(image.bitmap,emptyList(),File(context.noBackupFilesDir,"outputs"))
            val next=ReviewedStepInput(png.file,png.sha256,32,48,StepOrigin.ImportedImage(image.source),emptyList(),"quota-next")
            rejected { store.addReviewedScreenshot(quota,next) }
            check(store.readProject(quota)==full && store.screenshotCount(quota)==20)
            val deletedRaw=(full.steps.first().origin as StepOrigin.ImportedImage).source
            store.deleteStep(quota,full.steps.first().id)
            check(store.screenshotCount(quota)==19 && !File(context.noBackupFilesDir,deletedRaw.privateRelativePath).exists())
            check(store.addReviewedScreenshot(quota,next).steps.size==20 && store.screenshotCount(quota)==20)
        }
        // A path obstruction deterministically simulates an undeletable raw file without changing
        // device security settings. The row is removed, but deletion must report pending raw bytes.
        val deletionSnapshot=checkNotNull(store.readProject(quota))
        val blockedSource=(deletionSnapshot.steps.first().origin as StepOrigin.ImportedImage).source
        val blocked=File(context.noBackupFilesDir,blockedSource.privateRelativePath)
        val parked=File(blocked.parentFile,"parked-original")
        check(blocked.renameTo(parked)); check(blocked.mkdir())
        val deletion=store.deleteProject(quota)
        check(deletion.pendingAssetCleanupCount>0 && parked.isFile)
        check(blocked.delete() && parked.renameTo(blocked))
        store.listProjects() // Normal access retries the precise raw cleanup journal.
        check(store.screenshotCount(quota)==0 && privateRawFiles(context).isEmpty())
        check(ViewerPackageCodec.sha256(sealedFile)==sealedDigest)
        status("PASS screenshot quota: twenty committed sources, rejected twenty-first has no row/step, delete releases quota, project cleanup preserves sealed release")
    }
    private fun privateRawFiles(context: Context) = File(context.noBackupFilesDir,"image-sources").walkTopDown().filter { it.isFile }.toList()
    private suspend fun rejected(block: suspend () -> Unit) { check(runCatching { block() }.isFailure) }
    private fun id()=UUID.randomUUID().toString()
}
