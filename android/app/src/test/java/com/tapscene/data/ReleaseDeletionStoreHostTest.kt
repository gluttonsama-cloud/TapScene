package com.tapscene.data

import android.app.Application
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.ByteArrayInputStream
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

/** Real synthetic PNG packages and host fsync. Android power-loss/device behavior is not modeled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ReleaseDeletionStoreHostTest.DeletionSyncShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReleaseDeletionStoreHostTest {
    @Before fun configureHost() = HostFileSyncShadow.begin(RuntimeEnvironment.getApplication().noBackupFilesDir)
    @After fun closeHostDescriptors() {
        DeletionSyncShadow.reset()
        HostFileSyncShadow.reset()
    }

    @Test fun staleBindingAndCleanupRetryNeverStartAnotherDeletion() = fixtureTest { f ->
        val before = f.hashes(f.root)
        val invalid = listOf(
            f.summary.copy(id = UUID.randomUUID().toString()),
            f.summary.copy(contentDigest = "0".repeat(64)),
            f.summary.copy(origin = "local"),
            f.summary.copy(sealedAt = f.summary.sealedAt + 1),
        )
        invalid.forEach { expected ->
            check(runCatching { f.store.deleteRelease(expected) }.isFailure)
            check(f.hashes(f.root) == before)
        }
        check(f.store.inspectDeletion(f.summary.copy(sealedAt = f.summary.sealedAt + 1)) == ReleaseDeletionState.REPLACED)
        check(f.store.retryDeletionCleanup(f.summary) == ReleaseDeletionState.PRESENT)
        check(f.hashes(f.root) == before)
        // A malformed extant object is unknown, never interpreted as a deleted version.
        val metadata = File(f.release, "summary.json")
        val bytes = metadata.readBytes()
        metadata.writeText("{")
        check(runCatching { f.store.inspectDeletion(f.summary) }.isFailure)
        metadata.writeBytes(bytes)
        check(f.store.inspectDeletion(f.summary) == ReleaseDeletionState.PRESENT)
        // Deleting a bound private version does not require its damaged media to decode again.
        File(File(f.release, "package"), f.input.scene.assets.first().path).writeText("damaged synthetic media")
        f.store.deleteRelease(f.summary)
        check(f.store.inspectDeletion(f.summary) == ReleaseDeletionState.DELETED)
        f.checkExternal()
    }

    @Test fun cancellationAfterStageSyncBeforeRenamePreservesVersionAndPrivatePlan() = fixtureTest { f ->
        val before = f.hashes(f.root)
        val gate = DeletionSyncShadow.arm(File(f.root, "staging"))
        coroutineScope {
            val deletion = launch(Dispatchers.IO) { f.store.deleteRelease(f.summary) }
            try {
                check(gate.reached.await(30, TimeUnit.SECONDS)) { "Deletion never reached the pre-commit fsync" }
                deletion.cancel()
            } finally { gate.release.countDown() }
            deletion.join()
            check(deletion.isCancelled)
        }
        check(f.store.inspectDeletion(f.summary) == ReleaseDeletionState.PRESENT)
        check(f.hashes(f.root) == before)
        check(f.store.readAiPlan(f.summary.id)!!.toBytes().contentEquals(f.input.plan.toBytes()))
        f.checkExternal()
    }

    @Test fun committedSyncFailureKeepsReceiptAndRetriesBeforeReimportWithoutPlanInheritance() = fixtureTest { f ->
        val summaryBytes = File(f.release, "summary.json").readBytes()
        val failurePath = File(f.root, "releases")
        DeletionSyncShadow.failSync(failurePath)
        check(runCatching { f.store.deleteRelease(f.summary) }.isFailure)
        check(!f.release.exists())
        val operation = f.pendingStage()
        check(File(operation, ".owner").isFile)
        check(File(operation, "deleted-release/summary.json").readBytes().contentEquals(summaryBytes))
        // The failed releases fsync did not skip any later cache/config deletion.
        check(f.privateOutputs().none { it.exists() })
        check(f.store.inspectDeletion(f.summary) == ReleaseDeletionState.CLEANUP_PENDING)
        check(runCatching { f.store.importPackage(ByteArrayInputStream(f.packageBytes)) }.isFailure)
        check(File(operation, "deleted-release/summary.json").isFile && !f.release.exists())
        DeletionSyncShadow.clearFailures()
        val imported = ReleaseStore(f.app).importPackage(ByteArrayInputStream(f.packageBytes))
        check(imported.id == f.summary.id && imported.contentDigest == f.summary.contentDigest)
        check(f.store.readAiPlan(imported.id) == null)
        check(!operation.exists() && f.privateOutputs().none { it.exists() })
        f.checkExternal()
        HostFileSyncShadow.assertProductionEvidence()
    }

    @Test fun configDirectorySyncFailureRemainsPendingUntilExplicitBoundCleanup() = fixtureTest { f ->
        DeletionSyncShadow.failSync(File(f.root, "ai-configs"))
        check(runCatching { f.store.deleteRelease(f.summary) }.isFailure)
        val operation = f.pendingStage()
        check(f.privateOutputs().none { it.exists() })
        check(f.store.inspectDeletion(f.summary) == ReleaseDeletionState.CLEANUP_PENDING)
        // A different confirmation cannot consume this committed deletion receipt.
        check(f.store.retryDeletionCleanup(f.summary.copy(sealedAt = f.summary.sealedAt + 1)) == ReleaseDeletionState.DELETED)
        check(operation.exists())
        DeletionSyncShadow.clearFailures()
        check(f.store.retryDeletionCleanup(f.summary) == ReleaseDeletionState.DELETED)
        check(!operation.exists())
        check(f.store.retryDeletionCleanup(f.summary) == ReleaseDeletionState.DELETED)
        // Historical outputs without a receipt are unknown, never authorized for deletion.
        f.privateOutputs().forEachIndexed { index, leftover ->
            leftover.writeText("UNOWNED SYNTHETIC HISTORICAL OUTPUT $index")
            val before = ViewerPackageCodec.sha256(leftover)
            check(runCatching { f.store.inspectDeletion(f.summary) }.isFailure)
            check(runCatching { f.store.retryDeletionCleanup(f.summary) }.isFailure)
            check(ViewerPackageCodec.sha256(leftover) == before)
            check(!operation.exists() && !f.release.exists())
            // Explicit test-fixture removal lets each of the three paths be checked alone.
            check(leftover.delete())
        }
        check(f.store.inspectDeletion(f.summary) == ReleaseDeletionState.DELETED)
        f.checkExternal()
    }

    @Test fun sameIdReplacementEvenWithIdenticalSummaryBlocksOldReceiptCleanup() = fixtureTest { f ->
        DeletionSyncShadow.failSync(File(f.root, "releases"))
        check(runCatching { f.store.deleteRelease(f.summary) }.isFailure)
        val operation = f.pendingStage()
        DeletionSyncShadow.clearFailures()
        // Model another same-ID object installed outside this interrupted store instance.
        check(File(operation, "deleted-release").copyRecursively(f.release))
        f.privateOutputs().forEachIndexed { index, file -> file.writeText("NEW OBJECT PRIVATE DATA $index") }
        val before = f.hashes(f.root)
        check(f.store.inspectDeletion(f.summary) == ReleaseDeletionState.REPLACED)
        check(runCatching { f.store.retryDeletionCleanup(f.summary) }.isFailure)
        check(runCatching { f.store.importPackage(ByteArrayInputStream(f.packageBytes)) }.isFailure)
        check(f.hashes(f.root) == before)
        f.checkExternal()
    }

    @Test fun cleanedStageRecoveryOnlyRemovesItsOwnTreeAndRetainsOwnerOnPartialFailure() = fixtureTest { f ->
        val releaseBefore = f.hashes(f.release)
        val outputsBefore = f.privateOutputs().associate { it.path to it.readBytes().toList() }
        val operation = File(f.root, "staging/${UUID.randomUUID()}").apply { check(mkdir()) }
        File(operation, ".owner").writeText("tapscene-release-v1:${operation.name}")
        val cleaned = File(operation, "cleaned-release").apply { check(mkdir()) }
        // The stage's payload may already be partially deleted; it must never be reinterpreted
        // as an instruction to remove a current version's private files.
        File(cleaned, "summary.json").writeText("partially removed")
        val escape = File(cleaned, "external-link")
        Files.createSymbolicLink(escape.toPath(), f.external.first().toPath())
        check(f.store.listReleases().single() == f.summary)
        check(File(operation, ".owner").isFile)
        check(f.hashes(f.release) == releaseBefore)
        check(f.privateOutputs().all { outputsBefore[it.path] == it.readBytes().toList() })
        f.checkExternal()
        Files.delete(escape.toPath())
        check(ReleaseStore(f.app).listReleases().single() == f.summary)
        check(!operation.exists())
        check(f.hashes(f.release) == releaseBefore)
        check(f.privateOutputs().all { outputsBefore[it.path] == it.readBytes().toList() })
        f.checkExternal()
        // Validate the operation itself before probing any child through an intermediate link.
        val outside = File(f.app.noBackupFilesDir, "outside-stage").apply { check(mkdir()) }
        val sentinel = File(outside, "keep.txt").apply { writeText("SYNTHETIC EXTERNAL STAGE") }
        val redirected = File(f.root, "staging/${UUID.randomUUID()}")
        Files.createSymbolicLink(redirected.toPath(), outside.toPath())
        try {
            check(runCatching { f.store.inspectDeletion(f.summary) }.isFailure)
            check(runCatching { f.store.listReleases() }.isFailure)
            check(sentinel.readText() == "SYNTHETIC EXTERNAL STAGE")
            check(outside.list()!!.toSet() == setOf("keep.txt"))
            check(f.hashes(f.release) == releaseBefore)
        } finally { Files.delete(redirected.toPath()) }
    }

    private fun fixtureTest(block: suspend (Fixture) -> Unit) = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            AiDraftImportFixtures.isolated(RuntimeEnvironment.getApplication(), "release-deletion") { app ->
                val input = AiDraftImportFixtures.complete(app)
                val zip = File(app.noBackupFilesDir, "original-viewer.tapscene")
                ViewerPackageCodec.writePackage(input.scene, input.assetRoot, zip, {}, null)
                val bytes = zip.readBytes()
                val store = ReleaseStore(app)
                val summary = store.importPackage(ByteArrayInputStream(bytes))
                store.exportRelease(summary.id)
                store.exportAiRelease(summary.id, input.plan)
                // Copies outside release-store stand in for a user's previously saved packages.
                val external = listOf(zip, File(app.noBackupFilesDir, "saved-animation.tapscene-ai"))
                File(app.noBackupFilesDir, "release-store/exports/${summary.id}.tapscene-ai")
                    .copyTo(external.last())
                block(Fixture(app, store, input, summary, bytes, external))
            }
        }
    }

    private class Fixture(val app: Context, val store: ReleaseStore,
        val input: AiDraftImportFixtures.Fixture, val summary: ReleaseSummary,
        val packageBytes: ByteArray, val external: List<File>) {
        val root = File(app.noBackupFilesDir, "release-store")
        val release = File(root, "releases/${summary.id}")
        private val externalHashes = external.associate { it.path to ViewerPackageCodec.sha256(it) }
        fun privateOutputs() = listOf(File(root, "exports/${summary.id}.tapscene"),
            File(root, "exports/${summary.id}.tapscene-ai"), File(root, "ai-configs/${summary.id}.json"))
        fun pendingStage() = File(root, "staging").listFiles()!!.single {
            File(it, "deleted-release").isDirectory
        }
        fun hashes(file: File) = file.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(file).invariantSeparatorsPath to ViewerPackageCodec.sha256(it)
        }
        fun checkExternal() { check(external.all { ViewerPackageCodec.sha256(it) == externalHashes[it.path] }) }
    }

    class SyncGate(val path: String) {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    /** Real fsync delegation except explicit EIO faults, with no production test hooks. */
    @Implements(Os::class)
    class DeletionSyncShadow {
        companion object {
            private val paths = Collections.synchronizedMap(IdentityHashMap<FileDescriptor, String>())
            private val failed = Collections.synchronizedSet(mutableSetOf<String>())
            private val pending = AtomicReference<SyncGate?>()
            fun arm(path: File) = SyncGate(path.canonicalPath).also { check(pending.compareAndSet(null, it)) }
            fun failSync(path: File) { failed += path.canonicalPath }
            fun clearFailures() { failed.clear() }
            fun reset() {
                pending.getAndSet(null)?.release?.countDown()
                failed.clear()
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
                if (failed.contains(path)) throw ErrnoException("synthetic deletion fsync", OsConstants.EIO)
                delegate("fsync", arrayOf(FileDescriptor::class.java), fd)
                val gate = pending.get() ?: return
                if (gate.path != path || !pending.compareAndSet(gate, null)) return
                gate.reached.countDown()
                check(gate.release.await(30, TimeUnit.SECONDS)) { "Deletion fsync gate was not released" }
            }

            private fun delegate(name: String, types: Array<Class<*>>, vararg arguments: Any): Any? = try {
                HostFileSyncShadow::class.java.getDeclaredMethod(name, *types)
                    .apply { isAccessible = true }.invoke(null, *arguments)
            } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
}
