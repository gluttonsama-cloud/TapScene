package com.tapscene.data

import android.app.Application
import android.content.Context
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.packageformat.ViewerTraversal
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real private files and synthetic native PNG packages; no device/process-death claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackCheckpointHostTest {
    @Before fun configureHost() = HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
    @After fun closeHostDescriptors() = HostFileSyncShadow.reset()

    @Test fun freshOpenAndNewStoreRestoreExactHistoryBackRestartAndEnding() = fixtureTest { f ->
        var opened = f.store.openPlayback(f.scene.releaseId)
        check(opened.checkpoint == null && !opened.checkpointUnavailable)
        check(opened.session.releaseId == f.scene.releaseId && opened.session.contentDigest == f.digest)
        val before = f.packageHashes()
        val summary = File(f.release, "summary.json").readBytes()
        var expected = ViewerTraversal.start(f.scene).advance(f.scene, f.input.loopEdgeId)
            .advance(f.scene, f.input.nextEdgeId)
        check(f.store.savePlayback(opened.session, expected, 1))
        opened = ReleaseStore(f.app).openPlayback(f.scene.releaseId)
        same(expected, checkNotNull(opened.checkpoint))
        check(!opened.checkpointUnavailable && opened.checkpoint!!.ended)
        expected = expected.previous(f.scene)
        check(f.store.savePlayback(opened.session, expected, 1))
        opened = ReleaseStore(f.app).openPlayback(f.scene.releaseId)
        same(expected, checkNotNull(opened.checkpoint))
        check(opened.checkpoint!!.history == listOf(f.input.startId, f.input.startId))
        expected = expected.restart(f.scene)
        check(f.store.savePlayback(opened.session, expected, 1))
        opened = ReleaseStore(f.app).openPlayback(f.scene.releaseId)
        same(expected, checkNotNull(opened.checkpoint))
        check(opened.checkpoint!!.history == listOf(f.input.startId))
        check(f.packageHashes() == before && File(f.release, "summary.json").readBytes().contentEquals(summary))
        check(f.store.exportRelease(f.scene.releaseId).readBytes().contentEquals(f.packageBytes))
    }

    @Test fun invalidBoundedCheckpointsNeverResumeAnotherFrameOrChangePackage() = fixtureTest { f ->
        val opened = f.store.openPlayback(f.scene.releaseId)
        val expected = ViewerTraversal.start(f.scene).advance(f.scene, f.input.nextEdgeId)
        check(f.store.savePlayback(opened.session, expected, 1))
        val valid = f.checkpoint.readBytes()
        val packageBefore = f.packageHashes()
        val edits = listOf<Pair<String, (JSONObject) -> Unit>>(
            "version" to { it.put("version", 2) },
            "release" to { it.put("releaseId", id()) },
            "digest" to { it.put("contentDigest", "0".repeat(64)) },
            "missing state" to { it.put("currentStateId", id()) },
            "history mismatch" to { it.put("history", JSONArray(listOf(f.input.startId))) },
            "wrong start" to { it.put("history", JSONArray(listOf(f.input.endId))) },
            "graph jump" to { it.put("history", JSONArray(listOf(f.input.startId, f.input.branchId, f.input.endId))) },
            "history limit" to { it.put("history", JSONArray(List(ViewerTraversal.MAX_VISITS + 1) { f.input.startId })) },
            "foreign edge" to { it.put("visitedEdgeIds", JSONArray(listOf(id()))) },
            "duplicate edge" to { it.put("visitedEdgeIds", JSONArray(listOf(f.input.nextEdgeId, f.input.nextEdgeId))) },
            "end state" to { it.put("ended", false) },
            "end label" to { it.put("endLabel", "Another ending") },
            "end edge" to { it.put("endEdgeId", f.input.nextEdgeId) },
            "completion" to { it.put("completedFromStart", false) },
            "coerced boolean" to { it.put("ended", "true") },
            "missing field" to { it.remove("endEdgeId") },
            "pending target" to { it.put("pendingToStateId", f.input.branchId) },
        )
        val malformed = edits.map { (name, edit) ->
            name to JSONObject(valid.toString(Charsets.UTF_8)).also(edit).toString().toByteArray()
        } + listOf(
            "truncated" to "{".toByteArray(),
            "empty" to byteArrayOf(),
            "oversized" to ByteArray(PlaybackCheckpoint.MAX_BYTES + 1) { ' '.code.toByte() },
            "invalid UTF-8" to byteArrayOf(0xc3.toByte(), 0x28),
            "trailing JSON" to (valid + "{}".toByteArray()),
            "duplicate field" to ("{\"version\":1," + valid.toString(Charsets.UTF_8).drop(1)).toByteArray(),
            "nested input" to ("{\"history\":" + "[".repeat(5000) + "0" + "]".repeat(5000) + "}").toByteArray(),
        )
        for ((name, bytes) in malformed) {
            f.checkpoint.writeBytes(bytes)
            val result = ReleaseStore(f.app).openPlayback(f.scene.releaseId)
            check(result.checkpoint == null && result.checkpointUnavailable) { "Accepted $name" }
            check(f.checkpoint.readBytes().contentEquals(bytes)) { "Rewrote $name without a save" }
            check(f.packageHashes() == packageBefore) { "Changed immutable package for $name" }
        }
        f.checkpoint.writeBytes(valid)
        same(expected, checkNotNull(f.store.openPlayback(f.scene.releaseId).checkpoint))
    }

    @Test fun newerOpenRejectsDelayedAndForgedOwnersAcrossStoreInstances() = fixtureTest { f ->
        coroutineScope {
            val old = f.store.openPlayback(f.scene.releaseId)
            val releaseOldSave = CompletableDeferred<Unit>()
            val delayed = async {
                releaseOldSave.await()
                f.store.savePlayback(old.session, ViewerTraversal.start(f.scene), 1)
            }
            val other = ReleaseStore(f.app)
            val current = other.openPlayback(f.scene.releaseId)
            check(current.session.ownerToken != old.session.ownerToken)
            check(!f.store.isPlaybackCurrent(old.session) && f.store.isPlaybackCurrent(current.session))
            val expected = ViewerTraversal.start(f.scene).advance(f.scene, f.input.branchEdgeId)
            check(other.savePlayback(current.session, expected, 1))
            val bytes = f.checkpoint.readBytes()
            releaseOldSave.complete(Unit)
            check(!delayed.await())
            check(!other.savePlayback(current.session.copy(ownerToken = id()), ViewerTraversal.start(f.scene), 2))
            check(!other.savePlayback(current.session.copy(contentDigest = "0".repeat(64)), ViewerTraversal.start(f.scene), 2))
            check(f.checkpoint.readBytes().contentEquals(bytes))
            same(expected, checkNotNull(ReleaseStore(f.app).openPlayback(f.scene.releaseId).checkpoint))
        }
    }

    @Test fun deletionAndReimportRemoveProgressAndInvalidateOldOwners() = fixtureTest { f ->
        val old = f.store.openPlayback(f.scene.releaseId)
        check(f.store.savePlayback(old.session, ViewerTraversal.start(f.scene).advance(f.scene, f.input.nextEdgeId), 1))
        val packageBefore = f.packageHashes()
        f.store.deleteRelease(f.scene.releaseId)
        check(!f.release.exists() && !f.checkpoint.exists())
        check(!f.store.isPlaybackCurrent(old.session))
        check(!f.store.savePlayback(old.session, ViewerTraversal.start(f.scene), 1))
        f.store.importPackage(ByteArrayInputStream(f.packageBytes))
        // A save delayed past delete AND reimport must not create a checkpoint before a new open.
        check(!f.store.savePlayback(old.session, ViewerTraversal.start(f.scene), 1))
        check(!f.checkpoint.exists() && f.packageHashes() == packageBefore)
        check(!f.store.isPlaybackCurrent(old.session))
        val fresh = ReleaseStore(f.app).openPlayback(f.scene.releaseId)
        check(fresh.checkpoint == null && !fresh.checkpointUnavailable)
        check(fresh.session.ownerToken != old.session.ownerToken)
        check(!f.store.isPlaybackCurrent(old.session) && f.store.isPlaybackCurrent(fresh.session))
        check(!f.store.savePlayback(old.session, ViewerTraversal.start(f.scene), 1))
    }

    @Test fun sameOwnerLateSaveCannotReplaceNewerPublishedSequence() = fixtureTest { f ->
        val opened = f.store.openPlayback(f.scene.releaseId)
        val older = ViewerTraversal.start(f.scene).advance(f.scene, f.input.nextEdgeId)
        val newer = older.previous(f.scene).advance(f.scene, f.input.branchEdgeId)
        check(f.store.savePlayback(opened.session, newer, 2))
        val bytes = f.checkpoint.readBytes()
        for (sequence in listOf(-1L, 0L, 1L, 2L)) {
            check(!ReleaseStore(f.app).savePlayback(opened.session, older, sequence))
            check(f.checkpoint.readBytes().contentEquals(bytes))
        }
        same(newer, PlaybackCheckpoint.read(f.checkpoint, f.scene, f.digest))
        val restarted = newer.restart(f.scene)
        check(f.store.savePlayback(opened.session, restarted, 3))
        same(restarted, checkNotNull(ReleaseStore(f.app).openPlayback(f.scene.releaseId).checkpoint))
    }

    @Test fun corruptPackageFailsBeforeProgressAndExactInterruptedMetadataIsRecovered() = fixtureTest { f ->
        val old = f.store.openPlayback(f.scene.releaseId)
        val expected = ViewerTraversal.start(f.scene).advance(f.scene, f.input.nextEdgeId)
        check(f.store.savePlayback(old.session, expected, 1))
        val interrupted = File(f.release, ".playback.json-${id()}.part").apply { writeText("{") }
        val unrelated = File(f.release, ".playback.json-not-owned.part").apply { writeText("Keep") }
        val packageBefore = f.packageHashes()
        same(expected, checkNotNull(ReleaseStore(f.app).openPlayback(f.scene.releaseId).checkpoint))
        check(!interrupted.exists() && unrelated.readText() == "Keep" && f.packageHashes() == packageBefore)
        val image = File(File(f.release, "package"), f.scene.assets.first().path)
        val bytes = image.readBytes()
        image.writeBytes(bytes + byteArrayOf(1))
        check(runCatching { ReleaseStore(f.app).openPlayback(f.scene.releaseId) }.isFailure)
        image.writeBytes(bytes)
        same(expected, checkNotNull(ReleaseStore(f.app).openPlayback(f.scene.releaseId).checkpoint))
    }

    @Test fun invalidTraversalSavePreservesCommittedCheckpoint() = fixtureTest { f ->
        val opened = f.store.openPlayback(f.scene.releaseId)
        check(f.store.savePlayback(opened.session, ViewerTraversal.start(f.scene), 1))
        val before = f.checkpoint.readBytes()
        val invalid = ViewerTraversal(f.input.endId, listOf(f.input.startId, f.input.branchId, f.input.endId),
            true, "Chosen end", null, setOf(f.input.nextEdgeId), true)
        check(runCatching { f.store.savePlayback(opened.session, invalid, 2) }.isFailure)
        check(f.checkpoint.readBytes().contentEquals(before))
        // A failed save does not consume its sequence or prevent a valid retry.
        check(f.store.savePlayback(opened.session, ViewerTraversal.start(f.scene), 2))
        val json = JSONObject(before.toString(Charsets.UTF_8))
        check(json.keys().asSequence().toSet() == setOf("version", "releaseId", "contentDigest", "currentStateId",
            "history", "ended", "endLabel", "endEdgeId", "visitedEdgeIds", "completedFromStart"))
    }

    private fun fixtureTest(block: suspend (Fixture) -> Unit) = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            AiDraftImportFixtures.isolated(RuntimeEnvironment.getApplication(), "playback-checkpoint") { app ->
                val input = AiDraftImportFixtures.complete(app)
                val zip = File(app.noBackupFilesDir, "viewer.tapscene")
                ViewerPackageCodec.writePackage(input.scene, input.assetRoot, zip, {}, null)
                val bytes = zip.readBytes()
                val store = ReleaseStore(app)
                val imported = store.importPackage(ByteArrayInputStream(bytes))
                block(Fixture(app, store, input, imported.contentDigest, bytes))
            }
        }
    }

    private data class Fixture(val app: Context, val store: ReleaseStore,
        val input: AiDraftImportFixtures.Fixture, val digest: String, val packageBytes: ByteArray) {
        val scene: ViewerScene get() = input.scene
        val release: File get() = File(app.noBackupFilesDir, "release-store/releases/${scene.releaseId}")
        val checkpoint: File get() = File(release, "playback.json")
        fun packageHashes(): Map<String, String> {
            val payload = File(release, "package")
            return payload.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(payload).invariantSeparatorsPath to ViewerPackageCodec.sha256(it)
            }
        }
    }

    private fun same(expected: ViewerTraversal, actual: ViewerTraversal) {
        check(actual.currentStateId == expected.currentStateId && actual.history == expected.history &&
            actual.ended == expected.ended && actual.endLabel == expected.endLabel && actual.endEdgeId == expected.endEdgeId &&
            actual.visitedEdgeIds == expected.visitedEdgeIds && actual.completedFromStart == expected.completedFromStart)
        check(actual.pendingEdgeId == null && actual.pendingTransitionAssetId == null && actual.pendingToStateId == null &&
            actual.pendingEndLabel == null && actual.mediaRunId == 0L && !actual.transitionFailed && !actual.closed)
    }

    private fun id() = UUID.randomUUID().toString()
}
