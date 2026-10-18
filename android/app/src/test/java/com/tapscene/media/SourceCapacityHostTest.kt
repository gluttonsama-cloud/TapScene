package com.tapscene.media

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.tapscene.data.HostFileSyncShadow
import com.tapscene.data.SourceDraft
import com.tapscene.data.WorkspaceStore
import com.tapscene.data.WorkspaceCapacityException
import com.tapscene.data.WorkspaceCapacityLimit
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real atomic metadata and synthetic source bytes/lengths. No video decoding or device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [HostFileSyncShadow::class])
class SourceCapacityHostTest {
    @Test fun aggregateLimitsRejectBeforeWriteAndAcceptExactBoundary() = isolated { context ->
        val mib = 1024L * 1024
        fun source(bytes: Long, duration: Long): ImportedSource {
            val id = UUID.randomUUID().toString()
            val relative = "sources/$id.mp4"
            File(context.noBackupFilesDir, relative).apply { parentFile!!.mkdirs(); writeText(id) }
            return ImportedSource(id, relative, "原录屏.mp4",
                SourceMetadata("video/mp4", bytes, "0".repeat(64), 32, 48, 0, duration))
        }
        val cases = listOf(
            Triple(listOf(source(200 * mib, 100_000_000), source(200 * mib, 100_000_000)),
                source(100 * mib + 1, 100_000_000),
                "当前项目录屏合计不能超过 500 MiB。请选择更小的录屏，或在新项目中导入。"),
            Triple(listOf(source(mib, 180_000_000), source(mib, 120_000_000)),
                source(mib, 1),
                "当前项目录屏合计不能超过 5 分钟。请选择更短的录屏，或在新项目中导入。"),
        )
        for ((existing, addition, expectedMessage) in cases) {
            val projectId = UUID.randomUUID().toString()
            val store = WorkspaceStore(context, projectId)
            existing.forEach(store::append)
            val before = store.read()
            val metadata = File(context.noBackupFilesDir, "project-media-$projectId.json")
            val beforeBytes = metadata.readBytes()
            val files = sourceBytes(context)
            val failure = runCatching { store.append(addition) }.exceptionOrNull()
            assertNotNull("Over-limit registration must fail", failure)
            assertTrue(failure is WorkspaceCapacityException)
            val expectedLimit = if (addition.metadata.byteLength > 100 * mib)
                WorkspaceCapacityLimit.TOTAL_BYTES else WorkspaceCapacityLimit.TOTAL_DURATION
            assertEquals(expectedLimit, (failure as WorkspaceCapacityException).limit)
            assertEquals(expectedMessage, sourceImportFailure(failure).message)
            assertEquals(before, store.read())
            assertArrayEquals(beforeBytes, metadata.readBytes())
            assertEquals(files, sourceBytes(context))
            assertFalse(context.noBackupFilesDir.listFiles()!!.any { it.name.startsWith(".workspace-") })
        }
        val boundary = WorkspaceStore(context, UUID.randomUUID().toString())
        val exact = listOf(source(200 * mib, 100_000_000), source(200 * mib, 100_000_000),
            source(100 * mib, 100_000_000))
        exact.forEach(boundary::append)
        assertEquals(exact.map(::SourceDraft), boundary.read())
        assertEquals(exact.last(), boundary.append(exact.last())) // idempotent retry at the exact limit
        val beforeFourth = boundary.read()
        val countFailure = runCatching { boundary.append(source(1, 1)) }.exceptionOrNull()
        assertTrue(countFailure is WorkspaceCapacityException)
        assertEquals(WorkspaceCapacityLimit.SOURCE_COUNT, (countFailure as WorkspaceCapacityException).limit)
        assertEquals(beforeFourth, boundary.read())
        HostFileSyncShadow.assertDirectorySyncEvidence()
    }

    @Test fun capacityMappingIsClosedAndUnknownFailuresStayPrivate() {
        for (limit in WorkspaceCapacityLimit.entries) {
            val cause = WorkspaceCapacityException(limit)
            val mapped = sourceImportFailure(cause)
            assertEquals(limit.userMessage, mapped.message)
            assertSame(cause, mapped.cause)
        }
        for (cause in listOf(IllegalArgumentException("private source name"), IOException("private path"))) {
            val mapped = sourceImportFailure(cause)
            assertEquals("无法导入录屏，请检查文件访问权限和本机空间后重试。", mapped.message)
            assertSame(cause, mapped.cause)
        }
    }

    @Test fun interruptedProviderReadCleansOnlyItsUnregisteredCopy() = isolated { context ->
        val directory = File(context.noBackupFilesDir, "sources").apply { mkdirs() }
        val existing = File(directory, "existing.mp4").apply { writeText("original bytes") }
        val before = sourceBytes(context)
        val providerFailure = IOException("private provider URI and path")
        var reads = 0
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = error("The importer must use bounded bulk reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check(length > 0)
                if (reads++ > 0) throw providerFailure
                byteArrayOf(1, 2, 3).copyInto(buffer, offset)
                return 3
            }
            override fun close() { closed = true }
        }
        val uri = Uri.parse("content://test.source-capacity/partial")
        // Synthetic provider stream, real importer copy/failure/finally; no media validation reached.
        shadowOf(context.contentResolver).registerInputStream(uri, input)
        var registered = false
        val failure = runCatching { runBlocking {
            SourceImporter(context).importSource(uri) { registered = true }
        } }.exceptionOrNull()
        assertTrue(failure is MediaImportException)
        assertEquals("无法导入录屏，请检查文件访问权限和本机空间后重试。", failure!!.message)
        // Coroutine stack-trace recovery may retain the thrown exception as an extra cause.
        assertSame(providerFailure, generateSequence(failure) { it.cause }.last())
        assertFalse(registered)
        assertEquals(2, reads)
        assertTrue(closed)
        assertEquals(before, sourceBytes(context))
        assertEquals("original bytes", existing.readText())
    }

    private fun sourceBytes(context: Context) = File(context.noBackupFilesDir, "sources").listFiles()!!
        .associate { it.name to it.readBytes().toList() }

    private fun isolated(block: (Context) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.noBackupFilesDir, "source-capacity-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        HostFileSyncShadow.begin(root)
        try { block(context) } finally {
            try { HostFileSyncShadow.reset() } finally { check(root.deleteRecursively()) }
        }
    }
}
