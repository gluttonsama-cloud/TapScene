package com.tapscene.runtime

import android.app.Instrumentation
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.tapscene.clickplan.ClickPlayback
import java.io.File

/** Normal on-screen setup only. No shell grants, app-ops, secure-settings writes or fake grants. */
internal class RuntimeSystemUi(private val instrumentation: Instrumentation) {
    val automation: UiAutomation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    var systemUiAnrWaitAttempted = false
        private set
    var systemUiAnrWaitedOnce = false
        private set
    private var observedSettingsAfterWait = false
    private val serviceLabel = instrumentation.targetContext.getString(com.tapscene.R.string.click_service_label)
    private val appLabel = instrumentation.targetContext.applicationInfo.loadLabel(instrumentation.targetContext.packageManager).toString()

    init {
        // Test controller only. Production service retains canRetrieveWindowContent=false.
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    fun clickApp(text: String) = awaitOwnControl(text, overlayOnly = false) { click(it) }
    fun clickOverlay(text: String) = awaitOwnControl(text, overlayOnly = true) { click(it) }
    fun awaitApp(text: String) = awaitOwnControl(text, overlayOnly = false) { true }

    fun searchSyntheticTarget() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) try {
                if (root.packageName?.toString() == instrumentation.targetContext.packageName) {
                    val edit = find(root) { it.isVisibleToUser && it.isEnabled && it.isEditable }
                    if (edit != null) try {
                        check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, TARGET_PACKAGE)
                        })) { "Target search input rejected text" }
                        return
                    } finally { edit.recycle() }
                }
            } finally { root.recycle() }
            SystemClock.sleep(100)
        }
        error("Own target-search input was not visible")
    }

    /** Normal test input is restricted to TapScene's visible picking/control windows. */
    fun tapPickingOverlay(x: Int, y: Int) {
        ownControlBounds("点一下定位 · 取消")
        check(ClickPlayback.state.value.overlayVisible && !ClickPlayback.state.value.busy)
        pointerPath(x, y, x, y, 80)
    }

    fun dragBrowseControl(): Pair<Rect, Rect> {
        val before = ownControlBounds("继续定位")
        check(ClickPlayback.state.value.overlayVisible && !ClickPlayback.state.value.busy)
        pointerPath(before.centerX(), before.centerY(), before.centerX() + 110, before.centerY() + 70, 350)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val after = ownControlBounds("继续定位", deadline)
            if (after.left - before.left >= 80 && after.top - before.top >= 40) return before to after
            SystemClock.sleep(100)
        }
        error("Small browse control did not actually move after drag")
    }

    private fun pointerPath(x: Int, y: Int, toX: Int, toY: Int, durationMs: Long) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, px: Int, py: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px.toFloat(), py.toFloat(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) { "Own-overlay pointer event rejected" } }
            finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..10) {
            SystemClock.sleep(durationMs / 10)
            send(MotionEvent.ACTION_MOVE, x + (toX - x) * step / 10, y + (toY - y) * step / 10)
        }
        send(MotionEvent.ACTION_UP, toX, toY)
    }

    private fun ownControlBounds(text: String, deadline: Long = SystemClock.uptimeMillis() + 15_000): Rect {
        var result: Rect? = null
        awaitOwnControl(text, overlayOnly = true, deadline = deadline) { node ->
            result = Rect().also(node::getBoundsInScreen); true
        }
        return requireNotNull(result)
    }

    private fun awaitOwnControl(text: String, overlayOnly: Boolean,
        deadline: Long = SystemClock.uptimeMillis() + 15_000, action: (AccessibilityNodeInfo) -> Boolean) {
        while (SystemClock.uptimeMillis() < deadline) {
            val windows = automation.windows
            for (window in windows) try {
                if (overlayOnly && window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val root = window.root ?: continue
                try {
                    // Read labels only inside our app. Never scrape target-app nodes or text.
                    if (root.packageName?.toString() != instrumentation.targetContext.packageName) continue
                    // A search field can contain the same text as a result row. A setup
                    // button/row action must never resolve to an editable field.
                    val node = find(root) { !it.isEditable && it.isEnabled && it.isVisibleToUser &&
                        it.text?.toString() == text && hasClickableControl(it) } ?: continue
                    try { check(action(node)) { "Own control rejected action: $text" }; return }
                    finally { node.recycle() }
                } finally { root.recycle() }
            } finally { window.recycle() }
            SystemClock.sleep(100)
        }
        error("Own control not visible: $text (overlayOnly=$overlayOnly)")
    }

    fun enableService(openSettings: Boolean = true) {
        if (ClickPlayback.state.value.connected) return
        val setupLog = File(instrumentation.targetContext.filesDir, "runtime-smoke/accessibility-setup.txt")
        fun log(message: String) { setupLog.appendText("${SystemClock.uptimeMillis()} $message\n") }
        // Run 38067672180 needed 17.9 seconds just to draw Settings, then 6.9 for its subpage.
        // This is environment preparation before any production click run is armed.
        val deadline = SystemClock.uptimeMillis() + 120_000
        log("open_settings_requested; overall_setup_budget_ms=120000")
        if (openSettings) instrumentation.targetContext.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var selectedService = false
        var toggleRequested = false
        var confirmationSent = false
        var previousPackage: String? = null
        var loggedAnrWait = false
        while (SystemClock.uptimeMillis() < deadline && !ClickPlayback.state.value.connected) {
            val root = automation.rootInActiveWindow
            if (root != null) try {
                val currentPackage = root.packageName?.toString()
                if (currentPackage != previousPackage) { log("active_window_package=$currentPackage"); previousPackage = currentPackage }
                if (!waitForSystemUiOnce(root) && currentPackage == SETTINGS_PACKAGE) {
                    val permissionTitle = find(root) { it.isVisibleToUser &&
                        it.viewIdResourceName == "android:id/accessibility_permissionDialog_title" }
                    if (permissionTitle != null) try {
                        check(selectedService && toggleRequested && permissionTitle.text?.toString() ==
                            "Allow $serviceLabel to have full control of your device?") { "Unexpected accessibility permission dialog" }
                        if (!confirmationSent && clickId(root, "android:id/accessibility_permission_enable_allow_button")) {
                            confirmationSent = true; log("normal_service_confirmation_click_accepted")
                        }
                    } finally { permissionTitle.recycle() }
                    else if (!selectedService) {
                        if (clickText(root, serviceLabel)) {
                            selectedService = true; log("service_entry_click_accepted")
                        } else clickText(root, "Downloaded apps", "Installed apps", "已下载的应用", "已安装的应用")
                    } else if (!toggleRequested) {
                        val switch = findServiceMainSwitch(root)
                        if (switch != null) try {
                            check(!switch.isChecked) { "Disposable service was already enabled unexpectedly" }
                            check(click(switch)) { "Accessibility switch did not accept normal UI action" }
                            toggleRequested = true; log("identified_service_main_switch_click_accepted")
                        } finally { switch.recycle() }
                    }
                }
                if (systemUiAnrWaitedOnce && !loggedAnrWait) { loggedAnrWait = true; log("single_system_ui_anr_wait_accepted") }
            } finally { root.recycle() }
            SystemClock.sleep(200)
        }
        log("setup_finished; service_connected=${ClickPlayback.state.value.connected}; selected=$selectedService; toggleRequested=$toggleRequested; confirmationSent=$confirmationSent")
        check(ClickPlayback.state.value.connected) { "Accessibility UI did not enable the service; no bypass attempted" }
    }

    /** The shortcut switch is not the service switch. Bind to the labelled main-switch group. */
    private fun findServiceMainSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val group = find(root) { it.isVisibleToUser && it.viewIdResourceName == "$SETTINGS_PACKAGE:id/main_switch_bar" }
            ?: return null
        return try {
            val label = visibleText(group, "$SETTINGS_PACKAGE:id/switch_text")
            if (label != "Use $serviceLabel") return null
            // Android15 makes the Switch non-clickable; its main-switch parent owns the action.
            find(group) { it.viewIdResourceName == "android:id/switch_widget" &&
                it.isVisibleToUser && it.isEnabled && it.isCheckable && hasClickableControl(it) }
        } finally { group.recycle() }
    }

    /** One normal Wait action for the exact system startup dialog observed in run 38066025600. */
    private fun waitForSystemUiOnce(root: AccessibilityNodeInfo): Boolean {
        if (root.packageName?.toString() == "com.android.settings") {
            if (systemUiAnrWaitedOnce) observedSettingsAfterWait = true
            return false
        }
        if (root.packageName?.toString() != "android") return false
        val title = find(root) { it.isVisibleToUser && it.viewIdResourceName == "android:id/alertTitle" && it.text?.toString() == "System UI isn't responding" }
            ?: return false
        title.recycle()
        if (systemUiAnrWaitAttempted) {
            check(!observedSettingsAfterWait) { "System UI ANR recurred after the only allowed Wait action" }
            return true // The old dialog may remain briefly while the normal Wait action is handled.
        }
        File(instrumentation.targetContext.filesDir, "runtime-smoke/system-ui-anr-before-wait.txt").writeText(snapshotTree(root))
        val button = find(root) { it.viewIdResourceName == "android:id/aerr_wait" && it.text?.toString() == "Wait" && it.isVisibleToUser && it.isEnabled && it.isClickable }
            ?: error("System UI ANR has no normal Wait control")
        systemUiAnrWaitAttempted = true
        try { check(click(button)) { "System UI ANR Wait action was rejected" } }
        finally { button.recycle() }
        systemUiAnrWaitedOnce = true
        return true
    }

    /** Bound to the app's confirmation screen and the OS permission UI, never the target app. */
    fun approveFreshCapture() {
        val deadline = SystemClock.uptimeMillis() + 35_000
        var checked = false
        var checkRequested = false
        var ownConsentObserved = false
        var submitted = false
        var notificationSubmitted = false
        var systemApproved = false
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) try {
                val pkg = root.packageName?.toString().orEmpty()
                when {
                    pkg == instrumentation.targetContext.packageName && !submitted -> {
                        if (!ownConsentObserved) {
                            val title = find(root) { it.isVisibleToUser && it.text?.toString() == "确认起始页并播放一次" }
                            ownConsentObserved = title != null
                            title?.recycle()
                        } else if (!checked) {
                            val box = find(root) { it.isEnabled && it.isVisibleToUser && it.isCheckable && it.className?.toString()?.contains("CheckBox") == true }
                            if (box != null) try {
                                // Observe the changed state in a fresh tree; action acceptance
                                // alone must not advance or repeat the checkbox toggle.
                                checked = box.isChecked
                                if (!checked && !checkRequested) checkRequested = click(box)
                            } finally { box.recycle() }
                            if (box == null && !checkRequested) scroll(root)
                        } else {
                            submitted = clickText(root, "确认并授权录屏")
                            if (!submitted) scroll(root)
                        }
                    }
                    pkg == PERMISSION_PACKAGE && submitted && !notificationSubmitted -> {
                        val message = visibleText(root, "$PERMISSION_PACKAGE:id/permission_message")
                        if (message != null) {
                            check(message == "Allow $appLabel to send you notifications?") { "Unexpected runtime permission prompt" }
                            notificationSubmitted = clickId(root, "$PERMISSION_PACKAGE:id/permission_allow_button")
                        }
                    }
                    pkg == SYSTEM_UI_PACKAGE && submitted && !systemApproved -> {
                        systemApproved = approveProjectionDialog(root)
                    }
                    pkg == TARGET_PACKAGE && systemApproved -> return
                }
            } finally { root.recycle() }
            SystemClock.sleep(100)
        }
        error("Fresh MediaProjection consent UI did not complete; no grant reuse or privileged workaround attempted")
    }

    /** Two exact Android15 layouts; neither branch changes the requested capture scope. */
    private fun approveProjectionDialog(root: AccessibilityNodeInfo): Boolean {
        check(root.packageName?.toString() == SYSTEM_UI_PACKAGE)
        val expectedTitle = "Start recording or casting with $appLabel?"
        val title = visibleText(root, "$SYSTEM_UI_PACKAGE:id/screen_share_dialog_title")
        val layout: String
        if (title != null) {
            check(title == expectedTitle) { "Unexpected MediaProjection dialog identity" }
            val spinner = find(root) { it.isVisibleToUser &&
                it.viewIdResourceName == "$SYSTEM_UI_PACKAGE:id/screen_share_mode_spinner" } ?: return false
            try {
                // Inspect only this known spinner, never target-app node content.
                val mode = find(spinner) { it.isVisibleToUser && it.text?.toString() == "Entire screen" }
                check(mode != null) { "MediaProjection is not set to the complete display" }
                mode.recycle()
            } finally { spinner.recycle() }
            layout = "api35_selected_entire_screen"
        } else {
            // With partial-screen-sharing disabled, Android15's legacy positive action
            // grants ENTIRE_SCREEN directly. Identify its full warning, never any button1.
            val legacyTitle = visibleText(root, "android:id/alertTitle") ?: return false
            val expectedWarning = "$appLabel will have access to all of the information that is visible on your screen or played from your device while recording or casting. This includes information such as passwords, payment details, photos, messages, and audio that you play."
            check(legacyTitle == expectedTitle && visibleText(root, "android:id/message") == expectedWarning) {
                "Unexpected legacy MediaProjection dialog identity or scope warning"
            }
            layout = "api35_legacy_entire_screen"
        }
        check(instrumentation.targetContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) { "Notification permission did not become granted" }
        val accepted = clickId(root, "android:id/button1")
        if (accepted) File(instrumentation.targetContext.filesDir, "runtime-smoke/capture-setup.txt")
            .appendText("${SystemClock.uptimeMillis()} $layout; normal_positive_button_accepted\n")
        return accepted
    }

    fun snapshotTree(): String {
        val root = automation.rootInActiveWindow ?: return "No active accessibility root"
        return try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == instrumentation.targetContext.packageName || pkg in setOf("android", SETTINGS_PACKAGE, SYSTEM_UI_PACKAGE, PERMISSION_PACKAGE)) snapshotTree(root)
            else "Active root omitted outside the synthetic setup allowlist: $pkg"
        } finally { root.recycle() }
    }

    private fun snapshotTree(root: AccessibilityNodeInfo): String {
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || lines.size >= 500) return
            lines += "${node.packageName} ${node.className} text=${node.text} id=${node.viewIdResourceName} checked=${node.isChecked} enabled=${node.isEnabled}"
            for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        visit(root, 0)
        return lines.joinToString("\n")
    }

    private fun clickText(root: AccessibilityNodeInfo, vararg texts: String): Boolean {
        val node = find(root) { !it.isEditable && it.isEnabled && it.isVisibleToUser &&
            it.text?.toString() in texts && hasClickableControl(it) } ?: return false
        return try { click(node) } finally { node.recycle() }
    }

    private fun clickId(root: AccessibilityNodeInfo, id: String): Boolean {
        val node = find(root) { it.viewIdResourceName == id && it.isEnabled && it.isVisibleToUser &&
            !it.isEditable && hasClickableControl(it) } ?: return false
        return try { click(node) } finally { node.recycle() }
    }

    private fun visibleText(root: AccessibilityNodeInfo, id: String): String? {
        val node = find(root) { it.viewIdResourceName == id && it.isVisibleToUser } ?: return null
        return try { node.text?.toString() } finally { node.recycle() }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.isEnabled) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var current = node.parent
        repeat(5) {
            val parent = current ?: return false
            try {
                if (parent.isClickable && parent.isEnabled) return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current = parent.parent
            } finally { parent.recycle() }
        }
        current?.recycle()
        return false
    }

    /** A label is not readiness proof when its owning button is disabled or absent. */
    private fun hasClickableControl(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return node.isEnabled && node.isVisibleToUser
        var current = node.parent
        repeat(5) {
            val parent = current ?: return false
            try {
                if (parent.isClickable) return parent.isEnabled && parent.isVisibleToUser
                current = parent.parent
            } finally { parent.recycle() }
        }
        current?.recycle()
        return false
    }

    private fun scroll(root: AccessibilityNodeInfo): Boolean {
        val node = find(root) { it.isScrollable && it.isVisibleToUser } ?: return false
        return try { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } finally { node.recycle() }
    }

    private fun find(root: AccessibilityNodeInfo, depth: Int = 0, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (predicate(root)) return AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) root.getChild(i)?.let { child ->
            try { find(child, depth + 1, predicate)?.let { return it } } finally { child.recycle() }
        }
        return null
    }

    companion object {
        const val TARGET_PACKAGE = "com.tapscene.runtime.target"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val PERMISSION_PACKAGE = "com.android.permissioncontroller"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
