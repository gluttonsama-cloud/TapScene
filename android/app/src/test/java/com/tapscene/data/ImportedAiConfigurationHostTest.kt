package com.tapscene.data

import android.app.Application
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.tapscene.packageformat.AiPackageCodec
import com.tapscene.packageformat.RenderPlan
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Synthetic PNG releases, real codec round trips and host fsync; no device/power-loss claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class,
    shadows = [ImportedAiConfigurationHostTest.ConfigurationSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportedAiConfigurationHostTest {
    @Before fun configureHost() = HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
    @After fun closeHostDescriptors() {
        ConfigurationSyncShadow.reset()
        HostFileSyncShadow.reset()
    }

    @Test fun openRetainsRawFingerprintsWithoutAdoptingOrRewritingImportedPlans() = fixtureTest { f ->
        File(f.release, "draft-plan.json").writeBytes(f.input.plan.toBytes())
        val wrongDigest = f.input.plan.toBytes().toString(Charsets.UTF_8)
            .replace(f.summary.contentDigest, "0".repeat(64)).toByteArray()
        val valid = f.input.plan.toBytes()
        val layouts = listOf(null, byteArrayOf(), "{".toByteArray(), byteArrayOf(-1), wrongDigest,
            ByteArray(RenderPlan.MAX_BYTES) { ' '.code.toByte() }, valid, JSONObject(String(valid)).toString(2).toByteArray())
        layouts.forEachIndexed { index, bytes ->
            if (bytes == null) Files.deleteIfExists(f.config.toPath()) else f.config.writeBytes(bytes)
            val before = f.hashes()
            val opened = f.store.openAiConfiguration(f.summary.id)
            check(opened.summary == f.summary && opened.scene.releaseId == f.summary.id && !opened.fromDraft)
            val snapshot = checkNotNull(opened.importedSnapshot)
            check(snapshot.byteLength == bytes?.size?.toLong())
            check(snapshot.sha256 == bytes?.let { ViewerPackageCodec.sha256(f.config) })
            check((opened.plan != null) == (index >= 6))
            opened.plan?.let { check(it.toBytes().contentEquals(valid)) }
            check(f.hashes() == before) { "Opening or offering a retained plan must not rewrite any file" }
        }
        // Local reads retain their old draft/saved-plan precedence and failure behavior.
        val metadata = File(f.release, "summary.json")
        metadata.writeText(JSONObject(metadata.readText()).put("origin", "local").toString())
        check(f.config.delete())
        val local = f.store.openAiConfiguration(f.summary.id)
        check(local.summary.origin == "local" && local.importedSnapshot == null && local.fromDraft)
        check(checkNotNull(local.plan).toBytes().contentEquals(valid))
        f.config.writeText("broken local plan")
        check(runCatching { f.store.openAiConfiguration(f.summary.id) }.isFailure)
        check(runCatching { f.store.readAiPlan(f.summary.id) }.isFailure)
    }

    @Test fun unreadableOversizedAndMalformedPathsStayUnknownAndCannotBeOverwritten() = fixtureTest { f ->
        val missing = checkNotNull(f.store.openAiConfiguration(f.summary.id).importedSnapshot)
        suspend fun rejected() {
            check(runCatching { f.store.openAiConfiguration(f.summary.id) }.isFailure)
            check(runCatching { f.store.exportImportedAiRelease(f.summary, missing, f.target) }.isFailure)
            check(runCatching { f.store.inspectImportedAiConfiguration(f.summary, missing, f.target) }.isFailure)
            check(!f.export.exists())
        }
        f.config.writeBytes(ByteArray(RenderPlan.MAX_BYTES + 1))
        val oversized = ViewerPackageCodec.sha256(f.config)
        rejected()
        check(ViewerPackageCodec.sha256(f.config) == oversized)
        check(f.config.delete() && f.config.mkdir())
        rejected()
        check(f.config.isDirectory && f.config.delete())
        val outside = File(f.app.noBackupFilesDir, "outside-config.json").apply { writeText("SYNTHETIC KEEP") }
        Files.createSymbolicLink(f.config.toPath(), outside.toPath())
        try { rejected(); check(outside.readText() == "SYNTHETIC KEEP") }
        finally { Files.delete(f.config.toPath()) }
        Files.createSymbolicLink(f.config.toPath(), File(f.app.noBackupFilesDir, "missing-target").toPath())
        try { rejected() } finally { Files.delete(f.config.toPath()) }
        f.config.writeText("SYNTHETIC UNREADABLE")
        val permissions = Files.getPosixFilePermissions(f.config.toPath())
        try {
            Files.setPosixFilePermissions(f.config.toPath(), emptySet())
            check(!Files.isReadable(f.config.toPath())) { "Unreadable-file check requires an unprivileged host" }
            rejected()
        } finally { Files.setPosixFilePermissions(f.config.toPath(), permissions) }
        check(f.config.readText() == "SYNTHETIC UNREADABLE" && f.config.delete())
        val folder = f.config.parentFile!!
        val folderPermissions = Files.getPosixFilePermissions(folder.toPath())
        try {
            Files.setPosixFilePermissions(folder.toPath(), emptySet())
            check(!Files.isReadable(folder.toPath())) { "Directory access check requires an unprivileged host" }
            rejected()
        } finally { Files.setPosixFilePermissions(folder.toPath(), folderPermissions) }
        val displaced = File(f.root, "held-ai-configs")
        check(folder.renameTo(displaced))
        try {
            // A read-only outcome query never recreates missing directories.
            check(runCatching { f.store.inspectImportedAiConfiguration(f.summary, missing, f.target) }.isFailure)
            check(!folder.exists())
            folder.writeText("SYNTHETIC DIRECTORY FAULT")
            rejected()
            check(folder.readText() == "SYNTHETIC DIRECTORY FAULT" && folder.delete())
            Files.createSymbolicLink(folder.toPath(), displaced.toPath())
            try { rejected() } finally { Files.delete(folder.toPath()) }
            check(displaced.list()!!.isEmpty())
        } finally { check(displaced.renameTo(folder)) }
    }

    @Test fun staleSummaryAndRawLayoutChangesRejectEvenEquivalentPlans() = fixtureTest { f ->
        f.config.writeBytes(f.input.plan.toBytes())
        val snapshot = checkNotNull(f.store.openAiConfiguration(f.summary.id).importedSnapshot)
        val before = f.hashes()
        listOf(f.summary.copy(id = UUID.randomUUID().toString()),
            f.summary.copy(contentDigest = "0".repeat(64)), f.summary.copy(origin = "local"),
            f.summary.copy(sealedAt = f.summary.sealedAt + 1)).forEach { stale ->
            check(runCatching { f.store.exportImportedAiRelease(stale, snapshot, f.target) }.isFailure)
            check(f.hashes() == before)
        }
        val canonical = f.input.plan.toBytes().toString(Charsets.UTF_8)
        f.config.writeText("\n$canonical")
        val spaced = checkNotNull(f.store.openAiConfiguration(f.summary.id).importedSnapshot)
        f.config.writeText(" $canonical") // Same length and semantic plan, different actual bytes.
        check(checkNotNull(f.store.openAiConfiguration(f.summary.id).plan).toBytes().contentEquals(f.input.plan.toBytes()))
        check(runCatching { f.store.exportImportedAiRelease(f.summary, spaced, f.target) }.isFailure)
        check(f.config.readText() == " $canonical" && !f.export.exists())
        check(f.store.inspectImportedAiConfiguration(f.summary, spaced, f.target) == ImportedAiConfigurationState.CHANGED_OR_REPLACED)
        // Equality cannot prove this call committed: an independently installed target also matches.
        f.config.writeBytes(f.target.toBytes())
        check(runCatching { f.store.exportImportedAiRelease(f.summary, spaced, f.target) }.isFailure)
        check(f.store.inspectImportedAiConfiguration(f.summary, spaced, f.target) == ImportedAiConfigurationState.MATCHES_TARGET)
        check(!f.export.exists())
        val metadata = File(f.release, "summary.json")
        val importedBytes = metadata.readBytes()
        metadata.writeText(JSONObject(String(importedBytes)).put("origin", "local").toString())
        val local = f.store.openAiConfiguration(f.summary.id)
        check(local.summary.origin == "local" && local.importedSnapshot == null)
        metadata.writeBytes(importedBytes)
        val replaced = f.hashes()
        check(runCatching { f.store.exportAiRelease(f.summary.id, f.target, expectedLocal = local.summary) }.isFailure)
        check(f.hashes() == replaced && !f.export.exists())
        // The same bound path remains available for an unchanged local object.
        metadata.writeText(JSONObject(String(importedBytes)).put("origin", "local").toString())
        f.store.exportAiRelease(f.summary.id, f.input.plan, expectedLocal = local.summary)
        check(f.config.readBytes().contentEquals(f.input.plan.toBytes()))
    }

    @Test fun finalCasRechecksAfterPackageSyncAndPackageFailureLeavesPreviousBytes() = fixtureTest { f ->
        f.config.writeText("SYNTHETIC ORIGINAL INVALID PLAN")
        val previous = checkNotNull(f.store.openAiConfiguration(f.summary.id).importedSnapshot)
        ConfigurationSyncShadow.failNextPackageSync()
        check(runCatching { f.store.exportImportedAiRelease(f.summary, previous, f.target) }.isFailure)
        check(!f.export.exists())
        check(f.store.inspectImportedAiConfiguration(f.summary, previous, f.target) == ImportedAiConfigurationState.PREVIOUS_UNCHANGED)
        val summaryFile = File(f.release, "summary.json")
        val summaryBytes = summaryFile.readBytes()
        val original = f.config.readBytes()
        for (replaceSummary in listOf(false, true)) {
            val snapshot = checkNotNull(f.store.openAiConfiguration(f.summary.id).importedSnapshot)
            val gate = ConfigurationSyncShadow.arm(f.export.parentFile!!)
            val failure = AtomicReference<Throwable?>()
            coroutineScope {
                val save = launch(Dispatchers.IO) {
                    try { f.store.exportImportedAiRelease(f.summary, snapshot, f.target) }
                    catch (error: Throwable) { failure.set(error) }
                }
                try {
                    check(gate.reached.await(30, TimeUnit.SECONDS))
                    check(f.export.isFile && f.config.readBytes().contentEquals(original))
                    if (replaceSummary) summaryFile.writeText(JSONObject(String(summaryBytes))
                        .put("sealedAt", f.summary.sealedAt + 1).toString())
                    else f.config.writeText("SYNTHETIC NEWER CONFIGURATION")
                } finally { gate.release.countDown() }
                save.join()
            }
            check(failure.get() is IllegalStateException)
            check(f.store.inspectImportedAiConfiguration(f.summary, snapshot, f.target) == ImportedAiConfigurationState.CHANGED_OR_REPLACED)
            if (replaceSummary) check(f.config.readBytes().contentEquals(original))
            else check(f.config.readText() == "SYNTHETIC NEWER CONFIGURATION")
            summaryFile.writeBytes(summaryBytes)
            f.config.writeBytes(original)
        }
        // Also recheck a locally opened session if the same ID is replaced during generation.
        val localOriginal = f.input.plan.toBytes()
        f.config.writeBytes(localOriginal)
        summaryFile.writeText(JSONObject(String(summaryBytes)).put("origin", "local").toString())
        val local = f.store.openAiConfiguration(f.summary.id)
        val gate = ConfigurationSyncShadow.arm(f.export.parentFile!!)
        val failure = AtomicReference<Throwable?>()
        coroutineScope {
            val save = launch(Dispatchers.IO) {
                try { f.store.exportAiRelease(f.summary.id, f.target, expectedLocal = local.summary) }
                catch (error: Throwable) { failure.set(error) }
            }
            try {
                check(gate.reached.await(30, TimeUnit.SECONDS))
                summaryFile.writeBytes(summaryBytes)
            } finally { gate.release.countDown() }
            save.join()
        }
        check(failure.get() is IllegalStateException)
        check(f.config.readBytes().contentEquals(localOriginal))
        check(f.store.listReleases().single() == f.summary)
        HostFileSyncShadow.assertProductionEvidence()
    }

    @Test fun cancellationBeforeAndAfterConfigurationCommitIsResolvedFromActualDisk() = fixtureTest { f ->
        for (afterCommit in listOf(false, true)) {
            f.config.writeText("SYNTHETIC RETAINED INVALID BYTES")
            val snapshot = checkNotNull(f.store.openAiConfiguration(f.summary.id).importedSnapshot)
            val original = f.config.readBytes()
            val gate = ConfigurationSyncShadow.arm(if (afterCommit) f.config.parentFile!! else f.export.parentFile!!)
            coroutineScope {
                val save = launch(Dispatchers.IO) { f.store.exportImportedAiRelease(f.summary, snapshot, f.target) }
                try {
                    check(gate.reached.await(30, TimeUnit.SECONDS))
                    check(f.export.isFile) { "Both gates are after verified package installation" }
                    check(f.config.readBytes().contentEquals(if (afterCommit) f.target.toBytes() else original))
                    save.cancel()
                } finally { gate.release.countDown() }
                save.join()
                check(save.isCancelled)
            }
            val beforeInspection = f.hashes()
            check(f.store.inspectImportedAiConfiguration(f.summary, snapshot, f.target) ==
                if (afterCommit) ImportedAiConfigurationState.MATCHES_TARGET else ImportedAiConfigurationState.PREVIOUS_UNCHANGED)
            check(f.hashes() == beforeInspection)
        }
        HostFileSyncShadow.assertProductionEvidence()
    }

    @Test fun explicitSaveRoundTripsAndDoesNotChangeImportedReviewSharingOrHostingRights() = fixtureTest { f ->
        f.config.writeBytes(byteArrayOf())
        val snapshot = checkNotNull(f.store.openAiConfiguration(f.summary.id).importedSnapshot)
        val releaseBefore = f.hashes(f.release)
        val exported = f.store.exportImportedAiRelease(f.summary, snapshot, f.target)
        check(exported == f.export && f.config.readBytes().contentEquals(f.target.toBytes()))
        val verified = File(f.app.noBackupFilesDir, "readback").apply { check(mkdir()) }
        val loaded = AiPackageCodec.readPackage(exported, verified, {}, null)
        check(loaded.contentDigest == f.summary.contentDigest && loaded.renderPlan.toBytes().contentEquals(f.target.toBytes()))
        check(f.store.listReleases().single() == f.summary && f.summary.origin == "imported")
        check(f.hashes(f.release) == releaseBefore && !File(f.release, "review.json").exists())
        check(runCatching { f.store.prepareOfflineShare(f.summary.id, f.summary.contentDigest) }.isFailure)
        check(runCatching { f.store.readHostedRelease(f.summary.id, f.summary.contentDigest) }.isFailure)
        val upload = File(f.app.noBackupFilesDir, "upload").apply { check(mkdir()) }
        check(runCatching { f.store.copyHostedRelease(f.summary.id, f.summary.contentDigest, upload) }.isFailure)
        check(upload.list()!!.isEmpty())
        val before = f.hashes()
        check(f.store.inspectImportedAiConfiguration(f.summary, snapshot, f.target) == ImportedAiConfigurationState.MATCHES_TARGET)
        check(f.hashes() == before)
        f.config.writeBytes(byteArrayOf())
        check(f.store.inspectImportedAiConfiguration(f.summary, snapshot, f.target) == ImportedAiConfigurationState.PREVIOUS_UNCHANGED)
        check(f.config.delete())
        check(f.store.inspectImportedAiConfiguration(f.summary, snapshot, f.target) == ImportedAiConfigurationState.CHANGED_OR_REPLACED)
        val missing = checkNotNull(f.store.openAiConfiguration(f.summary.id).importedSnapshot)
        check(missing.byteLength == null && missing.sha256 == null)
        f.store.exportImportedAiRelease(f.summary, missing, f.target)
        check(f.store.inspectImportedAiConfiguration(f.summary, missing, f.target) == ImportedAiConfigurationState.MATCHES_TARGET)
        // Existing internal callers remain compatible; origin remains imported after the legacy API.
        f.store.exportAiRelease(f.summary.id, f.input.plan)
        check(f.store.listReleases().single() == f.summary && f.hashes(f.release) == releaseBefore)
        HostFileSyncShadow.assertProductionEvidence()
    }

    private fun fixtureTest(block: suspend (Fixture) -> Unit) = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            AiDraftImportFixtures.isolated(RuntimeEnvironment.getApplication(), "imported-ai-config") { app ->
                val input = AiDraftImportFixtures.complete(app)
                val zip = File(app.noBackupFilesDir, "viewer.tapscene")
                ViewerPackageCodec.writePackage(input.scene, input.assetRoot, zip, {}, null)
                val store = ReleaseStore(app)
                val summary = zip.inputStream().use { store.importPackage(it) }
                block(Fixture(app, store, input, summary))
            }
        }
    }

    private class Fixture(val app: Context, val store: ReleaseStore,
        val input: AiDraftImportFixtures.Fixture, val summary: ReleaseSummary) {
        val root = File(app.noBackupFilesDir, "release-store")
        val release = File(root, "releases/${summary.id}")
        val config = File(root, "ai-configs/${summary.id}.json")
        val export = File(root, "exports/${summary.id}.tapscene-ai")
        val target = RenderPlan.build(input.scene, 1080, 1920, input.plan.visits, input.plan.effects)
        fun hashes(directory: File = root) = directory.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(directory).invariantSeparatorsPath to ViewerPackageCodec.sha256(it)
        }
    }

    class SyncGate(val path: String) {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    /** Delegate real descriptor operations; inject only a bounded EIO or synchronization gate. */
    @Implements(Os::class)
    class ConfigurationSyncShadow {
        companion object {
            private val paths = Collections.synchronizedMap(IdentityHashMap<FileDescriptor, String>())
            private val pending = AtomicReference<SyncGate?>()
            private val failPackage = java.util.concurrent.atomic.AtomicBoolean()
            fun arm(path: File) = SyncGate(path.canonicalPath).also { check(pending.compareAndSet(null, it)) }
            fun failNextPackageSync() { check(failPackage.compareAndSet(false, true)) }
            fun reset() {
                pending.getAndSet(null)?.release?.countDown()
                failPackage.set(false)
                paths.clear()
            }

            @JvmStatic @Implementation
            fun open(path: String, flags: Int, mode: Int): FileDescriptor {
                val fd = delegate("open", arrayOf(String::class.java, Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!), path, flags, mode) as FileDescriptor
                paths[fd] = File(path).canonicalPath
                return fd
            }
            @JvmStatic @Implementation
            fun fstat(fd: FileDescriptor): StructStat = delegate("fstat", arrayOf(FileDescriptor::class.java), fd) as StructStat
            @JvmStatic @Implementation
            fun close(fd: FileDescriptor) {
                try { delegate("close", arrayOf(FileDescriptor::class.java), fd) } finally { paths.remove(fd) }
            }
            @JvmStatic @Implementation
            fun fsync(fd: FileDescriptor) {
                val path = paths[fd]
                if (path != null && File(path).name == "animation.tapscene-ai" && failPackage.compareAndSet(true, false)) {
                    throw ErrnoException("synthetic AI package fsync", OsConstants.EIO)
                }
                delegate("fsync", arrayOf(FileDescriptor::class.java), fd)
                val gate = pending.get() ?: return
                if (gate.path != path || !pending.compareAndSet(gate, null)) return
                gate.reached.countDown()
                check(gate.release.await(30, TimeUnit.SECONDS)) { "AI configuration fsync gate was not released" }
            }
            private fun delegate(name: String, types: Array<Class<*>>, vararg arguments: Any): Any? = try {
                HostFileSyncShadow::class.java.getDeclaredMethod(name, *types)
                    .apply { isAccessible = true }.invoke(null, *arguments)
            } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
}
