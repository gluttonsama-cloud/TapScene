package com.tapscene.clickplan

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.ViewConfiguration

data class ClickGeometry(val width: Int, val height: Int, val rotation: Int, val displayId: Int = Display.DEFAULT_DISPLAY)
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

    /** Conservatively keep taps away from status/navigation bars, cutouts and gesture edges. */
    fun pointsInsideSafeArea(context: Context, plan: ClickPlan): Boolean {
        val margin = (48 * context.resources.displayMetrics.density).toInt()
        return plan.actions.all { it.x >= margin && it.y >= margin && it.x < plan.width - margin && it.y < plan.height - margin }
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
