package com.tapscene.clickplan

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityRecord
import android.view.inspector.WindowInspector
import android.widget.Button
import com.tapscene.data.HostFileSyncShadow
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Real production overlay Views/ViewRoot events, touch handler, service and AtomicFile store.
 * Robolectric has no complete AMS accessibility routing: target/foreign metadata below are
 * explicit fixtures, while overlay events are captured from real ViewRoot traversal. The earlier
 * baseline bridged actual emitted events and reproduced 0 points versus 1 without their relay.
 * This regression is not an Android/device runtime or screen-reader usability pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h800dp-mdpi",
    shadows = [HostFileSyncShadow::class, ClickOverlayPointHostTest.FailNextOverlayWindowManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClickOverlayPointHostTest {
    private lateinit var app: Application
    private lateinit var controller: ServiceController<ClickAccessibilityService>
    private lateinit var service: ClickAccessibilityService
    private lateinit var accessibility: AccessibilityManager
    private lateinit var store: ClickPlanStore
    private lateinit var initial: ClickPlan
    private var previousRequestedSize: String? = null
    private var fileSyncStarted = false
    private val ownedRoots = linkedSetOf<View>()

    @Before fun begin() {
        FailNextOverlayWindowManager.failNextOverlay = false
        FailNextOverlayWindowManager.injectedFailures = 0
        FailNextOverlayWindowManager.failRemovalOf = null
        app = RuntimeEnvironment.getApplication()
        val privateRoot = app.noBackupFilesDir.canonicalFile
        check(privateRoot.toPath().startsWith(RuntimeEnvironment.getTempDirectory().basePath.toRealPath()))
        HostFileSyncShadow.begin(privateRoot)
        fileSyncStarted = true
        previousRequestedSize = System.setProperty("robolectric.windowManager.useRequestedSize", "true")
        val activity = ActivityInfo().apply {
            name = TARGET_ACTIVITY
            packageName = TARGET_PACKAGE
            enabled = true
            exported = true
            applicationInfo = ApplicationInfo().apply {
                packageName = TARGET_PACKAGE
                enabled = true
                flags = ApplicationInfo.FLAG_INSTALLED
            }
        }
        shadowOf(app.packageManager).addOrUpdateActivity(activity)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY),
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        checkNotNull(app.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE))
        accessibility = requireNotNull(app.getSystemService(AccessibilityManager::class.java))
        shadowOf(accessibility).setEnabled(true)
        controller = Robolectric.buildService(ClickAccessibilityService::class.java).create()
        service = controller.get()
        // Host connection fixture only; this does not claim Android service authorization.
        ClickPlayback.connected(service)
        awaitRecoverySetup()
        store = ClickPlanStore(app)
        val geometry = ClickDevice.geometry(app)
        assertEquals(ClickGeometry(480, 800, 0, 0), geometry)
        initial = ClickPlan.create(UUID.randomUUID().toString(), TARGET_PACKAGE,
            geometry.width, geometry.height, geometry.rotation, emptyList())
        store.savePlan(initial, null)
        ClickPlayback.openEditor(app, initial)
        idle()
        assertTrue("Production editor failed to attach: ${ClickPlayback.state.value}", ClickPlayback.state.value.overlayVisible)
        assertEquals(2, overlayRoots().size)
        assertTrue(requireNotNull(service.currentViewport()).contains(126, 181))
    }

    @After fun end() {
        FailNextOverlayWindowManager.failNextOverlay = false
        FailNextOverlayWindowManager.failRemovalOf = null
        try {
            if (::controller.isInitialized) {
                service.hideEditor { assertTrue("Host overlay did not detach", it) }
                controller.destroy()
                idle()
            }
        } finally {
            FailNextOverlayWindowManager.failNextOverlay = false
            try { if (fileSyncStarted) HostFileSyncShadow.reset() } finally {
                val key = "robolectric.windowManager.useRequestedSize"
                previousRequestedSize?.let { System.setProperty(key, it) } ?: System.clearProperty(key)
            }
        }
    }

    @Test fun actualOverlayRootsPreservePointSavingAndForeignGuardsAndCloseOnRevisionConflict() {
        assertNoOwnWindowAnnouncements("initial overlay attachment")
        savePointWithTargetFixture()

        val rejected = listOf(
            "unknown" to (windowFixture(null, null) to ClickPointFailure.TargetWindowUnknown),
            "foreign" to (windowFixture("com.example.foreign", "com.example.foreign.MainActivity") to ClickPointFailure.TargetWindowBlocked),
            "own Activity" to (windowFixture(app.packageName, "com.tapscene.MainActivity") to ClickPointFailure.TargetWindowBlocked),
            "wrong display" to (windowFixture(displayId = actualRootDisplay() + 1) to ClickPointFailure.TargetWindowBlocked),
        )
        for ((label, fixture) in rejected) {
            val before = requireNotNull(store.getPlan(initial.projectId))
            service.onAccessibilityEvent(fixture.first)
            enterPickingThroughAccessibleButton()
            tapMarks(126, 181)
            assertEquals("$label must not save a point", before.digest,
                requireNotNull(store.getPlan(initial.projectId)).digest)
            assertEquals(label, fixture.second, ClickPlayback.state.value.pointFailure)
            assertTrue(ClickPlayback.state.value.overlayVisible)
            assertNoOwnWindowAnnouncements("$label rejection")
            savePointWithTargetFixture()
        }

        // Actual optimistic-store conflict: the editor retains its previous revision while an
        // independent ordinary store edit advances the durable revision. No throwing store stub.
        val editorVersion = requireNotNull(store.getPlan(initial.projectId))
        val external = editorVersion.copy(revision = editorVersion.revision + 1,
            actions = editorVersion.actions.mapIndexed { index, action ->
                if (index == 0) action.copy(waitAfterMs = action.waitAfterMs + 1) else action
            })
        store.savePlan(external, editorVersion.revision)
        service.onAccessibilityEvent(windowFixture())
        enterPickingThroughAccessibleButton()
        tapMarks(126, 181)
        assertEquals(ClickPointFailure.SaveUnconfirmed, ClickPlayback.state.value.pointFailure)
        assertEquals(ClickPointFailure.SaveUnconfirmed.message, ClickPlayback.state.value.message)
        assertFalse(ClickPlayback.state.value.overlayVisible)
        assertFalse(ClickPlayback.observing)
        assertTrue(overlayRoots().isEmpty())
        assertEquals(external.digest, requireNotNull(store.getPlan(initial.projectId)).digest)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals("No point may be retried after an unconfirmed save", external.digest,
            requireNotNull(store.getPlan(initial.projectId)).digest)
        assertFalse(ClickPlayback.state.value.overlayVisible)
        assertNull(ClickPlayback.state.value.run)

        // Explicitly reopen the externally saved plan. Fail attachment only after the real picker
        // is ready, so this fault occurs after addPoint's actual durable store write, not at entry.
        ClickPlayback.openEditor(app, external)
        idle()
        assertTrue(ClickPlayback.state.value.overlayVisible)
        assertNull(ClickPlayback.state.value.pointFailure)
        service.onAccessibilityEvent(windowFixture())
        enterPickingThroughAccessibleButton()
        FailNextOverlayWindowManager.failNextOverlay = true
        tapMarks(126, 181)
        assertEquals(1, FailNextOverlayWindowManager.injectedFailures)
        assertFalse(FailNextOverlayWindowManager.failNextOverlay)
        val committed = requireNotNull(store.getPlan(initial.projectId))
        assertEquals(external.actions.size + 1, committed.actions.size)
        assertEquals(external.revision + 1, committed.revision)
        assertEquals(ClickPointFailure.OverlayRefresh, ClickPlayback.state.value.pointFailure)
        assertEquals(ClickPointFailure.OverlayRefresh.message, ClickPlayback.state.value.message)
        assertFalse(ClickPlayback.state.value.overlayVisible)
        assertFalse(ClickPlayback.observing)
        assertTrue(overlayRoots().isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertEquals("Committed point must not be retried after render failure", committed.digest,
            requireNotNull(store.getPlan(initial.projectId)).digest)
        assertNoOwnWindowAnnouncements("post-save attachment failure")

        // Conversely, a failed removal while the exact root remains registered/attached must
        // never be reclassified as successful cleanup by the failed-add handling.
        ClickPlayback.openEditor(app, committed)
        idle()
        val remaining = overlayRoots().first()
        FailNextOverlayWindowManager.failRemovalOf = remaining
        var detached: Boolean? = null
        service.hideEditor { detached = it }
        assertEquals(false, detached)
        assertTrue(remaining.isAttachedToWindow)
        assertTrue(WindowInspector.getGlobalWindowViews().any { it === remaining })
        assertTrue(ClickPlayback.state.value.overlayVisible)
        assertFalse(service.canPreparePlayback)
        assertFalse(ClickPlayback.dispatchAllowed())
        assertEquals(committed.digest, requireNotNull(store.getPlan(initial.projectId)).digest)
        FailNextOverlayWindowManager.failRemovalOf = null
        ClickPlayback.closeEditor(app)
        idle()
        assertFalse(ClickPlayback.state.value.overlayVisible)
        assertFalse(ClickPlayback.observing)

        // Before the first traversal, a root may already be registered while not yet attached.
        // Two failed hides must retain this root in the production ledger, rather than treating
        // !isAttachedToWindow alone as proof that WindowManager has forgotten it.
        ClickPlayback.openEditor(app, committed) // Deliberately do not idle/traverse yet.
        val registered = registeredOverlayRoots()
        assertEquals(2, registered.size)
        val pending = registered.firstOrNull { !it.isAttachedToWindow }
        assertNotNull("HOST MODEL INCONCLUSIVE: no registered pre-attachment overlay root", pending)
        val pendingRoot = requireNotNull(pending)
        ownedRoots += registered
        FailNextOverlayWindowManager.failRemovalOf = pendingRoot
        repeat(2) {
            var completed: Boolean? = null
            service.hideEditor { completed = it }
            assertEquals("Pending registered root must survive failed hide ${it + 1}", false, completed)
            assertTrue(WindowInspector.getGlobalWindowViews().any { root -> root === pendingRoot })
            assertTrue(ClickPlayback.state.value.overlayVisible)
            assertFalse(service.canPreparePlayback)
        }
        FailNextOverlayWindowManager.failRemovalOf = null
        service.hideEditor { assertTrue("Recovery must actually remove the pending registered root", it) }
        assertFalse(WindowInspector.getGlobalWindowViews().any { it === pendingRoot })
        ClickPlayback.closeEditor(app)
        idle()
        assertFalse(ClickPlayback.state.value.overlayVisible)
        assertFalse(ClickPlayback.observing)
        assertEquals(committed.digest, requireNotNull(store.getPlan(initial.projectId)).digest)
        HostFileSyncShadow.assertDirectorySyncEvidence()
        println("HOST_OVERLAY_POINT_REGRESSION savedPoints=${committed.actions.size} rejectedWindowFixtures=4 revisionConflict=closed-no-retry postSaveRenderFailure=closed-no-retry attachedDetachFailure=fail-closed pendingDetachFailure=retained-twice ownRootWindowEvents=0 androidRouting=not-modeled")
    }

    private fun savePointWithTargetFixture() {
        val before = requireNotNull(store.getPlan(initial.projectId))
        service.onAccessibilityEvent(windowFixture())
        enterPickingThroughAccessibleButton()
        tapMarks(126, 181)
        val saved = requireNotNull(store.getPlan(initial.projectId))
        assertEquals(before.actions.size + 1, saved.actions.size)
        assertEquals(ClickScreenPoint(126, 181), ClickScreenPoint(saved.actions.last().x, saved.actions.last().y))
        assertEquals(before.revision + 1, saved.revision)
        assertNull(ClickPlayback.state.value.pointFailure)
        assertNull(ClickPlayback.state.value.run)
        assertNoOwnWindowAnnouncements("saved point overlay refresh")
    }

    private fun enterPickingThroughAccessibleButton() {
        val oldRoots = overlayRoots()
        val add = button("添加点位")
        assertFalse("The panel's AddPoint child is not a WindowManager root", add in oldRoots)
        // This deliberately sent child event must traverse the panel root unfiltered. It is
        // captured at the manager, not relayed as a foreground fixture to the production service.
        assertOwnEventPasses(add, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        clickAccessible(add)
        idle()
        val roots = overlayRoots()
        assertEquals(2, roots.size)
        assertTrue("AddPoint must use the real replacement roots", oldRoots.none { it.isAttachedToWindow })
        assertTrue(roots.all {
            (it.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
        })
        assertNoOwnWindowAnnouncements("AddPoint replacement")

        // The compact control is itself an owned root carrying the production delegate.
        val control = button("点一下定位 · 取消")
        assertTrue(control in roots)
        assertOwnEventPasses(control, AccessibilityEvent.TYPE_VIEW_FOCUSED)
        assertOwnEventPasses(control, AccessibilityEvent.TYPE_VIEW_CLICKED)
        clickAccessible(control) // The root's actual ACTION_CLICK must cancel Picking.
        idle()
        assertTrue("Root control ACTION_CLICK did not detach the picking roots", roots.none { it.isAttachedToWindow })
        clickAccessible(button("添加点位")) // The real returned panel must allow entering again.
        idle()
        assertTrue(button("点一下定位 · 取消").isAttachedToWindow)
        assertNoOwnWindowAnnouncements("root control cancel and re-enter")
    }

    private fun clickAccessible(control: Button) {
        val node = control.createAccessibilityNodeInfo()
        try {
            assertTrue("Own control node must remain clickable", node.isClickable)
            assertTrue("Own control node must retain ACTION_CLICK",
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        } finally { node.recycle() }
        assertTrue(control.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
    }

    private fun assertOwnEventPasses(control: View, type: Int) {
        val from = shadowOf(accessibility).sentAccessibilityEvents.size
        control.sendAccessibilityEvent(type)
        idle()
        assertTrue("Owned UI event $type must pass from its exact source",
            shadowOf(accessibility).sentAccessibilityEvents.drop(from).any {
                it.eventType == type &&
                    shadowOf(it as AccessibilityRecord).sourceRoot === control
            })
    }

    private fun assertNoOwnWindowAnnouncements(stage: String) {
        assertTrue("Own overlay root window-state announcement leaked at $stage",
            shadowOf(accessibility).sentAccessibilityEvents.none {
                it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    shadowOf(it as AccessibilityRecord).sourceRoot in ownedRoots
            })
    }

    private fun actualRootDisplay(): Int {
        val roots = overlayRoots()
        assertTrue(roots.isNotEmpty())
        assertTrue(roots.all { it.windowToken != null })
        val displays = roots.map { requireNotNull(it.display).displayId }.distinct()
        assertEquals(listOf(Display.DEFAULT_DISPLAY), displays)
        return displays.single()
    }

    /** Explicit metadata fixture, never evidence that a target/foreign app emitted this event. */
    private fun windowFixture(pkg: String? = TARGET_PACKAGE, cls: String? = TARGET_ACTIVITY,
        displayId: Int = actualRootDisplay()): AccessibilityEvent =
        AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = pkg
            className = cls
            isFullScreen = true
            // AMS normally resolves windowId -> windowToken -> display before service delivery.
            // Only a fixture uses this hidden setter; no production event or guard is modified.
            // AOSP android-15.0.0_r1 AccessibilityManagerService.sendAccessibilityEvent and
            // AccessibilityWindowManager.getDisplayIdByUserIdAndWindowId define that boundary.
            ReflectionHelpers.callInstanceMethod<Void>(this, "setDisplayId",
                ClassParameter.from(Int::class.javaPrimitiveType!!, displayId))
            setSource(null as View?)
        }

    private fun registeredOverlayRoots(): List<View> = WindowInspector.getGlobalWindowViews().filter {
        (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    }

    private fun overlayRoots(): List<View> = registeredOverlayRoots().filter { it.isAttachedToWindow }

    private fun button(label: String): Button = overlayRoots().asSequence().flatMap(::descendants)
        .filterIsInstance<Button>().single { it.text.toString() == label }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun tapMarks(x: Int, y: Int) {
        val marks = overlayRoots().single { it !is Button && it !is ViewGroup }
        val origin = IntArray(2).also(marks::getLocationOnScreen)
        assertTrue(requireNotNull(service.currentViewport()).acceptsFrame(
            ClickOverlayFrame(origin[0], origin[1], marks.width, marks.height)))
        val down = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 80)) {
            val event = MotionEvent.obtain(down, at, action, x.toFloat(), y.toFloat(), 0)
            event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
            try { assertTrue("Production picking View rejected touch", marks.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }
        idle()
    }

    private fun idle() {
        ownedRoots += overlayRoots()
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        ownedRoots += overlayRoots()
    }

    private fun awaitRecoverySetup() {
        // Reflection is limited to observing asynchronous singleton setup, never guard/editor state.
        val ready = ClickPlayback::class.java.getDeclaredField("recoveryDone").apply { isAccessible = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!ready.getBoolean(ClickPlayback) && System.nanoTime() < deadline) {
            idle()
            Thread.sleep(5)
        }
        idle()
        assertTrue("Production journal recovery did not finish during host setup", ready.getBoolean(ClickPlayback))
    }

    /** All normal host behavior is inherited; only the explicitly armed next overlay add fails. */
    @Implements(className = "android.view.WindowManagerImpl", isInAndroidSdk = false)
    class FailNextOverlayWindowManager : ShadowWindowManagerImpl() {
        @Implementation
        public override fun addView(view: View, params: ViewGroup.LayoutParams) {
            if (failNextOverlay && (params as? WindowManager.LayoutParams)?.type ==
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                failNextOverlay = false
                injectedFailures++
                throw WindowManager.BadTokenException("Host-only overlay reattachment failure")
            }
            super.addView(view, params)
        }

        @Implementation
        public override fun removeViewImmediate(view: View) {
            if (view === failRemovalOf) {
                throw IllegalStateException("Host-only removal failure with root still registered")
            }
            super.removeViewImmediate(view)
        }

        companion object {
            var failNextOverlay = false
            var injectedFailures = 0
            var failRemovalOf: View? = null
        }
    }

    companion object {
        private const val TARGET_PACKAGE = "com.example.overlaytarget"
        private const val TARGET_ACTIVITY = "$TARGET_PACKAGE.MainActivity"
    }
}
