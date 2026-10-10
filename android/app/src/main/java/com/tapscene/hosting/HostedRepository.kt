package com.tapscene.hosting

import android.content.Context
import com.tapscene.BuildConfig
import com.tapscene.data.HostedReleaseBinding
import com.tapscene.data.ReleaseStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Explicit user actions only. Reopening the UI never starts or resumes a network task. */
class HostedRepository(
    context: Context,
    private val api: HostedApi = HostedApi(),
    private val vault: SessionVault = AndroidKeystoreSessionVault(context),
) {
    private val app = context.applicationContext
    private val releases by lazy { ReleaseStore(app) }
    private val store by lazy { HostedStore(File(app.noBackupFilesDir.canonicalFile, "hosted-v1")) { directory -> AndroidKeystoreSessionVault.syncDirectory(directory) } }
    private val sessions by lazy { HostedSessions(api, vault) }
    private val engine by lazy { HostedEngine(store, api) { expected ->
        enabled()
        val current = sessions.current()
        if (current == null || current.account.accountId != expected.account.accountId || current.bearerToken() != expected.bearerToken()) {
            throw HostedApi.ApiException(401, "SESSION_CHANGED", 0)
        }
    } }
    private fun enabled() { check(BuildConfig.HOSTED_ENABLED) { "HOSTED_DISABLED" } }
    private fun requireSession(): HostedModels.Session {
        enabled()
        return sessions.current() ?: throw HostedApi.ApiException(401, "AUTH_REQUIRED", 0)
    }
    suspend fun session(): HostedModels.Session? = withContext(Dispatchers.IO) {
        if (BuildConfig.HOSTED_ENABLED) sessions.current() else null
    }
    suspend fun capabilities(): HostedModels.Capabilities = runInterruptible(Dispatchers.IO) { enabled(); api.capabilities() }
    suspend fun requestChallenge(email: String): HostedModels.Challenge = runInterruptible(Dispatchers.IO) { enabled(); api.requestChallenge(email) }
    /** Does not install the token. The caller revokes a response arriving after login cancellation. */
    suspend fun createSession(challengeId: String, code: String): HostedModels.Session {
        var created: HostedModels.Session? = null
        try {
            return withContext(Dispatchers.IO) {
                enabled(); sessions.create(challengeId, code).also { created = it }
            }
        } catch (cancelled: CancellationException) {
            // Preserve the result across the dispatcher cancellation boundary so a late token is not installed.
            created?.let { retireSession(it) }
            throw cancelled
        }
    }
    suspend fun installSession(session: HostedModels.Session) = withContext(Dispatchers.IO + NonCancellable) {
        enabled(); sessions.install(session)
    }
    suspend fun revokeSession(session: HostedModels.Session) = withContext(Dispatchers.IO + NonCancellable) {
        enabled(); sessions.revoke(session)
    }
    suspend fun retireSession(session: HostedModels.Session) = withContext(Dispatchers.IO + NonCancellable) {
        enabled(); sessions.retire(session)
    }
    suspend fun pendingSessionRevocationCount(): Int = withContext(Dispatchers.IO) {
        enabled(); sessions.pendingCount()
    }
    suspend fun retryPendingSessionRevocations() = withContext(Dispatchers.IO + NonCancellable) {
        enabled(); sessions.retryPending()
    }
    suspend fun logout() { val session = session() ?: return; revokeSession(session) }
    suspend fun readRelease(releaseId: String, contentDigest: String): HostedReleaseBinding = withContext(Dispatchers.IO) {
        enabled(); releases.readHostedRelease(releaseId, contentDigest)
    }
    suspend fun tasks(): List<HostedModels.Task> = withContext(Dispatchers.IO) {
        val session = session() ?: return@withContext emptyList()
        store.tasks(session.account.accountId)
    }
    suspend fun preparePublication(releaseId: String, contentDigest: String, expiryDays: Int): HostedModels.Task = withContext(Dispatchers.IO) {
        val session = requireSession()
        preparation.withLock {
            store.tasks(session.account.accountId).firstOrNull { it.releaseId == releaseId && it.contentDigest == contentDigest }?.let {
                check(it.expiryDays == expiryDays) { "RELEASE_TASK_EXISTS" }
                return@withLock it
            }
            val caps = api.capabilities()
            check(expiryDays in caps.expiryDays) { "INVALID_EXPIRY" }
            currentCoroutineContext().ensureActive()
            val stage = store.begin(session.account.accountId, releaseId, contentDigest)
            try {
                // This is the only ReleaseStore lock. It ends before any hosted project/upload request.
                val binding = releases.copyHostedRelease(releaseId, contentDigest, stage.payload)
                check(binding.scene.schemaVersion.toString() in caps.schemaVersions && binding.scene.policyVersion in caps.policyVersions) { "UNSUPPORTED_PROFILE" }
                check(binding.uploadByteLength <= caps.packageBytes) { "PACKAGE_BYTES_EXCEEDED" }
                currentCoroutineContext().ensureActive()
                val current = requireSession()
                check(current.account.accountId == session.account.accountId && current.bearerToken() == session.bearerToken()) { "SESSION_CHANGED" }
                store.install(stage, binding.projectId, binding.projectRevision, binding.summary.title, binding.fileListDigest, expiryDays)
            } catch (error: Throwable) {
                withContext(NonCancellable) { runCatching { store.abandon(stage) } }
                throw error
            }
        }
    }
    suspend fun resumePublication(taskId: String, onUpdate: (HostedModels.Task) -> Unit = {}): HostedModels.Task {
        val session = withContext(Dispatchers.IO) { requireSession() }
        val queryOnly = withContext(Dispatchers.IO) {
            val original = store.get(session.account.accountId, taskId)
            HostedEngine.terminal(original.state) && original.state !in listOf("cancelled", "expired", "failed") && !original.cancelRequested
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val task = runInterruptible(Dispatchers.IO) { engine.step(session, taskId) }
            onUpdate(task)
            if (queryOnly || task.publication != null || HostedEngine.terminal(task.state)) return task
            delay(1000)
        }
    }
    suspend fun cancelPublication(taskId: String): HostedModels.Task {
        // Persist first, without waiting for a running network call's task mutex.
        val session = withContext(Dispatchers.IO + NonCancellable) {
            requireSession().also { store.requestCancellation(it.account.accountId, taskId) }
        }
        return runInterruptible(Dispatchers.IO) { engine.step(session, taskId) }
    }
    suspend fun projectsPage(cursor: String? = null): HostedModels.Page<HostedModels.Project> = runInterruptible(Dispatchers.IO) {
        api.projects(requireSession(), cursor)
    }
    suspend fun publicationsPage(serverProjectId: String, cursor: String? = null): HostedModels.Page<HostedModels.Publication> = runInterruptible(Dispatchers.IO) {
        val session = requireSession()
        api.publications(session, serverProjectId, cursor).also { store.cachePublications(session.account.accountId, it.items) }
    }
    suspend fun publication(publicationId: String): HostedModels.Publication = runInterruptible(Dispatchers.IO) {
        val session = requireSession()
        api.publication(session, publicationId).also { store.cachePublications(session.account.accountId, listOf(it)) }
    }
    suspend fun cachedPublications(serverProjectId: String): List<HostedModels.Publication> = withContext(Dispatchers.IO) {
        store.cachedPublications(requireSession().account.accountId, serverProjectId)
    }
    suspend fun projects(): List<HostedModels.Project> {
        val result = mutableListOf<HostedModels.Project>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do { val page = projectsPage(cursor); result += page.items; cursor = page.nextCursor
            check(cursor == null || seen.add(cursor)) { "PAGINATION_LOOP" }
        } while (cursor != null)
        return result
    }
    suspend fun publications(serverProjectId: String): List<HostedModels.Publication> {
        val result = mutableListOf<HostedModels.Publication>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do { val page = publicationsPage(serverProjectId, cursor); result += page.items; cursor = page.nextCursor
            check(cursor == null || seen.add(cursor)) { "PAGINATION_LOOP" }
        } while (cursor != null)
        return result
    }
    /** Call only after the user confirms this exact publication. */
    suspend fun revokePublication(publicationId: String): HostedModels.Revocation = withContext(Dispatchers.IO) {
        val session = requireSession()
        store.requestRevocation(session.account.accountId, publicationId)
        api.revoke(session, publicationId).also { store.recordRevocation(session.account.accountId, it) }
    }
    suspend fun pendingRevocations(): Set<String> = withContext(Dispatchers.IO) {
        store.pendingRevocations(requireSession().account.accountId).toSet()
    }
    companion object { private val preparation = Mutex() }
}
