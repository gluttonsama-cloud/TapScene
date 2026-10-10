package com.tapscene.sharing

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tapscene.packageformat.ViewerPackageCodec
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Synthetic bytes exercise transport boundaries; the native round-trip check supplies a real ZIP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class OfflineShareHostTest {
    private lateinit var provider: OfflineShareProvider

    @Before fun attachProviderForThisSandbox() {
        val app = RuntimeEnvironment.getApplication()
        val info = requireNotNull(app.packageManager.resolveContentProvider(OfflineShareStore.authority(app), PackageManager.GET_META_DATA))
        check(!info.exported && info.grantUriPermissions)
        // Each Robolectric method has a different cache directory. Match Android provider startup
        // before getUriForFile so FileProvider invalidates the previous sandbox's cached root.
        provider = OfflineShareProvider().apply { attachInfo(app, info) }
    }

    @Test fun exactReadOnlyUrisRejectTraversalTamperingAndExpiry() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, OfflineShareStore.DIRECTORY)
        val source = File(app.cacheDir, "synthetic-source.tapscene").apply { writeBytes(ByteArray(1024) { it.toByte() }) }
        val original = source.readBytes()
        val store = OfflineShareStore(app)
        val share = store.create(source, id(), "1".repeat(64), {})
        val file = File(File(root, share.token), OfflineShareStore.FILE_NAME)
        source.writeText("Mutable SAF-like source was replaced")
        provider.openFile(share.uri, "r").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { check(it.readBytes().contentEquals(original)) }
        }
        provider.query(share.uri, null, null, null, null).use { cursor ->
            check(cursor.moveToFirst())
            check(cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) == OfflineShareStore.FILE_NAME)
            check(cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)) == original.size.toLong())
        }
        check(provider.getType(share.uri) == "application/octet-stream")
        check(provider.getTypeAnonymous(share.uri) == null)
        for (mode in listOf("w", "wa", "rw", "rwt", "wt", "")) rejected { provider.openFile(share.uri, mode) }
        rejected { provider.delete(share.uri, null, null) }
        val valid = share.uri.toString()
        val invalid = listOf(
            "$valid/", "$valid?displayName=secret", "$valid#fragment", valid.replace(share.token, id()),
            valid.replace(OfflineShareStore.FILE_NAME, "metadata.json"),
            valid.replace(share.token, ".."), valid.replace(share.token, "%2e%2e"),
            valid.replace(share.token, "%252e%252e"), valid.replace(share.token, share.token + "%2f.."),
            valid.replace("offline_share/", "offline_share/../"), valid.replace("content://", "file://"),
            valid.replace(".offline-share/", ".offline-share:123/"),
            valid.replace("content://", "content://someone@"), valid.replace(".offline-share/", ".other-provider/"),
            valid.replace("offline_share/", "offline_share//"), valid.replace("offline_share", "%6fffline_share"), "$valid?",
        )
        for (text in invalid) {
            val uri = Uri.parse(text)
            rejected { provider.openFile(uri, "r") }
            rejected { provider.query(uri, null, null, null, null) }
            rejected { provider.getType(uri) }
            check(provider.getTypeAnonymous(uri) == null)
        }
        val chooser = offlineShareChooser(share)
        check(chooser.action == Intent.ACTION_CHOOSER)
        @Suppress("DEPRECATION") val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        check(send.action == Intent.ACTION_SEND && send.type == "application/octet-stream")
        @Suppress("DEPRECATION") val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        check(stream == share.uri && send.clipData?.itemCount == 1 && send.clipData?.getItemAt(0)?.uri == share.uri)
        check(send.flags == Intent.FLAG_GRANT_READ_URI_PERMISSION && send.`package` == null && send.component == null)
        check(chooser.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION) == 0)
        file.writeBytes(ByteArray(original.size) { 42 })
        rejected { provider.openFile(share.uri, "r") }
        file.writeBytes(original)
        val metadata = File(file.parentFile, OfflineShareStore.METADATA)
        val held = provider.openFile(share.uri, "r")
        expire(metadata)
        // Expiry denies new opens without any cleanup invocation.
        rejected { provider.openFile(share.uri, "r") }
        rejected { provider.query(share.uri, null, null, null, null) }
        rejected { provider.getType(share.uri) }
        check(file.exists())
        store.create(source, id(), "2".repeat(64), {})
        check(!file.exists())
        ParcelFileDescriptor.AutoCloseInputStream(held).use { check(it.readBytes().contentEquals(original)) }
        println("HOST_OFFLINE_SHARE api=${Build.VERSION.SDK_INT} exact-uri/read-only/hash/expiry/chooser PASS; cross-app grants/device chooser NOT_RUN")
    }

    @Test fun cacheLimitsAndCancelledPreparationPreserveExistingSnapshots() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.cacheDir, OfflineShareStore.DIRECTORY)
        val source = File(app.cacheDir, "synthetic-source.tapscene").apply { writeBytes(ByteArray(100_000) { 37 }) }
        val store = OfflineShareStore(app)
        val shares = (1..8).map { store.create(source, id(), "3".repeat(64), {}) }
        // Clock rollback fails reads closed, but must not evict an unexpired published entry.
        val futureMetadata = File(File(root, shares.last().token), OfflineShareStore.METADATA)
        val future = JSONObject(futureMetadata.readText())
        val tomorrow = System.currentTimeMillis() + OfflineShareStore.LIFETIME_MS
        future.put("createdAt", tomorrow).put("expiresAt", tomorrow + OfflineShareStore.LIFETIME_MS)
        futureMetadata.writeText(future.toString())
        val snapshots = root.listFiles()!!.associate { it.name to File(it, OfflineShareStore.FILE_NAME).readBytes() }
        rejected { store.create(source, id(), "3".repeat(64), {}) }
        check(root.listFiles()!!.size == 8)
        snapshots.forEach { (token, bytes) -> check(File(File(root, token), OfflineShareStore.FILE_NAME).readBytes().contentEquals(bytes)) }
        expire(File(File(root, shares.first().token), OfflineShareStore.METADATA))
        var checks = 0
        rejected { store.create(source, id(), "3".repeat(64)) { if (++checks == 2) error("Synthetic cancellation") } }
        check(root.listFiles()!!.size == 7 && root.listFiles()!!.none { it.name.startsWith(".pending-") })
        // Sparse, synthetic retained entries exercise the byte cap without a large fixture in Git.
        root.listFiles()!!.take(4).forEach { directory ->
            RandomAccessFile(File(directory, OfflineShareStore.FILE_NAME), "rw").use { it.setLength(50L * 1024 * 1024) }
        }
        rejected { store.create(source, id(), "3".repeat(64), {}) }
        check(root.listFiles()!!.size == 7)
        check(source.readBytes().all { it == 37.toByte() })
        println("HOST_OFFLINE_SHARE_LIMIT api=${Build.VERSION.SDK_INT} count/bytes/cancel PASS; retained entries not evicted")
    }

    private fun expire(file: File) {
        val metadata = JSONObject(file.readText())
        val expiry = System.currentTimeMillis() - 1_000
        metadata.put("createdAt", expiry - OfflineShareStore.LIFETIME_MS).put("expiresAt", expiry)
        file.writeText(metadata.toString())
    }
    private fun id() = UUID.randomUUID().toString()
    private fun rejected(action: () -> Any?) { check(runCatching(action).isFailure) { "Expected sharing boundary rejection" } }
}
