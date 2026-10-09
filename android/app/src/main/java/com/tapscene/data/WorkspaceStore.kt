package com.tapscene.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SourceMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class RetainedMediaWorkspace(val projectId: String?, val label: String, val sourceCount: Int)

data class SourceDraft(
    val source: ImportedSource,
    val frameTimeUs: Long = 0,
    val masks: List<OpaqueMask> = emptyList(),
)

/** Metadata is visible, but durability was not confirmed. Never delete its source as rollback. */
class WorkspaceDurabilityException(cause: Exception) :
    IOException("素材记录已写入，但持久化确认未完成。请保留原片并重试。", cause)

/** Media workbench state only. This is not the project's future graph database. */
class WorkspaceStore(context: Context, projectId: String? = null) {
    private val root = context.noBackupFilesDir
    private val state = AtomicFile(File(root, if (projectId == null) "media-workspace.json" else {
        require(projectId.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))) { "项目标识无效" }
        "project-media-$projectId.json"
    }))

    fun read(): List<SourceDraft> = synchronized(lock) {
        // openRead restores the backup first on API 26. A missing base file alone can mean
        // an interrupted atomic write, not an empty workspace.
        val saved = try {
            state.openRead()
        } catch (missing: FileNotFoundException) {
            if (!state.baseFile.exists() && !File(state.baseFile.path + ".bak").exists() &&
                !File(state.baseFile.path + ".new").exists()) return@synchronized emptyList()
            throw missing
        }
        val document = saved.use { input ->
            require(input.channel.size() <= 64 * 1024) { "素材记录大小超限" }
            val bytes = input.readBytes()
            require(bytes.size <= 64 * 1024) { "素材记录大小超限" }
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
        require(document.getInt("version") == 1) { "素材记录版本不受支持" }
        val items = document.getJSONArray("sources")
        require(items.length() <= 3) { "素材记录数量超限" }
        List(items.length()) { index ->
            val item = items.getJSONObject(index)
            val metadata = item.getJSONObject("metadata")
            val relativePath = item.getString("path")
            val sourceFile = File(root, relativePath)
            require(sourceFile.canonicalFile.parentFile == File(root, "sources").canonicalFile) {
                "素材路径不受支持"
            }
            SourceDraft(
                source = ImportedSource(
                    sourceId = item.getString("id"),
                    privateRelativePath = relativePath,
                    displayName = item.getString("name"),
                    metadata = SourceMetadata(
                        mime = metadata.getString("mime"),
                        byteLength = metadata.getLong("bytes"),
                        sha256 = metadata.getString("sha256"),
                        width = metadata.getInt("width"),
                        height = metadata.getInt("height"),
                        rotationDeg = metadata.getInt("rotation"),
                        durationUs = metadata.getLong("durationUs"),
                        pixelWidthHeightRatio = metadata.optDouble("pixelWidthHeightRatio", 1.0).toFloat(),
                    ),
                ),
                frameTimeUs = item.getLong("frameTimeUs"),
                masks = item.getJSONArray("masks").let { rectangles ->
                    require(rectangles.length() <= 20) { "遮挡记录数量超限" }
                    List(rectangles.length()) { maskIndex ->
                        val mask = rectangles.getJSONArray(maskIndex)
                        OpaqueMask(
                            mask.getDouble(0).toFloat(), mask.getDouble(1).toFloat(),
                            mask.getDouble(2).toFloat(), mask.getDouble(3).toFloat(),
                        )
                    }
                },
            )
        }
    }

    /**
     * Read/modify/write under the same process-wide lock used by every workspace instance.
     * [transform] must be synchronous and must not retain or mutate the supplied list later.
     * Returning means the atomic replacement and parent-directory sync succeeded. A
     * WorkspaceDurabilityException means replacement happened: callers must retain source files.
     */
    fun update(transform: (List<SourceDraft>) -> List<SourceDraft>): List<SourceDraft> = synchronized(lock) {
        val drafts = transform(read()).toList()
        write(drafts)
        drafts
    }

    /** Idempotent registration never resets an existing source's frame or privacy edits. */
    fun append(source: ImportedSource): ImportedSource = synchronized(lock) {
        val current = read()
        val existing = current.firstOrNull { it.source.sourceId == source.sourceId }
        if (existing != null) {
            require(existing.source == source) { "素材标识已用于其他内容" }
            confirmDirectoryDurability()
            existing.source
        } else {
            write(current + SourceDraft(source))
            source
        }
    }

    /** Finish an interrupted registration before its caller may discard the journal's original. */
    fun confirmRegistration(source: ImportedSource): ImportedSource = synchronized(lock) {
        val existing = read().firstOrNull { it.source.sourceId == source.sourceId }
        require(existing?.source == source) { "素材登记已改变，请重新读取" }
        confirmDirectoryDurability()
        source
    }

    /** Change only one source's draft, preserving concurrently added recordings. */
    fun updateSource(sourceId: String, transform: (SourceDraft) -> SourceDraft): List<SourceDraft> = update { current ->
        require(current.any { it.source.sourceId == sourceId }) { "素材已移除，请重新读取" }
        current.map { draft ->
            if (draft.source.sourceId != sourceId) draft else transform(draft).also {
                require(it.source == draft.source) { "不能在编辑时替换原素材" }
            }
        }
    }

    /** Whole-workspace replacement is for initialization only; live callers use update/append. */
    fun write(drafts: List<SourceDraft>): Unit = synchronized(lock) {
        require(drafts.size <= 3) { "最多保留 3 段录屏" }
        require(drafts.map { it.source.sourceId }.distinct().size == drafts.size) { "素材标识重复" }
        require(drafts.all { it.source.metadata.byteLength in 1..200L * 1024 * 1024 &&
            it.source.metadata.durationUs in 1..180_000_000L }) { "单段录屏超过时长或大小限额" }
        require(drafts.sumOf { it.source.metadata.byteLength } <= 500L * 1024 * 1024) {
            "全部录屏不能超过 500 MiB"
        }
        require(drafts.sumOf { it.source.metadata.durationUs } <= 300_000_000L) {
            "全部录屏不能超过 5 分钟"
        }
        val items = JSONArray()
        drafts.forEach { draft ->
            require(draft.frameTimeUs in 0..draft.source.metadata.durationUs) { "已选帧位置无效" }
            require(draft.masks.size <= 20) { "最多保留 20 块固定遮挡" }
            val source = draft.source
            val meta = source.metadata
            val masks = JSONArray()
            draft.masks.forEach { masks.put(JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
            items.put(JSONObject().apply {
                put("id", source.sourceId)
                put("path", source.privateRelativePath)
                put("name", source.displayName)
                put("frameTimeUs", draft.frameTimeUs)
                put("masks", masks)
                put("metadata", JSONObject().apply {
                    put("mime", meta.mime)
                    put("bytes", meta.byteLength)
                    put("sha256", meta.sha256)
                    put("width", meta.width)
                    put("height", meta.height)
                    put("rotation", meta.rotationDeg)
                    put("durationUs", meta.durationUs)
                    put("pixelWidthHeightRatio", meta.pixelWidthHeightRatio)
                })
            })
        }
        val bytes = JSONObject().put("version", 1).put("sources", items).toString().toByteArray()
        val temporary = File.createTempFile(".workspace-", ".json.part", root)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                // Unlike AtomicFile.finishWrite, these failures propagate to the caller.
                output.fd.sync()
            }
            // Replacement is the visibility point; a directory fsync makes its name durable.
            // Failure after replacement is explicitly uncertain, never permission to roll back
            // the source file that the new metadata might already reference.
            Files.move(temporary.toPath(), state.baseFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            confirmDirectoryDurability()
        } finally {
            runCatching { temporary.delete() }
        }
        Unit
    }

    private fun confirmDirectoryDurability() {
        try {
            val descriptor = Os.open(root.absolutePath, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        } catch (cause: Exception) {
            throw WorkspaceDurabilityException(cause)
        }
    }

    companion object {
        private val lock = Any()

        /** Reuse existing records after project deletion; no new archive or duplicate media. */
        fun retainedWorkspaces(context: Context, liveProjectIds: Set<String>): List<RetainedMediaWorkspace> = synchronized(lock) {
            val root = context.noBackupFilesDir
            val result = mutableListOf<RetainedMediaWorkspace>()
            val legacy = runCatching { WorkspaceStore(context).read() }.getOrNull()
            result += RetainedMediaWorkspace(null, "原素材工作台", legacy?.size ?: 0)
            val pattern = Regex("project-media-([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12})\\.json(?:\\.bak|\\.new)?")
            val ids = root.listFiles().orEmpty().mapNotNull { pattern.matchEntire(it.name)?.groupValues?.get(1) }
                .distinct().filterNot { it in liveProjectIds }.sorted()
            ids.forEach { id ->
                val drafts = runCatching { WorkspaceStore(context, id).read() }.getOrNull()
                if (drafts == null) result += RetainedMediaWorkspace(id, "保留素材（记录待恢复）", 0)
                else if (drafts.isNotEmpty()) result += RetainedMediaWorkspace(id, drafts.first().source.displayName, drafts.size)
            }
            result
        }
    }
}
