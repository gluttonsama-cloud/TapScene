package com.tapscene.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tapscene.data.HostedReleaseBinding
import com.tapscene.hosting.HostedModels
import com.tapscene.ui.HOSTED_LOCAL_ENDPOINT
import com.tapscene.ui.HostingPage
import com.tapscene.ui.HostingUiState

/** Only synthetic state. These are the actual hosting composables, without a network repository. */
private val hostingPreviewAccount = HostedModels.Account("layout-account", "author@example.test")
private val hostingPreviewBinding = HostedReleaseBinding(
    ReleasePreviewFixture.summary.copy(id = ReleasePreviewFixture.scene.releaseId),
    "layout-project", 3L, ReleasePreviewFixture.scene, "1".repeat(64),
)

@PreviewTest
@Preview(name = "hosting_local_login", widthDp = 412, heightDp = 915, locale = "zh-rCN", showBackground = true)
@Composable
fun HostingLocalLoginPreview() = HostingPreviewSurface(HostingUiState(
    page = HostingPage.ACCOUNT, email = "author@example.test", challengeReady = true,
    challengeExpiresAt = "2026-10-10T10:30:00Z",
))

@PreviewTest
@Preview(name = "hosting_confirm_scope", widthDp = 412, heightDp = 1200, locale = "zh-rCN", showBackground = true)
@Composable
fun HostingConfirmScopePreview() = HostingPreviewSurface(HostingUiState(
    page = HostingPage.CONFIRM, account = hostingPreviewAccount, selected = hostingPreviewBinding,
))

@PreviewTest
@Preview(name = "hosting_validating_recovery", widthDp = 360, heightDp = 1000, locale = "zh-rCN", fontScale = 1.1f, showBackground = true)
@Composable
fun HostingValidatingRecoveryPreview() = HostingPreviewSurface(HostingUiState(
    page = HostingPage.TASKS, account = hostingPreviewAccount,
    task = HostedModels.Task("layout-task", HOSTED_LOCAL_ENDPOINT, "layout-account", "layout-project",
        hostingPreviewBinding.summary.id, hostingPreviewBinding.summary.contentDigest,
        "活动报名 · 布局样例", 3L, 450_000L, 7, 3, "{}", "1".repeat(64),
        "layout-create", "layout-commit", "{}", "{}", "layout-server-project", "layout-upload",
        "validating", false, null, null),
))

@Composable
private fun HostingPreviewSurface(state: HostingUiState) {
    TapSceneTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LocalHostingContent(state, HostingCallbacks())
        }
    }
}
