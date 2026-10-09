package com.tapscene.ui

import android.net.Uri
import android.widget.VideoView
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

/** A host-owned playback attempt. Only validated derived files may enter this model. */
data class LocalVideoRun(
    val file: File?,
    val width: Int,
    val height: Int,
    val runId: Long,
    val failed: Boolean = false,
)

/**
 * No seek bar, raw-source lookup, looping, or background playback. A replay is a new host run.
 * Both the view's lifetime token and the host's run ID guard asynchronous platform callbacks.
 */
@Composable
fun LocalVideoPlayback(
    file: File,
    width: Int,
    height: Int,
    runId: Long,
    modifier: Modifier = Modifier,
    onCompleted: (Long) -> Unit,
    onError: (Long) -> Unit,
    onInterrupted: (Long) -> Unit = {},
    onReplay: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(file, runId) { VideoView(context) }
    val completed by rememberUpdatedState(onCompleted)
    val failed by rememberUpdatedState(onError)
    val interrupted by rememberUpdatedState(onInterrupted)
    var ready by remember(player) { mutableStateOf(false) }
    var playing by remember(player) { mutableStateOf(false) }
    var finished by remember(player) { mutableStateOf(false) }
    var unavailable by remember(player) { mutableStateOf(false) }

    DisposableEffect(player, owner) {
        var active = true
        var started = false
        var terminal = false
        fun stop() {
            player.keepScreenOn = false
            player.setOnPreparedListener(null)
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            runCatching { player.stopPlayback() }
        }
        fun interrupt() {
            if (!active) return
            active = false
            unavailable = true
            playing = false
            stop()
            interrupted(runId)
        }
        player.setOnPreparedListener { media ->
            if (active && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && player.hasWindowFocus() && player.isShown) {
                media.isLooping = false
                media.setVolume(0f, 0f)
                ready = true
                started = true
                playing = true
                player.keepScreenOn = true
                player.start()
            } else interrupt()
        }
        player.setOnCompletionListener {
            if (active && started && !terminal) {
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !player.hasWindowFocus() || !player.isShown) interrupt()
                else {
                    terminal = true
                    finished = true
                    playing = false
                    player.keepScreenOn = false
                    completed(runId)
                }
            }
        }
        player.setOnErrorListener { _, _, _ ->
            if (active && !terminal) {
                terminal = true
                unavailable = true
                playing = false
                stop()
                failed(runId)
            }
            true
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) interrupt()
        }
        owner.lifecycle.addObserver(observer)
        val focusObserver = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (!focused && started) interrupt()
        }
        player.viewTreeObserver.addOnWindowFocusChangeListener(focusObserver)
        try { player.setVideoURI(Uri.fromFile(file)) }
        catch (_: Exception) { terminal = true; unavailable = true; failed(runId) }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (player.viewTreeObserver.isAlive) player.viewTreeObserver.removeOnWindowFocusChangeListener(focusObserver)
            val notify = active
            active = false
            stop()
            if (notify) interrupted(runId)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            val aspect = width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1)
            val canvasWidth = minOf(maxWidth, maxHeight * aspect)
            val canvasHeight = canvasWidth / aspect
            key(player) {
                AndroidView(factory = { player }, modifier = Modifier.width(canvasWidth).height(canvasHeight))
            }
            if (unavailable) Text("播放已中断，请重新播放", color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = {
                if (playing) { player.pause(); playing = false; player.keepScreenOn = false }
                else if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && player.hasWindowFocus() && player.isShown) {
                    player.start(); playing = true; player.keepScreenOn = true
                }
            }, enabled = ready && !finished && !unavailable, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (playing) "暂停" else "继续")
            }
            TextButton(onClick = { onReplay(runId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("从头播放") }
        }
    }
}
