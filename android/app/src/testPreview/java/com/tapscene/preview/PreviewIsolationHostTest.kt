package com.tapscene.preview

import android.content.Intent
import android.content.pm.PackageManager
import com.tapscene.BuildConfig
import com.tapscene.MainActivity
import com.tapscene.clickplan.ClickAccessibilityService
import com.tapscene.recording.RecordingService
import com.tapscene.sharing.OfflineShareStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Executes the actual preview manifest/resources and app-scoped Android context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreviewIsolationHostTest {
    @Test fun previewUsesItsOwnIdentityAuthoritiesAndExplicitServiceTargets() {
        val app = RuntimeEnvironment.getApplication()
        check(app.packageName == "com.tapscene.preview.r33")
        check(BuildConfig.APPLICATION_ID == app.packageName && !BuildConfig.HOSTED_ENABLED)
        check(BuildConfig.VERSION_CODE == 33 && BuildConfig.VERSION_NAME == "0.28.0-preview-integration-dev-preview")
        check(app.applicationInfo.loadLabel(app.packageManager).toString() == "点演预览33")
        check(OfflineShareStore.authority(app) == "com.tapscene.preview.r33.offline-share")
        val provider = requireNotNull(app.packageManager.resolveContentProvider(
            OfflineShareStore.authority(app), PackageManager.GET_META_DATA))
        check(provider.packageName == app.packageName && !provider.exported && provider.grantUriPermissions)
        for (legacy in listOf("com.tapscene", "com.tapscene.preview", "com.tapscene.preview.hevc", "com.tapscene.hosted.dev")) {
            check(app.packageManager.resolveContentProvider("$legacy.offline-share", 0) == null)
        }
        val ownData = java.io.File(app.applicationInfo.dataDir).canonicalPath + "/"
        check(app.getDatabasePath("workspace.db").canonicalPath.startsWith(ownData))
        check(app.noBackupFilesDir.canonicalPath.startsWith(ownData))
        check(app.cacheDir.canonicalPath.startsWith(ownData))
        for (type in listOf(MainActivity::class.java, RecordingService::class.java, ClickAccessibilityService::class.java)) {
            val component = requireNotNull(Intent(app, type).component)
            check(component.packageName == app.packageName && component.className == type.name)
        }
        check(app.packageManager.checkPermission("android.permission.INTERNET", app.packageName) == PackageManager.PERMISSION_DENIED)
        check(app.getString(com.tapscene.R.string.click_service_label).startsWith("点演预览33"))
        println("PREVIEW_ISOLATION actual preview context/manifest/provider/explicit components PASS; device co-install NOT_RUN")
    }
}
