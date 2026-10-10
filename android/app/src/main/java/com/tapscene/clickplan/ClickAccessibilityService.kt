package com.tapscene.clickplan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewConfiguration
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** No node, text, key, notification, clipboard or content retrieval. Events are never logged. */
class ClickAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val overlays = mutableListOf<View>()
    private var plan: ClickPlan? = null
    private val overlayContext by lazy {
        if (Build.VERSION.SDK_INT < 33) error("Click overlays require Android 13")
        val display = checkNotNull(getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY))
        createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    }
    private val windows by lazy { requireNotNull(overlayContext.getSystemService(WindowManager::class.java)) }
    private var editor = ClickEditorState()
    private var controlPosition: ClickScreenPoint? = null
    private var locatorFrame: ClickOverlayFrame? = null
    internal val canPreparePlayback: Boolean get() {
        if (editor.mode != ClickEditorMode.Locating) return false
        val value = plan ?: return false
        val frame = locatorFrame ?: return false
        val viewport = currentViewport() ?: return false
        return viewport.matches(value) && viewport.acceptsFrame(frame)
    }
    internal fun currentViewport(): ClickViewport? = runCatching { ClickDevice.viewport(overlayContext) }.getOrNull()

    override fun onServiceConnected() { super.onServiceConnected(); ClickPlayback.connected(this) }
    override fun onInterrupt() { ClickPlayback.interrupted(this) }
    override fun onUnbind(intent: Intent?): Boolean {
        hideEditor { }
        ClickPlayback.permissionLost(this)
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        hideEditor { }
        ClickPlayback.permissionLost(this)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !ClickPlayback.observing || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Read metadata only. Never access source/root/windows/text/contentDescription.
        val packageName = event.packageName?.toString()
        val className = event.className?.toString()
        val knownActivity = if (packageName != null && className != null) runCatching {
            packageManager.getActivityInfo(ComponentName(packageName, className), 0).packageName == packageName
        }.getOrDefault(false) else false
        ClickPlayback.windowChanged(packageName, className, event.isFullScreen, knownActivity,
            if (Build.VERSION.SDK_INT >= 33) event.displayId else -1, event.eventTime)
    }

    fun dispatch(token: ClickCallbackToken, action: ClickAction, callback: (ClickCallbackToken, ClickGestureResult) -> Unit): Boolean {
        if (!ClickPlayback.dispatchAllowed() || action.pressDurationMs > ClickDevice.maximumShortPressMs() ||
            getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != false ||
            Build.VERSION.SDK_INT < 33 ||
            !unmagnified() || currentViewport()?.contains(action.x, action.y) != true) return false
        val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
        val builder = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, action.pressDurationMs))
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(android.view.Display.DEFAULT_DISPLAY)
        return dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback(token, ClickGestureResult.Completed) }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback(token, ClickGestureResult.Cancelled) }
        }, main)
    }

    private fun unmagnified(): Boolean = if (Build.VERSION.SDK_INT < 33) false else runCatching {
        val controller = magnificationController
        val config = controller.magnificationConfig ?: return@runCatching false
        config.scale == 1f && if (Build.VERSION.SDK_INT >= 34) !config.isActivated else controller.currentMagnificationRegion.isEmpty
    }.getOrDefault(false)

    fun showEditor(value: ClickPlan) {
        editor = editor.open()
        plan = value
        renderEditor()
    }

    private fun changeEditor(event: ClickEditorEvent, generation: Long) {
        val next = editor.change(event, generation)
        if (next == editor || plan == null || ClickPlayback.state.value.busy) return
        editor = next
        try { renderEditor() }
        catch (_: Exception) {
            hideEditor { }
            notice("定位浮层无法更新，请返回 TapScene 重新打开定位。")
        }
    }

    private fun renderEditor() {
        locatorFrame = null
        check(removeOverlays()) { "Previous overlay still attached" }
        val value = checkNotNull(plan)
        val viewport = checkNotNull(currentViewport()) { "System bounds are unavailable" }
        check(viewport.matches(value))
        val generation = editor.generation
        val picking = editor.mode == ClickEditorMode.Picking
        if (editor.mode == ClickEditorMode.Browsing) {
            addMoveableControl("继续定位", viewport, generation, compact = true) { changeEditor(ClickEditorEvent.Resume, generation) }
        } else {
            val marks = object : View(overlayContext) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                private var down: ClickScreenPoint? = null
                private var moved = false
                private val slop = ViewConfiguration.get(context).scaledTouchSlop
                private fun frame(): ClickOverlayFrame {
                    val location = IntArray(2); getLocationOnScreen(location)
                    return ClickOverlayFrame(location[0], location[1], width, height)
                }
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val actual = frame()
                    val current = currentViewport()
                    if (generation != editor.generation) return
                    locatorFrame = actual.takeIf { current != null && current.matches(value) && current.acceptsFrame(it) }
                    if (locatorFrame == null) return
                    value.actions.forEachIndexed { index, point ->
                        val x = (point.x - actual.x).toFloat(); val y = (point.y - actual.y).toFloat()
                        paint.color = Color.rgb(30, 125, 103); paint.alpha = 220
                        canvas.drawCircle(x, y, 20 * resources.displayMetrics.density, paint)
                        paint.color = Color.WHITE; paint.alpha = 255; paint.textSize = 16 * resources.displayMetrics.scaledDensity
                        paint.textAlign = Paint.Align.CENTER
                        canvas.drawText((index + 1).toString(), x, y + paint.textSize / 3, paint)
                    }
                }
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (!picking || generation != editor.generation) return false
                    val current = currentViewport()?.takeIf { it.matches(value) }
                    val point = current?.screenPoint(frame(), event.x, event.y, event.rawX, event.rawY)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { down = point; moved = false }
                        MotionEvent.ACTION_MOVE -> {
                            val first = down
                            if (point == null || first == null || kotlin.math.abs(point.x - first.x) > slop || kotlin.math.abs(point.y - first.y) > slop) moved = true
                        }
                        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { down = null; moved = true }
                        MotionEvent.ACTION_UP -> {
                            if (down != null && point != null && !moved &&
                                kotlin.math.abs(point.x - requireNotNull(down).x) <= slop &&
                                kotlin.math.abs(point.y - requireNotNull(down).y) <= slop) {
                                ClickPlayback.addPoint(point.x, point.y)
                                performClick()
                            } else notice("未添加点位：请在 App 内容区短点一次，避开系统栏。")
                            down = null
                        }
                    }
                    return true
                }
                override fun performClick(): Boolean { super.performClick(); return true }
            }
            addOverlay(marks, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    (if (picking) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE), Gravity.TOP or Gravity.LEFT,
                fullDisplay = true)
            if (picking) {
                addMoveableControl("点一下定位 · 取消", viewport, generation) { changeEditor(ClickEditorEvent.CancelPick, generation) }
            } else {
                val panel = LinearLayout(overlayContext).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8); setBackgroundColor(Color.rgb(240, 248, 245))
                    addView(TextView(overlayContext).apply {
                        text = "TapScene · ${value.actions.size} 个点\n浏览页面后可继续添加；播放前回到起始页"; setTextColor(Color.BLACK)
                    })
                    fun button(label: String, action: () -> Unit) { addView(Button(overlayContext).apply {
                        text = label; minHeight = (48 * resources.displayMetrics.density).toInt()
                        setOnClickListener { if (generation == editor.generation) action() }
                    }) }
                    button("添加点位") { changeEditor(ClickEditorEvent.AddPoint, generation) }
                    button("浏览页面 · 暂藏点位") { changeEditor(ClickEditorEvent.Browse, generation) }
                    button("隐藏点位并准备播放") { ClickPlayback.prepareConsent(this@ClickAccessibilityService) }
                    button("返回编辑 / 取消") { ClickPlayback.closeEditor(this@ClickAccessibilityService) }
                }
                addOverlay(panel, (292 * overlayContext.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            }
        }
        ClickPlayback.overlayChanged(true)
    }

    /** Dragging only moves this control window. No touch is replayed to the underlying application. */
    private fun addMoveableControl(label: String, viewport: ClickViewport, generation: Long, compact: Boolean = false, onClick: () -> Unit) {
        val width = ((if (compact) 76 else 160) * overlayContext.resources.displayMetrics.density).toInt()
        val height = (52 * overlayContext.resources.displayMetrics.density).toInt()
        val position = checkNotNull(viewport.clampControl(controlPosition?.x ?: viewport.left,
            controlPosition?.y ?: (viewport.top + height), width, height))
        controlPosition = position
        val button = Button(overlayContext).apply {
            text = label; contentDescription = "$label，可拖动以避开页面内容"
            minWidth = 0; minimumWidth = 0; setPadding(4, 0, 4, 0)
            setOnClickListener { if (generation == editor.generation) onClick() }
        }
        val params = addOverlay(button, width, height, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            Gravity.TOP or Gravity.LEFT, fullDisplay = true, x = position.x, y = position.y)
        val slop = ViewConfiguration.get(overlayContext).scaledTouchSlop
        var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0; var dragged = false; var cancelled = false
        button.setOnTouchListener { _, event ->
            if (generation != editor.generation) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y
                    dragged = false; cancelled = false; button.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) dragged = true
                    if (dragged && !cancelled) {
                        button.isPressed = false
                        val next = currentViewport()?.takeIf { it.geometry == viewport.geometry }
                            ?.clampControl(initialX + dx.toInt(), initialY + dy.toInt(), width, height)
                        if (next == null) cancelled = true else {
                            params.x = next.x; params.y = next.y
                            try { windows.updateViewLayout(button, params); controlPosition = next }
                            catch (_: Exception) { cancelled = true }
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { cancelled = true; button.isPressed = false }
                MotionEvent.ACTION_UP -> {
                    button.isPressed = false
                    if (!dragged && !cancelled && kotlin.math.abs(event.rawX - downX) <= slop &&
                        kotlin.math.abs(event.rawY - downY) <= slop) button.performClick()
                }
            }
            true
        }
    }

    internal fun notice(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    /** WindowManager detachment is observed before the explicit clean-screen confirmation Activity. */
    fun hideEditor(onDetached: (Boolean) -> Unit) {
        editor = editor.close() // Invalidate old view callbacks even if a platform detach fails.
        locatorFrame = null
        val detached = removeOverlays()
        if (detached) { plan = null; ClickPlayback.overlayChanged(false) }
        onDetached(detached)
    }

    private fun removeOverlays(): Boolean {
        val removed = overlays.toList()
        var failed = false
        removed.forEach { view ->
            try { windows.removeViewImmediate(view) } catch (_: Exception) { failed = true }
        }
        overlays.removeAll { !it.isAttachedToWindow }
        return !failed && removed.none { it.isAttachedToWindow } && overlays.isEmpty()
    }

    private fun addOverlay(view: View, width: Int, height: Int, flags: Int, gravity: Int,
        fullDisplay: Boolean = false, x: Int = 0, y: Int = 0): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity; this.x = x; this.y = y
            if (fullDisplay && Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        overlays += view
        windows.addView(view, params)
        return params
    }
}
