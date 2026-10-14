package com.tapscene.ui.shell

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.tapscene.clickplan.ClickRun
import com.tapscene.clickplan.ClickChainReviewUiState
import com.tapscene.ui.ClickChainWorkspace
import com.tapscene.ui.MediaWorkspace
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    private var previousRequestedSize: String? = null
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
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
            // Pinned Robolectric defaults ignore WRAP_CONTENT size/gravity. Honor measured window
            // size instead of changing production picker content or weakening idle assertions.
            previousRequestedSize = System.setProperty("robolectric.windowManager.useRequestedSize", "true")
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
        override fun after() {
            try { HostFileSyncShadow.reset() } finally {
                resetApplicationViewModelFactory()
                val key = "robolectric.windowManager.useRequestedSize"
                previousRequestedSize?.let { System.setProperty(key, it) } ?: System.clearProperty(key)
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val failureDiagnostics = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching {
                compose.runOnUiThread {
                    WindowInspector.getGlobalWindowViews().forEach { root ->
                        println("HOST_WINDOW ${root.javaClass.simpleName} ${root.width}x${root.height} " +
                            "attached=${root.isAttachedToWindow} layoutRequested=${root.isLayoutRequested} focus=${root.hasWindowFocus()}")
                    }
                }
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
            compose.waitForIdle() // Attach the new Compose root before polling its restoration effect.
            val fresh = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
            check(fresh !== workspace)
            try {
                compose.waitUntil(30_000) {
                    // IO restoration posts back to Android's paused main looper, not the test clock.
                    compose.waitForIdle()
                    fresh.state.value.project?.project?.id == ProjectStore(app).listProjects().single().id
                }
            } catch (failure: Throwable) {
                println("HOST_RESTORE busy=${fresh.state.value.busy} stage=${fresh.state.value.stage} " +
                    "project=${fresh.state.value.project?.project?.id} message=${fresh.state.value.message}")
                println(compose.onRoot().printToString(maxDepth = 5))
                throw failure
            }
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
        compose.onNodeWithText("其他创建方式").performClick()
        compose.onNodeWithText("手动录屏").assertIsDisplayed()
        compose.onNodeWithText("导入录屏").assertIsDisplayed()
        compose.onNodeWithText("导入截图").assertIsDisplayed()
        compose.onNodeWithText("导入 AI 包").assertIsDisplayed()
        compose.onNodeWithText("手动录屏").performClick()
        compose.onNodeWithText("录制操作").assertIsDisplayed()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        println("HOST_CLICK_ENTRY production home/creation/plan/picker-cancel/settings-intent/back/reopen/manual PASS; Android overlay/playback/recording NOT_RUN")
    }

    @Test fun openingReviewedProjectSelectsStepsWhileOrdinaryBackKeepsSources() {
        waitFor("悬浮编排")
        val app = RuntimeEnvironment.getApplication()
        val projects = ViewModelProvider(compose.activity)[ProjectWorkspace::class.java]
        val media = ViewModelProvider(compose.activity)[MediaWorkspace::class.java]
        fun awaitWorkspaceIdle() = compose.waitUntil(30_000) {
            compose.waitForIdle(); !projects.state.value.busy && !media.state.value.busy
        }
        awaitWorkspaceIdle()
        val (source, created) = ProjectStore.withTemporary(app) { store ->
            store.createProject("原点击链项目") to store.createProject("已生成的新项目")
        }
        val geometry = ClickDevice.geometry(app)
        val plan = ClickPlan.create(source.project.id, "com.example.demo", geometry.width, geometry.height,
            geometry.rotation, listOf(ClickAction(x = geometry.width / 2, y = geometry.height / 2)))
        val store = ClickPlanStore(app)
        store.savePlan(plan, null)
        val run = ClickRun.create(plan, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, 100)
        store.beginRun(run)
        store.recoverInterruptedRuns()
        compose.runOnIdle { projects.reload() }
        for (entry in listOf("plan", "record-review", "record-plan")) {
            waitFor("原点击链项目")
            compose.onNodeWithText("原点击链项目").performClick()
            waitFor("素材")
            awaitWorkspaceIdle()
            compose.onNodeWithText("素材").performClick()
            val openEntry: () -> Unit = {
                if (entry == "plan") compose.onNodeWithText("悬浮编排").performClick()
                else {
                    compose.onNodeWithText("其他方式").performClick()
                    compose.onNodeWithText("手动录屏").performClick()
                }
                waitFor("整理已录制点击链")
            }
            openEntry()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("素材").assertIsSelected()
            openEntry()
            if (entry == "record-plan") {
                compose.onNodeWithText("悬浮编排").performScrollTo().performClick()
                waitFor("整理已录制点击链")
            }
            compose.onNodeWithText("整理已录制点击链").performScrollTo().performClick()
            waitFor("整理点击链")
            val review = ViewModelProvider(compose.activity)["click-chain-review", ClickChainWorkspace::class.java]
            compose.waitUntil(30_000) { compose.waitForIdle(); !review.state.value.loading }
            // Only the completed review state is synthetic. Exercise the real review callback,
            // nested routes, shell tab state and persisted project opening, not media generation.
            @Suppress("UNCHECKED_CAST")
            val reviewState = ClickChainWorkspace::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                .get(review) as MutableStateFlow<ClickChainReviewUiState>
            compose.runOnIdle {
                reviewState.value = ClickChainReviewUiState(runId = run.runId, editingLocked = true,
                    createdProjectId = created.project.id)
            }
            compose.onNodeWithText("打开新项目").performClick()
            waitFor("已生成的新项目")
            compose.onNodeWithText("步骤").assertIsSelected()
            compose.onNodeWithText("素材").assertIsNotSelected()
            compose.onNodeWithText("检查与交付").assertIsNotSelected()
            assertEquals(created.project.id, ViewModelProvider(compose.activity)[ProjectWorkspace::class.java].state.value.project?.project?.id)
            compose.onNodeWithText("整理点击链").assertDoesNotExist()
            awaitWorkspaceIdle()
            compose.onNodeWithContentDescription("返回").performClick()
            waitFor("其他创建方式")
        }
    }

    @Test @Config(sdk = [32]) fun unsupportedSystemShowsTheReasonAndDoesNotAskForService() {
        waitFor("悬浮编排")
        check(ProjectStore(RuntimeEnvironment.getApplication()).listProjects().isEmpty())
        compose.onNodeWithText("悬浮编排").performClick()
        waitFor("改用手动录屏")
        compose.onNodeWithText("改用手动录屏").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithText("选择目标 App").assertDoesNotExist()
        compose.onNodeWithText("按顺序执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("计划总时长", substring = true).assertDoesNotExist()
        compose.onNodeWithText("开启无障碍服务").assertDoesNotExist()
        compose.onNodeWithText("打开系统无障碍设置").assertDoesNotExist()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
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

    private fun resetApplicationViewModelFactory() {
        // Robolectric replaces Application between methods; Lifecycle 2.9.4 retains its first
        // AndroidViewModelFactory. Without resetting this host-only cache, later real activities
        // open the previous test's private database through their AndroidViewModels.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val app = RuntimeEnvironment.getApplication()
        assertEquals(app.noBackupFilesDir.canonicalFile, compose.activity.noBackupFilesDir.canonicalFile)
        assertSame("Project workspace must use this test's Application", app,
            ViewModelProvider(compose.activity)[ProjectWorkspace::class.java].getApplication<android.app.Application>())
        assertSame("Media workspace must use this test's Application", app,
            ViewModelProvider(compose.activity)[MediaWorkspace::class.java].getApplication<android.app.Application>())
    }

}
