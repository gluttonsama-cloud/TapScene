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
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext

/** Real PNG/SQLite checks. Synthetic source ownership bytes are never claimed as playable video
 * or author review; production safe-image edits never invent a source or a frame timestamp. */
object SafeImageStoreChecks {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "safe-image-checks-${id()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        try { checkStore(isolated, status) }
        finally { check(root.deleteRecursively() && !root.exists()) }
    }

    private suspend fun checkStore(context: Context, status: (String) -> Unit) {
        val store = ProjectStore(context)
        val sourceId = id()
        val sourceFile = File(context.noBackupFilesDir, "sources/$sourceId.mp4")
        check(sourceFile.parentFile!!.mkdir()); sourceFile.writeText("Synthetic ownership fixture; no actual video")
        val source = ImportedSource(sourceId,"sources/$sourceId.mp4","Synthetic.mp4",
            SourceMetadata("video/mp4",sourceFile.length(),ViewerPackageCodec.sha256(sourceFile),32,48,0,1_000_000))
        val original = Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val oldMask = OpaqueMask(.05f,.05f,.25f,.25f)
        val first = try { output(context,original,listOf(oldMask)) } finally { original.recycle() }
        val project = store.createProject("Safe image","Preserve recorded evidence").project.id
        var before = store.addReviewedStep(project,ReviewedStepInput(first.file,first.sha256,32,48,source,0,1000,listOf(oldMask)),"Keep title","Keep text")
        val stepId = before.steps.single().id
        before = store.saveRegion(project,stepId,null,"Crop",null,RegionBox(1,2,3,4),0,.5,.5,before.project.revision)
        val regionId = before.steps.single().regions.single().id
        before = store.generateRegion(project,regionId,before.project.revision)
        before = store.reviewRegion(project,regionId,before.project.revision,checkNotNull(before.steps.single().regions.single().asset).sha256)
        val step = before.steps.single()
        val session = store.beginEditorDraftSession(project)
        val draft = StoredEditorDraft(before.project.revision,step.editorFields(),step.editorFields().copy(title="Unsaved title"),
            EditorPendingForm(EditorFormKind.NEXT_ACTION,label="  keep pending  "))
        check(store.writeEditorDraft(project,stepId,session,draft))
        check(sourceFile.delete())
        check(store.readProject(project) == before) // Missing video does not hide a saved safe PNG.
        val base = step.safeImageBinding(before.project)
        val loaded = store.readSafeImageBase(base)
        val masks = listOf(OpaqueMask(.6f,.6f,.9f,.9f))
        val next = try { output(context,loaded,masks) } finally { loaded.recycle() }
        val input = ReviewedStepInput(next.file,next.sha256,32,48,StepOrigin.Image(base),masks)
        rejected { store.replaceReviewedStep(project,stepId,before.project.revision,
            ReviewedStepInput(next.file,next.sha256,32,48,source,0,1000,masks,captureId="missing-video")) }
        rejected { store.addReviewedStep(project,input,"Forbidden image append") }
        rejected { store.replaceReviewedStep(project,stepId,before.project.revision,input.copy(masks=emptyList())) }
        rejected { store.replaceReviewedStep(project,stepId,before.project.revision,input.copy(width=31)) }
        rejected { store.replaceReviewedStep(project,stepId,before.project.revision,input.copy(origin=StepOrigin.Image(base.copy(assetId=id())))) }
        rejected { store.replaceReviewedStep(project,stepId,before.project.revision,input.copy(origin=StepOrigin.Image(base.copy(sha256="b".repeat(64))))) }
        val other = store.createProject("Other").project.id
        rejected { store.replaceReviewedStep(project,stepId,before.project.revision,input.copy(origin=StepOrigin.Image(base.copy(projectId=other)))) }
        coroutineScope {
            val operation = async(start=CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                store.replaceReviewedStep(project,stepId,before.project.revision,input)
            }
            check(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
        }
        check(store.readProject(project)==before && store.readEditorDrafts(project)[stepId]==draft)

        // Attempt to restore an old burned-in area outside this round's masks; even a freshly
        // generated, opaque PNG with correct metadata must be rejected on exact pixel comparison.
        val oldBitmap = store.readSafeImageBase(base)
        val altered = checkNotNull(oldBitmap.copy(Bitmap.Config.ARGB_8888,true)); oldBitmap.recycle()
        altered.setPixel(3,3,Color.CYAN)
        val invalid = try { output(context,altered,masks) } finally { altered.recycle() }
        rejected { store.replaceReviewedStep(project,stepId,before.project.revision,
            input.copy(file=invalid.file,sha256=invalid.sha256,captureId=invalid.file.name)) }
        // A different unmasked pixel is also forbidden; no crop, recolor or hidden transparency.
        val modified = store.readSafeImageBase(base)
        val changed = modified.copy(Bitmap.Config.ARGB_8888,true); modified.recycle()
        checkNotNull(changed).setPixel(31,0,Color.RED)
        val outside = try { output(context,changed,masks) } finally { changed.recycle() }
        rejected { store.replaceReviewedStep(project,stepId,before.project.revision,
            input.copy(file=outside.file,sha256=outside.sha256,captureId=outside.file.name)) }
        val transparent = Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888)
        val transparentFile = File(context.noBackupFilesDir,"transparent-${id()}.png")
        try { transparentFile.outputStream().use { check(transparent.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { transparent.recycle() }
        rejected { store.replaceReviewedStep(project,stepId,before.project.revision,
            input.copy(file=transparentFile,sha256=ViewerPackageCodec.sha256(transparentFile),captureId=transparentFile.name)) }
        check(store.readProject(project)==before)
        val oldFile = store.resolveAsset(project,stepId)
        val saved = store.replaceReviewedStep(project,stepId,before.project.revision,input)
        val image = saved.steps.single()
        check(image.origin==StepOrigin.Image(base) && image.source==null && image.frameTimeUs==null && image.timePrecisionUs==null)
        check(image.id==step.id && image.title==step.title && image.description==step.description && image.hotspots==step.hotspots)
        check(image.regions.single().asset==null && image.regions.single().reviewedAt==null)
        check(store.readEditorDrafts(project)[stepId]==draft && !oldFile.exists())
        check(store.replaceReviewedStep(project,stepId,before.project.revision,input)==saved) // Exactly-once retry.
        rejected { store.readSafeImageBase(base) } // Historical base identity is not a readable handle.
        val latest = image.safeImageBinding(saved.project)
        val bitmap = store.readSafeImageBase(latest)
        check(bitmap.getPixel(3,3)==Color.BLACK && bitmap.getPixel(24,36)==Color.BLACK)
        val secondMasks = listOf(OpaqueMask(.3f,.3f,.4f,.4f))
        val second = try { output(context,bitmap,secondMasks) } finally { bitmap.recycle() }
        val secondInput=ReviewedStepInput(second.file,second.sha256,32,48,StepOrigin.Image(latest),secondMasks)
        store.renameProject(project,"Changed revision")
        rejected { store.replaceReviewedStep(project,stepId,saved.project.revision,secondInput) }
        val fresh=checkNotNull(store.readProject(project));val freshBase=fresh.steps.single().safeImageBinding(fresh.project)
        val twice=store.replaceReviewedStep(project,stepId,fresh.project.revision,secondInput.copy(origin=StepOrigin.Image(freshBase)))
        val final=store.readSafeImageBase(twice.steps.single().safeImageBinding(twice.project))
        try { check(final.getPixel(3,3)==Color.BLACK && final.getPixel(24,36)==Color.BLACK && final.getPixel(11,17)==Color.BLACK) }
        finally { final.recycle() }
        check(ProjectStore(context).readProject(project)==twice)
        status("PASS safe PNG additions: exact latest base binding, no fake video/timing, old burn-in cannot be recovered, unmasked and transparent mutations rejected, stale/cancelled commit retains state, region invalidation and raw editor drafts preserved")
    }
    private suspend fun output(context: Context, bitmap: Bitmap, masks: List<OpaqueMask>) =
        SafeMediaWriter(context).writePng(bitmap,masks,File(context.noBackupFilesDir,"outputs-${id()}"))
    private suspend fun rejected(block: suspend () -> Unit) {
        val failure=try { block();null } catch (error: Exception) { error }
        check(failure!=null) { "Invalid safe-image input accepted" }
    }
    private fun id()=UUID.randomUUID().toString()
}
