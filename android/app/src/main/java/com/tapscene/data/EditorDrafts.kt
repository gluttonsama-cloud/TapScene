package com.tapscene.data

import com.tapscene.media.OpaqueMask
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Private, uncommitted editor text and authored actions. Media is never part of this record. */
data class StoredEditorDraft(
    val baseRevision: Long,
    val base: EditorDraftFields,
    val edit: EditorDraftFields,
    val pendingForm: EditorPendingForm? = null,
)

data class EditorDraftFields(
    val title: String,
    val description: String,
    val isTerminal: Boolean,
    val hotspots: List<ProjectHotspot>,
    val nextAction: ProjectNextAction? = null,
)

enum class EditorFormKind { NAME, HOTSPOT, NEXT_ACTION }

/** Identifies only the saved safe pixels used for one new suggestion, never the OCR result. */
data class TextRegionSourceBinding(
    val projectId: String,
    val stepId: String,
    val assetId: String,
    val sha256: String,
    val width: Int,
    val height: Int,
) {
    init {
        require(listOf(projectId, stepId, assetId).all { id ->
            runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        } && sha256.matches(Regex("[0-9a-f]{64}")) && width > 0 && height > 0 &&
            width.toLong() * height <= 12_000_000) { "文字候选画面来源无效。" }
    }

    // Text edits may advance the project revision without changing the source pixels.
    fun matches(project: ProjectSummary, step: ProjectStep): Boolean = project.id == projectId &&
        step.id == stepId && step.asset.id == assetId && step.asset.sha256 == sha256 &&
        step.asset.width == width && step.asset.height == height

    companion object {
        const val IMAGE_CHANGED = "画面已更新，请取消此建议后重新识别或手动画区；输入仍保留"
    }
}

/** Raw strings intentionally retain incomplete/invalid typing until the author applies a form. */
data class EditorPendingForm(
    val kind: EditorFormKind,
    val objectId: String? = null,
    val edgeId: String? = null,
    val title: String = "",
    val description: String = "",
    val label: String = "",
    val left: String = "",
    val top: String = "",
    val right: String = "",
    val bottom: String = "",
    val targetStepId: String? = null,
    val endLabel: String = "演示结束",
    val endsDemo: Boolean = false,
    val textRegionSource: TextRegionSourceBinding? = null,
) {
    init {
        require(textRegionSource == null || kind == EditorFormKind.HOTSPOT && objectId != null && edgeId != null) {
            "文字候选来源只能绑定新热点表单。"
        }
    }
}

fun ProjectStep.editorFields() = EditorDraftFields(title, description, isTerminal,
    hotspots.sortedBy { it.id }.map { it.copy(transition = null) }, nextAction?.copy(transition = null))

/** Only the current official step may supply media; changed or removed objects lose bindings. */
fun EditorDraftFields.withCurrentMedia(step: ProjectStep): EditorDraftFields {
    val current = step.hotspots.associateBy { it.id }
    return copy(hotspots = hotspots.map { hotspot ->
        val clean = hotspot.copy(transition = null)
        val official = current[hotspot.id]
        clean.copy(transition = official?.takeIf { it.edgeId == clean.edgeId &&
            it.targetStepId == clean.targetStepId && it.endLabel == clean.endLabel }?.transition)
    }, nextAction = nextAction?.let { action ->
        val clean = action.copy(transition = null)
        clean.copy(transition = step.nextAction?.takeIf { it.id == clean.id &&
            it.targetStepId == clean.targetStepId }?.transition)
    })
}

/** Explicit whitelist. Only a pending suggestion's minimal image identity may be retained. */
internal object EditorDraftCodec {
    const val MAX_BYTES = 256 * 1024
    private const val VERSION = 2

    fun encode(draft: StoredEditorDraft): String {
        require(draft.baseRevision > 0) { "编辑暂存的修订号无效。" }
        validateNewSuggestion(draft)
        return JSONObject().apply {
            put("version", VERSION)
            put("baseRevision", draft.baseRevision)
            put("base", fieldsJson(draft.base))
            put("edit", fieldsJson(draft.edit))
            put("pendingForm", draft.pendingForm?.let(::formJson) ?: JSONObject.NULL)
        }.toString().also(::checkSize)
    }

    fun decode(value: String): StoredEditorDraft {
        checkSize(value)
        val parser = JSONTokener(value)
        val json = parser.nextValue() as? JSONObject ?: error("编辑暂存格式无效。")
        require(parser.nextClean() == '\u0000') { "编辑暂存包含额外内容。" }
        json.keysExactly("version", "baseRevision", "base", "edit", "pendingForm")
        val version = json.integer("version")
        require(version in 1L..VERSION.toLong()) { "编辑暂存版本不受支持，请保留本机数据。" }
        val revision = json.integer("baseRevision")
        require(revision > 0) { "编辑暂存的修订号无效。" }
        return StoredEditorDraft(revision, parseFields(json.getJSONObject("base")),
            parseFields(json.getJSONObject("edit")), json.nullableObject("pendingForm")?.let { parseForm(it, version) })
            .also(::validateNewSuggestion)
    }

    private fun validateNewSuggestion(draft: StoredEditorDraft) {
        val form = draft.pendingForm?.takeIf { it.textRegionSource != null } ?: return
        require((draft.base.hotspots + draft.edit.hotspots).none { it.id == form.objectId || it.edgeId == form.edgeId }) {
            "文字候选来源只能绑定新热点表单。"
        }
    }

    private fun fieldsJson(fields: EditorDraftFields) = JSONObject().apply {
        require(fields.hotspots.size <= ProjectLimits.MAX_HOTSPOTS_PER_STEP) { "编辑暂存热点数量超限。" }
        require(fields.hotspots.map { it.id }.toSet().size == fields.hotspots.size &&
            fields.hotspots.map { it.edgeId }.toSet().size == fields.hotspots.size) { "编辑暂存对象标识重复。" }
        put("title", fields.title); put("description", fields.description); put("isTerminal", fields.isTerminal)
        put("hotspots", JSONArray().apply { fields.hotspots.forEach { put(hotspotJson(it)) } })
        put("nextAction", fields.nextAction?.let { action ->
            validId(action.id); action.targetStepId?.let(::validId)
            JSONObject().apply {
                put("id", action.id); put("label", action.label)
                put("targetStepId", action.targetStepId ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)
    }

    private fun hotspotJson(hotspot: ProjectHotspot) = JSONObject().apply {
        validId(hotspot.id); validId(hotspot.edgeId); hotspot.targetStepId?.let(::validId)
        require((hotspot.targetStepId == null) != (hotspot.endLabel == null)) { "编辑暂存热点目标无效。" }
        put("id", hotspot.id); put("edgeId", hotspot.edgeId); put("label", hotspot.label)
        put("left", hotspot.rect.left.toDouble()); put("top", hotspot.rect.top.toDouble())
        put("right", hotspot.rect.right.toDouble()); put("bottom", hotspot.rect.bottom.toDouble())
        put("targetStepId", hotspot.targetStepId ?: JSONObject.NULL)
        put("endLabel", hotspot.endLabel ?: JSONObject.NULL)
    }

    private fun parseFields(json: JSONObject): EditorDraftFields {
        json.keysExactly("title", "description", "isTerminal", "hotspots", "nextAction")
        val hotspots = json.getJSONArray("hotspots")
        require(hotspots.length() <= ProjectLimits.MAX_HOTSPOTS_PER_STEP) { "编辑暂存热点数量超限。" }
        val fields = EditorDraftFields(json.string("title"), json.string("description"), json.boolean("isTerminal"),
            List(hotspots.length()) { index -> hotspots.getJSONObject(index).let { spot ->
                spot.keysExactly("id", "edgeId", "label", "left", "top", "right", "bottom", "targetStepId", "endLabel")
                ProjectHotspot(spot.string("id"), spot.string("label"),
                    OpaqueMask(spot.coordinate("left"), spot.coordinate("top"), spot.coordinate("right"), spot.coordinate("bottom")),
                    spot.nullableString("targetStepId"), spot.nullableString("endLabel"), spot.string("edgeId"))
            } }, json.nullableObject("nextAction")?.let { action ->
                action.keysExactly("id", "label", "targetStepId")
                ProjectNextAction(action.string("id"), action.string("label"), action.nullableString("targetStepId"))
            })
        // Apply the same structural constraints to reads and writes without trimming user text.
        fieldsJson(fields)
        return fields
    }

    private fun formJson(form: EditorPendingForm) = JSONObject().apply {
        form.objectId?.let(::validId); form.edgeId?.let(::validId); form.targetStepId?.let(::validId)
        put("kind", form.kind.name); put("objectId", form.objectId ?: JSONObject.NULL)
        put("edgeId", form.edgeId ?: JSONObject.NULL); put("title", form.title); put("description", form.description)
        put("label", form.label); put("left", form.left); put("top", form.top)
        put("right", form.right); put("bottom", form.bottom)
        put("targetStepId", form.targetStepId ?: JSONObject.NULL); put("endLabel", form.endLabel); put("endsDemo", form.endsDemo)
        put("textRegionSource", form.textRegionSource?.let { source -> JSONObject().apply {
            put("projectId", source.projectId); put("stepId", source.stepId); put("assetId", source.assetId)
            put("sha256", source.sha256); put("width", source.width); put("height", source.height)
        } } ?: JSONObject.NULL)
    }

    private fun parseForm(json: JSONObject, version: Long): EditorPendingForm {
        val keys = arrayOf("kind", "objectId", "edgeId", "title", "description", "label", "left", "top", "right", "bottom",
            "targetStepId", "endLabel", "endsDemo")
        json.keysExactly(*(if (version == 1L) keys else keys + "textRegionSource"))
        val source = if (version == 1L) null else json.nullableObject("textRegionSource")?.let {
            it.keysExactly("projectId", "stepId", "assetId", "sha256", "width", "height")
            TextRegionSourceBinding(it.string("projectId"), it.string("stepId"), it.string("assetId"),
                it.string("sha256"), it.positiveInt("width"), it.positiveInt("height"))
        }
        return EditorPendingForm(EditorFormKind.valueOf(json.string("kind")), json.nullableString("objectId"),
            json.nullableString("edgeId"), json.string("title"), json.string("description"), json.string("label"),
            json.string("left"), json.string("top"), json.string("right"), json.string("bottom"),
            json.nullableString("targetStepId"), json.string("endLabel"), json.boolean("endsDemo"), source).also(::formJson)
    }

    private fun checkSize(value: String) {
        require(value.length <= MAX_BYTES && value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "本步骤编辑暂存超过 256 KiB，请缩短输入后重试；内容未被截断。"
        }
    }
    private fun validId(value: String) {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "编辑暂存对象标识无效。" }
    }
    private fun JSONObject.keysExactly(vararg expected: String) {
        require(keys().asSequence().toSet() == expected.toSet()) { "编辑暂存字段无效，请保留本机数据。" }
    }
    private fun JSONObject.string(key: String): String = get(key).let {
        require(it is String) { "编辑暂存文本格式无效。" }; it
    }
    private fun JSONObject.nullableString(key: String): String? = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.boolean(key: String): Boolean = get(key).let {
        require(it is Boolean) { "编辑暂存开关格式无效。" }; it
    }
    private fun JSONObject.integer(key: String): Long = get(key).let {
        require(it is Int || it is Long) { "编辑暂存整数格式无效。" }; (it as Number).toLong()
    }
    private fun JSONObject.positiveInt(key: String): Int = integer(key).let {
        require(it in 1L..Int.MAX_VALUE.toLong()) { "编辑暂存画面尺寸无效。" }; it.toInt()
    }
    private fun JSONObject.coordinate(key: String): Float = get(key).let {
        require(it is Number && it.toDouble().isFinite() && it.toDouble() in 0.0..1.0) { "编辑暂存坐标格式无效。" }
        it.toFloat()
    }
    private fun JSONObject.nullableObject(key: String): JSONObject? = if (get(key) == JSONObject.NULL) null else getJSONObject(key)
}
