package com.tapscene.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Color
import com.tapscene.packageformat.AiDraftImportPolicy
import com.tapscene.packageformat.AiPackageCodec
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import java.io.File
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext

/** Real private SQLite/PNG/complete-ZIP checks; synthetic confirmations are not human review. */
object AiDraftImportChecks {
    suspend fun run(context: Context, status: (String) -> Unit) =
        AiDraftImportFixtures.isolated(context, "ai-import-checks") { isolated ->
            lifecycle(isolated, status)
            invalidInputs(isolated, status)
        }

    /** One successful static round trip plus the existing cancel/stale/video rejection boundaries. */
    suspend fun roundTrip(context: Context, output: File, status: (String) -> Unit) =
        AiDraftImportFixtures.isolated(context, "ai-round-trip") { isolated ->
            check(output.isDirectory && output.listFiles().orEmpty().isEmpty())
            lifecycle(isolated, status, output)
        }

    private suspend fun lifecycle(context: Context, status: (String) -> Unit, output: File? = null) {
        val originalFixture = AiDraftImportFixtures.complete(context)
        val projects = AiDraftImportFixtures.projectStore(context)
        var imports = AiDraftImportFixtures.importStore(context)
        val releases = ReleaseStore(context)
        val first = originalFixture.zip.inputStream().use { imports.prepare(it) }
        val originalId = checkNotNull(imports.commit(first.sessionId, first.previewDigest).projectId)
        AiDraftImportFixtures.reviewDraftRegions(projects, originalId)
        val original = checkNotNull(projects.readProject(originalId))
        val baseline = AiDraftImportFixtures.sealFixture(context, originalId, originalFixture)
        val editorSession = projects.beginEditorDraftSession(originalId)
        val originalStep = original.steps.first()
        val editorDraft = StoredEditorDraft(original.project.revision, originalStep.editorFields(),
            originalStep.editorFields().copy(title = "Unapplied original title"),
            EditorPendingForm(EditorFormKind.HOTSPOT, label = "Partial original label", left = "0."))
        check(projects.writeEditorDraft(originalId, originalStep.id, editorSession, editorDraft))
        val originalDraftRows = editorRows(context, originalId)
        val originalAssets = assetDigests(context, original)
        val originalReleaseBytes = treeDigests(File(context.noBackupFilesDir, "release-store/releases/${baseline.id}"))
        val originalZipDigest = ViewerPackageCodec.sha256(originalFixture.zip)
        val originalRows = projectRows(context, originalId)
        val fixture = AiDraftImportFixtures.edited(context, originalFixture)
        val incomingZipDigest = ViewerPackageCodec.sha256(fixture.zip)

        fun unchanged() {
            check(projects.readProject(originalId) == original) { "AI import changed the original project" }
            check(editorRows(context, originalId) == originalDraftRows) { "AI import changed unapplied editor bytes" }
            check(projects.readEditorDrafts(originalId)[originalStep.id] == editorDraft)
            check(assetDigests(context, original) == originalAssets)
            check(treeDigests(File(context.noBackupFilesDir, "release-store/releases/${baseline.id}")) == originalReleaseBytes)
            check(projectRows(context, originalId) == originalRows) { "AI import changed original database row values" }
            check(ViewerPackageCodec.sha256(originalFixture.zip) == originalZipDigest)
            check(ViewerPackageCodec.sha256(fixture.zip) == incomingZipDigest) { "Caller-owned package changed" }
        }

        val beforeIds = projects.listProjects().map { it.id }.toSet()
        val cancelled = fixture.zip.inputStream().use { imports.prepare(it, baseline.id) }
        check(cancelled.baselineReleaseId == baseline.id && cancelled.issues.isEmpty())
        check(projects.listProjects().map { it.id }.toSet() == beforeIds)
        check(imports.cancel(cancelled.sessionId).let { it.status == "cancelled" && it.projectId == null && !it.projectExists })
        rejected { imports.commit(cancelled.sessionId, cancelled.previewDigest) }
        check(projects.readProject(cancelled.newProjectId) == null)
        check(imports.readPrepared(cancelled.sessionId) == null)
        unchanged()

        val pending = fixture.zip.inputStream().use { imports.prepare(it, baseline.id) }
        imports = AiDraftImportFixtures.importStore(context) // A new instance must resume the durable exact preview.
        check(pending.sessionId in imports.pending())
        check(checkNotNull(imports.readPrepared(pending.sessionId)).previewDigest == pending.previewDigest)
        check(pending.scene.states.size == 3 && pending.plan.visits.size == 3)
        check(pending.plan.visits.count { it.stateId == fixture.startId } == 2)
        check(pending.scene.states.any { it.id == fixture.branchId } && pending.plan.visits.none { it.stateId == fixture.branchId })
        check(pending.expandedByteLength == fixture.scene.states.sumOf { state ->
            fixture.scene.assets.single { it.id == state.imageAssetId }.byteLength
        } + fixture.scene.regions.sumOf { region -> fixture.scene.assets.single { it.id == region.assetId }.byteLength })
        check(pending.differences.any { it.category == "text" && it.subjectId == fixture.branchId &&
            it.field == "title" && it.before == "Unselected branch" && it.after == "Edited unselected branch" })
        check(pending.differences.any { it.category == "text" && it.subjectId == fixture.branchEdgeId &&
            it.field == "label" && it.after == "Alternate ending" })
        check(pending.differences.any { it.category == "region" && it.field == "zIndex" && it.before == "-2" && it.after == "4" })
        check(pending.differences.none { it.category == "path" }) // No exported baseline plan snapshot is claimed.
        check(pending.summary.contains("Edited unselected branch") && pending.summary.contains("Alternate ending"))
        check(pending.plan.toBytes().contentEquals(fixture.plan.toBytes()))
        val noBaseline = imports.preview(pending.sessionId, null)
        check(noBaseline.previewDigest != pending.previewDigest && noBaseline.baselineReleaseId == null)
        rejected { imports.commit(pending.sessionId, pending.previewDigest) }
        check(projects.readProject(pending.newProjectId) == null)
        coroutineScope {
            val cancelledCommit = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                imports.commit(noBaseline.sessionId, noBaseline.previewDigest)
            }
            check(runCatching { cancelledCommit.await() }.exceptionOrNull() is CancellationException)
        }
        check(projects.readProject(pending.newProjectId) == null)
        check(checkNotNull(imports.readPrepared(pending.sessionId)).previewDigest == noBaseline.previewDigest)
        unchanged()
        status("PASS AI import preview: full unselected branch retained, repeated visits stay a plan, cancel creates no project, resumed preview and changed comparison reject stale commit")

        // Re-select the explicit local baseline and acknowledge this exact complete comparison.
        val confirmed = imports.preview(pending.sessionId, baseline.id)
        check(confirmed.previewDigest == pending.previewDigest && confirmed.baselineReleaseId == baseline.id)
        rejected { imports.commit(noBaseline.sessionId, noBaseline.previewDigest) }
        val result = imports.commit(confirmed.sessionId, confirmed.previewDigest)
        val newId = checkNotNull(result.projectId)
        check(result.status == "committed" && result.projectExists && newId != originalId && newId == pending.newProjectId)
        val imported = checkNotNull(projects.readProject(newId))
        check(imported.steps.map { it.id } == fixture.scene.states.map { it.id })
        check(imported.steps.size == 3 && imported.project.startStepId == fixture.startId)
        check(imported.steps.map { it.isTerminal } == fixture.scene.states.map { it.terminal })
        val incomingIds = fixture.scene.assets.map { it.id }.toSet()
        val originalAssetIds = (original.steps.map { it.asset } + original.steps.flatMap { it.regions }.mapNotNull { it.asset }).map { it.id }.toSet()
        val newAssets = imported.steps.map { it.asset } + imported.steps.flatMap { it.regions }.map { checkNotNull(it.asset) }
        check(newAssets.map { it.id }.distinct().size == 4)
        check(newAssets.none { it.id in incomingIds || it.id in originalAssetIds })
        imported.steps.zip(fixture.scene.states).forEach { (step, state) ->
            val origin = step.origin as? StepOrigin.PackageSafeImage ?: error("Imported safe pixels acquired raw provenance")
            check(step.title == state.title && step.description == state.description && step.isTerminal == state.terminal)
            check(origin.importId == pending.sessionId && origin.sourceStateId == state.id && origin.sourceAssetId == state.imageAssetId)
            check(origin.declaredKind == state.sourceKind && step.evidenceKind == "imported")
            check(step.source == null && step.sourceId == null && step.frameTimeUs == null && step.timePrecisionUs == null)
            check(step.masks.isEmpty() && step.asset.sha256 == origin.sha256)
            val file = projects.resolveAsset(newId, step.id)
            check(file.canonicalFile.toPath().startsWith(context.noBackupFilesDir.canonicalFile.toPath()))
            check(ViewerPackageCodec.sha256(file) == fixture.scene.assets.single { it.id == state.imageAssetId }.sha256)
            check(file.path != projects.resolveAsset(originalId, step.id).path)
        }
        check(projects.screenshotCount(newId) == 0)
        check(File(context.noBackupFilesDir, "image-sources").walkTopDown().none { it.isFile })
        check(File(context.noBackupFilesDir, "sources").walkTopDown().none { it.isFile })
        val region = imported.steps.flatMap { it.regions }.single()
        check(region.reviewedAt == null && region.matchesBase(imported.steps.first().asset))
        check(region.baseAssetId == imported.steps.first().asset.id && checkNotNull(region.asset).id != fixture.scene.regions.single().assetId)
        check(region.name == "Visible crop" && region.group == "Header" && region.bbox == RegionBox(3, 4, 10, 12))
        check(region.zIndex == 4 && region.anchorX == .25 && region.anchorY == .75 && region.sourceWidth == 32 && region.sourceHeight == 48)
        imported.steps.first().hotspots.forEach { hotspot ->
            val expected = fixture.scene.edges.single { it.hotspotId == hotspot.id }
            check(hotspot.edgeId == expected.id && hotspot.label == expected.label && hotspot.targetStepId == expected.toStateId && hotspot.endLabel == expected.endLabel)
        }
        check(imported.steps.first().hotspots.map { it.edgeId }.toSet() == fixture.scene.edges.filter { it.trigger == "tap" }.map { it.id }.toSet())
        check(imported.steps.first().nextAction?.id == fixture.nextEdgeId)
        rejected { releases.createCandidate(newId, imported.project.revision) } // Regions never inherit review.
        val retry = AiDraftImportFixtures.importStore(context).commit(pending.sessionId, confirmed.previewDigest)
        check(retry == result && imports.cancel(pending.sessionId) == result && imports.readResult(pending.sessionId) == result)
        check(imports.readPrepared(pending.sessionId) == null && pending.sessionId !in imports.pending())
        check(projects.listProjects().map { it.id }.toSet() == beforeIds + newId)
        check(!File(context.noBackupFilesDir, "ai-draft-imports/${pending.sessionId}").exists())
        unchanged()
        status("PASS AI import commit: distinct private PNG identities, complete graph, imported-only provenance, no PTS/raw sources, unreviewed regions, exactly-once retry and post-commit cancel preserve saved result and all original bytes")

        val importedConfig = checkNotNull(projects.readDraftAiConfig(newId))
        check(importedConfig.resolve(fixture.scene).toBytes().contentEquals(fixture.plan.toBytes()))
        val configJson = DraftAiConfigCodec.encode(importedConfig)
        val recoveredConfig = ProjectStore.withTemporary(context) { checkNotNull(it.readDraftAiConfig(newId)) }
        check(DraftAiConfigCodec.encode(recoveredConfig) == configJson)
        check(recoveredConfig.effects.map { it.id } == importedConfig.effects.map { it.id })
        AiDraftImportFixtures.reviewDraftRegions(projects, newId)
        val reviewedProject = checkNotNull(projects.readProject(newId))
        val initialCandidate = releases.createCandidate(newId, reviewedProject.project.revision)
        rejected { releases.createCandidate(newId, reviewedProject.project.revision + 1, replaceExisting = true) }
        check(releases.readCandidate(newId)?.id == initialCandidate.id)
        val replacement = releases.createCandidate(newId, reviewedProject.project.revision, replaceExisting = true)
        val candidate = releases.createCandidate(newId, reviewedProject.project.revision, replaceExisting = true)
        check(setOf(initialCandidate.id, replacement.id, candidate.id).size == 3)
        check(releases.listCandidates().map { it.id } == listOf(candidate.id))
        check(projects.readProject(newId) == reviewedProject) // The long-lived owner's pool still works.
        status("PASS AI candidate lifecycle: three real candidate copies, failed stale revision preserves old candidate, long-lived project owner remains usable")
        check(candidate.reviewedStateIds.isEmpty() && candidate.reviewedRegionIds.isEmpty() && candidate.visitedEdgeIds.isEmpty())
        check(!candidate.summaryReviewed && !candidate.fileListReviewed && !candidate.completedPath)
        rejected { releases.seal(candidate.id, candidate.contentDigest) }
        val sealed = AiDraftImportFixtures.sealFixture(context, newId, fixture)
        check(sealed.id == candidate.id && sealed.id != baseline.id && sealed.id != fixture.scene.releaseId)
        val sealedScene = releases.loadRelease(sealed.id)
        // Asset/release identities and external provenance deliberately change; all editable content must survive.
        val differences = AiDraftImportPolicy.compare(sealedScene, fixture.scene)
        check(differences.size == 2 && differences.all { it.category == "state" &&
            it.field == "sourceKindDeclaration" && it.after == "imported" }) { "Round trip changed scene content: $differences" }
        val sealedPlan = checkNotNull(releases.readAiPlan(sealed.id))
        val expectedPlan = RenderPlan.build(sealedScene, fixture.plan.width, fixture.plan.height, fixture.plan.visits, fixture.plan.effects)
        check(sealedPlan.toBytes().contentEquals(expectedPlan.toBytes()))
        check(sealedPlan.releaseId == sealed.id && sealedPlan.contentDigest == sealed.contentDigest)
        rejected { releases.exportAiRelease(sealed.id, fixture.plan) }
        val exported = releases.exportAiRelease(sealed.id, sealedPlan)
        val checkedRoot = File(context.noBackupFilesDir, "round-trip-java-reader").also { check(it.mkdir()) }
        val readBack = AiPackageCodec.readPackage(exported, checkedRoot, {}, null)
        check(readBack.contentDigest == sealed.contentDigest && readBack.renderPlan.toBytes().contentEquals(expectedPlan.toBytes()))
        check(ViewerPackageCodec.writeScene(readBack.scene).contentEquals(ViewerPackageCodec.writeScene(sealedScene)))
        unchanged()
        status("PASS AI round trip: synthetic local review required, new release sealed, exact full graph/regions/visits/effects preserved, rebound exported package read by Java")

        val beforeVideo = projects.listProjects().map { it.id }.toSet()
        val video = AiDraftImportFixtures.videoOnUnselectedBranch(context, fixture)
        val videoFailure = rejected { video.inputStream().use { imports.prepare(it) } }
        check(videoFailure.message.orEmpty().contains("视频")) { "Expected explicit static-only video gate: $videoFailure" }
        check(projects.listProjects().map { it.id }.toSet() == beforeVideo && imports.pending().isEmpty())
        unchanged()
        status("PASS AI round trip video gate: existing declared-video counterexample rejected before video decode; no project added")
        output?.let {
            fixture.zip.copyTo(File(it, "incoming.tapscene-ai"))
            exported.copyTo(File(it, "round-trip.tapscene-ai"))
            File(it, "expected-scene.json").writeBytes(ViewerPackageCodec.writeScene(sealedScene))
            File(it, "expected-plan.json").writeBytes(expectedPlan.toBytes())
        }
    }

    private suspend fun invalidInputs(context: Context, status: (String) -> Unit) {
        val fixture = AiDraftImportFixtures.complete(context)
        val projects = AiDraftImportFixtures.projectStore(context)
        val imports = AiDraftImportFixtures.importStore(context)
        val beforeIds = projects.listProjects().map { it.id }.toSet()
        val zipChanged = fixture.zip.inputStream().use { imports.prepare(it) }
        val ownedZip = File(context.noBackupFilesDir, "ai-draft-imports/${zipChanged.sessionId}/input.tapscene-ai")
        ownedZip.appendBytes(byteArrayOf(1))
        rejected { imports.commit(zipChanged.sessionId, zipChanged.previewDigest) }
        check(projects.readProject(zipChanged.newProjectId) == null)
        imports.cancel(zipChanged.sessionId)

        val imageChanged = fixture.zip.inputStream().use { imports.prepare(it) }
        val sourceAsset = fixture.scene.assets.first()
        val shownImage = imports.imageFile(imageChanged.sessionId, sourceAsset.id)
        shownImage.writeBytes(byteArrayOf(0, 1, 2, 3))
        rejected { imports.imageFile(imageChanged.sessionId, sourceAsset.id) }
        // Commit must read the original complete ZIP again, not publish mutable preview files.
        val restored = imports.commit(imageChanged.sessionId, imageChanged.previewDigest)
        val saved = checkNotNull(projects.readProject(checkNotNull(restored.projectId)))
        check(saved.steps.all { ViewerPackageCodec.sha256(projects.resolveAsset(saved.project.id, it.id)) == sourceAsset.sha256 })
        check(projects.listProjects().map { it.id }.toSet() == beforeIds + saved.project.id)

        val beforeInvalid = projects.listProjects().map { it.id }.toSet()
        val video = AiDraftImportFixtures.videoOnUnselectedBranch(context, fixture)
        val videoFailure = rejected { video.inputStream().use { imports.prepare(it) } }
        check(videoFailure.message.orEmpty().contains("视频")) { "Declared video must reach the explicit static-only gate: $videoFailure" }
        val expanded = AiDraftImportFixtures.expandedBeyondLimit(context)
        val sizeFailure = rejected { expanded.inputStream().use { imports.prepare(it) } }
        check(sizeFailure.message.orEmpty().contains("展开")) { "Shared-asset expansion must fail its own budget: $sizeFailure" }
        check(projects.listProjects().map { it.id }.toSet() == beforeInvalid)
        check(imports.pending().isEmpty())
        check(File(context.noBackupFilesDir, "ai-draft-imports").listFiles().orEmpty().isEmpty())
        status("PASS AI import revalidation: altered ZIP rejected, altered preview PNG re-extracted and verified, declared video on an unselected branch and legal shared-PNG expansion over 50 MiB create no project")
    }

    /** Compare every original project-scoped SQL value, including pending JSON and private plan IDs.
     * The shared database file itself must change when another project is inserted. */
    private fun projectRows(context: Context, projectId: String): Map<String, List<List<String?>>> =
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "projects.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            buildMap {
                for (table in tables) {
                    check(table.matches(Regex("[a-z_]+")))
                    val scoped = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                        var found = false
                        while (cursor.moveToNext()) if (cursor.getString(1) == "project_id") found = true
                        found
                    }
                    if (scoped) put(table, db.rawQuery("SELECT * FROM $table WHERE project_id=? ORDER BY rowid", arrayOf(projectId)).use { cursor ->
                        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                            if (cursor.isNull(column)) null else "${cursor.getType(column)}:${cursor.getString(column)}"
                        }) }
                    })
                }
            }
        }

    private fun editorRows(context: Context, projectId: String): Map<String, String> =
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, "projects.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT state_id,draft_json FROM editor_drafts WHERE project_id=?", arrayOf(projectId)).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            }
        }
    private fun assetDigests(context: Context, project: ProjectSnapshot) =
        (project.steps.map { it.asset } + project.steps.flatMap { it.regions }.mapNotNull { it.asset })
            .associate { it.privateRelativePath to ViewerPackageCodec.sha256(File(context.noBackupFilesDir, it.privateRelativePath)) }
    private fun treeDigests(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).path to ViewerPackageCodec.sha256(it) }
    private suspend fun rejected(action: suspend () -> Unit): Throwable {
        val failure = runCatching { action() }.exceptionOrNull()
        check(failure is IllegalArgumentException || failure is IllegalStateException || failure is java.io.IOException) {
            "Expected a rejected AI import, got $failure"
        }
        return checkNotNull(failure)
    }
}

/** Small, real complete-package fixtures shared by the two data checks. */
internal object AiDraftImportFixtures {
    data class Fixture(val scene: ViewerScene, val plan: RenderPlan, val assetRoot: File, val zip: File,
        val startId: String, val endId: String, val branchId: String, val loopEdgeId: String, val nextEdgeId: String, val branchEdgeId: String)

    suspend fun isolated(context: Context, name: String, block: suspend (Context) -> Unit) {
        val parent = context.noBackupFilesDir.canonicalFile
        val root = File(parent, "$name-${id()}")
        check(root.mkdir() && root.canonicalFile.parentFile == parent)
        val isolated = FixtureContext(context.applicationContext, root)
        var failure: Throwable? = null
        try { block(isolated) } catch (error: Throwable) { failure = error; throw error }
        finally {
            var cleanup: Throwable? = null
            for (store in isolated.stores.asReversed()) {
                val error = runCatching {
                    // Test-owned objects only. Close before deleting the private database directory.
                    val helper = ProjectStore::class.java.getDeclaredField("helper").apply { isAccessible = true }.get(store) as SQLiteOpenHelper
                    helper.close()
                }.exceptionOrNull()
                if (error != null) { if (cleanup == null) cleanup = error else cleanup.addSuppressed(error) }
            }
            val deleteError = runCatching { check(root.canonicalFile.parentFile == parent); check(root.deleteRecursively() && !root.exists()) }.exceptionOrNull()
            if (deleteError != null) { if (cleanup == null) cleanup = deleteError else cleanup.addSuppressed(deleteError) }
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    private class FixtureContext(base: Context, private val root: File) : ContextWrapper(base) {
        val stores = mutableListOf<ProjectStore>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
    }

    fun projectStore(context: Context): ProjectStore = ProjectStore(context).also {
        (context as FixtureContext).stores += it
    }

    fun importStore(context: Context): AiDraftImportStore = AiDraftImportStore(context).also { owner ->
        val projects = AiDraftImportStore::class.java.getDeclaredField("projects").apply { isAccessible = true }.get(owner) as ProjectStore
        (context as FixtureContext).stores += projects
    }

    fun complete(context: Context): Fixture {
        val root = File(context.noBackupFilesDir, "ai-fixture-${id()}").also { check(it.mkdir()) }
        val assets = File(root, "assets").also { check(it.mkdir()) }
        val imageId = id(); val cropId = id(); val start = id(); val end = id(); val branch = id()
        val loopHotspot = id(); val branchHotspot = id(); val loopEdge = id(); val nextEdge = id(); val branchEdge = id(); val regionId = id()
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val image = File(assets, "$imageId.png"); val crop = File(assets, "$cropId.png")
        try {
            for (y in 0 until 48) for (x in 0 until 32) bitmap.setPixel(x, y, Color.rgb(x * 7, y * 5, (x + y) * 3))
            writePng(bitmap, image)
            val region = Bitmap.createBitmap(bitmap, 3, 4, 10, 12)
            try { writePng(region, crop) } finally { region.recycle() }
        } finally { bitmap.recycle() }
        val scene = ViewerScene(3, ViewerPackageCodec.REGION_POLICY_VERSION, ViewerPackageCodec.COMPILER_VERSION,
            id(), "Complete external graph", "Retain the branch and exact plan", 123456L, start,
            listOf(ViewerScene.State(start, imageId, 32, 48, "Start", "First line\nSecond line", "recorded", false),
                ViewerScene.State(end, imageId, 32, 48, "Chosen end", "", "authored", true),
                ViewerScene.State(branch, imageId, 32, 48, "Unselected branch", "Still part of the complete scene", "imported", true)),
            listOf(ViewerScene.Edge(loopEdge, start, start, null, loopHotspot, "Again", "tap", "authored"),
                ViewerScene.Edge(nextEdge, start, end, null, null, "Continue", "continue", "authored"),
                ViewerScene.Edge(branchEdge, start, branch, null, branchHotspot, "Other ending", "tap", "authored")),
            listOf(ViewerScene.Hotspot(loopHotspot, start, "Again", ViewerScene.Rect(.1, .1, .2, .2)),
                ViewerScene.Hotspot(branchHotspot, start, "Other ending", ViewerScene.Rect(.6, .6, .2, .2))),
            listOf(ViewerScene.Region(regionId, start, imageId, cropId, "Visible crop", 32, 48,
                ViewerScene.PixelRect(3, 4, 10, 12), "Header", -2, ViewerScene.Anchor(.25, .75))),
            listOf(asset(imageId, image, 32, 48), asset(cropId, crop, 10, 12, ViewerScene.Asset.ROLE_REGION_CROP)))
        val visits = listOf(RenderPlan.Visit(id(), start, loopEdge, 90), RenderPlan.Visit(id(), start, nextEdge, 75), RenderPlan.Visit(id(), end, null, 60))
        val effects = listOf(
            RenderPlan.Effect("click", visits[0].visitId, 2, 8, loopHotspot, null, null, null),
            RenderPlan.Effect("annotation", visits[0].visitId, 10, 20, null, null, "First annotation", ViewerScene.Rect(.1, .2, .3, .1)),
            RenderPlan.Effect("annotation", visits[0].visitId, 40, 15, null, null, "Second annotation", ViewerScene.Rect(.2, .4, .4, .1)),
            RenderPlan.Effect("focus", visits[0].visitId, 20, 25, null, regionId, null, null),
            RenderPlan.Effect("highlight", visits[1].visitId, 5, 20, null, null, null, ViewerScene.Rect(.1, .2, .3, .4)),
            RenderPlan.Effect("transition", visits[1].visitId, 65, 10, null, null, null, null))
        val plan = RenderPlan.build(scene, 1920, 1080, visits, effects)
        val zip = File(context.noBackupFilesDir, "fixture-${id()}.tapscene-ai")
        AiPackageCodec.writePackage(scene, plan, root, zip, {}, null)
        return Fixture(scene, plan, root, zip, start, end, branch, loopEdge, nextEdge, branchEdge)
    }

    /** Legitimate external edits retain full branches and the same source release declaration. */
    fun edited(context: Context, original: Fixture): Fixture {
        val s = original.scene
        val branchHotspot = s.edges.single { it.id == original.branchEdgeId }.hotspotId
        val scene = ViewerScene(s.schemaVersion, s.policyVersion, s.compilerVersion, s.releaseId,
            s.title, s.goal, s.createdAt, s.startStateId,
            s.states.map { ViewerScene.State(it.id, it.imageAssetId, it.width, it.height,
                if (it.id == original.branchId) "Edited unselected branch" else it.title, it.description, it.sourceKind, it.terminal) },
            s.edges.map { ViewerScene.Edge(it.id, it.fromStateId, it.toStateId, it.endLabel, it.hotspotId,
                if (it.id == original.branchEdgeId) "Alternate ending" else it.label, it.trigger, it.sourceKind) },
            s.hotspots.map { ViewerScene.Hotspot(it.id, it.stateId,
                if (it.id == branchHotspot) "Alternate ending" else it.label, it.rect) },
            s.regions.map { ViewerScene.Region(it.id, it.stateId, it.baseAssetId, it.assetId, it.name,
                it.sourceWidth, it.sourceHeight, it.bbox, it.group, 4, it.anchor) }, s.assets)
        val visits = original.plan.visits.mapIndexed { index, it ->
            RenderPlan.Visit(it.visitId, it.stateId, it.selectedEdgeId, if (index == 0) 105 else it.holdFrames)
        }
        val plan = RenderPlan.build(scene, original.plan.width, original.plan.height, visits, original.plan.effects)
        val zip = File(context.noBackupFilesDir, "edited-${id()}.tapscene-ai")
        AiPackageCodec.writePackage(scene, plan, original.assetRoot, zip, {}, null)
        return original.copy(scene = scene, plan = plan, zip = zip)
    }

    suspend fun reviewDraftRegions(projects: ProjectStore, projectId: String) {
        var snapshot = checkNotNull(projects.readProject(projectId))
        snapshot.steps.flatMap { it.regions }.forEach { region ->
            projects.regionFile(projectId, region.id)
            snapshot = projects.reviewRegion(projectId, region.id, snapshot.project.revision, checkNotNull(region.asset).sha256)
        }
        val config = checkNotNull(projects.readDraftAiConfig(projectId))
        check(!projects.saveDraftAiConfig(projectId, snapshot.project.revision, config).needsRepair)
    }

    suspend fun sealFixture(context: Context, projectId: String, fixture: Fixture): ReleaseSummary {
        val store = ReleaseStore(context)
        val snapshot = ProjectStore.withTemporary(context) { checkNotNull(it.readProject(projectId)) }
        var candidate = store.createCandidate(projectId, snapshot.project.revision)
        candidate.scene.states.forEach { state ->
            store.candidateAssetFile(candidate.id, state.id)
            candidate = store.reviewState(candidate.id, candidate.contentDigest, state.id)
        }
        candidate.scene.regions.forEach { region ->
            store.candidateRegionFile(candidate.id, region.id)
            candidate = store.reviewRegion(candidate.id, candidate.contentDigest, region.id)
        }
        candidate = store.reviewSummary(candidate.id, candidate.contentDigest)
        candidate = store.reviewFileList(candidate.id, candidate.contentDigest)
        candidate = store.recordTraversal(candidate.id, candidate.contentDigest, null, false)
        candidate = store.recordTraversal(candidate.id, candidate.contentDigest, fixture.loopEdgeId, false)
        candidate = store.recordTraversal(candidate.id, candidate.contentDigest, fixture.nextEdgeId, false)
        candidate = store.rewindTraversal(candidate.id, candidate.contentDigest)
        candidate = store.recordTraversal(candidate.id, candidate.contentDigest, fixture.branchEdgeId, false)
        return store.seal(candidate.id, candidate.contentDigest)
    }

    fun videoOnUnselectedBranch(context: Context, fixture: Fixture): File {
        val videoId = id(); val file = File(fixture.assetRoot, "assets/$videoId.mp4")
        file.writeText("Synthetic declared video: rejection must happen before decode")
        val video = ViewerScene.Asset(videoId, "assets/$videoId.mp4", "video/mp4", file.length(),
            ViewerPackageCodec.sha256(file), 32, 48, ViewerScene.Asset.ROLE_TRANSITION, 1000L)
        val scene = fixture.scene.let { original -> ViewerScene(original.schemaVersion, original.policyVersion, original.compilerVersion,
            original.releaseId, original.title, original.goal, original.createdAt, original.startStateId, original.states,
            original.edges.map { edge -> ViewerScene.Edge(edge.id, edge.fromStateId, edge.toStateId, edge.endLabel,
                edge.hotspotId, edge.label, edge.trigger, edge.sourceKind, videoId.takeIf { edge.id == fixture.branchEdgeId }) },
            original.hotspots, original.regions, original.assets + video) }
        val plan = RenderPlan.build(scene, fixture.plan.width, fixture.plan.height, fixture.plan.visits, fixture.plan.effects)
        return File(context.noBackupFilesDir, "declared-video-${id()}.tapscene-ai").also {
            // Deliberately permissive fixture writer; this is a declaration-gate test, not a claim
            // that marker bytes are a valid MP4. The production reader must reject every video.
            AiPackageCodec.writePackage(scene, plan, fixture.assetRoot, it, {}, { _, _, _ -> })
        }
    }

    fun expandedBeyondLimit(context: Context): File {
        val root = File(context.noBackupFilesDir, "shared-image-${id()}").also { check(it.mkdir()) }
        val assets = File(root, "assets").also { check(it.mkdir()) }
        val imageId = id(); val image = File(assets, "$imageId.png")
        val bitmap = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
        try {
            val random = Random(107L)
            val pixels = IntArray(768 * 768) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
            bitmap.setPixels(pixels, 0, 768, 0, 0, 768, 768)
            writePng(bitmap, image)
        } finally { bitmap.recycle() }
        check(image.length() < ViewerPackageCodec.MAX_PACKAGE_BYTES && image.length() * 40 > ViewerPackageCodec.MAX_PACKAGE_BYTES)
        val states = (0 until 40).map { index -> ViewerScene.State(id(), imageId, 768, 768, "Shared $index", "", "authored", index == 39) }
        val edges = (0 until 39).map { index -> ViewerScene.Edge(id(), states[index].id, states[index + 1].id, null, null, "Next", "continue", "authored") }
        val scene = ViewerScene(id(), "Expansion limit", "", 0L, states.first().id, states, edges, emptyList(), listOf(asset(imageId, image, 768, 768)))
        val visits = states.mapIndexed { index, state -> RenderPlan.Visit(id(), state.id, edges.getOrNull(index)?.id, 1) }
        val plan = RenderPlan.build(scene, 1080, 1920, visits, emptyList())
        return File(context.noBackupFilesDir, "expanded-${id()}.tapscene-ai").also { AiPackageCodec.writePackage(scene, plan, root, it, {}, null) }
    }

    private fun asset(id: String, file: File, width: Int, height: Int, role: String = ViewerScene.Asset.ROLE_IMAGE) =
        ViewerScene.Asset(id, "assets/$id.png", "image/png", file.length(), ViewerPackageCodec.sha256(file), width, height, role, null)
    private fun writePng(bitmap: Bitmap, file: File) { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
    private fun id() = UUID.randomUUID().toString()
}
