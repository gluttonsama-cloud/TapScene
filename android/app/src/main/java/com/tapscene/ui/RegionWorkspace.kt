package com.tapscene.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tapscene.data.ProjectRegion
import com.tapscene.data.ProjectLimits
import com.tapscene.data.ProjectSnapshot
import com.tapscene.data.ProjectStep
import com.tapscene.data.ProjectStore
import com.tapscene.data.RegionBox
import com.tapscene.data.StepAsset
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** A form is bound to the revision on which the author opened it. */
data class RegionEdit(
    val regionId: String?,
    val name: String,
    val group: String?,
    val bbox: RegionBox,
    val zIndex: Int,
    val anchorX: Double,
    val anchorY: Double,
    val expectedRevision: Long,
)

data class RegionUiState(
    val projectId: String? = null,
    val stepId: String? = null,
    val snapshot: ProjectSnapshot? = null,
    val bitmap: Bitmap? = null,
    val selectedId: String? = null,
    val cropBitmap: Bitmap? = null,
    val cropSha256: String? = null,
    val displayedSha256: String? = null,
    val busy: Boolean = false,
    val failedToLoad: Boolean = false,
    val stage: String? = null,
    val message: String? = null,
) {
    val step: ProjectStep? get() = snapshot?.steps?.firstOrNull { it.id == stepId }
    val revision: Long get() = snapshot?.project?.revision ?: 0L
    val regions: List<ProjectRegion> get() = step?.regions.orEmpty().sortedWith(compareBy({ it.zIndex }, { it.id }))
    val selected: ProjectRegion? get() = regions.firstOrNull { it.id == selectedId }
    val canEdit: Boolean get() = !busy && !failedToLoad && bitmap != null && step != null
    val canAdd: Boolean get() = canEdit && regions.size < ProjectLimits.MAX_REGIONS_PER_STEP &&
        snapshot!!.steps.sumOf { it.regions.size } < ProjectLimits.MAX_REGIONS
    val canReview: Boolean get() = canEdit && cropBitmap != null && cropSha256 != null &&
        cropSha256 == displayedSha256 && selected?.asset?.sha256 == cropSha256 &&
        selected?.matchesBase(requireNotNull(step).asset) == true && selected?.reviewedAt == null
}

/** Reads only persisted safe PNGs. Saving metadata, generating pixels and human review are separate. */
class RegionWorkspace(application: Application) : AndroidViewModel(application) {
    private val store = ProjectStore(application)
    private val mutableState = MutableStateFlow(RegionUiState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private var generation = 0L

    fun open(projectId: String, stepId: String) {
        if (state.value.busy || task?.isActive == true) return
        if (state.value.projectId == projectId && state.value.stepId == stepId && !state.value.failedToLoad) return
        generation++
        clearImages()
        mutableState.value = RegionUiState(projectId = projectId, stepId = stepId)
        execute("读取安全步骤") { load(projectId, stepId, null) }
    }

    fun reload() {
        val before = state.value
        val projectId = before.projectId ?: return
        val stepId = before.stepId ?: return
        if (before.busy) return
        closeCrop()
        execute("重新读取区域") { load(projectId, stepId, before.selectedId) }
    }

    fun select(id: String?) {
        if (state.value.busy || (id != null && state.value.regions.none { it.id == id })) return
        closeCrop()
        mutableState.update { it.copy(selectedId = id, message = null) }
    }

    fun save(edit: RegionEdit, onSaved: () -> Unit) {
        val before = state.value
        val projectId = before.projectId ?: return
        val stepId = before.stepId ?: return
        if (!before.canEdit) return
        if (edit.expectedRevision != before.revision) {
            message("项目已改变。请关闭表单，核对当前区域后重新编辑。")
            return
        }
        execute("保存区域定义", mutation = true) {
            val saved = withContext(Dispatchers.IO) {
                store.saveRegion(projectId, stepId, edit.regionId, edit.name, edit.group, edit.bbox,
                    edit.zIndex, edit.anchorX, edit.anchorY, edit.expectedRevision)
            }
            val previousIds = before.regions.map { it.id }.toSet()
            val selected = edit.regionId ?: saved.steps.first { it.id == stepId }.regions.first { it.id !in previousIds }.id
            applySnapshot(saved, selected)
            message("区域定义已保存。生成实际裁片后还需人工复核。")
            onSaved()
        }
    }

    fun generate() {
        val before = state.value
        val projectId = before.projectId ?: return
        val region = before.selected ?: return
        if (!before.canEdit || !region.matchesBase(requireNotNull(before.step).asset)) return
        closeCrop()
        execute("从安全画面生成裁片", mutation = true) {
            val saved = withContext(Dispatchers.IO) { store.generateRegion(projectId, region.id, before.revision) }
            applySnapshot(saved, region.id)
            loadCrop()
            message("这是实际生成的 PNG，请检查完整裁片后确认。")
        }
    }

    fun openCrop() {
        val before = state.value
        val region = before.selected ?: return
        if (!before.canEdit || region.asset == null || !region.matchesBase(requireNotNull(before.step).asset)) return
        execute("读取实际裁片") { loadCrop() }
    }

    /** The UI calls this after the exact decoded image has been presented for a frame. */
    fun displayed(sha256: String) {
        if (state.value.cropBitmap != null && state.value.cropSha256 == sha256)
            mutableState.update { it.copy(displayedSha256 = sha256) }
    }

    fun review() {
        val before = state.value
        if (!before.canReview) return
        val projectId = requireNotNull(before.projectId)
        val regionId = requireNotNull(before.selectedId)
        val sha256 = requireNotNull(before.cropSha256)
        execute("确认实际裁片", mutation = true) {
            val saved = withContext(Dispatchers.IO) { store.reviewRegion(projectId, regionId, before.revision, sha256) }
            // Keep these exact displayed pixels on screen after recording the review.
            mutableState.update { it.copy(snapshot = saved, message = "已记录这张实际裁片的人工复核。") }
        }
    }

    fun delete() {
        val before = state.value
        val projectId = before.projectId ?: return
        val regionId = before.selectedId ?: return
        if (!before.canEdit) return
        execute("移除区域", mutation = true) {
            val saved = withContext(Dispatchers.IO) { store.deleteRegion(projectId, regionId, before.revision) }
            applySnapshot(saved, null)
            message("区域已移除。")
        }
    }

    fun closeCrop() {
        if (state.value.busy) return
        val old = state.value.cropBitmap
        mutableState.update { it.copy(cropBitmap = null, cropSha256 = null, displayedSha256 = null) }
        old?.recycle()
    }

    fun leave() {
        if (state.value.busy) return
        generation++
        task?.cancel()
        clearImages()
        mutableState.value = RegionUiState()
    }

    fun cancel() { task?.cancel() }
    fun message(text: String) { mutableState.update { it.copy(message = text) } }

    private suspend fun load(projectId: String, stepId: String, selectedId: String?) {
        var decoded: Bitmap? = null
        var retained = false
        try {
            val project = withContext(Dispatchers.IO) {
                val project = store.readProject(projectId) ?: error("项目已移除")
                val step = project.steps.firstOrNull { it.id == stepId } ?: error("步骤已移除")
                decoded = decodeVerified(store.resolveAsset(projectId, stepId), step.asset, sampleForDisplay = true)
                project
            }
            coroutineContext.ensureActive()
            val old = state.value.bitmap
            applySnapshot(project, selectedId)
            mutableState.update { it.copy(bitmap = requireNotNull(decoded), failedToLoad = false) }
            retained = true
            old?.recycle()
        } finally {
            // withContext may discard its return value when cancellation wins the IO -> Main handoff.
            if (!retained) decoded?.recycle()
        }
    }

    private fun applySnapshot(snapshot: ProjectSnapshot, selectedId: String?) {
        val oldCrop = state.value.cropBitmap
        val step = snapshot.steps.firstOrNull { it.id == state.value.stepId } ?: error("步骤已移除")
        mutableState.update { it.copy(snapshot = snapshot, selectedId = selectedId?.takeIf { id -> step.regions.any { it.id == id } },
            cropBitmap = null, cropSha256 = null, displayedSha256 = null, failedToLoad = false) }
        oldCrop?.recycle()
    }

    private suspend fun loadCrop() {
        val before = state.value
        val region = requireNotNull(before.selected)
        val asset = requireNotNull(region.asset) { "先生成实际裁片" }
        check(region.matchesBase(requireNotNull(before.step).asset)) { "底图已改变，请重新校正区域并生成" }
        var decoded: Bitmap? = null
        var retained = false
        try {
            withContext(Dispatchers.IO) {
                decoded = decodeVerified(store.regionFile(requireNotNull(before.projectId), region.id), asset)
            }
            coroutineContext.ensureActive()
            val old = state.value.cropBitmap
            mutableState.update { it.copy(cropBitmap = requireNotNull(decoded), cropSha256 = asset.sha256, displayedSha256 = null) }
            retained = true
            old?.recycle()
        } finally { if (!retained) decoded?.recycle() }
    }

    private fun execute(stage: String, mutation: Boolean = false, block: suspend () -> Unit) {
        if (state.value.busy) return
        val currentGeneration = generation
        val before = state.value
        mutableState.update { it.copy(busy = true, stage = stage, message = null) }
        task = viewModelScope.launch {
            try { MediaWorkspace.operationLock.withLock { block() } }
            catch (cancelled: CancellationException) {
                if (mutation) reconcile(before, currentGeneration, "处理已停止，已重新读取保存结果。")
                else if (currentGeneration == generation) {
                    if (before.snapshot == null) mutableState.update { it.copy(failedToLoad = true) }
                    message("已取消读取，可重试。")
                }
                throw cancelled
            } catch (failure: Exception) {
                val safe = failure.message?.takeIf { (failure is IllegalArgumentException || failure is IllegalStateException) &&
                    it.length <= 240 && '/' !in it && '\\' !in it }
                val error = safe ?: "区域处理未完成，请检查本机存储后重试。"
                if (mutation) reconcile(before, currentGeneration, "$error 已重新读取保存结果。")
                else if (currentGeneration == generation) {
                    mutableState.update { it.copy(failedToLoad = before.snapshot == null || stage == "重新读取区域", message = error) }
                }
            } finally {
                if (currentGeneration == generation) mutableState.update { it.copy(busy = false, stage = null) }
            }
        }
    }

    /** Cancellation can race a committed transaction; never imply that a write rolled back. */
    private suspend fun reconcile(before: RegionUiState, currentGeneration: Long, resultMessage: String) {
        if (generation != currentGeneration) return
        withContext(NonCancellable) {
            try {
                MediaWorkspace.operationLock.withLock {
                    load(requireNotNull(before.projectId), requireNotNull(before.stepId), before.selectedId)
                }
                message(resultMessage)
            } catch (_: Exception) {
                clearImages()
                mutableState.update { it.copy(failedToLoad = true,
                    message = "无法确认保存结果。请重新读取区域后继续，已保存内容不会被自动覆盖。") }
            }
        }
    }

    private fun clearImages() {
        val before = state.value
        mutableState.update { it.copy(bitmap = null, cropBitmap = null, cropSha256 = null, displayedSha256 = null) }
        before.bitmap?.recycle()
        before.cropBitmap?.recycle()
    }

    override fun onCleared() {
        generation++
        task?.cancel()
        clearImages()
        super.onCleared()
    }

    /** Verify the bytes we decode as well as the store's path-bound verification. */
    private suspend fun decodeVerified(file: File, asset: StepAsset, sampleForDisplay: Boolean = false): Bitmap {
        require(asset.byteLength in 1..50L * 1024 * 1024 && asset.width > 0 && asset.height > 0 &&
            asset.width.toLong() * asset.height <= 12_000_000) { "安全图片尺寸超限" }
        val bytes = file.inputStream().use { input ->
            val result = ByteArray(asset.byteLength.toInt())
            var offset = 0
            while (offset < result.size) {
                coroutineContext.ensureActive()
                val count = input.read(result, offset, minOf(64 * 1024, result.size - offset))
                check(count > 0) { "安全图片不完整" }
                offset += count
            }
            check(input.read() == -1) { "安全图片长度已变化" }
            result
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == asset.sha256) { "安全图片校验不一致，请重新生成" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outMimeType == "image/png" && bounds.outWidth == asset.width && bounds.outHeight == asset.height) {
            "安全图片尺寸校验不一致"
        }
        coroutineContext.ensureActive()
        // The base canvas is bounded; the actual crop remains full-resolution for zoomed review.
        var sample = 1
        if (sampleForDisplay) while ((asset.width.toLong() / sample) * (asset.height / sample) > 1080L * 2400) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = sample
        }) ?: error("安全图片无法解码")
    }
}
