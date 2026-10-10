package com.tapscene.sharing

import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

/** FileProvider supplies URI grants/canonical resolution; this one-purpose view is read-only. */
class OfflineShareProvider : FileProvider() {
    private fun store() = OfflineShareStore(requireNotNull(context))

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("分享副本仅可读取。")
        return store().read(uri, verifyBytes = true) {
            super.openFile(uri, "r") ?: throw FileNotFoundException("分享副本无法打开。")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        store().read(uri) { super.query(uri, projection, selection, selectionArgs, sortOrder) }

    override fun getType(uri: Uri): String = store().read(uri) { "application/octet-stream" }

    // Android 14+ may ask this without a grant. Reveal neither existence nor a token oracle.
    override fun getTypeAnonymous(uri: Uri): String? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("分享副本不可删除。")
}
