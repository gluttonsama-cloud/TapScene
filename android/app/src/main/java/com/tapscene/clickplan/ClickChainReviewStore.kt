package com.tapscene.clickplan

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.tapscene.media.OpaqueMask
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class ClickChainOutput(val relativePath: String, val sha256: String, val width: Int, val height: Int)
internal data class ClickChainFrameDraft(val key: String, val originalPngSha256: String, val title: String,
    val masks: List<OpaqueMask> = emptyList(), val output: ClickChainOutput? = null, val reviewed: Boolean = false)
internal data class ClickChainActionDraft(val actionId: String, val fromKey: String, val toKey: String,
    val fromOutputSha: String, val toOutputSha: String, val label: String, val rect: OpaqueMask,
    val confirmed: Boolean = false)
internal data class ClickChainReviewDraft(val runId: String, val sourceSha256: String, val owner: String,
    val serial: Long = 0, val title: String = "点击链演示", val firstAction: String = "1", val lastAction: String,
    val framesOnly: Boolean = false, val stageChoices: Map<Int, String> = emptyMap(),
    val selectedFrameKeys: Set<String>? = null, val frames: Map<String, ClickChainFrameDraft> = emptyMap(),
    val actions: Map<String, ClickChainActionDraft> = emptyMap(), val markLastTerminal: Boolean = false,
    val operationId: String? = null, val outputJobs: Set<String> = emptySet())

/** Author inputs and generated candidates only. Its durable file journal never owns capture PNGs. */
internal class ClickChainReviewStore(context: Context) {
    private val base = context.applicationContext.noBackupFilesDir.canonicalFile
    private val root = File(base, "click-chain-reviews")

    fun readExisting(runId: String): ClickChainReviewDraft? = synchronized(lock) { read(runId) }

    fun open(runId: String, sourceSha: String, actionCount: Int, isCurrent: () -> Boolean = { true }): ClickChainReviewDraft = synchronized(lock) {
        check(isCurrent()) { "这次读取已停止。" }
        id(runId); hash(sourceSha); require(actionCount in 1..40)
        directory(root, base); val directory = directory(File(root, runId), root)
        val previous = read(runId)
        check(previous == null || previous.sourceSha256 == sourceSha) { "原片已改变，已保留之前的复核输入。" }
        val next = (previous ?: ClickChainReviewDraft(runId, sourceSha, UUID.randomUUID().toString(),
            lastAction = actionCount.toString())).copy(owner = UUID.randomUUID().toString())
        check(isCurrent()) { "这次读取已停止。" }
        write(next)
        runCatching { cleanup(next, directory) }.getOrDefault(next)
    }

    fun save(draft: ClickChainReviewDraft): ClickChainReviewDraft = synchronized(lock) {
        val previous = read(draft.runId) ?: error("复核记录已不存在。")
        check(previous.owner == draft.owner && draft.serial > previous.serial && draft.sourceSha256 == previous.sourceSha256) {
            "复核已在其他窗口更新，请重新读取；当前输入尚未保存。"
        }
        write(draft)
        runCatching { cleanup(draft, File(root, draft.runId)) }.getOrDefault(draft)
    }

    fun beginOutput(draft: ClickChainReviewDraft): Pair<ClickChainReviewDraft, File> = synchronized(lock) {
        val request = UUID.randomUUID().toString()
        val next = draft.copy(serial = draft.serial + 1, outputJobs = draft.outputJobs + request)
        active.add("${draft.runId}/$request")
        try { save(next) to File(File(root, draft.runId), request) }
        catch (failure: Throwable) { active.remove("${draft.runId}/$request"); throw failure }
    }

    /** A successful metadata save is authoritative. A failed/uncertain save never authorizes cleanup. */
    fun finishOutput(draft: ClickChainReviewDraft, directory: File): ClickChainReviewDraft = synchronized(lock) {
        require(directory.parentFile == File(root, draft.runId)); id(directory.name)
        active.remove("${draft.runId}/${directory.name}")
        val current = read(draft.runId) ?: return@synchronized draft
        if (current.owner != draft.owner) {
            runCatching { cleanup(current, File(root, draft.runId)) }; return@synchronized draft
        }
        val next = current.copy(serial = current.serial + 1)
        write(next); runCatching { cleanup(next, File(root, draft.runId)) }.getOrDefault(next)
    }

    fun releaseOutput(runId: String, directory: File) = synchronized(lock) {
        active.remove("$runId/${directory.name}")
        // Read before deleting. An uncertain metadata commit may already reference this output.
        read(runId)?.let { runCatching { cleanup(it, File(root, runId)) } }
    }

    fun outputFile(runId: String, output: ClickChainOutput): File = synchronized(lock) {
        id(runId); hash(output.sha256)
        require(output.width > 0 && output.height > 0 && output.width.toLong() * output.height <= 12_000_000)
        val parts = output.relativePath.split('/')
        require(parts.size == 4 && parts[0] == "click-chain-reviews" && parts[1] == runId)
        id(parts[2]); require(parts[3].matches(Regex("candidate-[0-9a-f-]{36}\\.png")))
        File(base, output.relativePath).also {
            check(it.canonicalFile == it.absoluteFile && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                it.length() in 1..50L * 1024 * 1024) { "已生成画面缺失，请重新生成并复核。" }
        }
    }

    fun output(draft: ClickChainReviewDraft, file: File, sha: String, width: Int, height: Int): ClickChainOutput {
        val value = ClickChainOutput(file.relativeTo(base).path, sha, width, height)
        val adopted = outputFile(draft.runId, value)
        // The PNG writer syncs contents before rename; persist that exact directory entry too.
        sync(requireNotNull(adopted.parentFile))
        sync(requireNotNull(adopted.parentFile?.parentFile))
        return value
    }

    private fun read(runId: String): ClickChainReviewDraft? {
        id(runId); val file = File(File(root, runId), "draft.json")
        if (!file.exists()) return null
        check(file.canonicalFile == file.absoluteFile && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() <= MAX_BYTES)
        val json = JSONObject(file.readText(Charsets.UTF_8)); check(json.getInt("version") == 1)
        check(json.getString("runId") == runId)
        val frameItems = json.getJSONArray("frames"); check(frameItems.length() <= 80)
        val frames = (0 until frameItems.length()).map { index -> frameItems.getJSONObject(index).let { item ->
            val masks = item.getJSONArray("masks").let { values ->
                check(values.length() <= 20)
                List(values.length()) { values.getJSONArray(it).let { rect -> OpaqueMask(rect.getDouble(0).toFloat(),
                    rect.getDouble(1).toFloat(), rect.getDouble(2).toFloat(), rect.getDouble(3).toFloat()) } }
            }
            val output = item.optJSONObject("output")?.let { ClickChainOutput(it.getString("path"),it.getString("sha"),it.getInt("width"),it.getInt("height")) }
            ClickChainFrameDraft(item.getString("key"), item.getString("originalSha"), item.getString("title"), masks, output,
                item.getBoolean("reviewed")).also { check(!it.reviewed || it.output != null) }
        } }.associateBy { it.key }.also { check(it.size == frameItems.length()) }
        val actionItems = json.getJSONArray("actions"); check(actionItems.length() <= 40)
        val actions = (0 until actionItems.length()).map { index -> actionItems.getJSONObject(index).let { item ->
            val rect = item.getJSONArray("rect")
            ClickChainActionDraft(item.getString("id"),item.getString("from"),item.getString("to"),item.getString("fromSha"),item.getString("toSha"),
                item.getString("label"),OpaqueMask(rect.getDouble(0).toFloat(),rect.getDouble(1).toFloat(),rect.getDouble(2).toFloat(),rect.getDouble(3).toFloat()),
                item.getBoolean("confirmed"))
        } }.associateBy { it.actionId }.also { check(it.size == actionItems.length()) }
        val choices = json.getJSONArray("choices").let { values ->
            check(values.length() <= 41); (0 until values.length()).associate { values.getJSONArray(it).let { row -> row.getInt(0) to row.getString(1) } }
        }
        fun strings(name: String, limit: Int): Set<String> = json.getJSONArray(name).let { values ->
            check(values.length() <= limit); (0 until values.length()).map { values.getString(it) }.toSet()
        }
        return ClickChainReviewDraft(runId,json.getString("sourceSha"),json.getString("owner"),json.getLong("serial"),
            json.getString("title"),json.getString("first"),json.getString("last"),json.getBoolean("framesOnly"),choices,
            if (json.isNull("selected")) null else strings("selected",80),frames,actions,json.getBoolean("terminal"),
            json.optString("operation").takeIf { it.isNotBlank() },strings("jobs",160)).also(::validate)
    }

    private fun write(draft: ClickChainReviewDraft) {
        validate(draft)
        val frames = JSONArray().apply { draft.frames.values.forEach { frame -> put(JSONObject().apply {
            put("key",frame.key);put("originalSha",frame.originalPngSha256);put("title",frame.title)
            put("masks",JSONArray().apply { frame.masks.forEach { put(rect(it)) } });put("reviewed",frame.reviewed)
            put("output",frame.output?.let { JSONObject().put("path",it.relativePath).put("sha",it.sha256).put("width",it.width).put("height",it.height) } ?: JSONObject.NULL)
        }) } }
        val actions = JSONArray().apply { draft.actions.values.forEach { action -> put(JSONObject().apply {
            put("id",action.actionId);put("from",action.fromKey);put("to",action.toKey);put("fromSha",action.fromOutputSha);put("toSha",action.toOutputSha)
            put("label",action.label);put("rect",rect(action.rect));put("confirmed",action.confirmed)
        }) } }
        val json = JSONObject().put("version",1).put("runId",draft.runId).put("sourceSha",draft.sourceSha256).put("owner",draft.owner)
            .put("serial",draft.serial).put("title",draft.title).put("first",draft.firstAction).put("last",draft.lastAction)
            .put("framesOnly",draft.framesOnly).put("choices",JSONArray().apply { draft.stageChoices.toSortedMap().forEach { (index,key) -> put(JSONArray(listOf(index,key))) } })
            .put("selected",draft.selectedFrameKeys?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL).put("frames",frames).put("actions",actions)
            .put("terminal",draft.markLastTerminal).put("operation",draft.operationId ?: "").put("jobs",JSONArray(draft.outputJobs.sorted()))
        val bytes = json.toString().toByteArray(Charsets.UTF_8); check(bytes.size <= MAX_BYTES)
        val directory = directory(File(root,draft.runId),root)
        val temp = File.createTempFile(".draft-", ".part", directory)
        try {
            FileOutputStream(temp).use { it.write(bytes);it.fd.sync() }
            Files.move(temp.toPath(), File(directory,"draft.json").toPath(), StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            sync(directory)
        } finally { if (temp.exists()) temp.delete() }
    }

    private fun outputDirectories(draft: ClickChainReviewDraft): Set<String> = draft.frames.values.mapNotNull { it.output?.relativePath?.split('/')?.getOrNull(2) }.toSet()

    private fun cleanup(draft: ClickChainReviewDraft, directory: File): ClickChainReviewDraft {
        // Only directories explicitly journaled by this draft or referenced by a generated output.
        // Old referenced directories are retained unless separately journaled: no directory sweep.
        val retained = outputDirectories(draft)
        val cleaned = mutableSetOf<String>()
        for (job in draft.outputJobs) {
            if (job in retained || "${draft.runId}/$job" in active) continue
            val folder = File(directory,job); id(job)
            if (!folder.exists()) { cleaned += job; continue }
            check(folder.canonicalFile == folder.absoluteFile && folder.parentFile == directory && folder.isDirectory)
            folder.listFiles().orEmpty().forEach { file ->
                check(file.canonicalFile == file.absoluteFile && file.parentFile == folder && file.isFile &&
                    (file.name.matches(Regex("candidate-[0-9a-f-]{36}\\.png")) || file.name.matches(Regex("\\.candidate-[0-9]+\\.png\\.part"))))
                check(file.delete())
            }
            check(folder.delete()); sync(directory); cleaned += job
        }
        if (cleaned.isEmpty()) return draft
        val next = draft.copy(outputJobs = draft.outputJobs - cleaned)
        write(next)
        return next
    }

    private fun validate(draft: ClickChainReviewDraft) {
        id(draft.runId);id(draft.owner);hash(draft.sourceSha256);require(draft.serial >= 0)
        require(draft.title.length <= 120 && draft.firstAction.length <= 8 && draft.lastAction.length <= 8 && draft.frames.size <= 80 && draft.actions.size <= 40)
        draft.operationId?.let(::id);draft.outputJobs.forEach(::id)
        require(draft.outputJobs.size <= 160)
        draft.frames.forEach { (key,frame) -> require(key == frame.key && frame.title.length <= 120 && frame.masks.size <= 20);hash(frame.originalPngSha256) }
        draft.actions.forEach { (key,action) -> id(key);require(action.actionId == key && action.label.length <= 120) }
    }
    private fun directory(file: File,parent: File): File {
        val created = !file.exists() && file.mkdir()
        check(file.canonicalFile == file.absoluteFile && file.parentFile == parent && file.isDirectory)
        if (created) sync(parent)
        return file
    }
    private fun sync(directory: File) { val descriptor = Os.open(directory.path,OsConstants.O_RDONLY,0);try { Os.fsync(descriptor) } finally { Os.close(descriptor) } }
    private fun id(value: String) { require(UUID.fromString(value).toString() == value) }
    private fun hash(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
    private fun rect(value: OpaqueMask) = JSONArray(listOf(value.left,value.top,value.right,value.bottom))
    companion object { private val lock = Any();private val active = mutableSetOf<String>();private const val MAX_BYTES = 512 * 1024 }
}
