package com.tapscene.ui.shell

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tapscene.MainActivity
import com.tapscene.clickplan.ClickPlayback
import com.tapscene.clickplan.ClickAccessibilityService
import com.tapscene.clickplan.ClickPlanStore
import org.robolectric.Robolectric
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
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
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClickEntryNavigationHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            HostSqlite.configure()
            val app = RuntimeEnvironment.getApplication()
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
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeOpensRealPlanAndKeepsItThroughProjectCreationPickerCancelAndBack() {
        waitFor("悬浮编排")
        compose.onNodeWithText("悬浮编排").assertIsDisplayed().performClick()
        waitFor("选择目标 App")
        compose.onNodeWithText("选择目标 App").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithText("录制操作").assertDoesNotExist()
        val app = RuntimeEnvironment.getApplication()
        check(ProjectStore(app).listProjects().isEmpty())
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        capture("02-plan-first-use")

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
        compose.onNodeWithText("确认并打开").performClick()
        compose.waitUntil(30_000) { ProjectStore(app).listProjects().size == 1 }
        val created = ProjectStore(app).listProjects().single()
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
        capture("05-home-empty")
        compose.onNodeWithText("悬浮编排").performClick()
        waitFor("改用手动录屏")
        compose.onNodeWithText("改用手动录屏").assertIsEnabled().assertIsDisplayed()
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

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val output = System.getenv("TAPSCENE_UI_OUTPUT") ?: return
        val file = File(output, "$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { stream ->
            check(compose.onAllNodes(isRoot())[compose.onAllNodes(isRoot()).fetchSemanticsNodes().lastIndex].captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
