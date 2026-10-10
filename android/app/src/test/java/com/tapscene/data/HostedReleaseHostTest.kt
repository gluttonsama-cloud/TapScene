package com.tapscene.data

import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Actual native PNG/SQLite + production seal/copy. Confirmations and all content are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HostedReleaseHostTest {
    @Before fun configureHost() {
        HostSqlite.configure()
        HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
    }
    @After fun closeHostDescriptors() = HostFileSyncShadow.reset()

    @Test fun sealedProvenanceAndExactSnapshotSurviveSourceDeletion() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            AiDraftImportFixtures.isolated(RuntimeEnvironment.getApplication(), "hosted-release") { app ->
                val fixture = AiDraftImportFixtures.complete(app)
                val imports = AiDraftImportFixtures.importStore(app)
                val prepared = fixture.zip.inputStream().use { imports.prepare(it) }
                val projectId = checkNotNull(imports.commit(prepared.sessionId, prepared.previewDigest).projectId)
                val projects = AiDraftImportFixtures.projectStore(app)
                AiDraftImportFixtures.reviewDraftRegions(projects, projectId)
                val sealed = AiDraftImportFixtures.sealFixture(app, projectId, fixture)
                val store = ReleaseStore(app)
                val binding = store.readHostedRelease(sealed.id, sealed.contentDigest)
                check(binding.projectId == projectId)
                check(binding.projectRevision == checkNotNull(projects.readProject(projectId)).project.revision)
                check(binding.scene.releaseId == sealed.id && binding.summary == sealed)
                val release = File(app.noBackupFilesDir, "release-store/releases/${sealed.id}")
                val review = File(release, "review.json")
                val originalReview = review.readBytes()
                suspend fun rejected(block: suspend () -> Unit) { check(runCatching { block() }.isFailure) }
                rejected { store.readHostedRelease(sealed.id, "0".repeat(64)) }
                for (change in listOf<(JSONObject) -> Unit>(
                    { it.put("contentDigest", "0".repeat(64)) },
                    { it.put("fileListDigest", "0".repeat(64)) },
                    { it.put("projectRevision", 0) },
                    { it.put("projectId", "not-a-project-id") },
                    { it.put("summaryReviewed", false) },
                )) {
                    val changed = JSONObject(originalReview.toString(Charsets.UTF_8)); change(changed)
                    review.writeText(changed.toString())
                    rejected { store.readHostedRelease(sealed.id, sealed.contentDigest) }
                    review.writeBytes(originalReview)
                }
                // Only declared safe payload is copied, even if unrelated private files exist nearby.
                File(release, "original-source.private").writeText("SYNTHETIC PRIVATE SOURCE, NEVER UPLOAD")
                File(release, "ocr.private").writeText("SYNTHETIC PRIVATE OCR, NEVER UPLOAD")
                val payload = File(app.noBackupFilesDir, "hosted-v1/staging/synthetic/payload").apply { check(mkdirs()) }
                val copied = store.copyHostedRelease(sealed.id, sealed.contentDigest, payload)
                check(copied.projectId == binding.projectId && copied.fileListDigest == binding.fileListDigest)
                val expected = setOf("scene.json") + binding.scene.assets.map { it.path }
                fun hashes() = payload.walkTopDown().filter { it.isFile }.associate {
                    it.relativeTo(payload).invariantSeparatorsPath to ViewerPackageCodec.sha256(it)
                }
                val before = hashes()
                check(before.keys == expected)
                check(payload.walkTopDown().filter { it.isFile }.sumOf { it.length() } == binding.uploadByteLength)
                check(File(payload, "scene.json").readBytes().contentEquals(ViewerPackageCodec.writeScene(binding.scene)))
                binding.scene.assets.forEach { check(before[it.path] == it.sha256) }
                rejected { store.copyHostedRelease(sealed.id, sealed.contentDigest, payload) }
                projects.deleteProject(projectId)
                check(projects.readProject(projectId) == null)
                check(store.readHostedRelease(sealed.id, sealed.contentDigest).projectId == projectId)
                store.deleteRelease(sealed.id)
                check(hashes() == before)
                // A valid external viewing package has no locally sealed review provenance.
                val external = File(app.noBackupFilesDir, "external.tapscene")
                ViewerPackageCodec.writePackage(fixture.scene, fixture.assetRoot, external, {}, null)
                val imported = external.inputStream().use { store.importPackage(it) }
                check(imported.origin == "imported")
                rejected { store.readHostedRelease(imported.id, imported.contentDigest) }
                println("HOST_HOSTED_RELEASE native PNG/SQLite local-review-binding/canonical-whitelist/import-refusal/source-delete PASS; network/Keystore/device NOT_RUN")
            }
        }
    }
}
