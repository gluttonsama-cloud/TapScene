package com.tapscene.data

import android.util.JsonReader
import android.util.JsonToken
import com.tapscene.packageformat.ViewerScene
import com.tapscene.packageformat.ViewerTraversal
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONArray
import org.json.JSONObject

/** Process-local ownership is never written into the immutable release or its checkpoint. */
data class PlaybackSession(val releaseId: String, val contentDigest: String, val ownerToken: String)

data class PlaybackOpen(
    val scene: ViewerScene,
    val session: PlaybackSession,
    val checkpoint: ViewerTraversal?,
    val checkpointUnavailable: Boolean,
)

/** App-private progress, independent of package contents and author review/coverage records. */
internal object PlaybackCheckpoint {
    const val MAX_BYTES = 64 * 1024
    private val fields = setOf("version", "releaseId", "contentDigest", "currentStateId", "history",
        "ended", "endLabel", "endEdgeId", "visitedEdgeIds", "completedFromStart")

    fun read(file: File, scene: ViewerScene, contentDigest: String): ViewerTraversal {
        require(file.canonicalFile == file.absoluteFile && file.isFile && file.length() in 1..MAX_BYTES.toLong()) {
            "播放进度文件无效或过大。"
        }
        // Keep the actual read bounded too, even if the file's length changes after the check.
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                require(count > 0)
                size += count
            }
            require(size in 1..MAX_BYTES) { "播放进度文件过大。" }
            buffer.copyOf(size)
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val values = mutableMapOf<String, Any?>()
        // Only this flat schema and bounded string arrays are accepted. Unknown/duplicate keys,
        // coercions, nested objects, malformed UTF-8 and trailing JSON cannot become progress.
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(name in fields && !values.containsKey(name)) { "播放进度字段无效。" }
                values[name] = when (name) {
                    "version" -> {
                        require(reader.peek() == JsonToken.NUMBER && reader.nextString() == "1")
                        1
                    }
                    "history" -> strings(reader, ViewerTraversal.MAX_VISITS)
                    "visitedEdgeIds" -> strings(reader, scene.edges.size)
                    "ended", "completedFromStart" -> {
                        require(reader.peek() == JsonToken.BOOLEAN)
                        reader.nextBoolean()
                    }
                    "endLabel", "endEdgeId" -> if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else string(reader)
                    else -> string(reader)
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && values.keys == fields)
        }
        require(values["releaseId"] == scene.releaseId && values["contentDigest"] == contentDigest) {
            "播放进度不属于这个固定版本。"
        }
        @Suppress("UNCHECKED_CAST")
        val history = values.getValue("history") as List<String>
        @Suppress("UNCHECKED_CAST")
        val visited = values.getValue("visitedEdgeIds") as List<String>
        require(visited.size == visited.toSet().size) { "播放进度包含重复动作。" }
        return ViewerTraversal(values.getValue("currentStateId") as String, history,
            values.getValue("ended") as Boolean, values["endLabel"] as String?, values["endEdgeId"] as String?,
            visited.toSet(), values.getValue("completedFromStart") as Boolean).cancelTransition(scene)
    }

    fun json(scene: ViewerScene, contentDigest: String, traversal: ViewerTraversal): JSONObject {
        // A pending clip retains its source visit. Never persist media IDs, callbacks, pending
        // targets or closed state; validation uses the same graph/ending rules as the viewer.
        val stable = ViewerTraversal(traversal.currentStateId, traversal.history, traversal.ended,
            traversal.endLabel, traversal.endEdgeId, traversal.visitedEdgeIds,
            traversal.completedFromStart).cancelTransition(scene)
        return JSONObject().apply {
            put("version", 1); put("releaseId", scene.releaseId); put("contentDigest", contentDigest)
            put("currentStateId", stable.currentStateId); put("history", JSONArray(stable.history))
            put("ended", stable.ended); put("endLabel", stable.endLabel ?: JSONObject.NULL)
            put("endEdgeId", stable.endEdgeId ?: JSONObject.NULL)
            put("visitedEdgeIds", JSONArray(stable.visitedEdgeIds.sorted()))
            put("completedFromStart", stable.completedFromStart)
            require(toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "播放进度超出限额。" }
        }
    }

    private fun strings(reader: JsonReader, limit: Int): List<String> {
        val result = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(result.size < limit) { "播放进度超出访问限额。" }
            result += string(reader)
        }
        reader.endArray()
        return result
    }

    private fun string(reader: JsonReader): String {
        require(reader.peek() == JsonToken.STRING)
        return reader.nextString().also { require(it.length <= 8192) }
    }
}
