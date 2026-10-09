package com.tapscene.data

import android.content.Context
import android.util.AtomicFile
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SourceMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class SourceDraft(
    val source: ImportedSource,
    val frameTimeUs: Long = 0,
    val masks: List<OpaqueMask> = emptyList(),
)

/** Media workbench state only. This is not the project's future graph database. */
class WorkspaceStore(context: Context) {
    private val root = context.noBackupFilesDir
    private val state = AtomicFile(File(root, "media-workspace.json"))

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

    fun write(drafts: List<SourceDraft>): Unit = synchronized(lock) {
        require(drafts.size <= 3) { "最多保留 3 段录屏" }
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
            // Same-directory replacement is the commit point. Do not perform fallible work
            // afterward: import registration must never report failure after it committed.
            Files.move(temporary.toPath(), state.baseFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            runCatching { temporary.delete() }
        }
        Unit
    }

    companion object { private val lock = Any() }
}
