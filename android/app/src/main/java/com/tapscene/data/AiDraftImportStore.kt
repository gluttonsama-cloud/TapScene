package com.tapscene.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.tapscene.packageformat.AiDraftImportPolicy
import com.tapscene.packageformat.AiPackageCodec
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Isolated complete-package import. The exact preview and its commit share a durable session. */
class AiDraftImportStore(context: Context) {
    private val app = context.applicationContext
    private val privateRoot = app.noBackupFilesDir.canonicalFile
    private val root = File(privateRoot, "ai-draft-imports")
    private val projects = ProjectStore(app)
    private val releases = ReleaseStore(app)

    suspend fun prepare(input: InputStream, baselineReleaseId: String? = null): AiDraftImportPreview = locked {
        val id = UUID.randomUUID().toString()
        val session = projects.beginAiImport(id, UUID.randomUUID().toString())
        val operation = child(id, create = true)
        try {
            val zip = File(operation, "input.tapscene-ai")
            FileOutputStream(zip).use { output ->
                var total = 0L; val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_BYTES - total + 1).toInt())
                    if (n < 0) break
                    check(n > 0) { "无法继续读取 AI 包。" }; total += n
                    require(total <= MAX_BYTES) { "AI 包超过 50 MiB。" }
                    output.write(buffer, 0, n)
                }
                require(total > 0) { "AI 包为空。" }; output.fd.sync()
            }
            sync(operation)
            val packageRoot = directory(File(operation, "package"), operation)
            val loaded = readPackage(zip, packageRoot)
            // Full Android decode supplements the codec's complete PNG/crop verification.
            ReleaseCompiler.verifyMedia(app, loaded.scene, packageRoot)
            val copies = loaded.scene.states.map { AiImportAssetCopy(it.id, UUID.randomUUID().toString(), it.imageAssetId, false) } +
                loaded.scene.regions.map { AiImportAssetCopy(it.id, UUID.randomUUID().toString(), it.assetId, true) }
            val prepared = Prepared(loaded.scene, loaded.renderPlan, baseline(baselineReleaseId), copies, ViewerPackageCodec.sha256(zip))
            capacity(prepared)
            savePrepared(session, prepared)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { projects.stopAiImport(id, "failed") }
                runCatching { clear(child(id)) }
            }
            throw failure
        }
    }

    /** The caller explicitly chose this local fixed version. Self-reported package IDs are ignored. */
    suspend fun preview(sessionId: String, baselineReleaseId: String?): AiDraftImportPreview = locked {
        val session = ready(sessionId)
        val prepared = decode(session)
        verifyInput(session, prepared, reparse = false)
        savePrepared(session, prepared.copy(baseline = baseline(baselineReleaseId)))
    }

    suspend fun readPrepared(sessionId: String): AiDraftImportPreview? = locked {
        val session = projects.readAiImport(sessionId) ?: return@locked null
        if (session.status != "ready") return@locked null
        val prepared = decode(session)
        verifyInput(session, prepared, reparse = false)
        asPreview(session, prepared)
    }

    /** Interrupted preparation is never presented as a completed review. Ready sessions survive. */
    suspend fun pending(): List<String> = locked {
        projects.finishedAiImports().forEach { session -> runCatching { clear(child(session.id)) } }
        projects.pendingAiImports().mapNotNull { session ->
            if (session.status == "ready") session.id
            else {
                projects.stopAiImport(session.id, "failed")
                runCatching { clear(child(session.id)) }
                null
            }
        }
    }

    suspend fun imageFile(sessionId: String, assetId: String): File = locked {
        val session = ready(sessionId); val prepared = decode(session)
        val asset = prepared.scene.assets.singleOrNull { it.id == assetId } ?: error("此图片不在待导入内容中。")
        check(asset.mime == "image/png") { "本次只支持静态图片。" }
        val file = File(File(child(sessionId), "package"), asset.path)
        check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() == asset.byteLength &&
            ViewerPackageCodec.sha256(file) == asset.sha256) { "待核对图片已改变，请重新选择 AI 包。" }
        file
    }

    suspend fun commit(sessionId: String, previewDigest: String): AiDraftImportResult = locked {
        val previous = projects.readAiImport(sessionId) ?: error("导入会话已不存在。")
        if (previous.status == "committed") return@locked projects.aiImportResult(sessionId)
        val session = ready(sessionId)
        check(session.previewDigest == previewDigest) { "待导入内容或对比版本已变化，请重新核对。" }
        val prepared = decode(session)
        val issues = AiDraftImportPolicy.inspect(prepared.scene)
        check(issues.isEmpty()) { issues.joinToString("\n") { it.message } }
        prepared.baseline?.let { original ->
            val current = baseline(original.releaseId) ?: error("选定的本机版本已不存在。")
            check(ViewerPackageCodec.contentDigest(current) == ViewerPackageCodec.contentDigest(original)) { "对比版本已改变，请重新核对。" }
        }
        capacity(prepared)
        val packageRoot = verifyInput(session, prepared, reparse = true)
        try {
            val result = projects.installAiDraft(sessionId, previewDigest, prepared.scene, prepared.plan, prepared.copies, packageRoot)
            if (result.status == "committed") runCatching { clear(child(sessionId)) }
            result
        } finally {
            // Re-read handles cancellation at the SQL commit boundary. Never undo a saved project.
            withContext(NonCancellable) {
                if (runCatching { projects.aiImportResult(sessionId).status }.getOrNull() == "committed") runCatching { clear(child(sessionId)) }
            }
        }
    }

    suspend fun cancel(sessionId: String): AiDraftImportResult = locked {
        val result = projects.stopAiImport(sessionId, "cancelled")
        runCatching { clear(child(sessionId)) }
        result
    }
    suspend fun readResult(sessionId: String): AiDraftImportResult = locked {
        projects.aiImportResult(sessionId).also { if (it.status in setOf("committed", "cancelled", "failed")) runCatching { clear(child(sessionId)) } }
    }

    private data class Prepared(val scene: ViewerScene, val plan: RenderPlan, val baseline: ViewerScene?,
        val copies: List<AiImportAssetCopy>, val inputSha: String)

    private suspend fun baseline(id: String?): ViewerScene? {
        if (id == null) return null
        // This is a user-selected comparison, not proof that this package came from this release.
        check(releases.listReleases().any { it.id == id && it.origin == "local" }) { "请选择这台设备上封存的本机版本作为对比。" }
        return releases.loadRelease(id)
    }

    private fun encode(value: Prepared): String = JSONObject().apply {
        put("version", 1); put("inputSha", value.inputSha)
        put("scene", ViewerPackageCodec.writeScene(value.scene).toString(Charsets.UTF_8))
        put("plan", value.plan.toBytes().toString(Charsets.UTF_8))
        put("baseline", value.baseline?.let { ViewerPackageCodec.writeScene(it).toString(Charsets.UTF_8) } ?: JSONObject.NULL)
        put("copies", JSONArray().apply { value.copies.forEach {
            put(JSONObject().put("owner", it.ownerId).put("asset", it.assetId).put("source", it.sourceAssetId).put("region", it.region))
        } })
    }.toString()

    private fun decode(session: AiImportSession): Prepared {
        val raw = requireNotNull(session.preparedJson)
        check(raw.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024 && digest(raw) == session.previewDigest) { "待导入预览记录不完整，请重新选择 AI 包。" }
        val json = JSONObject(raw)
        check(json.keys().asSequence().toSet() == setOf("version", "inputSha", "scene", "plan", "baseline", "copies") && json.getInt("version") == 1)
        val scene = ViewerPackageCodec.parseScene(json.getString("scene").toByteArray(Charsets.UTF_8))
        val plan = RenderPlan.parse(scene, json.getString("plan").toByteArray(Charsets.UTF_8))
        val baseline = if (json.isNull("baseline")) null else ViewerPackageCodec.parseScene(json.getString("baseline").toByteArray(Charsets.UTF_8))
        val copies = json.getJSONArray("copies").let { a -> (0 until a.length()).map { index ->
            val item = a.getJSONObject(index)
            check(item.keys().asSequence().toSet() == setOf("owner", "asset", "source", "region"))
            AiImportAssetCopy(item.getString("owner"), item.getString("asset"), item.getString("source"), item.getBoolean("region"))
        } }
        check(json.getString("inputSha") == session.inputSha)
        return Prepared(scene, plan, baseline, copies, requireNotNull(session.inputSha))
    }

    private fun savePrepared(session: AiImportSession, prepared: Prepared): AiDraftImportPreview {
        val raw = encode(prepared)
        require(raw.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "本次完整预览记录超过容量，请缩减包内容。" }
        projects.readyAiImport(session.id, prepared.inputSha, digest(raw), raw)
        return asPreview(ready(session.id), prepared)
    }
    private fun asPreview(session: AiImportSession, prepared: Prepared): AiDraftImportPreview {
        val review = AiDraftImportPolicy.review(prepared.scene, prepared.plan, prepared.baseline)
        return AiDraftImportPreview(session.id, requireNotNull(session.previewDigest), session.projectId, prepared.scene, prepared.plan,
            prepared.baseline?.releaseId, prepared.baseline?.title, review.issues, review.differences, review.summary, review.trustNotice,
            expanded(prepared))
    }
    private fun expanded(prepared: Prepared): Long = prepared.copies.sumOf { copy -> prepared.scene.assets.single { it.id == copy.sourceAssetId }.byteLength }
    private fun capacity(prepared: Prepared) {
        val bytes = expanded(prepared)
        require(bytes <= MAX_BYTES) { "共享图片展开为独立步骤后超过 50 MiB，请先缩减包；未建立新项目。" }
        require(privateRoot.usableSpace > bytes + 8L * 1024 * 1024) { "本机空间不足以保存独立图片副本，请腾出空间后重试。" }
    }
    private fun ready(id: String): AiImportSession = requireNotNull(projects.readAiImport(id)) { "导入会话已不存在。" }.also {
        check(it.status == "ready") { if (it.status == "committed") "这个包已建为新草稿，请打开导入结果。" else "本次导入未准备好或已取消。" }
    }
    private suspend fun verifyInput(session: AiImportSession, prepared: Prepared, reparse: Boolean): File {
        val operation = child(session.id); val zip = File(operation, "input.tapscene-ai")
        check(zip.canonicalFile == zip.absoluteFile && zip.isFile && zip.length() in 1..MAX_BYTES && ViewerPackageCodec.sha256(zip) == prepared.inputSha) { "待导入包已改变，请重新选择并核对。" }
        val payload = File(operation, "package")
        if (reparse) {
            // Re-extract into a fresh isolated directory, never trust mutable preview files.
            if (payload.exists()) clear(payload)
            directory(payload, operation)
            val loaded = readPackage(zip, payload)
            check(ViewerPackageCodec.writeScene(loaded.scene).contentEquals(ViewerPackageCodec.writeScene(prepared.scene)) &&
                loaded.renderPlan.toBytes().contentEquals(prepared.plan.toBytes())) { "提交内容与已显示的预览不一致。" }
            ReleaseCompiler.verifyMedia(app, loaded.scene, payload)
        }
        return payload
    }
    private suspend fun readPackage(zip: File, destination: File): AiPackageCodec.LoadedPackage {
        val owner = currentCoroutineContext()
        return AiPackageCodec.readPackage(zip, destination, { owner.ensureActive() }, { _, _, _ ->
            error("这个 AI 包含视频过渡，目前只能回流完整静态包；请在外部移除视频并重新生成合法完整包。不会把视频悄悄改成静态。")
        })
    }
    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { directory(root, privateRoot); block() }
    private fun child(id: String, create: Boolean = false): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "导入会话标识无效。" }
        val file = File(root, id)
        check(file.canonicalFile == file.absoluteFile && file.parentFile == root) { "导入隔离路径无效。" }
        return if (create) directory(file, root) else file
    }
    private fun directory(file: File, parent: File): File {
        val created = !file.exists() && file.mkdir()
        check(file.canonicalFile == file.absoluteFile && file.parentFile == parent && file.isDirectory && !Files.isSymbolicLink(file.toPath())) { "无法建立私有导入隔离区。" }
        if (created) sync(parent)
        return file
    }
    private fun clear(file: File) {
        check(file.toPath().toAbsolutePath().normalize().startsWith(root.toPath()) && file != root && !Files.isSymbolicLink(file.toPath())) { "不能清理这个路径。" }
        if (!file.exists()) return
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) file.listFiles().orEmpty().forEach(::clear)
        check(file.delete()) { "导入暂存清理未完成，下次可重试。" }
        file.parentFile?.takeIf { it.exists() }?.let(::sync)
    }
    private fun sync(file: File) {
        val descriptor = Os.open(file.path, OsConstants.O_RDONLY, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)); Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    companion object { private val mutex = Mutex(); private const val MAX_BYTES = 50L * 1024 * 1024 }
}
