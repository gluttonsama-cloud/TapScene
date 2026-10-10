package com.tapscene.sharing

import android.content.ClipData
import android.content.Intent

/** No package targeting, upload or persistent/write/prefix grant. The person chooses the recipient. */
fun offlineShareChooser(share: OfflineShare): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, share.uri)
        clipData = ClipData.newRawUri("TapScene 离线观看包", share.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "分享离线观看包")
}
