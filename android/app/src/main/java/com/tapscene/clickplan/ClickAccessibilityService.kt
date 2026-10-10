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
    private val windows by lazy { requireNotNull(getSystemService(WindowManager::class.java)) }

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
            !unmagnified()) return false
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

    fun showEditor(value: ClickPlan, picking: Boolean = false) {
        hideEditor { detached -> check(detached) { "Previous overlay still attached" } }
        plan = value
        val marks = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                value.actions.forEachIndexed { index, point ->
                    paint.color = Color.rgb(30, 125, 103); paint.alpha = 220
                    canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 20 * resources.displayMetrics.density, paint)
                    paint.color = Color.WHITE; paint.alpha = 255; paint.textSize = 16 * resources.displayMetrics.scaledDensity
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText((index + 1).toString(), point.x.toFloat(), point.y + paint.textSize / 3, paint)
                }
                if (picking) {
                    paint.color = Color.rgb(12, 30, 28); paint.alpha = 200
                    canvas.drawRect(0f, 0f, width.toFloat(), 80 * resources.displayMetrics.density, paint)
                    paint.color = Color.WHITE; paint.alpha = 255; paint.textSize = 16 * resources.displayMetrics.scaledDensity
                    canvas.drawText("点一下添加位置（不会点击目标 App）", width / 2f, 48 * resources.displayMetrics.density, paint)
                }
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (!picking) return false
                if (event.action == MotionEvent.ACTION_UP) {
                    ClickPlayback.addPoint(event.rawX.toInt(), event.rawY.toInt())
                    performClick()
                }
                return true
            }
            override fun performClick(): Boolean { super.performClick(); return true }
        }
        addOverlay(marks, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (picking) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE), Gravity.TOP or Gravity.START)
        if (picking) {
            val cancel = Button(this).apply { text = "取消添加"; setOnClickListener { showEditor(value) } }
            addOverlay(cancel, WindowManager.LayoutParams.WRAP_CONTENT, (48 * resources.displayMetrics.density).toInt(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, Gravity.TOP or Gravity.END)
        }
        if (!picking) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8); setBackgroundColor(Color.rgb(240, 248, 245))
                addView(TextView(this@ClickAccessibilityService).apply {
                    text = "TapScene · ${value.actions.size} 个点\n先手动回到安全起始页，保持方向"; setTextColor(Color.BLACK)
                })
                fun button(label: String, action: () -> Unit) { addView(Button(this@ClickAccessibilityService).apply {
                    text = label; minHeight = (48 * resources.displayMetrics.density).toInt(); setOnClickListener { action() }
                }) }
                button("添加点位") { showEditor(value, true) }
                button("隐藏点位并准备播放") { ClickPlayback.prepareConsent(this@ClickAccessibilityService) }
                button("返回编辑 / 取消") { ClickPlayback.closeEditor(this@ClickAccessibilityService) }
            }
            addOverlay(panel, (292 * resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        }
        ClickPlayback.overlayChanged(true)
    }

    internal fun notice(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    /** WindowManager detachment is observed before the explicit clean-screen confirmation Activity. */
    fun hideEditor(onDetached: (Boolean) -> Unit) {
        val removed = overlays.toList()
        var failed = false
        removed.forEach { view ->
            try { windows.removeViewImmediate(view) } catch (_: Exception) { failed = true }
        }
        overlays.removeAll { !it.isAttachedToWindow }
        val detached = !failed && removed.none { it.isAttachedToWindow } && overlays.isEmpty()
        if (detached) { plan = null; ClickPlayback.overlayChanged(false) }
        onDetached(detached)
    }

    private fun addOverlay(view: View, width: Int, height: Int, flags: Int, gravity: Int) {
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Add first to tracking: even a partial platform failure must be detached before recording.
        overlays += view
        windows.addView(view, params)
    }
}
