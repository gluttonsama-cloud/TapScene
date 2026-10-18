package com.tapscene.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.min

/** Raw time inputs belong to one manual workbench source, not to a generated output or picker. */
class ManualMediaTimeInputs private constructor(
    val projectId: String, val sourceId: String,
    requestedSeconds: Float, clipStart: String, clipEnd: String,
) {
    var requestedSeconds by mutableStateOf(requestedSeconds)
    var clipStart by mutableStateOf(clipStart)
    var clipEnd by mutableStateOf(clipEnd)

    fun matches(projectId: String?, sourceId: String) = this.projectId == projectId && this.sourceId == sourceId

    companion object {
        val Saver = listSaver<ManualMediaTimeInputs, Any>(
            save = { listOf(it.projectId, it.sourceId, it.requestedSeconds, it.clipStart, it.clipEnd) },
            restore = { ManualMediaTimeInputs(it[0] as String, it[1] as String, (it[2] as Number).toFloat(), it[3] as String, it[4] as String) },
        )
        fun create(projectId: String, sourceId: String, frameTimeUs: Long, durationUs: Long) = ManualMediaTimeInputs(
            projectId, sourceId, frameTimeUs / 1_000_000f, "0.000",
            String.format(Locale.ROOT, "%.3f", min(durationUs, 3_000_000) / 1_000_000.0),
        )
    }
}
