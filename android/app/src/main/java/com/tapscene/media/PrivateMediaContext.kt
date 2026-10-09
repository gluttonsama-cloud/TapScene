package com.tapscene.media

import android.content.Context
import android.content.ContextWrapper

/**
 * FrameExtractor 1.9.4 does not expose ExoPlayer.setUsePlatformDiagnostics(false).
 * Keep its private player from creating a MediaMetrics playback session: the public Android
 * Context service boundary returns null, which Media3 explicitly supports. This changes only
 * this context, never the device's services, permissions or usage/diagnostics preference.
 */
internal class PrivateMediaContext(base: Context) : ContextWrapper(base.applicationContext) {
    override fun getApplicationContext(): Context = this

    override fun getSystemService(name: String): Any? =
        // Literal also compiles for the API-26 baseline; the service was introduced in API 31.
        if (name == "media_metrics") null else super.getSystemService(name)
}
