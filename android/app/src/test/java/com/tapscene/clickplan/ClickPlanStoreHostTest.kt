package com.tapscene.clickplan

import android.content.ContextWrapper
import com.tapscene.data.HostFileSyncShadow
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual host AtomicFile round trips + directory fsync. Not a device/power-loss guarantee. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
class ClickPlanStoreHostTest {
    private lateinit var root: File
    private lateinit var context: ContextWrapper

    @Before fun begin() {
        val application = RuntimeEnvironment.getApplication()
        root = File(application.noBackupFilesDir, "click-plan-fixture-${UUID.randomUUID()}")
        check(root.mkdirs())
        context = object : ContextWrapper(application) { override fun getNoBackupFilesDir(): File = root }
        HostFileSyncShadow.begin(root)
    }
    @After fun end() {
        try { HostFileSyncShadow.reset() } finally { root.deleteRecursively() }
    }

    @Test fun optimisticPlansRetainImmutableRunSnapshotAndRecoverUncertainIntentWithoutReplay() {
        val store = ClickPlanStore(context)
        val original = ClickPlan.create(UUID.randomUUID().toString(), "com.example.target", 1080, 1920, 0,
            listOf(ClickAction(x = 120, y = 240)))
        store.savePlan(original, null)
        val run = ClickRun.create(original, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 42, 100)
        store.beginRun(run)
        val running = run.copy(phase = ClickRunPhase.Running, journalRevision = 1,
            events = listOf(ClickRunEvent(ClickRunEventType.Started, diagnosticUptimeMs = 20)))
        store.saveRun(running)
        val uncertain = running.copy(journalRevision = 2,
            outcomes = listOf(ClickActionOutcome(original.actions.first().actionId, ClickActionStatus.Intent)),
            events = running.events + ClickRunEvent(ClickRunEventType.DispatchIntent, original.actions.first().actionId, 30))
        store.saveRun(uncertain)
        val edited = original.copy(revision = 2, actions = listOf(original.actions.first().copy(x = 300)))
        store.savePlan(edited, original.revision)
        expectFailure { store.savePlan(original.copy(revision = 2), original.revision) }
        expectFailure { store.saveRun(uncertain.copy(plan = edited, journalRevision = 3)) }

        val reopened = ClickPlanStore(context)
        assertEquals(edited, reopened.getPlan(original.projectId))
        assertEquals(original.digest, reopened.readRuns().single().plan.digest)
        val recovered = reopened.recoverInterruptedRuns(40).single()
        assertEquals(ClickRunPhase.Interrupted, recovered.phase)
        assertEquals(ClickStopReason.ProcessInterrupted, recovered.stopReason)
        assertEquals(ClickActionStatus.Unknown, recovered.outcomes.single().status)
        assertEquals(run.recordingSessionId, recovered.recordingSessionId)
        assertEquals(run.sourceId, recovered.sourceId)
        assertEquals(42L, recovered.generation)
        assertNull(recovered.outcomes.single().beforeFrameId)
        assertNull(recovered.outcomes.single().afterFrameId)
        assertEquals(recovered, ClickPlanStore(context).readRuns().single())
        assertEquals(recovered, reopened.recoverInterruptedRuns(50).single())
        expectFailure { reopened.saveRun(running.copy(journalRevision = recovered.journalRevision + 1)) }
        HostFileSyncShadow.assertDirectorySyncEvidence()
    }

    @Test fun draftDigestTamperingAndForeignFrameMappingAreRejectedAndPrivatePathIsUsed() {
        val store = ClickPlanStore(context)
        val draft = ClickPlan.create(UUID.randomUUID().toString(), "com.example.target", 100, 200, 0, emptyList())
        store.savePlan(draft, null)
        assertEquals(draft.digest, ClickPlanStore(context).getPlan(draft.projectId)?.digest)
        val file = File(root, "click-plans/plans/${draft.projectId}.json")
        val json = JSONObject(file.readText()).put("width", 101)
        file.writeText(json.toString())
        expectFailure { store.getPlan(draft.projectId) }
        expectFailure { store.savePlan(draft.copy(revision = 2), 1) }

        val plan = draft.copy(actions = listOf(ClickAction(x = 1, y = 2)))
        val run = ClickRun.create(plan, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 100)
        store.beginRun(run)
        val journal = File(root, "click-plans/runs/${run.runId}.json")
        val runJson = JSONObject(journal.readText())
        runJson.getJSONArray("outcomes").getJSONObject(0).put("beforeFrameId", UUID.randomUUID().toString())
        journal.writeText(runJson.toString())
        assertTrue(store.readRuns().isEmpty())
        assertTrue(store.recoverInterruptedRuns(10).isEmpty())
        assertTrue(journal.exists()) // Do not delete or reinterpret an invalid journal.
    }

    private fun expectFailure(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Expected a rejected operation", failed)
    }
}
