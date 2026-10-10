package com.tapscene.runtime

import android.app.Instrumentation
import android.app.UiAutomation
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

    fun enableService() {
        if (ClickPlayback.state.value.connected) return
        instrumentation.targetContext.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = SystemClock.uptimeMillis() + 30_000
        var selectedService = false
        var toggled = false
        while (SystemClock.uptimeMillis() < deadline && !ClickPlayback.state.value.connected) {
            val root = automation.rootInActiveWindow
            if (root != null) try {
                if (!waitForSystemUiOnce(root) && root.packageName?.toString() == "com.android.settings") when {
                    !selectedService && clickText(root, "TapScene 点击链定位与播放") -> selectedService = true
                    !selectedService -> clickText(root, "Downloaded apps", "Installed apps", "已下载的应用", "已安装的应用")
                    !toggled -> {
                        val switch = find(root) { it.isEnabled && it.isVisibleToUser && it.isCheckable && it.className?.toString()?.contains("Switch") == true }
                        if (switch != null) {
                            try {
                                if (!switch.isChecked) check(click(switch)) { "Accessibility switch did not accept normal UI action" }
                                toggled = true
                            } finally { switch.recycle() }
                        }
                    }
                    else -> clickText(root, "Allow", "允许", "OK", "确定")
                }
            } finally { root.recycle() }
            SystemClock.sleep(200)
        }
        check(ClickPlayback.state.value.connected) { "Accessibility UI did not enable the service; no bypass attempted" }
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
        var submitted = false
        var systemApproved = false
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) try {
                val pkg = root.packageName?.toString().orEmpty()
                when {
                    pkg == instrumentation.targetContext.packageName && !submitted -> {
                        if (!checked) {
                            val box = find(root) { it.isEnabled && it.isVisibleToUser && it.isCheckable && it.className?.toString()?.contains("CheckBox") == true }
                            if (box != null) try {
                                checked = box.isChecked || click(box)
                            } finally { box.recycle() }
                            if (!checked) scroll(root)
                        } else {
                            submitted = clickText(root, "确认并授权录屏")
                            if (!submitted) scroll(root)
                        }
                    }
                    pkg.endsWith("permissioncontroller") -> clickText(root, "Allow", "允许")
                    pkg == "com.android.systemui" && submitted -> {
                        systemApproved = clickText(root, "Start now", "Start recording", "Share screen", "立即开始", "开始录制") || systemApproved
                    }
                    pkg == TARGET_PACKAGE && systemApproved -> return
                }
            } finally { root.recycle() }
            SystemClock.sleep(100)
        }
        error("Fresh MediaProjection consent UI did not complete; no grant reuse or privileged workaround attempted")
    }

    fun snapshotTree(): String {
        val root = automation.rootInActiveWindow ?: return "No active accessibility root"
        return try { snapshotTree(root) } finally { root.recycle() }
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
        val node = find(root) { it.isEnabled && it.isVisibleToUser && it.text?.toString() in texts } ?: return false
        return try { click(node) } finally { node.recycle() }
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

    companion object { const val TARGET_PACKAGE = "com.tapscene.runtime.target" }
}
