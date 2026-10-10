package com.tapscene

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tapscene.ui.shell.TapSceneApp
import com.tapscene.recording.RecordingCoordinator

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RecordingCoordinator.recover(applicationContext)
        com.tapscene.clickplan.ClickPlayback.initialize(applicationContext)
        setContent { TapSceneApp(projects = viewModel(), media = viewModel(), candidates = viewModel(), releases = viewModel()) }
    }
}
