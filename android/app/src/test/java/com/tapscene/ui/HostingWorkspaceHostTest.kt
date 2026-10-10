package com.tapscene.ui

import androidx.lifecycle.ViewModelStore
import com.tapscene.data.HostedReleaseBinding
import com.tapscene.data.ReleaseSummary
import com.tapscene.hosting.HostedApi
import com.tapscene.hosting.HostedModels
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Production workspace, fake IO only. This does not claim device UI or real HTTP coverage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class HostingWorkspaceHostTest {
    @Test fun repeatedConfirmPreparesOnceAndBusyObservationCanCancel() = runBlocking {
        fixture { workspace, backend ->
            select(workspace)
            main { workspace.confirmPublication(); workspace.confirmPublication() }
            backend.prepared.await()
            check(backend.prepareCalls.get() == 1)
            backend.allowPrepare.complete(Unit)
            backend.resuming.await()
            check(workspace.state.value.busy && workspace.state.value.observing)
            main { workspace.cancelTask(); workspace.cancelTask() }
            backend.cancelRecorded.await()
            workspace.state.first { !it.busy && it.task?.state == "cancelled" }
            check(backend.cancelCalls.get() == 1)
            check(workspace.state.value.task?.cancelRequested == true)
            println("HOST_HOSTING_WORKSPACE single-confirm/busy-cancel PASS")
        }
    }

    @Test fun confirmedCancelIntentSurvivesImmediateLeave() = runBlocking {
        fixture { workspace, backend ->
            backend.allowPrepare.complete(Unit)
            select(workspace)
            main { workspace.confirmPublication() }
            backend.resuming.await()
            main { workspace.cancelTask(); workspace.leave() }
            backend.cancelRecorded.await()
            check(backend.cancelCalls.get() == 1 && backend.task.cancelRequested)
            check(!workspace.state.value.busy && !workspace.state.value.observing)
            main { workspace.open(HostingPage.TASKS) }
            workspace.state.first { !it.busy && it.tasks.isNotEmpty() }
            check(workspace.state.value.tasks.single().cancelRequested)
            println("HOST_HOSTING_WORKSPACE confirmed-cancel/immediate-leave/reopen PASS")
        }
    }

    @Test fun cancelledLoginDrainsAndRevokesLateSessionWithoutResurrectingRoute() = runBlocking {
        fixture { workspace, backend ->
            backend.active = null
            main { workspace.open(HostingPage.ACCOUNT) }
            workspace.state.first { !it.busy }
            main { workspace.editEmail("author@example.test"); workspace.requestCode() }
            workspace.state.first { it.challengeReady && !it.busy }
            main { workspace.editCode("123456"); workspace.verifyCode() }
            backend.loginStarted.await()
            main { workspace.leave(); workspace.open(HostingPage.SOURCE) }
            workspace.state.first { !it.busy && it.page == HostingPage.SOURCE }
            backend.loginResult.complete(backend.session)
            backend.revoked.await()
            check(backend.installCalls.get() == 0)
            check(workspace.state.value.account == null && workspace.state.value.page == HostingPage.SOURCE)
            check(workspace.state.value.code.isEmpty() && !workspace.state.value.challengeReady)
            println("HOST_HOSTING_WORKSPACE late-login/revoke/no-route-resurrection PASS")
        }
    }

    @Test fun cancellingAfterInstallCannotReopenAccountWhenRetirementIsOffline() = runBlocking {
        fixture { workspace, backend ->
            backend.active = null
            backend.pauseInstall = true
            backend.retireOffline = true
            main { workspace.open(HostingPage.ACCOUNT) }
            workspace.state.first { !it.busy }
            main { workspace.editEmail("author@example.test"); workspace.requestCode() }
            workspace.state.first { it.challengeReady && !it.busy }
            backend.loginResult.complete(backend.session)
            main { workspace.editCode("123456"); workspace.verifyCode() }
            backend.installStarted.await()
            main { workspace.leave(); workspace.open(HostingPage.SOURCE) }
            backend.allowInstall.complete(Unit)
            backend.revoked.await()
            workspace.state.first { !it.busy && it.page == HostingPage.SOURCE }
            check(workspace.state.value.account == null && backend.active == null)
            check(workspace.state.value.pendingSessionRevocations == 1)
            println("HOST_HOSTING_WORKSPACE installed-cancel/offline-retire/no-account-resurrection PASS")
        }
    }

    @Test fun unauthorizedManagementAllowsNewLoginWithoutLogout() = runBlocking {
        fixture { workspace, backend ->
            backend.projectsError = HostedApi.ApiException(401, "AUTH_REQUIRED", 0)
            main { workspace.open(HostingPage.PROJECTS) }
            workspace.state.first { !it.busy && it.page == HostingPage.ACCOUNT }
            check(workspace.state.value.account == null)
            main { workspace.editEmail("other@example.test"); workspace.requestCode() }
            workspace.state.first { it.challengeReady && !it.busy }
            check(backend.requestedEmail == "other@example.test")
            check(backend.logoutCalls == 0)
            println("HOST_HOSTING_WORKSPACE expired-session/can-relogin PASS")
        }
    }

    @Test fun malformedSuccessfulLoginResponseIsUnknownNotAReusableChallenge() = runBlocking {
        fixture { workspace, backend ->
            backend.active = null
            backend.loginError = HostedApi.ApiException(201, "INVALID_RESPONSE", 0)
            main { workspace.open(HostingPage.ACCOUNT) }
            workspace.state.first { !it.busy }
            main { workspace.editEmail("author@example.test"); workspace.requestCode() }
            workspace.state.first { it.challengeReady && !it.busy }
            main { workspace.editCode("123456"); workspace.verifyCode() }
            workspace.state.first { !it.busy && it.message.orEmpty().contains("未知会话") }
            check(!workspace.state.value.challengeReady && workspace.state.value.code.isEmpty())
            check(workspace.state.value.account == null && backend.installCalls.get() == 0)
            println("HOST_HOSTING_WORKSPACE lost-session-response/one-day-warning PASS")
        }
    }

    private suspend fun select(workspace: HostingWorkspace) {
        main { workspace.open(HostingPage.SOURCE) }
        workspace.state.first { !it.busy && it.releases.isNotEmpty() }
        check(workspace.state.value.selected == null)
        main { workspace.selectRelease(workspace.state.value.releases.single().id) }
        workspace.state.first { !it.busy && it.page == HostingPage.CONFIRM }
    }
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun fixture(action: suspend (HostingWorkspace, FakeBackend) -> Unit) = withTimeout(15_000) {
        val owner = ViewModelStore()
        val backend = FakeBackend()
        val workspace = withContext(Dispatchers.Main) {
            HostingWorkspace(RuntimeEnvironment.getApplication(), backend).also { owner.put("hosting", it) }
        }
        try { action(workspace, backend) }
        finally { main { workspace.leave(); owner.clear() } }
    }

    private class FakeBackend : HostingBackend {
        fun id(name: String) = UUID.nameUUIDFromBytes(name.toByteArray()).toString()
        val session = HostedModels.Session(HostedModels.Account(id("account"), "author@example.test"), "2027-01-01T00:00:00Z", "synthetic-token")
        @Volatile var active: HostedModels.Session? = session
        val scene = ViewerScene(id("release"), "Synthetic sealed source", "Synthetic only", 1L, id("state"),
            listOf(ViewerScene.State(id("state"), id("image"), 1, 1, "End", "", "recorded", true)),
            emptyList(), emptyList(), listOf(ViewerScene.Asset(id("image"), "assets/${id("image")}.png", "image/png", 100L, "0".repeat(64), 1, 1)))
        val summary = ReleaseSummary(scene.releaseId, scene.title, ViewerPackageCodec.contentDigest(scene), 1L, 1, 100L, "local")
        val binding = HostedReleaseBinding(summary, id("local-project"), 1L, scene, "1".repeat(64))
        @Volatile var task = task("prepared", false)
        val prepareCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()
        val installCalls = AtomicInteger()
        val prepared = CompletableDeferred<Unit>()
        val allowPrepare = CompletableDeferred<Unit>()
        val resuming = CompletableDeferred<Unit>()
        val cancelRecorded = CompletableDeferred<Unit>()
        val loginStarted = CompletableDeferred<Unit>()
        val loginResult = CompletableDeferred<HostedModels.Session>()
        val revoked = CompletableDeferred<Unit>()
        val installStarted = CompletableDeferred<Unit>()
        val allowInstall = CompletableDeferred<Unit>()
        var pauseInstall = false
        var retireOffline = false
        @Volatile var pendingSessions = 0
        @Volatile var requestedEmail: String? = null
        var logoutCalls = 0
        var projectsError: Exception? = null
        var loginError: Exception? = null
        override suspend fun session() = active
        override suspend fun localReleases() = listOf(summary)
        override suspend fun readRelease(id: String, digest: String) = binding.also { check(id == summary.id && digest == summary.contentDigest) }
        override suspend fun requestChallenge(email: String) = HostedModels.Challenge(id("challenge"), "2027-01-01T00:00:00Z", 0).also { requestedEmail = email }
        override suspend fun createSession(challengeId: String, code: String): HostedModels.Session {
            loginStarted.complete(Unit)
            loginError?.let { throw it }
            return loginResult.await()
        }
        override suspend fun installSession(session: HostedModels.Session) {
            installCalls.incrementAndGet(); active = session; installStarted.complete(Unit)
            if (pauseInstall) allowInstall.await()
        }
        override suspend fun revokeSession(session: HostedModels.Session) { if (active?.bearerToken() == session.bearerToken()) active = null; revoked.complete(Unit) }
        override suspend fun retireSession(session: HostedModels.Session) {
            pendingSessions = 1
            if (active?.bearerToken() == session.bearerToken()) active = null
            revoked.complete(Unit)
            if (retireOffline) throw java.io.IOException("Synthetic offline cleanup")
            pendingSessions = 0
        }
        override suspend fun logout() { logoutCalls++; active = null }
        override suspend fun pendingSessionRevocationCount() = pendingSessions
        override suspend fun retryPendingSessionRevocations() = Unit
        override suspend fun pendingRevocations() = emptySet<String>()
        override suspend fun tasks() = if (prepareCalls.get() > 0) listOf(task) else emptyList()
        override suspend fun preparePublication(id: String, digest: String, days: Int): HostedModels.Task {
            check(id == binding.summary.id && digest == binding.summary.contentDigest && days == 7)
            prepareCalls.incrementAndGet(); prepared.complete(Unit); allowPrepare.await(); return task
        }
        override suspend fun resumePublication(id: String, onUpdate: (HostedModels.Task) -> Unit): HostedModels.Task {
            check(id == task.taskId)
            task = task("receiving", false); onUpdate(task); resuming.complete(Unit)
            awaitCancellation()
        }
        override suspend fun cancelPublication(id: String): HostedModels.Task {
            // Same cancellation boundary as Repository: persistence is independent of route lifetime.
            withContext(NonCancellable) { cancelCalls.incrementAndGet(); task = task("cancelled", true); cancelRecorded.complete(Unit) }
            return task
        }
        override suspend fun projectsPage(cursor: String?): HostedModels.Page<HostedModels.Project> {
            projectsError?.let { throw it }; return HostedModels.Page(emptyList(), null)
        }
        override suspend fun publicationsPage(project: String, cursor: String?) = HostedModels.Page<HostedModels.Publication>(emptyList(), null)
        override suspend fun cachedPublications(project: String) = emptyList<HostedModels.Publication>()
        override suspend fun publication(id: String): HostedModels.Publication = error("Not used by this test")
        override suspend fun revokePublication(id: String): HostedModels.Revocation = error("Not used by this test")
        private fun task(state: String, cancel: Boolean) = HostedModels.Task(id("task"), HOSTED_LOCAL_ENDPOINT, session.account.accountId,
            binding.projectId, summary.id, summary.contentDigest, summary.title, 1L, 100L, 7, 1, "{}", binding.fileListDigest,
            id("create"), id("commit"), "{}", "{}", id("server-project"), id("upload"), state, cancel, null, null)
    }
}
