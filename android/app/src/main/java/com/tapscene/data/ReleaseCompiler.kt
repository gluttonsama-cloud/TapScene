package com.tapscene.data

import android.content.Context
import android.os.Looper
import com.tapscene.media.SafeMediaWriterValidation
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

/** Explicit whitelist projection: no source paths, capture tokens, masks, OCR or edit history. */
internal object ReleaseCompiler {
    fun scene(snapshot: ProjectSnapshot, releaseId: String, createdAt: Long): ViewerScene {
        val states = snapshot.steps.map { step ->
            ViewerScene.State(step.id, step.asset.id, step.asset.width, step.asset.height,
                step.title, step.description, "recorded", step.isTerminal)
        }
        val hotspots = snapshot.steps.flatMap { step -> step.hotspots.map { hotspot ->
            val left = six(hotspot.rect.left.toDouble())
            val top = six(hotspot.rect.top.toDouble())
            val right = six(hotspot.rect.right.toDouble())
            val bottom = six(hotspot.rect.bottom.toDouble())
            require(right > left && bottom > top) { "热点过小，规范化后没有面积，请重新编辑。" }
            ViewerScene.Hotspot(hotspot.id, step.id, hotspot.label,
                ViewerScene.Rect(left, top, six(right - left), six(bottom - top)))
        } }
        val edges = snapshot.steps.flatMap { step ->
            step.hotspots.map { hotspot ->
                ViewerScene.Edge(hotspot.edgeId, step.id, hotspot.targetStepId, hotspot.endLabel,
                    hotspot.id, hotspot.label, "tap", "authored", hotspot.transition?.asset?.id)
            } + listOfNotNull(step.nextAction?.let { next ->
                require(next.targetStepId != null) { "下一步动作没有目标，请返回编辑。" }
                ViewerScene.Edge(next.id, step.id, next.targetStepId, null,
                    null, next.label, "continue", "authored", next.transition?.asset?.id)
            })
        }
        val transitions = snapshot.steps.flatMap { step ->
            step.hotspots.mapNotNull { it.transition } + listOfNotNull(step.nextAction?.transition)
        }
        require(transitions.sumOf { it.asset.durationUs } <= ProjectLimits.MAX_TOTAL_TRANSITION_US) {
            "全部边绑定视频累计不能超过 60 秒。"
        }
        val regions = snapshot.steps.flatMap { step -> step.regions.map { region ->
            require(region.matchesBase(step.asset) && region.reviewedAt != null) { "区域底图已失效或裁片尚未复核。" }
            val crop = requireNotNull(region.asset) { "区域裁片需要重新生成。" }
            ViewerScene.Region(region.id, step.id, step.asset.id, crop.id, region.name,
                region.sourceWidth, region.sourceHeight,
                ViewerScene.PixelRect(region.bbox.x, region.bbox.y, region.bbox.width, region.bbox.height),
                region.group, region.zIndex, ViewerScene.Anchor(region.anchorX, region.anchorY))
        } }
        val regionAssets = snapshot.steps.flatMap { it.regions }.map { requireNotNull(it.asset) }
        val assets = snapshot.steps.map { step -> step.asset.let { asset ->
            ViewerScene.Asset(asset.id, "assets/${asset.id}.png", "image/png", asset.byteLength,
                asset.sha256, asset.width, asset.height)
        } } + transitions.map { it.asset }.map { asset ->
            require(asset.durationUs in 1..ProjectLimits.MAX_TRANSITION_US) { "单段过渡实际时长超过 10 秒。" }
            ViewerScene.Asset(asset.id, "assets/${asset.id}.mp4", "video/mp4", asset.byteLength,
                asset.sha256, asset.width, asset.height, ViewerScene.Asset.ROLE_TRANSITION, (asset.durationUs + 999L) / 1_000L)
        }
        val allAssets = assets + regionAssets.map { asset ->
            ViewerScene.Asset(asset.id, "assets/${asset.id}.png", "image/png", asset.byteLength,
                asset.sha256, asset.width, asset.height, ViewerScene.Asset.ROLE_REGION_CROP, null)
        }
        val schema = if (regions.isNotEmpty()) 3 else if (transitions.isEmpty()) 1 else 2
        val policy = when (schema) { 1 -> ViewerPackageCodec.POLICY_VERSION; 2 -> ViewerPackageCodec.VIDEO_POLICY_VERSION
            else -> ViewerPackageCodec.REGION_POLICY_VERSION }
        return ViewerScene(schema, policy,
            ViewerPackageCodec.COMPILER_VERSION, releaseId, snapshot.project.title, snapshot.project.goal, createdAt,
            requireNotNull(snapshot.project.startStepId) { "请先设置项目起点。" }, states, edges, hotspots, regions, allAssets)
            .also(ViewerPackageCodec::validateScene)
    }

    /** The copied/imported bytes themselves are fully decoded, not a preview or source bitmap. */
    suspend fun verifyMedia(context: Context, scene: ViewerScene, packageRoot: File) {
        scene.assets.forEach { asset -> verifyAsset(context, asset, packageRoot) }
    }

    suspend fun verifyAsset(context: Context, asset: ViewerScene.Asset, packageRoot: File) {
        currentCoroutineContext().ensureActive()
        val file = File(packageRoot, asset.path)
        check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() == asset.byteLength &&
            ViewerPackageCodec.sha256(file) == asset.sha256) { "成品实际媒体缺失或摘要改变。" }
        if (asset.role == ViewerScene.Asset.ROLE_TRANSITION) verifyVideo(context, file, asset)
        else SafeMediaWriterValidation.verifyPng(file, asset.width, asset.height, emptyList())
        check(ViewerPackageCodec.sha256(file) == asset.sha256) { "成品实际媒体在验证期间改变。" }
    }

    /** Java's synchronous package boundary still executes full coroutine-aware Android decode.
     * Dispatchers.IO avoids carrying a Main dispatcher into the nested blocking bridge; the
     * parent Job is retained so cancellation reaches the complete decode and all sample loops. */
    fun videoValidator(context: Context, owner: CoroutineContext): ViewerPackageCodec.VideoValidator =
        ViewerPackageCodec.VideoValidator { file, declared, cancel ->
            check(Looper.myLooper() != Looper.getMainLooper()) { "视频包校验必须在后台执行。" }
            cancel.check()
            runBlocking(owner + Dispatchers.IO) { verifyVideo(context, file, declared) }
            cancel.check()
        }

    private suspend fun verifyVideo(context: Context, file: File, asset: ViewerScene.Asset) {
        val actual = SafeMediaWriterValidation.verifyStoredVideoOutput(context, file,
            expectedWidth = asset.width, expectedHeight = asset.height)
        check(actual.durationUs in 1..ProjectLimits.MAX_TRANSITION_US &&
            (actual.durationUs + 999L) / 1_000L == asset.durationMs) {
            "实际过渡时长与包声明不一致或超过 10 秒。"
        }
    }

    private fun six(value: Double): Double = BigDecimal.valueOf(value)
        .setScale(6, RoundingMode.HALF_UP).toDouble()
}
