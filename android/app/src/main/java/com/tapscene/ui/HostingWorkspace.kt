package com.tapscene.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.BuildConfig
import com.tapscene.data.HostedReleaseBinding
import com.tapscene.data.ReleaseStore
import com.tapscene.data.ReleaseSummary
import com.tapscene.hosting.HostedApi
import com.tapscene.hosting.HostedModels
import com.tapscene.hosting.HostedRepository
import com.tapscene.hosting.HostedSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

const val HOSTED_LOCAL_ENDPOINT = "http://127.0.0.1:4173"
enum class HostingPage { SOURCE, ACCOUNT, CONFIRM, TASKS, PROJECTS, VERSIONS }

/** No session token, OTP challenge identifier, current draft, or inferred release selection. */
data class HostingUiState(
    val page: HostingPage = HostingPage.SOURCE,
    val busy: Boolean = false,
    val observing: Boolean = false,
    val message: String? = null,
    val account: HostedModels.Account? = null,
    val sessionExpiresAt: String? = null,
    val email: String = "",
    val code: String = "",
    val challengeReady: Boolean = false,
    val challengeExpiresAt: String? = null,
    val resendAt: Long = 0,
    val releases: List<ReleaseSummary> = emptyList(),
    val selected: HostedReleaseBinding? = null,
    val expiryDays: Int = 7,
    val tasks: List<HostedModels.Task> = emptyList(),
    val task: HostedModels.Task? = null,
    val projects: List<HostedModels.Project> = emptyList(),
    val projectsCursor: String? = null,
    val project: HostedModels.Project? = null,
    val publications: List<HostedModels.Publication> = emptyList(),
    val publicationsCursor: String? = null,
    val versionsFresh: Boolean = false,
    val pendingRevocations: Set<String> = emptySet(),
    val pendingSessionRevocations: Int = 0,
)

/**
 * A route generation owns UI callbacks, never a durable publication. Leaving observation cancels
 * only this process's worker; an explicit Cancel records server cancellation through the repository.
 * Login deliberately drains an in-flight session response so a late bearer can be revoked.
 */
class HostingWorkspace private constructor(application: Application, private val repository: HostingBackend,
    private val enabled: Boolean) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, RepositoryHostingBackend(application), BuildConfig.HOSTED_ENABLED)
    internal constructor(application: Application, backend: HostingBackend) : this(application, backend, true)
    private val sessionInstallation = Mutex()
    private val mutableState = MutableStateFlow(HostingUiState())
    val state = mutableState.asStateFlow()
    @Volatile private var generation = 0L
    @Volatile private var visible = false
    private var operation: Job? = null
    private var challenge: HostedModels.Challenge? = null
    private var afterLogin = HostingPage.ACCOUNT
    @Volatile private var authWarning: String? = null

    fun open(entry: HostingPage) {
        if (!enabled) return
        visible = true
        val expected = invalidate()
        challenge = null
        mutableState.value = HostingUiState(page = entry, busy = true, message = authWarning)
        operation = viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { sessionInstallation.withLock { repository.session() } }
                val local = if (entry == HostingPage.SOURCE) withContext(Dispatchers.IO) { repository.localReleases().filter { it.origin == "local" } } else emptyList()
                val pendingSessions = withContext(Dispatchers.IO) { repository.pendingSessionRevocationCount() }
                val pendingRevoke = if (session == null) emptySet() else withContext(Dispatchers.IO) { repository.pendingRevocations() }
                if (!active(expected)) return@launch
                mutableState.update { it.copy(account = session?.account, sessionExpiresAt = session?.expiresAt,
                    email = session?.account?.email.orEmpty(), releases = local, pendingSessionRevocations = pendingSessions, pendingRevocations = pendingRevoke, busy = false) }
                when (entry) {
                    HostingPage.PROJECTS, HostingPage.VERSIONS -> if (session == null) loginFor(HostingPage.PROJECTS) else showProjects()
                    HostingPage.TASKS -> if (session == null) loginFor(HostingPage.TASKS) else showTasks()
                    else -> Unit
                }
            } catch (error: Exception) { fail(expected, error) }
        }
    }

    fun leave() {
        visible = false
        invalidate()
        challenge = null
        mutableState.update { it.copy(code = "", challengeReady = false, challengeExpiresAt = null,
            busy = false, observing = false, selected = null) }
    }

    /** True means the inner page consumed Back; false lets the shell close this hosting visit. */
    fun back(): Boolean = when (state.value.page) {
        HostingPage.SOURCE, HostingPage.ACCOUNT -> { leave(); false }
        HostingPage.CONFIRM -> { showSources(); true }
        HostingPage.TASKS -> { showAccount(); true }
        HostingPage.PROJECTS -> { showAccount(); true }
        HostingPage.VERSIONS -> { showProjects(); true }
    }

    fun showSources() {
        move(HostingPage.SOURCE)
        mutableState.update { it.copy(selected = null, expiryDays = 7) }
        execute { expected ->
            val list = withContext(Dispatchers.IO) { repository.localReleases().filter { it.origin == "local" } }
            if (active(expected)) mutableState.update { it.copy(releases = list) }
        }
    }

    fun selectRelease(id: String) {
        if (state.value.busy || state.value.page != HostingPage.SOURCE) return
        val selected = state.value.releases.singleOrNull { it.id == id && it.origin == "local" } ?: return
        execute { expected ->
            val binding = try { withContext(Dispatchers.IO) { repository.readRelease(selected.id, selected.contentDigest) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (active(expected)) mutableState.update { it.copy(selected = null,
                    message = "所选本机封存版未通过来源或实际文件校验。请刷新列表后重新选择；尚未建立上传快照。") }
                return@execute
            }
            if (!active(expected)) return@execute
            mutableState.update { it.copy(selected = binding) }
            if (state.value.account == null) loginFor(HostingPage.CONFIRM)
            else move(HostingPage.CONFIRM)
        }
    }

    fun showAccount() { afterLogin = HostingPage.ACCOUNT; move(HostingPage.ACCOUNT) }
    private fun loginFor(destination: HostingPage) { afterLogin = destination; move(HostingPage.ACCOUNT) }

    fun editEmail(value: String) {
        if (state.value.busy || state.value.page != HostingPage.ACCOUNT || state.value.account != null) return
        challenge = null
        mutableState.update { it.copy(email = value.take(254), code = "", challengeReady = false,
            challengeExpiresAt = null, message = null) }
    }
    fun editCode(value: String) {
        if (!state.value.busy && state.value.challengeReady) mutableState.update { it.copy(code = value.filter(Char::isDigit).take(6)) }
    }
    fun requestCode() {
        val current = state.value
        if (current.busy || current.account != null || current.page != HostingPage.ACCOUNT || System.currentTimeMillis() < current.resendAt) return
        val email = current.email.trim().lowercase(java.util.Locale.ROOT)
        if (!email.endsWith("@example.test") || email.substringBefore('@').isBlank() || email.count { it == '@' } != 1) {
            mutableState.update { it.copy(message = "本地开发只接受 @example.test 合成邮箱。") }; return
        }
        challenge = null
        mutableState.update { it.copy(email = email, code = "", challengeReady = false, challengeExpiresAt = null) }
        execute { expected ->
            val result = withContext(Dispatchers.IO) { repository.requestChallenge(email) }
            if (active(expected)) {
                challenge = result
                mutableState.update { it.copy(challengeReady = true, challengeExpiresAt = result.expiresAt,
                    resendAt = System.currentTimeMillis() + result.resendAfterSeconds * 1000L,
                    message = "验证码已写入运行服务的电脑上的合成收件箱，请在本机查看后手动输入。不会发送真实邮件。") }
            }
        }
    }

    fun verifyCode() {
        val current = state.value
        val selectedChallenge = challenge ?: return
        if (current.busy || current.page != HostingPage.ACCOUNT || current.code.length != 6) return
        val code = current.code
        val expected = generation
        challenge = null
        mutableState.update { it.copy(code = "", challengeReady = false, challengeExpiresAt = null, busy = true, message = null) }
        // This job is intentionally not the observation job: dismissing login must still drain and
        // revoke a late response, including cancellation during the dispatcher return boundary.
        viewModelScope.launch {
            var received: HostedModels.Session? = null
            var retired = false
            var accepted = false
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    val session = repository.createSession(selectedChallenge.challengeId, code)
                    received = session
                    sessionInstallation.withLock {
                        try {
                            if (active(expected)) {
                                repository.installSession(session)
                                // Publish the account on Main before releasing the installation lock.
                                // A newer open cannot read an installed-but-already-cancelled token.
                                withContext(Dispatchers.Main.immediate) {
                                    if (active(expected)) {
                                        mutableState.update { it.copy(account = session.account, sessionExpiresAt = session.expiresAt,
                                            email = session.account.email, busy = false, tasks = emptyList(), task = null, projects = emptyList(),
                                            publications = emptyList(), project = null, pendingRevocations = emptySet()) }
                                        accepted = true
                                        when (afterLogin) {
                                            HostingPage.CONFIRM -> if (state.value.selected != null) move(HostingPage.CONFIRM) else showSources()
                                            HostingPage.PROJECTS, HostingPage.VERSIONS -> showProjects()
                                            HostingPage.TASKS -> showTasks()
                                            else -> move(HostingPage.ACCOUNT)
                                        }
                                    }
                                }
                            }
                        } finally {
                            if (!accepted) { retireLateSession(session); retired = true }
                        }
                    }
                }
            } catch (error: Exception) {
                val rejected = error is HostedApi.ApiException && error.statusCode in 400..499 &&
                    error.code in setOf("INVALID_CHALLENGE", "INVALID_REQUEST", "RATE_LIMITED")
                val message = when {
                    error is HostedSessions.CleanupException -> sessionCleanupMessage(error).also { authWarning = it }
                    received != null -> "登录未能安全完成，正在请求撤销已收到的会话。可在账号页检查待撤销会话。"
                    rejected -> "验证码无效、已过期或尝试过多。请核对后重试，或重新生成验证码。"
                    else -> "登录响应未能完整收到：服务端可能已创建一天有效的会话，因缺少令牌，当前无法撤销该未知会话。可稍后重新登录。".also { authWarning = it }
                }
                if (active(expected)) {
                    if (rejected) challenge = selectedChallenge
                    mutableState.update { it.copy(busy = false, challengeReady = challenge != null,
                        challengeExpiresAt = challenge?.expiresAt, message = message) }
                }
            } finally {
                val late = received
                if (late != null && !retired && !accepted) withContext(NonCancellable + Dispatchers.IO) { retireLateSession(late) }
                val pending = withContext(NonCancellable + Dispatchers.IO) { runCatching { repository.pendingSessionRevocationCount() }.getOrNull() }
                if (visible && pending != null) mutableState.update { it.copy(pendingSessionRevocations = pending) }
            }
        }
    }

    private suspend fun retireLateSession(session: HostedModels.Session) {
        try { repository.retireSession(session) }
        catch (error: Exception) { authWarning = sessionCleanupMessage(error) }
    }

    fun setExpiry(days: Int) {
        if (!state.value.busy && state.value.page == HostingPage.CONFIRM && days in setOf(1, 7, 30)) mutableState.update { it.copy(expiryDays = days) }
    }

    /** The confirmation button is the ONLY snapshot/create-upload entry. */
    fun confirmPublication() {
        val current = state.value
        val binding = current.selected ?: return
        if (current.page != HostingPage.CONFIRM || current.busy || current.account == null) return
        move(HostingPage.TASKS)
        mutableState.update { it.copy(task = null) }
        execute(observing = true) { expected ->
            val task = withContext(Dispatchers.IO) { repository.preparePublication(binding.summary.id, binding.summary.contentDigest, current.expiryDays) }
            if (!active(expected)) return@execute
            mutableState.update { it.copy(task = task, tasks = mergeTask(it.tasks, task)) }
            val result = withContext(Dispatchers.IO) { repository.resumePublication(task.taskId) { update -> updateTask(expected, update) } }
            updateTask(expected, result)
        }
    }

    fun showTasks() {
        if (state.value.account == null) { loginFor(HostingPage.TASKS); return }
        move(HostingPage.TASKS)
        execute { expected ->
            val tasks = withContext(Dispatchers.IO) { repository.tasks() }
            if (active(expected)) mutableState.update { it.copy(tasks = tasks, task = null) }
        }
    }
    fun selectTask(taskId: String) {
        if (state.value.busy) return
        val task = state.value.tasks.singleOrNull { it.taskId == taskId && it.accountId == state.value.account?.accountId } ?: return
        mutableState.update { it.copy(task = task, message = null) }
    }
    fun resumeTask() {
        val task = state.value.task ?: return
        if (state.value.busy || task.accountId != state.value.account?.accountId || task.publication != null || task.state in setOf("cancelled", "expired", "failed")) return
        execute(observing = true) { expected ->
            val result = withContext(Dispatchers.IO) { repository.resumePublication(task.taskId) { update -> updateTask(expected, update) } }
            updateTask(expected, result)
        }
    }
    fun stopObservation() {
        if (!state.value.observing) return
        invalidate()
        mutableState.update { it.copy(busy = false, observing = false, message = "已停止本页观察，未取消发布。重新进入任务可用原账号与原快照查询或继续；服务端校验可能仍在进行。") }
    }
    fun cancelTask() {
        val task = state.value.task ?: return
        if (task.accountId != state.value.account?.accountId || task.publication != null || (state.value.busy && !state.value.observing)) return
        invalidate()
        mutableState.update { it.copy(busy = false, observing = false) }
        execute { expected ->
            val result = try { repository.cancelPublication(task.taskId) }
            finally {
                if (active(expected)) withContext(NonCancellable) {
                    runCatching { withContext(Dispatchers.IO) { repository.tasks() } }.getOrNull()
                        ?.firstOrNull { it.taskId == task.taskId }?.let { updateTask(expected, it) }
                }
            }
            updateTask(expected, result)
            if (active(expected)) mutableState.update { it.copy(message = if (result.publication != null)
                when (result.publication.status) {
                    "revoked" -> "取消时已有正式发布记录，该版本现已撤销。"
                    "expired" -> "取消时已有正式发布记录，该版本现已到期。"
                    else -> "取消时已正式发布。链接仍有效，请在版本管理中另行确认撤销。"
                } else if (result.state == "cancelled")
                if (result.uploadId == null) "未发起上传，已取消本机任务。" else "服务端已确认取消。"
                else "取消结果尚待确认，请重新查询任务。") }
        }
    }

    fun showProjects() {
        if (state.value.account == null) { loginFor(HostingPage.PROJECTS); return }
        move(HostingPage.PROJECTS)
        mutableState.update { it.copy(projects = emptyList(), projectsCursor = null, project = null, publications = emptyList()) }
        loadProjects(false)
    }
    fun moreProjects() { if (state.value.projectsCursor != null) loadProjects(true) }
    private fun loadProjects(append: Boolean) = execute { expected ->
        val cursor = if (append) state.value.projectsCursor else null
        val page = withContext(Dispatchers.IO) { repository.projectsPage(cursor) }
        if (active(expected)) mutableState.update { it.copy(projects = ((if (append) it.projects else emptyList()) + page.items).distinctBy { p -> p.serverProjectId }, projectsCursor = page.nextCursor) }
    }
    fun selectProject(id: String) {
        if (state.value.busy) return
        val project = state.value.projects.singleOrNull { it.serverProjectId == id } ?: return
        move(HostingPage.VERSIONS)
        mutableState.update { it.copy(project = project, publications = emptyList(), publicationsCursor = null, versionsFresh = false) }
        refreshVersions()
    }
    fun refreshVersions() = loadVersions(false)
    fun moreVersions() { if (state.value.publicationsCursor != null) loadVersions(true) }
    private fun loadVersions(append: Boolean) {
        val project = state.value.project ?: return
        if (state.value.page != HostingPage.VERSIONS || state.value.busy) return
        execute { expected ->
            val cursor = if (append) state.value.publicationsCursor else null
            val pending = withContext(Dispatchers.IO) { repository.pendingRevocations() }
            if (!append) {
                val cached = withContext(Dispatchers.IO) { repository.cachedPublications(project.serverProjectId) }
                if (active(expected)) mutableState.update { it.copy(publications = cached, versionsFresh = false, pendingRevocations = pending) }
            }
            val page = withContext(Dispatchers.IO) { repository.publicationsPage(project.serverProjectId, cursor) }
            if (active(expected)) mutableState.update { current ->
                val confirmed = page.items.filter { it.status == "revoked" }.map { it.publicationId }.toSet()
                current.copy(publications = ((if (append) current.publications else emptyList()) + page.items).distinctBy { it.publicationId },
                    publicationsCursor = page.nextCursor, versionsFresh = true, pendingRevocations = current.pendingRevocations - confirmed)
            }
        }
    }
    fun revokePublication(id: String) {
        val current = state.value
        if (current.busy || current.page != HostingPage.VERSIONS || current.publications.none { it.publicationId == id && it.status == "active" }) return
        mutableState.update { it.copy(pendingRevocations = it.pendingRevocations + id) }
        execute { expected ->
            val result = withContext(Dispatchers.IO) { repository.revokePublication(id) }
            if (active(expected)) mutableState.update { it.copy(publications = it.publications.map { publication ->
                if (publication.publicationId != id) publication else HostedModels.Publication(publication.publicationId,
                    publication.serverProjectId, publication.releaseId, publication.versionOrdinal, publication.contentDigest,
                    publication.title, publication.createdAt, publication.expiresAt, result.status, publication.shareUrl, result.revokedAt)
            }, pendingRevocations = it.pendingRevocations - id, message = "服务端已确认撤销此版本。已获取的副本无法收回。") }
        }
    }
    fun checkRevocation(id: String) {
        if (state.value.busy || id !in state.value.pendingRevocations) return
        execute { expected ->
            val result = withContext(Dispatchers.IO) { repository.publication(id) }
            if (active(expected)) mutableState.update { it.copy(publications = it.publications.map { current ->
                if (current.publicationId == id) result else current
            }, pendingRevocations = if (result.status == "revoked") it.pendingRevocations - id else it.pendingRevocations,
                message = if (result.status == "revoked") "服务端已确认此版本撤销。" else "服务端尚未返回撤销状态，此版本可能仍有效。可再次明确确认撤销。") }
        }
    }
    fun retryPendingSessions() {
        execute { expected ->
            try { withContext(NonCancellable + Dispatchers.IO) { repository.retryPendingSessionRevocations() } }
            finally {
                val count = withContext(NonCancellable + Dispatchers.IO) { repository.pendingSessionRevocationCount() }
                if (active(expected)) mutableState.update { it.copy(pendingSessionRevocations = count) }
            }
            if (active(expected)) {
                authWarning = null
                val current = withContext(Dispatchers.IO) { sessionInstallation.withLock { repository.session() } }
                if (active(expected)) mutableState.update { it.copy(account = current?.account, sessionExpiresAt = current?.expiresAt,
                    message = "服务端已确认已知待撤销会话失效。此前未收到令牌的未知会话无法在此撤销。") }
            }
        }
    }
    fun logout() {
        if (state.value.busy || state.value.account == null) return
        execute { expected ->
            try { withContext(NonCancellable) { sessionInstallation.withLock { repository.logout() } } }
            finally {
                val count = withContext(NonCancellable + Dispatchers.IO) { repository.pendingSessionRevocationCount() }
                if (active(expected)) mutableState.update { it.copy(pendingSessionRevocations = count) }
            }
            if (active(expected)) {
                authWarning = null
                challenge = null
                mutableState.value = HostingUiState(page = HostingPage.ACCOUNT, message = "服务端已确认登出。已发布链接仍按原期限有效，可重新登录逐版撤销。")
            }
        }
    }
    fun message(text: String) { mutableState.update { it.copy(message = text) } }

    private fun move(page: HostingPage) {
        invalidate(); challenge = null
        mutableState.update { it.copy(page = page, busy = false, observing = false, code = "", challengeReady = false,
            challengeExpiresAt = null, message = authWarning) }
    }
    private fun invalidate(): Long { generation++; operation?.cancel(); operation = null; return generation }
    private fun active(expected: Long) = visible && expected == generation
    private fun execute(observing: Boolean = false, action: suspend (Long) -> Unit) {
        if (!enabled || !visible || state.value.busy) return
        val expected = generation
        mutableState.update { it.copy(busy = true, observing = observing, message = null) }
        operation = viewModelScope.launch {
            try { action(expected) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { fail(expected, error) }
            finally { if (active(expected)) mutableState.update { it.copy(busy = false, observing = false) } }
        }
    }
    private fun updateTask(expected: Long, task: HostedModels.Task) {
        if (active(expected) && task.accountId == state.value.account?.accountId) mutableState.update {
            it.copy(task = task, tasks = mergeTask(it.tasks, task))
        }
    }
    private fun mergeTask(tasks: List<HostedModels.Task>, task: HostedModels.Task) = listOf(task) + tasks.filterNot { it.taskId == task.taskId }
    private fun fail(expected: Long, error: Exception) {
        if (!active(expected) || error is CancellationException) return
        if (error is HostedApi.ApiException && error.retryAfterSeconds > 0) {
            mutableState.update { it.copy(resendAt = System.currentTimeMillis() + error.retryAfterSeconds * 1000L) }
        }
        if (error is HostedApi.ApiException && error.statusCode == 401) {
            afterLogin = state.value.page.takeUnless { it == HostingPage.VERSIONS } ?: HostingPage.PROJECTS
            challenge = null
            mutableState.update { it.copy(page = HostingPage.ACCOUNT, account = null, sessionExpiresAt = null,
                code = "", challengeReady = false, challengeExpiresAt = null, busy = false, observing = false,
                versionsFresh = false, message = safeFailure(error)) }
        } else mutableState.update { it.copy(busy = false, observing = false, versionsFresh = false, message = safeFailure(error)) }
    }
    private fun safeFailure(error: Exception): String {
        if (error is HostedSessions.CleanupException) return sessionCleanupMessage(error)
        if (error is HostedApi.ApiException) {
            if (error.statusCode == 401) return "登录会话已失效，请返回账号页重新登录；恢复任务仍绑定原账号和固定快照。"
            val wait = error.retryAfterSeconds
            if (wait > 0) {
                return "服务暂时限流，请至少 $wait 秒后重试。"
            }
            return "本地服务未完成操作（${error.code}）。原任务保留；撤销或取消须重新查询服务端确认。"
        }
        return "未能确认本地服务结果。请检查电脑上的服务及 adb reverse tcp:4173 tcp:4173 后重试。原任务保留；尚未确认撤销的链接可能仍有效。"
    }
    private fun sessionCleanupMessage(error: Exception): String = when {
        error is HostedSessions.CleanupException && error.serverConfirmed ->
            "服务端已确认会话失效，但本机凭据清理异常。请恢复本机存储后重试清理。已发布链接不受登出影响。"
        error is HostedSessions.CleanupException && !error.credentialPreserved ->
            "会话撤销尚未确认，且未能持久保存重试凭据。此开发会话最多一天到期；请先检查本机存储。"
        error is HostedSessions.CleanupException ->
            "会话撤销尚未获服务端确认。加密重试凭据已保留，请恢复服务后在账号页重试待撤销会话；开发会话最多一天到期。"
        else -> "会话撤销尚未确认。请在账号页检查是否有可重试的待撤销会话；开发会话最多一天到期。"
    }
    override fun onCleared() { visible = false; invalidate(); challenge = null; super.onCleared() }
}

/** Inject only IO boundaries; host tests execute the production workspace and its route generation. */
internal interface HostingBackend {
    suspend fun session(): HostedModels.Session?
    suspend fun localReleases(): List<ReleaseSummary>
    suspend fun readRelease(id: String, digest: String): HostedReleaseBinding
    suspend fun requestChallenge(email: String): HostedModels.Challenge
    suspend fun createSession(challengeId: String, code: String): HostedModels.Session
    suspend fun installSession(session: HostedModels.Session)
    suspend fun revokeSession(session: HostedModels.Session)
    suspend fun retireSession(session: HostedModels.Session)
    suspend fun logout()
    suspend fun pendingSessionRevocationCount(): Int
    suspend fun retryPendingSessionRevocations()
    suspend fun pendingRevocations(): Set<String>
    suspend fun tasks(): List<HostedModels.Task>
    suspend fun preparePublication(id: String, digest: String, days: Int): HostedModels.Task
    suspend fun resumePublication(id: String, onUpdate: (HostedModels.Task) -> Unit): HostedModels.Task
    suspend fun cancelPublication(id: String): HostedModels.Task
    suspend fun projectsPage(cursor: String?): HostedModels.Page<HostedModels.Project>
    suspend fun publicationsPage(project: String, cursor: String?): HostedModels.Page<HostedModels.Publication>
    suspend fun cachedPublications(project: String): List<HostedModels.Publication>
    suspend fun publication(id: String): HostedModels.Publication
    suspend fun revokePublication(id: String): HostedModels.Revocation
}

private class RepositoryHostingBackend(application: Application) : HostingBackend {
    private val repository by lazy { HostedRepository(application) }
    private val releases by lazy { ReleaseStore(application) }
    override suspend fun session() = repository.session()
    override suspend fun localReleases() = releases.listReleases()
    override suspend fun readRelease(id: String, digest: String) = repository.readRelease(id, digest)
    override suspend fun requestChallenge(email: String) = repository.requestChallenge(email)
    override suspend fun createSession(challengeId: String, code: String) = repository.createSession(challengeId, code)
    override suspend fun installSession(session: HostedModels.Session) = repository.installSession(session)
    override suspend fun revokeSession(session: HostedModels.Session) = repository.revokeSession(session)
    override suspend fun retireSession(session: HostedModels.Session) = repository.retireSession(session)
    override suspend fun logout() = repository.logout()
    override suspend fun pendingSessionRevocationCount() = repository.pendingSessionRevocationCount()
    override suspend fun retryPendingSessionRevocations() = repository.retryPendingSessionRevocations()
    override suspend fun pendingRevocations() = repository.pendingRevocations()
    override suspend fun tasks() = repository.tasks()
    override suspend fun preparePublication(id: String, digest: String, days: Int) = repository.preparePublication(id, digest, days)
    override suspend fun resumePublication(id: String, onUpdate: (HostedModels.Task) -> Unit) = repository.resumePublication(id, onUpdate)
    override suspend fun cancelPublication(id: String) = repository.cancelPublication(id)
    override suspend fun projectsPage(cursor: String?) = repository.projectsPage(cursor)
    override suspend fun publicationsPage(project: String, cursor: String?) = repository.publicationsPage(project, cursor)
    override suspend fun cachedPublications(project: String) = repository.cachedPublications(project)
    override suspend fun publication(id: String) = repository.publication(id)
    override suspend fun revokePublication(id: String) = repository.revokePublication(id)
}
