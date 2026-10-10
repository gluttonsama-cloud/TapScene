package com.tapscene.data

import android.content.Context
import com.tapscene.packageformat.AiPackageCodec
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File

/** Private animation edits must preserve exact values and broken references until explicit repair. */
object DraftAiConfigChecks {
    suspend fun run(context: Context, status: (String) -> Unit) =
        AiDraftImportFixtures.isolated(context, "draft-ai-config-checks") { isolated ->
            checkConfig(isolated, status)
        }

    private suspend fun checkConfig(context: Context, status: (String) -> Unit) {
        val fixture = AiDraftImportFixtures.complete(context)
        val imports = AiDraftImportFixtures.importStore(context)
        val projects = AiDraftImportFixtures.projectStore(context)
        val releases = ReleaseStore(context)
        val preview = fixture.zip.inputStream().use { imports.prepare(it) }
        val projectId = checkNotNull(imports.commit(preview.sessionId, preview.previewDigest).projectId)
        var project = checkNotNull(projects.readProject(projectId))
        val original = checkNotNull(projects.readDraftAiConfig(projectId))
        check(original.boundRevision == project.project.revision && !original.needsRepair)
        check(original.width == 1920 && original.height == 1080)
        // Rebuilding against the original scene proves every visit/effect/rect/text/timing and
        // crossfade timeline was imported exactly, rather than recreated from UI defaults.
        check(original.resolve(fixture.scene).toBytes().contentEquals(fixture.plan.toBytes()))
        val originalJson = DraftAiConfigCodec.encode(original)
        val recovered = checkNotNull(AiDraftImportFixtures.projectStore(context).readDraftAiConfig(projectId))
        check(DraftAiConfigCodec.encode(recovered) == originalJson)
        check(recovered.effects.map { it.id } == original.effects.map { it.id })
        check(recovered.effects.map { it.id }.distinct().size == original.effects.size)
        val annotations = recovered.effects.filter { it.value.type == "annotation" }
        check(annotations.size == 2 && annotations[0].id != annotations[1].id)
        check(annotations.map { it.value.text } == listOf("First annotation", "Second annotation"))
        check(annotations.map { it.value.startFrame } == listOf(10, 40))

        val editedId = annotations[1].id
        val editedEffects = original.effects.map { entry ->
            if (entry.id != editedId) entry else entry.copy(value = entry.value.let { effect ->
                RenderPlan.Effect(effect.type, effect.visitId, effect.startFrame, effect.durationFrames,
                    effect.hotspotId, effect.regionId, "Only the second annotation changes", effect.rect)
            })
        }
        val beforePlanEditRevision = project.project.revision
        val edited = projects.saveDraftAiConfig(projectId, project.project.revision, original.copy(effects = editedEffects))
        project = checkNotNull(projects.readProject(projectId))
        check(project.project.revision == beforePlanEditRevision + 1 && edited.boundRevision == project.project.revision)
        val editedRecovered = checkNotNull(AiDraftImportFixtures.projectStore(context).readDraftAiConfig(projectId))
        check(!edited.needsRepair && DraftAiConfigCodec.encode(editedRecovered) == DraftAiConfigCodec.encode(edited))
        check(editedRecovered.effects.map { it.id } == original.effects.map { it.id })
        check(editedRecovered.effects.single { it.id == annotations[0].id }.value.text == "First annotation")
        check(editedRecovered.effects.single { it.id == editedId }.value.text == "Only the second annotation changes")
        rejected { projects.saveDraftAiConfig(projectId, project.project.revision,
            edited.copy(effects = edited.effects + edited.effects.first())) }
        check(DraftAiConfigCodec.encode(checkNotNull(projects.readDraftAiConfig(projectId))) == DraftAiConfigCodec.encode(edited))
        status("PASS draft AI identity: exact imported canvas/visits/effects/timeline survive persistence, repeated annotation types keep independent stable IDs, editing one preserves the other, duplicate IDs are rejected")

        val shortened = original.copy(visits = original.visits.mapIndexed { index, visit ->
            RenderPlan.Visit(visit.visitId, visit.stateId, visit.selectedEdgeId, if (index == 0) 12 else visit.holdFrames)
        })
        val broken = projects.saveDraftAiConfig(projectId, project.project.revision, shortened)
        project = checkNotNull(projects.readProject(projectId))
        check(broken.needsRepair && projects.draftAiIssues(projectId, broken).any { it.contains("停留时间") })
        val brokenRecovered = checkNotNull(AiDraftImportFixtures.projectStore(context).readDraftAiConfig(projectId))
        check(brokenRecovered.needsRepair && brokenRecovered.visits.first().holdFrames == 12)
        check(DraftAiConfigCodec.encode(brokenRecovered.copy(visits = original.visits)) == originalJson)
        check(brokenRecovered.effects.single { it.id == annotations[1].id }.value.startFrame == 40)
        check(brokenRecovered.effects.single { it.id == annotations[1].id }.value.durationFrames == 15)
        rejected { releases.createCandidate(projectId, project.project.revision) }
        check(releases.listCandidates().isEmpty())
        projects.saveDraftAiConfig(projectId, project.project.revision, original)
        AiDraftImportFixtures.reviewDraftRegions(projects, projectId)
        project = checkNotNull(projects.readProject(projectId))
        val repaired = checkNotNull(projects.readDraftAiConfig(projectId))
        check(!repaired.needsRepair && repaired.boundRevision == project.project.revision)
        status("PASS draft AI repair: shorter hold persists every out-of-range effect unchanged and blocks release; explicit repair/review rebinds to the current revision")

        val candidate = releases.createCandidate(projectId, project.project.revision)
        val candidatePlanFile = File(context.noBackupFilesDir, "release-store/candidates/${candidate.id}/draft-plan.json")
        val candidatePlanBytes = candidatePlanFile.readBytes()
        val candidatePlan = RenderPlan.parse(candidate.scene, candidatePlanBytes)
        check(candidatePlan.releaseId == candidate.id && candidatePlan.contentDigest == candidate.contentDigest)
        check(candidatePlan.releaseId != fixture.plan.releaseId && candidatePlan.contentDigest != fixture.plan.contentDigest)
        check(candidatePlanBytes.contentEquals(repaired.resolve(candidate.scene).toBytes()))
        check(candidate.reviewedStateIds.isEmpty() && candidate.reviewedRegionIds.isEmpty() && candidate.visitedEdgeIds.isEmpty())
        check(!candidate.summaryReviewed && !candidate.fileListReviewed && !candidate.completedPath)
        rejected { RenderPlan.parse(candidate.scene, fixture.plan.toBytes()) }

        val planOnly = projects.saveDraftAiConfig(projectId, project.project.revision, repaired.copy(width = 1080, height = 1920))
        val planOnlyProject = checkNotNull(projects.readProject(projectId))
        check(planOnlyProject.project.revision == project.project.revision + 1 && planOnly.boundRevision == planOnlyProject.project.revision)
        rejected { releases.createCandidate(projectId, planOnlyProject.project.revision) }
        check(checkNotNull(releases.readCandidate(projectId)).id == candidate.id)
        check(candidatePlanFile.readBytes().contentEquals(candidatePlanBytes))
        project = projects.renameProject(projectId, "Changed after the first fixed candidate")
        val stale = checkNotNull(projects.readDraftAiConfig(projectId))
        check(stale.needsRepair && stale.boundRevision < project.project.revision)
        rejected { releases.createCandidate(projectId, project.project.revision, replaceExisting = true) }
        check(checkNotNull(releases.readCandidate(projectId)).id == candidate.id)
        check(candidatePlanFile.readBytes().contentEquals(candidatePlanBytes))
        val fresh = projects.saveDraftAiConfig(projectId, project.project.revision, stale)
        project = checkNotNull(projects.readProject(projectId))
        check(!fresh.needsRepair && fresh.boundRevision == project.project.revision)
        val replacement = releases.createCandidate(projectId, project.project.revision, replaceExisting = true)
        check(replacement.id != candidate.id && replacement.contentDigest != candidate.contentDigest)
        val replacementPlanBytes = File(context.noBackupFilesDir, "release-store/candidates/${replacement.id}/draft-plan.json").readBytes()
        val replacementPlan = RenderPlan.parse(replacement.scene, replacementPlanBytes)
        check(replacementPlanBytes.contentEquals(fresh.resolve(replacement.scene).toBytes()))
        check(replacementPlan.releaseId == replacement.id && replacementPlan.contentDigest == replacement.contentDigest)
        rejected { RenderPlan.parse(replacement.scene, candidatePlanBytes) }
        rejected { releases.seal(replacement.id, replacement.contentDigest) }
        val sealed = AiDraftImportFixtures.sealFixture(context, projectId, fixture)
        check(sealed.id == replacement.id)
        val sealedPlan = checkNotNull(ReleaseStore(context).readAiPlan(sealed.id))
        check(sealedPlan.toBytes().contentEquals(replacementPlanBytes))
        check(sealedPlan.contentDigest == sealed.contentDigest && sealedPlan.releaseId == sealed.id)
        rejected { releases.exportAiRelease(sealed.id, fixture.plan) }
        val output = releases.exportAiRelease(sealed.id, sealedPlan)
        val checkedRoot = File(context.noBackupFilesDir, "verified-new-ai-release").also { check(it.mkdir()) }
        val checked = AiPackageCodec.readPackage(output, checkedRoot, {}, null)
        check(checked.renderPlan.toBytes().contentEquals(replacementPlanBytes) && checked.contentDigest == sealed.contentDigest)
        val sealedSceneBytes = ViewerPackageCodec.writeScene(releases.loadRelease(sealed.id))
        status("PASS draft AI release binding: candidate and replacement use their own immutable release ID/digest, inherit no review, old plan is rejected, fresh review seals and exports the exact rebound plan")

        val beforeDelete = checkNotNull(projects.readDraftAiConfig(projectId))
        val beforeDeleteJson = DraftAiConfigCodec.encode(beforeDelete)
        val regionId = fixture.scene.regions.single().id
        project = projects.deleteRegion(projectId, regionId, project.project.revision)
        var dangling = checkNotNull(AiDraftImportFixtures.projectStore(context).readDraftAiConfig(projectId))
        check(dangling.needsRepair && DraftAiConfigCodec.encode(dangling) == beforeDeleteJson)
        check(dangling.effects.any { it.value.regionId == regionId })
        check(projects.draftAiIssues(projectId, dangling).any { it.contains("区域已删除") })
        dangling = projects.saveDraftAiConfig(projectId, project.project.revision, dangling)
        project = checkNotNull(projects.readProject(projectId))
        check(dangling.needsRepair && DraftAiConfigCodec.encode(dangling) == beforeDeleteJson)
        rejected { releases.createCandidate(projectId, project.project.revision) }

        project = projects.deleteStep(projectId, fixture.endId).snapshot
        dangling = checkNotNull(AiDraftImportFixtures.projectStore(context).readDraftAiConfig(projectId))
        check(dangling.needsRepair && DraftAiConfigCodec.encode(dangling) == beforeDeleteJson)
        check(dangling.visits.any { it.stateId == fixture.endId })
        check(dangling.visits.any { it.selectedEdgeId == fixture.nextEdgeId })
        check(dangling.effects.map { it.id } == original.effects.map { it.id })
        check(projects.draftAiIssues(projectId, dangling).any { it.contains("步骤已删除") })
        check(projects.saveDraftAiConfig(projectId, project.project.revision, dangling).needsRepair)
        project = checkNotNull(projects.readProject(projectId))
        rejected { releases.createCandidate(projectId, project.project.revision) }
        check(ViewerPackageCodec.writeScene(releases.loadRelease(sealed.id)).contentEquals(sealedSceneBytes))
        check(checkNotNull(releases.readAiPlan(sealed.id)).toBytes().contentEquals(replacementPlanBytes))
        status("PASS draft AI dangling references: deleting region/step marks repair without dropping visits/effects/IDs/parameters; old fixed release and rebound plan remain unchanged")
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        check(failure is IllegalArgumentException || failure is IllegalStateException) { "Expected rejected draft AI action, got $failure" }
    }
}
