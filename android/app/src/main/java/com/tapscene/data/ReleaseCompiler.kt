package com.tapscene.data

import com.tapscene.media.SafeMediaWriterValidation
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
                    hotspot.id, hotspot.label, "tap", "authored")
            } + listOfNotNull(step.nextAction?.let { next ->
                require(next.targetStepId != null) { "下一步动作没有目标，请返回编辑。" }
                ViewerScene.Edge(next.id, step.id, next.targetStepId, null,
                    null, next.label, "continue", "authored")
            })
        }
        val assets = snapshot.steps.map { step -> step.asset.let { asset ->
            ViewerScene.Asset(asset.id, "assets/${asset.id}.png", "image/png", asset.byteLength,
                asset.sha256, asset.width, asset.height)
        } }
        return ViewerScene(releaseId, snapshot.project.title, snapshot.project.goal, createdAt,
            requireNotNull(snapshot.project.startStepId) { "请先设置项目起点。" }, states, edges, hotspots, assets)
            .also(ViewerPackageCodec::validateScene)
    }

    /** The copied/imported bytes themselves are fully decoded, not a preview or source bitmap. */
    suspend fun verifyMedia(scene: ViewerScene, packageRoot: File) {
        scene.assets.forEach { asset -> verifyAsset(asset, packageRoot) }
    }

    suspend fun verifyAsset(asset: ViewerScene.Asset, packageRoot: File) {
        currentCoroutineContext().ensureActive()
        val file = File(packageRoot, asset.path)
        check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() == asset.byteLength &&
            ViewerPackageCodec.sha256(file) == asset.sha256) { "成品实际图片缺失或摘要改变。" }
        SafeMediaWriterValidation.verifyPng(file, asset.width, asset.height, emptyList())
        check(ViewerPackageCodec.sha256(file) == asset.sha256) { "成品实际图片在验证期间改变。" }
    }

    private fun six(value: Double): Double = BigDecimal.valueOf(value)
        .setScale(6, RoundingMode.HALF_UP).toDouble()
}
