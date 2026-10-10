package com.tapscene.clickplan

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager

data class ClickTargetApp(val packageName: String, val label: String)

object ClickDevice {
    @Suppress("DEPRECATION")
    fun geometry(context: Context): ClickGeometry {
        val display = checkNotNull(requireNotNull(context.getSystemService(DisplayManager::class.java)).getDisplay(Display.DEFAULT_DISPLAY))
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        check(metrics.widthPixels > 0 && metrics.heightPixels > 0)
        return ClickGeometry(metrics.widthPixels, metrics.heightPixels, display.rotation, display.displayId)
    }

    @Suppress("DEPRECATION")
    fun launcherApps(context: Context): List<ClickTargetApp> = context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.packageName != context.packageName && !ClickWindowPolicy.protectedPackage(it.activityInfo.packageName) }
        .map { ClickTargetApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
        .distinctBy { it.packageName }.sortedBy { it.label }

    /** Requires the service's default-display window context, not an Activity/application context. */
    fun viewport(windowContext: Context): ClickViewport? {
        if (android.os.Build.VERSION.SDK_INT < 33) return null
        return runCatching {
            check(windowContext.display?.displayId == Display.DEFAULT_DISPLAY)
            val geometry = geometry(windowContext)
            val metrics = requireNotNull(windowContext.getSystemService(WindowManager::class.java)).currentWindowMetrics
            // Window-relative insets may only be used as screen coordinates for verified full-display bounds.
            check(metrics.bounds == android.graphics.Rect(0, 0, geometry.width, geometry.height))
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            ClickViewport(geometry, insets.left, insets.top, insets.right, insets.bottom)
        }.getOrNull()
    }

    fun maximumShortPressMs(): Long = minOf(500L, ViewConfiguration.getLongPressTimeout().toLong() - 50L)
    fun shortPressesAllowed(plan: ClickPlan): Boolean = plan.actions.all { it.pressDurationMs <= maximumShortPressMs() }

    fun matches(context: Context, plan: ClickPlan): Boolean = runCatching {
        geometry(context) == ClickGeometry(plan.width, plan.height, plan.rotation, plan.displayId)
    }.getOrDefault(false)
}

/** Metadata heuristic, not a claim of comprehensive in-app payment/authentication protection. */
internal object ClickWindowPolicy {
    fun protectedPackage(value: String): Boolean = value == "android" ||
        listOf("systemui", "permissioncontroller", "packageinstaller", "settings", "inputmethod", "keyguard")
            .any { value.lowercase().contains(it) }

    fun classify(target: String, actual: String?, className: String?, fullscreen: Boolean, activityKnown: Boolean): ClickGuard {
        if (actual == null || className == null) return ClickGuard.Unknown
        if (actual != target || protectedPackage(actual)) return ClickGuard.Blocked
        val name = className.lowercase()
        if (listOf("permission", "biometric", "credential", "password", "payment", "checkout", "auth", "login", "signin", "payactivity")
                .any(name::contains)) return ClickGuard.Blocked
        return if (fullscreen && activityKnown) ClickGuard.Allowed else ClickGuard.Unknown
    }
}
