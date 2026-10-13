package com.tapscene.ui.shell

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.provider.Settings
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.tapscene.MainActivity
import com.tapscene.clickplan.ClickPlayback
import com.tapscene.clickplan.ClickAccessibilityService
import com.tapscene.clickplan.ClickPlanStore
import org.robolectric.Robolectric
import com.tapscene.data.HostSqlite
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.ProjectStore
import com.tapscene.ui.ProjectWorkspace
import com.tapscene.clickplan.ClickAction
import com.tapscene.clickplan.ClickDevice
import com.tapscene.clickplan.ClickPlan
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real MainActivity/Compose/navigation/store. No OS overlay, gestures or recording are simulated as a pass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClickEntryNavigationHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            HostSqlite.configure()
            val app = RuntimeEnvironment.getApplication()
            // Only discard synthetic files inside this Robolectric test's own private sandbox.
            val root = app.noBackupFilesDir.canonicalFile
            val sandbox = RuntimeEnvironment.getTempDirectory().basePath.toRealPath()
            check(root.toPath().startsWith(sandbox) && root.toPath() != sandbox)
            root.listFiles().orEmpty().forEach { child ->
                check(child.canonicalFile.toPath().startsWith(root.toPath()))
                check(child.deleteRecursively())
            }
            HostFileSyncShadow.begin(app.noBackupFilesDir)
            val target = ResolveInfo().apply {
                nonLocalizedLabel = "演示样例 App"
                activityInfo = ActivityInfo().apply {
                    name = "com.example.demo.MainActivity"
                    packageName = "com.example.demo"
                    applicationInfo = ApplicationInfo().apply { packageName = "com.example.demo" }
                }
            }
            @Suppress("DEPRECATION")
            shadowOf(app.packageManager).addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), target)
        }
        override fun after() { HostFileSyncShadow.reset() }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureCapture = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching {
                compose.runOnUiThread {
                    WindowInspector.getGlobalWindowViews().forEach { root ->
                        println("HOST_WINDOW ${root.javaClass.simpleName} ${root.width}x${root.height} " +
                            "attached=${root.isAttachedToWindow} layoutRequested=${root.isLayoutRequested} focus=${root.hasWindowFocus()}")
                    }
                }
                capture("failure-${description.methodName}", awaitIdle = false)
            }.onFailure { error.addSuppressed(it) }
        }
    }

    @Test fun homeOpensRealPlanAndKeepsItThroughProjectCreationPickerCancelAndBack() {
        waitFor("悬浮编排")
        compose.onNodeWithText("悬浮编排").assertIsDisplayed().performClick()
        waitFor("选择目标 App")
        compose.onNodeWithText("选择目标 App").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithText("录制操作").assertDoesNotExist()
        compose.onNodeWithText("按顺序执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("计划总时长", substring = true).assertDoesNotExist()
        compose.onNodeWithText("开启点击服务").assertDoesNotExist()
        val app = RuntimeEnvironment.getApplication()
        check(ProjectStore(app).listProjects().isEmpty())
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        capture("02-plan-first-use")

        val activeService = Robolectric.buildService(ClickAccessibilityService::class.java).create().get()
        compose.runOnIdle {
            ClickPlayback.connected(activeService)
            ClickPlayback.overlayChanged(true) // Host-only active-session state, not an OS overlay.
        }
        compose.onNodeWithText("打开悬浮窗").assertIsNotEnabled()
        compose.onNodeWithText("停止并关闭浮层").performScrollTo().performClick()
        check(!ClickPlayback.state.value.overlayVisible)
        check(ProjectStore(app).listProjects().isEmpty())
        compose.runOnIdle { ClickPlayback.permissionLost(activeService) }

        compose.onNodeWithText("选择目标 App").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("选择目标 App").assertIsDisplayed().performClick()
        compose.onNodeWithText("演示样例 App").performClick()
        compose.onNodeWithText("开启无障碍服务").assertIsEnabled().assertIsDisplayed()
        capture("03-plan-service-needed")
        compose.onNodeWithText("开启无障碍服务").performClick()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
        assertNull(shadowOf(compose.activity).nextStartedActivity)

        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("其他创建方式")
        check(ProjectStore(app).listProjects().isEmpty()) { "Canceling preparation created an empty project" }
        compose.onNodeWithText("悬浮编排").performClick()
        waitFor("选择目标 App")
        compose.onNodeWithText("选择目标 App").performClick()
        compose.onNodeWithText("演示样例 App").performClick()
        // Host-only connected-service fixture makes the create/return route reachable.
        // It is not an Android-enabled service and never stands in for device overlay validation.
        val service = Robolectric.buildService(ClickAccessibilityService::class.java).create().get()
        compose.runOnIdle { ClickPlayback.connected(service) }
        compose.onNodeWithText("打开悬浮窗").performClick()
        compose.onNodeWithText("取消").performClick()
        check(ProjectStore(app).listProjects().isEmpty()) { "Canceling opening created an empty project" }
        compose.onNodeWithText("打开悬浮窗").performClick()
        val workspace = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val operation = ProjectWorkspace::class.java.getDeclaredField("operationLock").apply { isAccessible = true }.get(workspace) as Mutex
        val storeLock = checkNotNull(ClickPlanStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(null))
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            synchronized(storeLock) { held.countDown(); check(release.await(90, TimeUnit.SECONDS)) }
        }.apply { start() }
        check(held.await(10, TimeUnit.SECONDS))
        try {
            runBlocking { operation.lock() }
            try {
                compose.onNodeWithText("确认并打开").performClick()
                compose.onNodeWithContentDescription("返回").performClick()
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText("其他创建方式").assertDoesNotExist()
                check(ProjectStore(app).listProjects().isEmpty())
            } finally { operation.unlock() }
            compose.waitUntil(30_000) { ProjectStore(app).listProjects().size == 1 }
            waitFor("保存计划") // Nested route has mounted, but its initial plan load is blocked.
            // Keep the service connected and restore a pending open request with a fresh workspace.
            // A saved auto-open token would otherwise silently save/open after releasing the load.
            compose.runOnIdle { compose.activity.viewModelStore.clear() }
            compose.activityRule.scenario.recreate()
            val fresh = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
            check(fresh !== workspace)
            compose.waitUntil(30_000) { fresh.state.value.project?.project?.id == ProjectStore(app).listProjects().single().id }
            waitFor("保存计划")
        } finally { release.countDown(); holder.join(10_000) }
        val created = ProjectStore(app).listProjects().single()
        waitFor("打开悬浮窗")
        assertNull(ClickPlanStore(app).getPlan(created.id))
        check(!ClickPlayback.state.value.overlayVisible && !ClickPlayback.state.value.busy)
        assertEquals(created.id, ViewModelProvider(compose.activity)[ProjectWorkspace::class.java].state.value.project?.project?.id)
        compose.onNodeWithText("打开悬浮窗").performClick()
        compose.onNodeWithText("确认并打开").performClick()
        compose.waitUntil(30_000) { ClickPlanStore(app).getPlan(created.id) != null }
        compose.runOnIdle { ClickPlayback.permissionLost(service) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("其他创建方式")
        compose.onNodeWithText(created.title).performClick()
        waitFor("素材")
        compose.onNodeWithText("素材").performClick()
        compose.onNodeWithText("悬浮编排").performClick()
        waitFor("开启无障碍服务")
        assertEquals(listOf(created.id), ProjectStore(app).listProjects().map { it.id })
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("其他创建方式")
        capture("01-home-recent-project")
        compose.onNodeWithText("其他创建方式").performClick()
        compose.onNodeWithText("手动录屏").assertIsDisplayed()
        compose.onNodeWithText("导入录屏").assertIsDisplayed()
        compose.onNodeWithText("导入截图").assertIsDisplayed()
        compose.onNodeWithText("导入 AI 包").assertIsDisplayed()
        capture("04-other-create-options")
        compose.onNodeWithText("手动录屏").performClick()
        compose.onNodeWithText("录制操作").assertIsDisplayed()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        println("HOST_CLICK_ENTRY production home/creation/plan/picker-cancel/settings-intent/back/reopen/manual PASS; Android overlay/playback/recording NOT_RUN")
    }

    @Test @Config(sdk = [32]) fun unsupportedSystemShowsTheReasonAndDoesNotAskForService() {
        waitFor("悬浮编排")
        check(ProjectStore(RuntimeEnvironment.getApplication()).listProjects().isEmpty())
        capture("05-home-empty")
        compose.onNodeWithText("悬浮编排").performClick()
        waitFor("改用手动录屏")
        compose.onNodeWithText("改用手动录屏").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithText("选择目标 App").assertDoesNotExist()
        compose.onNodeWithText("按顺序执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("计划总时长", substring = true).assertDoesNotExist()
        compose.onNodeWithText("开启无障碍服务").assertDoesNotExist()
        compose.onNodeWithText("打开系统无障碍设置").assertDoesNotExist()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        capture("06-plan-unsupported")
        check(ProjectStore(RuntimeEnvironment.getApplication()).listProjects().isEmpty())
        compose.onNodeWithText("改用手动录屏").performClick()
        compose.onNodeWithText("录制操作").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        waitFor("其他创建方式")
        check(ProjectStore(RuntimeEnvironment.getApplication()).listProjects().isEmpty())
        println("HOST_CLICK_ENTRY API32 unsupported reason visible; no permission request; Android overlay NOT_RUN")
    }

    @Test @Config(sdk = [32]) fun manualFallbackPreservesDirtyPlanUntilCancelDiscardOrSaveIsChosen() {
        waitFor("悬浮编排")
        val app = RuntimeEnvironment.getApplication()
        val project = ProjectStore(app).createProject("已保存点击链")
        val geometry = ClickDevice.geometry(app)
        val saved = ClickPlan.create(project.project.id, "com.example.demo", geometry.width, geometry.height,
            geometry.rotation, listOf(ClickAction(x = geometry.width / 2, y = geometry.height / 2)))
        ClickPlanStore(app).savePlan(saved, null)
        compose.runOnIdle { ViewModelProvider(compose.activity)[ProjectWorkspace::class.java].reload() }
        waitFor("已保存点击链")
        compose.onNodeWithText("已保存点击链").performClick()
        waitFor("素材")
        compose.onNodeWithText("素材").performClick()
        compose.onNodeWithText("悬浮编排").performClick()
        waitFor("改用手动录屏")
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        compose.onNodeWithText("点击后等待 / ms").performScrollTo().performTextReplacement("900")
        compose.onNodeWithText("改用手动录屏").performClick()
        compose.onNodeWithText("保留当前修改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("尚未保存").assertIsDisplayed()
        assertEquals(saved.digest, ClickPlanStore(app).getPlan(saved.projectId)?.digest)
        compose.onNodeWithText("改用手动录屏").performClick()
        compose.onNodeWithText("放弃修改并继续").performClick()
        compose.onNodeWithText("录制操作").assertIsDisplayed()
        assertEquals(saved.digest, ClickPlanStore(app).getPlan(saved.projectId)?.digest)
        compose.onNodeWithText("悬浮编排").performScrollTo().performClick()
        waitFor("改用手动录屏")
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        compose.onNodeWithText("点击后等待 / ms").performScrollTo().performTextReplacement("1200")
        compose.onNodeWithText("改用手动录屏").performClick()
        compose.onNodeWithText("保存并返回").performClick()
        waitFor("录制操作")
        assertEquals(1200L, ClickPlanStore(app).getPlan(saved.projectId)?.actions?.single()?.waitAfterMs)
        println("HOST_CLICK_ENTRY dirty manual fallback cancel/discard/save PASS; no recording permission requested")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals(RuntimeEnvironment.getApplication().noBackupFilesDir.canonicalFile,
            compose.activity.noBackupFilesDir.canonicalFile)
    }

    private fun capture(name: String, awaitIdle: Boolean = true) {
        val output = System.getenv("TAPSCENE_UI_OUTPUT") ?: return
        if (awaitIdle) compose.waitForIdle()
        // Robolectric's native UiAutomation capture renders all attached windows, including sheets.
        // Compose captureToImage waits for an OS redraw callback that this host does not dispatch.
        // Use a separate capture renderer: borrowing the live root renderer replaces its surface
        // and can prevent later dialog/layout work from becoming idle after that surface is released.
        val property = "robolectric.useEmbeddedViewRoot"
        val previous = System.setProperty(property, "false")
        val bitmap = try {
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
        try {
            check(bitmap.width >= 320 && bitmap.height >= 640)
            val colors = mutableSetOf<Int>()
            for (y in 0 until bitmap.height step (bitmap.height / 64).coerceAtLeast(1)) {
                for (x in 0 until bitmap.width step (bitmap.width / 64).coerceAtLeast(1)) {
                    colors += bitmap.getPixel(x, y)
                }
            }
            check(colors.size > 8) { "Host capture is blank: $name" }
            val file = File(output, "$name.png").apply { checkNotNull(parentFile).mkdirs() }
            file.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
        } finally {
            bitmap.recycle()
        }
    }
}
