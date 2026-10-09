package com.tapscene.data

import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.DraftAiJson
import com.tapscene.packageformat.ViewerScene
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A private editable plan, not a release or an assertion that any image was reviewed. */
data class DraftAiEffect(val id: String, val value: RenderPlan.Effect)
data class DraftAiConfig(
    val boundRevision: Long,
    val needsRepair: Boolean,
    val width: Int,
    val height: Int,
    val visits: List<RenderPlan.Visit>,
    val effects: List<DraftAiEffect>,
) {
    fun resolve(scene: ViewerScene): RenderPlan = RenderPlan.build(scene, width, height, visits, effects.map { it.value })
    companion object {
        fun imported(plan: RenderPlan) = DraftAiConfig(1, false, plan.width, plan.height, plan.visits,
            plan.effects.map { DraftAiEffect(UUID.randomUUID().toString(), it) })
    }
}

/** Can round-trip broken references. Repair never erases a visit or an effect implicitly. */
internal object DraftAiConfigCodec {
    const val MAX_BYTES = 524288
    fun encode(value: DraftAiConfig): String {
        require(value.boundRevision > 0 && (value.width to value.height) in setOf(1080 to 1920, 1920 to 1080)) { "动画配置尺寸或修订无效。" }
        require(value.visits.size in 1..RenderPlan.MAX_VISITS && value.effects.size <= RenderPlan.MAX_EFFECTS) { "动画配置超过访问或效果限额。" }
        require(value.visits.map { it.visitId }.distinct().size == value.visits.size &&
            value.effects.map { it.id }.distinct().size == value.effects.size) { "动画配置中有重复的编辑标识。" }
        val visits = JSONArray().apply { value.visits.forEach { visit ->
            id(visit.visitId); id(visit.stateId); visit.selectedEdgeId?.let(::id)
            require(visit.holdFrames in 1..RenderPlan.MAX_HOLD_FRAMES) { "每次停留须为 1–1800 帧。" }
            put(JSONObject().put("id", visit.visitId).put("state", visit.stateId)
                .put("edge", visit.selectedEdgeId ?: JSONObject.NULL).put("hold", visit.holdFrames))
        } }
        val effects = JSONArray().apply { value.effects.forEach { entry ->
            id(entry.id); val effect = entry.value; id(effect.visitId)
            effect.hotspotId?.let(::id); effect.regionId?.let(::id)
            require(effect.type in setOf("click", "focus", "highlight", "annotation", "transition") &&
                effect.startFrame in 0..RenderPlan.MAX_HOLD_FRAMES && effect.durationFrames in 1..RenderPlan.MAX_HOLD_FRAMES) { "效果种类或时间超出支持范围。" }
            effect.text?.let { require(it.length <= 240) { "标注最多 240 字。" }; DraftAiJson.validateText(it) }
            val rect = effect.rect?.let {
                require(listOf(it.x, it.y, it.width, it.height).all { n -> n.isFinite() && n in 0.0..1.0 }) { "效果范围无效。" }
                JSONObject().put("x", it.x.toString()).put("y", it.y.toString()).put("width", it.width.toString()).put("height", it.height.toString())
            }
            put(JSONObject().put("id", entry.id).put("type", effect.type).put("visit", effect.visitId)
                .put("start", effect.startFrame).put("duration", effect.durationFrames)
                .put("hotspot", effect.hotspotId ?: JSONObject.NULL).put("region", effect.regionId ?: JSONObject.NULL)
                .put("text", effect.text ?: JSONObject.NULL).put("rect", rect ?: JSONObject.NULL))
        } }
        val result = JSONObject().put("version", 1).put("width", value.width).put("height", value.height)
            .put("visits", visits).put("effects", effects).toString()
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "动画配置超过本机容量限制。" }
        return result
    }
    fun decode(raw: String, revision: Long, needsRepair: Boolean): DraftAiConfig {
        require(raw.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES) { "动画配置大小无效。" }
        // The trusted JSON parser rejects duplicate keys, malformed Unicode and unbounded input.
        DraftAiJson.validate(raw.toByteArray(Charsets.UTF_8), MAX_BYTES)
        val json = JSONObject(raw); fields(json, "version", "width", "height", "visits", "effects")
        require(json.getInt("version") == 1) { "动画草稿版本不支持。" }
        val visits = json.getJSONArray("visits").let { list -> (0 until list.length()).map { index ->
            val v = list.getJSONObject(index); fields(v, "id", "state", "edge", "hold")
            RenderPlan.Visit(v.getString("id"), v.getString("state"), nullable(v, "edge"), v.getInt("hold"))
        } }
        val effects = json.getJSONArray("effects").let { list -> (0 until list.length()).map { index ->
            val e = list.getJSONObject(index); fields(e, "id", "type", "visit", "start", "duration", "hotspot", "region", "text", "rect")
            val r = if (e.isNull("rect")) null else e.getJSONObject("rect").let {
                fields(it, "x", "y", "width", "height")
                ViewerScene.Rect(it.getDouble("x"), it.getDouble("y"), it.getDouble("width"), it.getDouble("height"))
            }
            DraftAiEffect(e.getString("id"), RenderPlan.Effect(e.getString("type"), e.getString("visit"), e.getInt("start"),
                e.getInt("duration"), nullable(e, "hotspot"), nullable(e, "region"), nullable(e, "text"), r))
        } }
        return DraftAiConfig(revision, needsRepair, json.getInt("width"), json.getInt("height"), visits, effects).also(::encode)
    }
    private fun id(value: String) { require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "动画对象标识无效。" } }
    private fun nullable(value: JSONObject, key: String) = if (value.isNull(key)) null else value.getString(key)
    private fun fields(value: JSONObject, vararg names: String) { require(value.keys().asSequence().toSet() == names.toSet()) { "动画草稿有缺失或未知字段。" } }
}

/** Ephemeral validation only: never stored as a release and never bypasses candidate review. */
internal object DraftPlanProjection {
    fun issues(snapshot: ProjectSnapshot, config: DraftAiConfig): List<String> = buildList {
        val states = snapshot.steps.associateBy { it.id }
        val edges = snapshot.steps.flatMap { step -> step.hotspots.map { it.edgeId } + listOfNotNull(step.nextAction?.id) }.toSet()
        val regions = snapshot.steps.flatMap { it.regions }.associateBy { it.id }
        val hotspots = snapshot.steps.flatMap { it.hotspots }.map { it.id }.toSet()
        config.visits.forEachIndexed { index, visit ->
            if (visit.stateId !in states) add("第 ${index + 1} 次访问的步骤已删除，请重新选择或明确移除。")
            if (visit.selectedEdgeId != null && visit.selectedEdgeId !in edges) add("第 ${index + 1} 次访问的动作已删除，请重新选择。")
        }
        config.effects.forEachIndexed { index, entry ->
            val effect = entry.value
            val visit = config.visits.firstOrNull { it.visitId == effect.visitId }
            if (visit == null) add("效果 ${index + 1} 的访问已删除，请改绑或明确移除。")
            if (visit != null && effect.startFrame.toLong() + effect.durationFrames > visit.holdFrames) add("效果 ${index + 1} 超出当前停留时间；参数已保留，请调整。")
            if (effect.hotspotId != null && effect.hotspotId !in hotspots) add("效果 ${index + 1} 的热点已删除，请改绑或明确移除。")
            if (effect.regionId != null) {
                val region = regions[effect.regionId]
                if (region == null) add("效果 ${index + 1} 的区域已删除，请改绑或明确移除。")
                else if (region.asset == null || states[region.stateId]?.asset?.let(region::matchesBase) != true) add("效果 ${index + 1} 的区域画面已变化，请先修复区域。")
            }
        }
        if (isEmpty()) runCatching { config.resolve(ReleaseCompiler.draftPlanScene(snapshot)) }.exceptionOrNull()?.let {
            add("动画计划需要调整：${it.message ?: "当前路径或效果未通过检查。"}")
        }
    }
}
