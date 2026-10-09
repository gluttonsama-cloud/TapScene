package com.tapscene.ui.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.tapscene.data.ProjectHotspot
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep
import com.tapscene.data.ProjectSummary
import com.tapscene.data.StepAsset
import com.tapscene.media.ImportedSource
import com.tapscene.media.OpaqueMask
import com.tapscene.media.SourceMetadata
import com.tapscene.ui.StepEditDraft

/** In-memory layout examples only. No store, decoder, file access, or production seed data. */
internal object ShellPreviewFixture {
    private const val Width = 480
    private const val Height = 840
    private val source = ImportedSource(
        sourceId = "layout-sample-source",
        privateRelativePath = "screenshot-only/not-a-real-video.mp4",
        displayName = "布局样例 · 合成画面",
        metadata = SourceMetadata("video/avc", 0L, "0".repeat(64), Width, Height, 0, 18_000_000L),
    )

    val project = ProjectSnapshot(
        project = ProjectSummary(
            id = "layout-sample-project",
            title = "活动报名 · 布局样例",
            goal = "演示选择活动、填写信息与完成报名的三个画面。",
            revision = 1L,
            updatedAt = 1_791_504_000_000L,
            stepCount = 3,
            startStepId = "sample-step-1",
        ),
        steps = listOf("选择活动", "填写信息", "报名完成").mapIndexed { index, title ->
            val id = "sample-step-${index + 1}"
            ProjectStep(
                id = id,
                title = title,
                description = listOf("选择想参加的活动。", "核对示例信息，然后继续。", "展示报名完成的结果。")[index],
                sortOrder = index,
                isTerminal = index == 2,
                asset = StepAsset("sample-image-${index + 1}", "screenshot-only/$id.png", "0".repeat(64), 0L, Width, Height),
                source = source,
                frameTimeUs = index * 6_000_000L,
                timePrecisionUs = 1_000L,
                masks = emptyList(),
                hotspots = if (index == 2) emptyList() else listOf(
                    ProjectHotspot(
                        id = "sample-hotspot-${index + 1}",
                        label = if (index == 0) "选择活动" else "确认报名",
                        rect = OpaqueMask(.1f, .78f, .9f, .89f),
                        targetStepId = "sample-step-${index + 2}",
                        endLabel = null,
                        edgeId = "sample-edge-${index + 1}",
                    ),
                ),
                captureId = "screenshot-only-$id",
            )
        },
    )

    val selectedStep = project.steps[1]
    val draft = StepEditDraft(
        stepId = selectedStep.id,
        title = selectedStep.title,
        description = selectedStep.description,
        isTerminal = selectedStep.isTerminal,
        hotspots = selectedStep.hotspots,
    )

    fun bitmap(index: Int): Bitmap {
        val image = Bitmap.createBitmap(Width, Height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(246, 244, 240))
        fun box(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
            paint.color = color
            canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, paint)
        }
        fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
            paint.color = color
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(value, x, y, paint)
        }
        val ink = Color.rgb(47, 49, 53)
        val muted = Color.rgb(117, 121, 124)
        val accent = Color.rgb(63, 95, 88)
        text("LAYOUT SAMPLE", 36f, 46f, 18f, muted)
        text(listOf("Choose an activity", "Your registration", "You're all set")[index], 36f, 112f, 32f, ink, true)
        text("Synthetic content for UI review", 36f, 150f, 19f, muted)
        box(36f, 196f, 444f, 360f, Color.rgb(223, 232, 227))
        text("Weekend workshop", 60f, 252f, 26f, accent, true)
        text("A small idea. A hands-on afternoon.", 60f, 292f, 18f, accent)
        text("SAT  /  14:00", 60f, 334f, 20f, accent)
        box(36f, 398f, 444f, 484f, Color.WHITE)
        text(if (index == 0) "Creative studio" else "Sample participant", 60f, 434f, 23f, ink)
        text("Example only", 60f, 463f, 18f, muted)
        box(36f, 502f, 444f, 588f, Color.WHITE)
        text(if (index == 2) "Registration complete" else "1 place selected", 60f, 540f, 23f, ink)
        text("No real account or personal details", 60f, 569f, 18f, muted)
        box(48f, 655f, 432f, 748f, accent)
        text(if (index == 2) "Done" else "Continue", 173f, 712f, 26f, Color.WHITE, true)
        text("SYNTHETIC PREVIEW", 130f, 801f, 17f, muted)
        return image
    }
}
