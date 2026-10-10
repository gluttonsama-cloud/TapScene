package com.tapscene.clickplan

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tapscene.ui.shell.TapSceneTheme

/** Separate temporary task: finishing returns to the target, never clicks an authorization prompt. */
class ClickConsentActivity : ComponentActivity() {
    private var nonce: String? = null
    private var message by mutableStateOf<String?>(null)
    private var awaiting by mutableStateOf(false)
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        awaiting = false
        val id = nonce
        if (id != null && result.resultCode == Activity.RESULT_OK && result.data != null) {
            ClickPlayback.confirmed(id, result.resultCode, result.data)
        } else if (id != null) ClickPlayback.cancelConsent(id)
        finishAndRemoveTask()
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        awaiting = false
        if (granted) startConsent() else message = "点击链需要可见的停止通知。请允许通知后再开始。"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonce = if (intent.getBooleanExtra("resume", false)) ClickPlayback.resumeRequest()?.nonce else intent.getStringExtra("request")
        val request = ClickPlayback.request(nonce)
        if (request == null) { finishAndRemoveTask(); return }
        setContent {
            var checked by rememberSaveable { mutableStateOf(false) }
            TapSceneTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(if (request.resume) "核对当前页后继续" else "确认起始页并播放一次", style = MaterialTheme.typography.headlineSmall)
                        Text("目标：${request.plan.targetPackage}\n${request.plan.actions.size} 个短按 · 固定 ${request.plan.width} × ${request.plan.height} · 修订 ${request.plan.revision}")
                        Text("定位点和面板已关闭。请确认目标 App 处于全屏、方向未变，当前页面与计划一致。")
                        Text("只编排普通演示操作。系统授权、服务设置、支付、登录验证等页面应手动操作；同一 App 内的敏感页面无法全部识别。系统回调只表示手势完成，不证明业务成功。")
                        Text(if (request.resume) "已派发的短按可能完成。核对后仅继续后面的点；返回目标 App 才会继续。"
                            else "下一步由你授权完整默认屏幕录制。可见输入会进入原片；点位保持隐藏，停止入口保留在通知中。")
                        Row { Checkbox(checked, { checked = it }, enabled = !awaiting)
                            Text("我已确认页面、点位顺序与普通演示用途，画面中已无定位点或面板。", Modifier.padding(top = 10.dp)) }
                        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = {
                            if (request.resume) {
                                nonce?.let { ClickPlayback.confirmed(it) }; finishAndRemoveTask()
                            } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                awaiting = true; notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else startConsent()
                        }, enabled = checked && !awaiting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (awaiting) "等待系统授权" else if (request.resume) "确认当前页，继续剩余点" else "确认并授权录屏")
                        }
                        OutlinedButton(onClick = ::cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消") }
                        Text("本机保存真实 MP4、完整点击链和执行日志。精确帧对应关系尚未知，录完仍可手动取帧校正。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            BackHandler { cancel() }
        }
    }
    private fun startConsent() {
        val manager = requireNotNull(getSystemService(NotificationManager::class.java))
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel("tapscene_recording")?.importance == NotificationManager.IMPORTANCE_NONE) {
            message = "停止通知被关闭。请在系统设置中允许 TapScene 的录制通知后重试。"; return
        }
        try {
            val projection = requireNotNull(getSystemService(MediaProjectionManager::class.java))
            awaiting = true
            capture.launch(if (Build.VERSION.SDK_INT >= 34) projection.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else projection.createScreenCaptureIntent())
        } catch (_: Exception) { awaiting = false; message = "无法打开本次录屏授权，请取消后重试。" }
    }
    private fun cancel() { nonce?.let(ClickPlayback::cancelConsent); finishAndRemoveTask() }
}
