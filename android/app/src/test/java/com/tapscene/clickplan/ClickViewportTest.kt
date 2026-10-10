package com.tapscene.clickplan

import org.junit.Assert.*
import org.junit.Test

class ClickViewportTest {
    @Test fun actualSystemInsetsLeaveAppNavigationAvailable() {
        val viewport = ClickViewport(ClickGeometry(1080, 2400, 0), 0, 72, 0, 48)
        // Ordinary app back/tab targets, previously rejected by the four-sided 48dp rule.
        assertTrue(viewport.contains(24, 120))
        assertTrue(viewport.contains(1050, 2300))
        assertFalse(viewport.contains(540, 71))
        assertFalse(viewport.contains(540, 2352))
        assertFalse(viewport.contains(-1, 300))
        assertFalse(viewport.contains(1080, 300))
        assertTrue(viewport.contains(0, 72))
        assertTrue(viewport.contains(1079, 2351))
        val landscape = ClickViewport(ClickGeometry(2400, 1080, 1), 90, 0, 48, 0)
        assertFalse(landscape.contains(89, 400))
        assertTrue(landscape.contains(90, 16))
        assertThrows(IllegalArgumentException::class.java) { ClickViewport(ClickGeometry(100, 100, 0), 60, 0, 40, 0) }
        assertThrows(IllegalArgumentException::class.java) { ClickViewport(ClickGeometry(100, 100, 0, 1), 0, 0, 0, 0) }
    }

    @Test fun actualOverlayOriginMustAgreeWithRawScreenCoordinates() {
        val viewport = ClickViewport(ClickGeometry(1080, 2400, 0), 0, 72, 0, 48)
        val fitted = ClickOverlayFrame(0, 72, 1080, 2280)
        assertTrue(viewport.acceptsFrame(fitted))
        assertEquals(ClickScreenPoint(24, 120), viewport.screenPoint(fitted, 24f, 48f, 24f, 120f))
        assertNull(viewport.screenPoint(fitted, 24f, 48f, 24f, 48f))
        assertNull(viewport.screenPoint(fitted, Float.NaN, 48f, 24f, 120f))
        assertNull(viewport.screenPoint(fitted, 24f, -1f, 24f, 71f))
        assertFalse(viewport.acceptsFrame(fitted.copy(y = 100)))
        assertFalse(viewport.acceptsFrame(fitted.copy(width = 900)))
        assertEquals(ClickScreenPoint(0, 2300), viewport.clampControl(-100, 9000, 160, 52))
        assertNull(viewport.clampControl(0, 0, 2000, 52))
    }

    @Test fun browsingOnlyRestoresEditorAndOldControlsCannotReopenClosedSession() {
        val locating = ClickEditorState().open()
        val picking = locating.change(ClickEditorEvent.AddPoint, locating.generation)
        assertEquals(ClickEditorMode.Picking, picking.mode)
        assertEquals(picking, picking.change(ClickEditorEvent.Browse, picking.generation))
        val restored = picking.change(ClickEditorEvent.CancelPick, picking.generation)
        val browsing = restored.change(ClickEditorEvent.Browse, restored.generation)
        assertEquals(ClickEditorMode.Browsing, browsing.mode)
        assertEquals(browsing, browsing.change(ClickEditorEvent.AddPoint, browsing.generation))
        assertEquals(browsing, browsing.change(ClickEditorEvent.Resume, locating.generation))
        assertEquals(ClickEditorMode.Locating, browsing.change(ClickEditorEvent.Resume, browsing.generation).mode)
        val closed = browsing.close()
        assertEquals(closed, closed.change(ClickEditorEvent.Resume, browsing.generation))
        assertEquals(closed, closed.change(ClickEditorEvent.Resume, closed.generation))
    }
}
