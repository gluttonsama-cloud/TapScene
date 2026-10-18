package com.tapscene.ui.shell

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.tapscene.MainActivity
import com.tapscene.data.AiDraftImportFixtures
import com.tapscene.data.AiDraftImportStore
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.HostSqlite
import com.tapscene.data.ProjectStore
import com.tapscene.data.ReleaseStore
import com.tapscene.data.ReleaseSummary
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import com.tapscene.sharing.OfflineShare
import com.tapscene.ui.ReleaseUiState
import com.tapscene.ui.ReleaseWorkspace
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real MainActivity/Compose/Store with synthetic PNG packages. System picker/recording/device NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-xhdpi", shadows = [HostFileSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OfflineImportPlaybackHostTest {
    @get:Rule(order = 0) val database = object : ExternalResource() {
        override fun before() {
            resetApplicationViewModelFactory()
            HostSqlite.configure()
            // This is Robolectric's test-owned sandbox, never a user's installed library.
            val root = RuntimeEnvironment.getApplication().noBackupFilesDir.canonicalFile
            val sandbox = RuntimeEnvironment.getTempDirectory().basePath.toRealPath()
            check(root.toPath().startsWith(sandbox) && root.toPath() != sandbox)
            root.listFiles().orEmpty().forEach { child ->
                check(child.canonicalFile.toPath().startsWith(root.toPath()))
                check(child.deleteRecursively())
            }
            HostFileSyncShadow.begin(root)
        }

        override fun after() {
            try { HostFileSyncShadow.reset() } finally { resetApplicationViewModelFactory() }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun repeatedOlderSameTitleImportOpensExactVersionAndExitBackAndReadFailureReturnToLibrary() {
        val releases = workspace()
        val app = RuntimeEnvironment.getApplication()
        val store = ReleaseStore(app)
        val older = packageFixture(app, "older", Color.RED)
        val newer = packageFixture(app, "newer", Color.BLUE)
        openImportFromHome(releases)
        importPackage(releases, older.file)
        val oldSummary = releases.state.value.releases.single()
        val supersededOpen = openAction()
        // Ensure B really sorts ahead of A, without changing either persisted timestamp.
        compose.waitUntil(30_000) { System.currentTimeMillis() > oldSummary.sealedAt }
        importPackage(releases, newer.file)
        val before = library(store)
        assertEquals(newer.scene.releaseId, before.first().id)
        assertTrue(before.first().sealedAt > oldSummary.sealedAt)
        assertEquals(oldSummary.title, before.first().title)
        assertNotEquals(oldSummary.id, before.first().id)
        compose.runOnIdle { supersededOpen() }
        compose.onNodeWithText("导入离线演示").assertIsDisplayed()
        assertNull(releases.state.value.player) // A callback for A cannot open after B replaces the result.

        importPackage(releases, older.file)
        assertEquals(before, library(store)) // Count, origin, byte length, timestamp and digest stay fixed.
        assertEquals(older.scene.releaseId, releases.state.value.lastImportedId)
        assertNull(releases.state.value.player) // Successful import must never autoplay.
        compose.onNodeWithText("版本 ${older.scene.releaseId.take(12)}").assertIsDisplayed()
        val lateOpen = openAction()
        compose.onNodeWithText("立即观看").performScrollTo().performClick()
        assertPlayer(releases, older)
        val displayed = releases.state.value.player
        compose.runOnIdle { lateOpen() }
        awaitIdle(releases)
        assertSame(displayed, releases.state.value.player) // A duplicate/late import callback cannot reopen.
        compose.onNodeWithText("退出").performClick()
        assertLibrary(releases)
        compose.runOnIdle { lateOpen() }
        assertLibrary(releases)

        openImportFromLibrary()
        compose.onNodeWithText("选择 TapScene 观看包").assertIsDisplayed()
        compose.onNodeWithText("立即观看").assertDoesNotExist()
        importPackage(releases, older.file)
        compose.onNodeWithText("立即观看").performScrollTo().performClick()
        assertPlayer(releases, older)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertLibrary(releases)

        // The result can be valid when shown but fail a later real read. Only this fixture's
        // installed PNG is temporarily moved; restoring it also verifies no release was deleted.
        openImportFromLibrary()
        importPackage(releases, older.file)
        val asset = runBlocking(Dispatchers.IO) { store.releaseAssetFile(older.scene.releaseId, older.scene.startStateId) }
        val parked = File(asset.parentFile, "${asset.name}.host-test-held")
        val assetHash = ViewerPackageCodec.sha256(asset)
        check(!parked.exists() && asset.renameTo(parked))
        try {
            compose.onNodeWithText("立即观看").performScrollTo().performClick()
            awaitIdle(releases)
            compose.onNodeWithText("尚未载入演示").assertIsDisplayed()
            assertNull(releases.state.value.player)
            assertNull(releases.state.value.playerBitmap)
            assertTrue(releases.state.value.message?.isNotBlank() == true)
            compose.onNodeWithText("返回演示库").performClick()
            assertLibrary(releases)
        } finally {
            check(parked.renameTo(asset))
        }
        assertEquals(assetHash, ViewerPackageCodec.sha256(asset))
        assertEquals(before, library(store))
        println("HOST_OFFLINE_IMPORT exact-id/no-autoplay/duplicate/exit/back/read-failure PASS; system-picker/recording/device NOT_RUN")
    }

    @Test fun repeatedLocalPackageRetainsProvenanceAndBackCancelsBlockedPlayerRead() {
        val releases = workspace()
        val app = RuntimeEnvironment.getApplication()
        val store = ReleaseStore(app)
        val (sealed, file) = runBlocking(Dispatchers.IO) {
            val fixture = AiDraftImportFixtures.complete(app)
            val imports = AiDraftImportStore(app)
            val prepared = fixture.zip.inputStream().use { imports.prepare(it) }
            val projectId = checkNotNull(imports.commit(prepared.sessionId, prepared.previewDigest).projectId)
            // Synthetic review preparation exercises real sealing, not human privacy approval.
            AiDraftImportFixtures.reviewDraftRegions(ProjectStore(app), projectId)
            val result = AiDraftImportFixtures.sealFixture(app, projectId, fixture)
            result to store.exportRelease(result.id)
        }
        assertEquals("local", sealed.origin)
        val before = library(store)
        val packageHash = ViewerPackageCodec.sha256(file)
        openImportFromHome(releases)
        repeat(2) {
            importPackage(releases, file)
            assertEquals(sealed.id, releases.state.value.lastImportedId)
            assertEquals(sealed, releases.state.value.releases.single())
            assertEquals(before, library(store))
            assertNull(releases.state.value.player)
        }

        val lateOpen = openAction()
        val lock = ReleaseStore::class.java.getDeclaredField("mutex")
            .apply { isAccessible = true }.get(null) as Mutex
        runBlocking { lock.lock() }
        try {
            compose.runOnIdle { lateOpen() }
            compose.waitForIdle()
            assertTrue(releases.state.value.busy)
            compose.onNodeWithText("尚未载入演示").assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            // Closing the shell route is immediate; cancellation's library reconciliation waits
            // for the lock, then must not resurrect the abandoned player's pending read.
            waitFor("离线观看")
            assertNull(releases.state.value.player)
        } finally {
            lock.unlock()
        }
        awaitIdle(releases)
        assertLibrary(releases)
        compose.runOnIdle { lateOpen() }
        assertLibrary(releases)
        assertNull(releases.state.value.playerBitmap)
        assertEquals(before, library(store))
        assertEquals(packageHash, ViewerPackageCodec.sha256(file))
        println("HOST_OFFLINE_IMPORT local-origin/idempotent-summary/cancelled-read PASS; system-picker/recording/device NOT_RUN")
    }

    @Test fun importBranchesOnlyExposeCurrentSuccessfulIdAndReselectionClearsSuccess() {
        val old = ReleaseSummary(id("summary-old"), "同名固定演示", "a".repeat(64), 1_000L, 1, 600L, "imported")
        val newer = old.copy(id = id("summary-new"), contentDigest = "b".repeat(64), sealedAt = 2_000L)
        val success = ReleaseUiState(releases = listOf(newer, old), lastImportedId = old.id,
            message = "完整观看包已加入演示库；文件校验不代表来源或隐私认证")
        val state = mutableStateOf(ReleaseUiState(releases = success.releases))
        val hasOpen = mutableStateOf(true)
        val opened = mutableListOf<String>()
        var chosen = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                TapSceneTheme {
                    // Keep onChoose as the third positional argument for existing callers.
                    OfflineImportContent(state.value, {}, {
                        chosen++
                        state.value = state.value.copy(lastImportedId = null, message = null)
                    }, onOpen = if (hasOpen.value) ({ opened += it }) else null)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("选择 TapScene 观看包").assertIsDisplayed()
        compose.onNodeWithText("立即观看").assertDoesNotExist()
        compose.runOnIdle { state.value = success.copy(busy = true, stage = "正在核对真实文件") }
        compose.onNodeWithText("正在导入").assertIsDisplayed()
        compose.onNodeWithText("立即观看").assertDoesNotExist()
        compose.onNodeWithText("已加入演示库").assertDoesNotExist()
        compose.runOnIdle { state.value = success.copy(lastImportedId = null, message = "合成包校验失败") }
        compose.onNodeWithText("导入未完成").assertIsDisplayed()
        compose.onNodeWithText("立即观看").assertDoesNotExist()
        compose.runOnIdle { state.value = success.copy(lastImportedId = null, message = null) }
        compose.onNodeWithText("立即观看").assertDoesNotExist()
        compose.runOnIdle { state.value = success.copy(lastImportedId = id("missing")) }
        compose.onNodeWithText("立即观看").assertDoesNotExist()
        compose.onNodeWithText("已加入演示库").assertDoesNotExist()
        assertTrue(opened.isEmpty())

        compose.runOnIdle { state.value = success }
        compose.onNodeWithText("版本 ${old.id.take(12)}").assertIsDisplayed()
        compose.onNodeWithText("版本 ${newer.id.take(12)}").assertDoesNotExist()
        compose.onNodeWithText("立即观看").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(old.id), opened)
        val pendingFile = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "synthetic-pending.tapscene")
        val pendingShare = OfflineShare(id("share"), old.id, Uri.parse("content://synthetic.test/offline"), 3_000L)
        for (blocked in listOf(success.copy(exportFile = pendingFile), success.copy(pendingShare = pendingShare),
            success.copy(shareChooserOpen = true))) {
            compose.runOnIdle { state.value = blocked }
            compose.onNodeWithText("立即观看").assertIsNotEnabled().performClick()
            assertEquals(listOf(old.id), opened)
        }
        compose.runOnIdle { state.value = success; hasOpen.value = false }
        compose.onNodeWithText("立即观看").assertDoesNotExist()
        compose.runOnIdle { hasOpen.value = true }
        compose.onNodeWithText("再导入一个包").performScrollTo().performClick()
        assertEquals(1, chosen)
        assertNull(state.value.lastImportedId)
        compose.onNodeWithText("已加入演示库").assertDoesNotExist()
        compose.onNodeWithText("立即观看").assertDoesNotExist()
        compose.onNodeWithText("选择观看包").performScrollTo().assertIsEnabled()
        assertEquals(listOf(old.id), opened)
    }

    private fun workspace(): ReleaseWorkspace {
        waitFor("悬浮编排")
        return ViewModelProvider(compose.activity)[ReleaseWorkspace::class.java].also { awaitIdle(it) }
    }

    private fun openImportFromHome(releases: ReleaseWorkspace) {
        compose.onNodeWithText("演示库").performClick()
        awaitIdle(releases)
        openImportFromLibrary()
    }

    private fun openImportFromLibrary() {
        compose.onNodeWithText("导入包").performScrollTo().assertIsEnabled().performClick()
        waitFor("导入离线演示")
    }

    private fun importPackage(releases: ReleaseWorkspace, file: File) {
        compose.runOnIdle { releases.importPackage(Uri.fromFile(file)) }
        awaitIdle(releases)
        waitFor("已加入演示库")
    }

    private fun assertPlayer(releases: ReleaseWorkspace, fixture: PackageFixture) {
        awaitIdle(releases)
        waitFor("固定版本 ${fixture.scene.releaseId.take(8)} · 离线")
        val player = checkNotNull(releases.state.value.player)
        assertEquals(fixture.scene.releaseId, player.scene.releaseId)
        assertEquals(fixture.scene.startStateId, player.currentStateId)
        assertEquals(listOf(fixture.scene.startStateId), player.history)
        assertNull(player.candidateId)
        val bitmap = checkNotNull(releases.state.value.playerBitmap)
        assertEquals(fixture.color, bitmap.getPixel(0, 0))
    }

    private fun assertLibrary(releases: ReleaseWorkspace) {
        waitFor("离线观看")
        compose.onNodeWithText("导入包").assertExists()
        compose.onNodeWithText("导入离线演示").assertDoesNotExist()
        compose.onNodeWithText("立即观看").assertDoesNotExist()
        assertNull(releases.state.value.player)
    }

    private fun openAction(): () -> Boolean = checkNotNull(compose.onNodeWithText("立即观看")
        .performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action)

    private fun awaitIdle(releases: ReleaseWorkspace) {
        compose.waitUntil(30_000) {
            compose.waitForIdle()
            !releases.state.value.busy
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun library(store: ReleaseStore): List<ReleaseSummary> = runBlocking(Dispatchers.IO) { store.listReleases() }

    private data class PackageFixture(val scene: ViewerScene, val file: File, val color: Int)

    private fun packageFixture(app: Application, name: String, color: Int): PackageFixture {
        val root = File(app.noBackupFilesDir, "offline-import-$name").also { check(it.mkdir()) }
        val assets = File(root, "assets").also { check(it.mkdir()) }
        val imageId = id("$name-image")
        val image = File(assets, "$imageId.png")
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val asset = ViewerScene.Asset(imageId, "assets/$imageId.png", "image/png", image.length(),
            ViewerPackageCodec.sha256(image), 32, 48)
        val step = ViewerScene.State(id("$name-step"), imageId, 32, 48, "合成画面 $name", "", "authored", true)
        val scene = ViewerScene(id("$name-release"), "同名固定演示", "仅供主机检查的合成画面", 1L,
            step.id, listOf(step), emptyList(), emptyList(), listOf(asset))
        val file = File(app.noBackupFilesDir, "offline-import-$name.tapscene")
        ViewerPackageCodec.writePackage(scene, root, file, {}, null)
        return PackageFixture(scene, file, color)
    }

    private fun id(name: String) = UUID.nameUUIDFromBytes("offline-import-playback-$name".toByteArray()).toString()

    private fun resetApplicationViewModelFactory() {
        // Lifecycle retains this cache across Robolectric's separate Application instances.
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance")
            .apply { isAccessible = true }.set(null, null)
    }
}
