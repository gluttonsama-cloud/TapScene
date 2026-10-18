package com.tapscene.ui

import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerScene
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AiAnnotationEditsTest {
    private val visitId = id("visit")
    private val otherVisitId = id("other-visit")

    @Test fun updatingTextPreservesOriginalSlotTimingRectangleAndEveryOtherField() {
        // Text editing must not silently repair or discard unrelated stored fields.
        val original = RenderPlan.Effect("annotation", visitId, 17, 49,
            id("hotspot"), id("region"), "原标注", ViewerScene.Rect(.21, .32, .43, .15))
        val before = highlight(visitId)
        val otherAnnotation = annotation(otherVisitId)
        val after = highlight(otherVisitId)
        val config = configuration(listOf(before, otherAnnotation, original, after)).copy(width = 1920, height = 1080)

        val updated = config.withAnnotation(visitId, "  修改后的文字  ")

        assertEquals(4, updated.effects.size)
        assertSame(before, updated.effects[0])
        assertSame(otherAnnotation, updated.effects[1])
        assertSame(after, updated.effects[3])
        val edited = updated.effects[2]
        assertEquals("  修改后的文字  ", edited.text)
        assertEquals(original.type, edited.type)
        assertEquals(original.visitId, edited.visitId)
        assertEquals(original.startFrame, edited.startFrame)
        assertEquals(original.durationFrames, edited.durationFrames)
        assertEquals(original.hotspotId, edited.hotspotId)
        assertEquals(original.regionId, edited.regionId)
        assertSame(original.rect, edited.rect)
        assertSame(config.scene, updated.scene)
        assertSame(config.visits, updated.visits)
        assertEquals(config.width, updated.width)
        assertEquals(config.height, updated.height)
        assertEquals(config.fromDraft, updated.fromDraft)
        assertEquals(listOf(before, otherAnnotation, original, after), config.effects)
        assertEquals("原标注", original.text)
    }

    @Test fun addingUsesVisitHoldAndDefaultRectangleWithoutChangingOtherEffects() {
        val sameVisitEffect = highlight(visitId)
        val otherAnnotation = annotation(otherVisitId)
        val config = configuration(listOf(sameVisitEffect, otherAnnotation))

        val updated = config.withAnnotation(visitId, "新标注")

        assertEquals(3, updated.effects.size)
        assertSame(sameVisitEffect, updated.effects[0])
        assertSame(otherAnnotation, updated.effects[1])
        val added = updated.effects[2]
        assertEquals("annotation", added.type)
        assertEquals(visitId, added.visitId)
        assertEquals("新标注", added.text)
        assertEquals(0, added.startFrame)
        assertEquals(137, added.durationFrames)
        assertNull(added.hotspotId)
        assertNull(added.regionId)
        assertEquals(.08, added.rect.x, 0.0)
        assertEquals(.08, added.rect.y, 0.0)
        assertEquals(.84, added.rect.width, 0.0)
        assertEquals(.14, added.rect.height, 0.0)
        assertEquals(listOf(sameVisitEffect, otherAnnotation), config.effects)
        assertEquals(3, updated.resolve().effects.size)
    }

    @Test fun explicitRemovalKeepsOtherVisitsEffectsAndOrder() {
        val first = highlight(visitId)
        val removed = annotation(visitId)
        val otherAnnotation = annotation(otherVisitId)
        val last = highlight(otherVisitId)
        val config = configuration(listOf(first, removed, otherAnnotation, last))

        val updated = config.withoutAnnotation(visitId)

        assertEquals(listOf(first, otherAnnotation, last), updated.effects)
        assertSame(config.visits, updated.visits)
        assertSame(config.scene, updated.scene)
        assertEquals(listOf(first, removed, otherAnnotation, last), config.effects)
        assertSame(updated, updated.withoutAnnotation(visitId))
    }

    @Test fun removingAbsentAnnotationIsAnIdempotentNoOp() {
        val config = configuration(listOf(highlight(visitId), annotation(otherVisitId)))
        assertSame(config, config.withoutAnnotation(visitId))
    }

    @Test fun blankAndOversizedTextRejectsBothAddAndUpdateWithoutDeleting() {
        for (effects in listOf(emptyList(), listOf(annotation(visitId)))) {
            val config = configuration(effects)
            for (text in listOf("", "   ", "\t\n", "\u3000", "字".repeat(241))) {
                rejectsUnchanged(config) { config.withAnnotation(visitId, text) }
            }
        }
    }

    @Test fun maximumLengthTextCanBeAddedAndUpdatedExactly() {
        val text = "字".repeat(240)
        val added = configuration().withAnnotation(visitId, text)
        assertEquals(text, added.effects.single().text)
        val updated = added.withAnnotation(visitId, "文".repeat(240))
        assertEquals("文".repeat(240), updated.effects.single().text)
        assertEquals(text, added.effects.single().text)
    }

    @Test fun missingVisitRejectsBothApplyAndRemovalWithoutChangingSource() {
        val config = configuration(listOf(annotation(visitId), highlight(otherVisitId)))
        rejectsUnchanged(config) { config.withAnnotation(id("missing"), "新文字") }
        rejectsUnchanged(config) { config.withoutAnnotation(id("missing")) }
    }

    @Test fun draftBoundPlanRejectsAddUpdateAndRemovalIncludingNoOpRemoval() {
        for (effects in listOf(emptyList(), listOf(annotation(visitId)))) {
            val config = configuration(effects).copy(fromDraft = true)
            rejectsUnchanged(config) { config.withAnnotation(visitId, "新文字") }
            rejectsUnchanged(config) { config.withoutAnnotation(visitId) }
        }
    }

    @Test fun multipleAnnotationsOnOneVisitRejectApplyAndRemovalDespiteDifferentRegions() {
        val first = annotation(visitId)
        val second = RenderPlan.Effect("annotation", visitId, 70, 10,
            null, id("different-region"), "另一标注", ViewerScene.Rect(.1, .2, .3, .4))
        val config = configuration(listOf(highlight(visitId), first, annotation(otherVisitId), second))

        rejectsUnchanged(config) { config.withAnnotation(visitId, "新文字") }
        rejectsUnchanged(config) { config.withoutAnnotation(visitId) }
        assertEquals("原标注", first.text)
        assertEquals("另一标注", second.text)
    }

    @Test fun effectLimitBlocksOnlyAdditionNotTextUpdatesOrRemoval() {
        val effects = List(RenderPlan.MAX_EFFECTS) { highlight(otherVisitId) }
        val full = configuration(effects)
        rejectsUnchanged(full) { full.withAnnotation(visitId, "新文字") }

        val original = annotation(visitId)
        val withExisting = configuration(effects.dropLast(1) + original)
        val updated = withExisting.withAnnotation(visitId, "新文字")
        assertEquals(RenderPlan.MAX_EFFECTS, updated.effects.size)
        assertEquals("新文字", updated.effects.last().text)
        assertEquals(original.startFrame, updated.effects.last().startFrame)
        assertEquals(original.durationFrames, updated.effects.last().durationFrames)
        assertEquals(RenderPlan.MAX_EFFECTS - 1, withExisting.withoutAnnotation(visitId).effects.size)
    }

    private fun configuration(effects: List<RenderPlan.Effect> = emptyList()): AiPackageConfiguration {
        val first = id("first-state")
        val last = id("last-state")
        val edge = id("edge")
        val image = id("image")
        val scene = ViewerScene(id("release"), "标注编辑", "", 1, first,
            listOf(ViewerScene.State(first, image, 32, 48, "开始", "", "authored", false),
                ViewerScene.State(last, image, 32, 48, "结束", "", "authored", true)),
            listOf(ViewerScene.Edge(edge, first, last, null, null, "下一步", "continue", "authored")),
            emptyList(), listOf(ViewerScene.Asset(image, "assets/$image.png", "image/png", 100, "0".repeat(64), 32, 48)))
        return AiPackageConfiguration(scene, listOf(RenderPlan.Visit(visitId, first, edge, 137),
            RenderPlan.Visit(otherVisitId, last, null, 90)), effects)
    }

    private fun annotation(visit: String) = RenderPlan.Effect("annotation", visit, 13, 31,
        null, null, "原标注", ViewerScene.Rect(.2, .3, .4, .1))

    private fun highlight(visit: String) = RenderPlan.Effect("highlight", visit, 5, 20,
        null, null, null, ViewerScene.Rect(.1, .2, .3, .4))

    private fun rejectsUnchanged(config: AiPackageConfiguration, change: () -> AiPackageConfiguration) {
        val effects = config.effects
        val snapshot = effects.toList()
        val visits = config.visits
        assertThrows(IllegalStateException::class.java) { change() }
        assertSame(effects, config.effects)
        assertEquals(snapshot, config.effects)
        assertSame(visits, config.visits)
    }

    private fun id(value: String) = UUID.nameUUIDFromBytes(value.toByteArray()).toString()
}
