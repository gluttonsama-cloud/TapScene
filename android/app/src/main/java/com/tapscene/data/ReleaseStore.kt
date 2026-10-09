package com.tapscene.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.tapscene.packageformat.ViewerPackageCodec
import com.tapscene.packageformat.ViewerScene
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-private fixed outputs. Every instance shares one process mutex, including recovery.
 * A directory rename is the install/seal commit point. Cleanup is always limited to an exact
 * owned staging directory; a post-commit cleanup/sync failure never deletes installed content.
 * Call on Dispatchers.IO. No operation sends data, references raw sources, or accepts a review
 * assertion from an imported package. Imported packages are view-only library entries.
 */
class ReleaseStore(context: Context) {
    private val app = context.applicationContext
    private val privateRoot = app.noBackupFilesDir.canonicalFile
    private val root = File(privateRoot, "release-store")
    private val candidates = File(root, "candidates")
    private val releases = File(root, "releases")
    private val staging = File(root, "staging")
    private val exports = File(root, "exports")

    suspend fun createCandidate(projectId: String, expectedRevision: Long,
        replaceExisting: Boolean = false): ReleaseCandidate = locked {
        validId(projectId)
        val previous = findCandidate(projectId)
        if (previous != null && !replaceExisting) {
            check(previous.projectRevision == expectedRevision) { "已有另一草稿修订的候选；确认替换后才能重新生成。" }
            return@locked previous
        }
        stage { operation ->
            val payload = directory(File(operation, "package"), operation)
            val assets = directory(File(payload, "assets"), payload)
            // ProjectStore holds its deletion/edit lock through snapshot AND copy.
            val snapshot = ProjectStore(app).copyReleaseInputs(projectId, expectedRevision, assets)
            currentCoroutineContext().ensureActive()
            val id = newId()
            val scene = ReleaseCompiler.scene(snapshot, id,
                maxOf(System.currentTimeMillis(), (previous?.scene?.createdAt ?: -1) + 1))
            writeSynced(File(payload, "scene.json"), ViewerPackageCodec.writeScene(scene))
            writeSynced(File(payload, "manifest.json"), ViewerPackageCodec.manifestBytes(scene))
            verifyPackage(scene, payload, decode = true)
            val result = ReleaseCandidate(id, projectId, expectedRevision, scene,
                ViewerPackageCodec.contentDigest(scene))
            writeCandidate(operation, result)
            syncDirectory(payload)
            syncDirectory(operation)
            currentCoroutineContext().ensureActive()
            val destination = child(candidates, id)
            check(!destination.exists()) { "候选标识冲突，请重试。" }
            Files.move(operation.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            // The complete directory is now installed. Never roll it back after this commit point.
            runCatching { syncDirectory(candidates); syncDirectory(staging) }
            if (previous != null) runCatching { discardCandidateLocked(previous.id) }
            result
        }
    }

    suspend fun readCandidate(projectId: String): ReleaseCandidate? = locked {
        validId(projectId)
        findCandidate(projectId)
    }

    suspend fun listCandidates(): List<ReleaseCandidate> = locked {
        candidates.listFiles().orEmpty().filter { it.isDirectory }.map { readCandidateDirectory(it, verifyAssets = false) }
            .sortedWith(compareByDescending<ReleaseCandidate> { it.scene.createdAt }.thenBy { it.id })
    }

    /** Explicitly confirmed discard affects only this fixed, unsealed candidate. */
    suspend fun discardCandidate(candidateId: String) = locked { discardCandidateLocked(candidateId) }

    suspend fun readCandidateById(candidateId: String): ReleaseCandidate = locked {
        readCandidateDirectory(child(candidates, candidateId))
    }

    /** UI calls only after displaying this state's actual PNG and all its text/actions. */
    suspend fun reviewState(candidateId: String, digest: String, stateId: String): ReleaseCandidate = locked {
        val candidate = boundCandidate(candidateId, digest)
        check(candidate.scene.states.any { it.id == stateId }) { "复核步骤不属于这个候选。" }
        val asset = stateAsset(candidate.scene, stateId)
        ReleaseCompiler.verifyAsset(app, asset, File(child(candidates, candidateId), "package"))
        update(candidate.copy(reviewedStateIds = candidate.reviewedStateIds + stateId))
    }

    /** Call only after this fixed candidate clip reached EOS from zero without a seek/skip,
     * then the author explicitly confirmed it. Traversal/decoder success alone is not review. */
    suspend fun reviewTransition(candidateId: String, digest: String, assetId: String,
        assetSha256: String): ReleaseCandidate = locked {
        val candidate = boundCandidate(candidateId, digest)
        val asset = transitionAsset(candidate.scene, assetId)
        check(asset.sha256 == assetSha256) { "完整观看记录对应另一份视频，请重新播放。" }
        ReleaseCompiler.verifyAsset(app, asset, File(child(candidates, candidateId), "package"))
        update(candidate.copy(reviewedTransitionAssetIds = candidate.reviewedTransitionAssetIds + assetId))
    }

    suspend fun reviewSummary(candidateId: String, digest: String): ReleaseCandidate = locked {
        val candidate = boundCandidate(candidateId, digest)
        update(candidate.copy(summaryReviewed = true))
    }

    suspend fun reviewFileList(candidateId: String, digest: String): ReleaseCandidate = locked {
        val candidate = boundCandidate(candidateId, digest)
        update(candidate.copy(fileListReviewed = true))
    }

    /**
     * Called after actual playback succeeds. null/false starts at the real start image; each
     * edge must follow the persisted current state. The boolean is never trusted as coverage.
     * End/terminal completion is derived from the actual contiguous traversal beginning at start.
     */
    suspend fun recordTraversal(candidateId: String, digest: String, edgeId: String?,
        completedFromStart: Boolean): ReleaseCandidate = locked {
        val candidate = boundCandidate(candidateId, digest)
        if (edgeId == null) {
            if (completedFromStart) {
                check(candidate.traversalStarted && candidate.traversalEnded) { "尚未从起点实际到达终点。" }
                return@locked candidate
            }
            val terminal = candidate.scene.states.single { it.id == candidate.scene.startStateId }.terminal
            return@locked update(candidate.copy(traversalStateId = candidate.scene.startStateId,
                traversalStarted = true, traversalEnded = terminal,
                traversalHistory = listOf(candidate.scene.startStateId), traversalEndEdgeId = null,
                completedPath = candidate.completedPath || terminal))
        }
        check(candidate.traversalStarted && !candidate.traversalEnded) { "请先从候选起点开始实际预览。" }
        val edge = candidate.scene.edges.singleOrNull { it.id == edgeId }
            ?: error("这条边不属于候选版本。")
        check(edge.fromStateId == candidate.traversalStateId) { "只能记录实际当前画面上的动作。" }
        check(candidate.traversalHistory.size < MAX_HISTORY) { "本次回访过多，请从起点重来。" }
        val target = edge.toStateId
        val ended = target == null || candidate.scene.states.single { it.id == target }.terminal
        update(candidate.copy(visitedEdgeIds = candidate.visitedEdgeIds + edge.id,
            completedPath = candidate.completedPath || ended,
            traversalStateId = target ?: edge.fromStateId, traversalEnded = ended,
            traversalHistory = if (target == null) candidate.traversalHistory else candidate.traversalHistory + target,
            traversalEndEdgeId = if (target == null) edge.id else null))
    }

    /** Back uses the actual stored history; it never creates coverage or accepts an arbitrary state. */
    suspend fun rewindTraversal(candidateId: String, digest: String): ReleaseCandidate = locked {
        val candidate = boundCandidate(candidateId, digest)
        check(candidate.traversalStarted) { "请先开始候选预览。" }
        if (candidate.traversalEndEdgeId != null) {
            return@locked update(candidate.copy(traversalEnded = false, traversalEndEdgeId = null))
        }
        check(candidate.traversalHistory.size > 1) { "已经是本次预览的起点。" }
        val history = candidate.traversalHistory.dropLast(1)
        update(candidate.copy(traversalStateId = history.last(), traversalHistory = history,
            traversalEnded = false, traversalEndEdgeId = null))
    }

    suspend fun seal(candidateId: String, digest: String): ReleaseSummary = locked {
        // Retry after a commit/cancellation can establish the already committed result.
        val destination = child(releases, candidateId)
        if (destination.exists()) {
            val existing = readReleaseDirectory(destination)
            check(existing.contentDigest == digest) { "此版本标识已对应另一份内容。" }
            return@locked existing
        }
        val candidate = boundCandidate(candidateId, digest)
        check(candidate.reviewedStateIds == candidate.scene.states.map { it.id }.toSet() &&
            candidate.summaryReviewed && candidate.fileListReviewed) { "请完成实际图片、文字、项目摘要及包文件清单复核。" }
        check(candidate.visitedEdgeIds == candidate.scene.edges.map { it.id }.toSet() && candidate.completedPath) {
            "请实际试走全部连线，并至少从起点完整到达一次终点。"
        }
        check(candidate.reviewedTransitionAssetIds == candidate.scene.assets
            .filter { it.role == ViewerScene.Asset.ROLE_TRANSITION }.map { it.id }.toSet()) {
            "请从头完整观看并逐段确认固定候选的全部实际过渡视频。"
        }
        val source = child(candidates, candidateId)
        verifyPackage(candidate.scene, File(source, "package"), decode = true)
        val summary = summary(candidate.scene, "local", System.currentTimeMillis())
        writeSummary(source, summary)
        syncDirectory(source)
        currentCoroutineContext().ensureActive()
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        runCatching { syncDirectory(releases); syncDirectory(candidates) }
        summary
    }

    suspend fun listReleases(): List<ReleaseSummary> = locked {
        // Fail visibly for corruption instead of inventing an empty or apparently healthy library.
        releases.listFiles().orEmpty().filter { it.isDirectory }.map { readReleaseDirectory(it, verifyAssets = false) }
            .sortedWith(compareByDescending<ReleaseSummary> { it.sealedAt }.thenBy { it.id })
    }

    suspend fun loadRelease(releaseId: String): ViewerScene = locked {
        val location = child(releases, releaseId)
        val summary = readReleaseDirectory(location)
        readScene(File(location, "package")).also { check(ViewerPackageCodec.contentDigest(it) == summary.contentDigest) }
    }

    suspend fun candidateAssetFile(candidateId: String, stateId: String): File = locked {
        val candidate = readCandidateDirectory(child(candidates, candidateId), verifyAssets = false)
        checkedAsset(candidate.scene, File(child(candidates, candidateId), "package"), stateId)
    }

    suspend fun releaseAssetFile(releaseId: String, stateId: String): File = locked {
        val location = child(releases, releaseId)
        readReleaseDirectory(location, verifyAssets = false)
        val payload = File(location, "package")
        checkedAsset(readScene(payload), payload, stateId)
    }

    suspend fun candidateTransitionFile(candidateId: String, assetId: String): File = locked {
        val candidate = readCandidateDirectory(child(candidates, candidateId), verifyAssets = false)
        checkedTransition(candidate.scene, File(child(candidates, candidateId), "package"), assetId)
    }

    suspend fun releaseTransitionFile(releaseId: String, assetId: String): File = locked {
        val location = child(releases, releaseId)
        readReleaseDirectory(location, verifyAssets = false)
        val payload = File(location, "package")
        checkedTransition(readScene(payload), payload, assetId)
    }

    /** A verified ZIP is prepared privately; the UI still owns the explicit SAF save operation. */
    suspend fun exportRelease(releaseId: String): File = locked {
        val location = child(releases, releaseId)
        val stored = readReleaseDirectory(location)
        val payload = File(location, "package")
        val scene = readScene(payload)
        verifyPackage(scene, payload, decode = true)
        stage { operation ->
            val output = File(operation, "viewer.tapscene")
            ViewerPackageCodec.writePackage(scene, payload, output, cancelCheck(), videoValidator())
            val verify = directory(File(operation, "verified"), operation)
            val loaded = ViewerPackageCodec.readPackage(output, verify, cancelCheck(), videoValidator())
            check(loaded.contentDigest == stored.contentDigest) { "导出包内容校验失败。" }
            verifyPackage(loaded.scene, verify, decode = true)
            syncFile(output)
            currentCoroutineContext().ensureActive()
            val final = File(exports, "$releaseId.tapscene")
            check(final.canonicalFile == final.absoluteFile) { "导出路径无效。" }
            Files.move(output.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            runCatching { syncDirectory(exports) }
            final
        }
    }

    /** Bounded streaming, isolated verification, then one atomic install. The caller owns input. */
    suspend fun importPackage(input: InputStream): ReleaseSummary = locked {
        stage { operation ->
            val zip = File(operation, "incoming.zip")
            FileOutputStream(zip).use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_BYTES - total + 1).toInt())
                    if (count < 0) break
                    check(count > 0) { "无法继续读取观看包。" }
                    total += count
                    require(total <= MAX_BYTES) { "观看包超过 50 MiB。" }
                    sink.write(buffer, 0, count)
                }
                check(total > 0) { "观看包为空。" }
                sink.fd.sync()
            }
            val payload = directory(File(operation, "package"), operation)
            val loaded = ViewerPackageCodec.readPackage(zip, payload, cancelCheck(), videoValidator())
            // Accepted external JSON may use whitespace/key-order variations. Persist only the
            // canonical, whitelisted representation whose file list is displayed and exported.
            writeSynced(File(payload, "scene.json"), ViewerPackageCodec.writeScene(loaded.scene))
            writeSynced(File(payload, "manifest.json"), ViewerPackageCodec.manifestBytes(loaded.scene))
            verifyPackage(loaded.scene, payload, decode = true)
            val destination = child(releases, loaded.scene.releaseId)
            if (destination.exists()) {
                val existing = readReleaseDirectory(destination)
                check(existing.contentDigest == loaded.contentDigest) { "相同版本标识对应不同内容，已拒绝导入。" }
                return@stage existing
            }
            check(zip.delete()) { "无法清理本次导入暂存。" }
            val result = summary(loaded.scene, "imported", System.currentTimeMillis())
            // ZIP extraction closes streams but does not promise fsync. Persist every extracted
            // file and the assets directory before publishing the enclosing directory.
            loaded.scene.assets.forEach { syncFile(File(payload, it.path)) }
            syncDirectory(File(payload, "assets"))
            writeSummary(operation, result)
            syncDirectory(payload)
            syncDirectory(operation)
            currentCoroutineContext().ensureActive()
            Files.move(operation.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            runCatching { syncDirectory(releases); syncDirectory(staging) }
            result
        }
    }

    /** Only call after explicit local-library deletion confirmation. Drafts/sources are untouched. */
    suspend fun deleteRelease(releaseId: String) = locked {
        val source = child(releases, releaseId)
        check(source.isDirectory) { "这个本机版本已不存在。" }
        stage { operation ->
            Files.move(source.toPath(), File(operation, "deleted-release").toPath(), StandardCopyOption.ATOMIC_MOVE)
            runCatching { syncDirectory(releases); File(exports, "$releaseId.tapscene").delete(); syncDirectory(exports) }
        }
    }

    private suspend fun checkedAsset(scene: ViewerScene, payload: File, stateId: String): File {
        val asset = stateAsset(scene, stateId)
        ReleaseCompiler.verifyAsset(app, asset, payload)
        return File(payload, asset.path)
    }

    private suspend fun checkedTransition(scene: ViewerScene, payload: File, assetId: String): File {
        val asset = transitionAsset(scene, assetId)
        ReleaseCompiler.verifyAsset(app, asset, payload)
        return File(payload, asset.path)
    }

    private fun transitionAsset(scene: ViewerScene, assetId: String): ViewerScene.Asset {
        check(scene.edges.any { it.transitionAssetId == assetId }) { "视频不属于当前固定版本的边。" }
        return scene.assets.singleOrNull { it.id == assetId && it.role == ViewerScene.Asset.ROLE_TRANSITION }
            ?: error("固定版本缺少这个过渡视频。")
    }

    private fun stateAsset(scene: ViewerScene, stateId: String): ViewerScene.Asset {
        val state = scene.states.singleOrNull { it.id == stateId } ?: error("成品中没有这个步骤。")
        return scene.assets.single { it.id == state.imageAssetId }
    }

    // Frequent review/playback updates bind canonical content; target pixels are checked by
    // the image/review operation. Initial open and the seal commit still verify every asset.
    private suspend fun boundCandidate(id: String, digest: String): ReleaseCandidate =
        readCandidateDirectory(child(candidates, id), verifyAssets = false).also {
            check(it.contentDigest == digest) { "复核对应另一份内容，请重新打开固定候选。" }
        }

    private fun update(candidate: ReleaseCandidate): ReleaseCandidate {
        writeCandidate(child(candidates, candidate.id), candidate)
        return candidate
    }

    private suspend fun findCandidate(projectId: String): ReleaseCandidate? {
        val latest = candidates.listFiles().orEmpty().filter { it.isDirectory }
            .map { readCandidateDirectory(it, verifyAssets = false) }.filter { it.projectId == projectId }
            .maxWithOrNull(compareBy<ReleaseCandidate> { it.scene.createdAt }.thenBy { it.id })
        return latest?.let { readCandidateDirectory(child(candidates, it.id)) }
    }

    private suspend fun readCandidateDirectory(location: File, verifyAssets: Boolean = true): ReleaseCandidate {
        check(location.parentFile == candidates && location.isDirectory) { "固定候选已不存在，请重新打开。" }
        validId(location.name)
        check(location.canonicalFile == location.absoluteFile) { "候选目录无效。" }
        val scene = readScene(File(location, "package"))
        verifyPackage(scene, File(location, "package"), decode = false, verifyAssets = verifyAssets)
        val json = readJson(File(location, "review.json"))
        val digest = ViewerPackageCodec.contentDigest(scene)
        check(scene.releaseId == location.name && json.getInt("version") == 1 &&
            json.getString("contentDigest") == digest && json.getString("fileListDigest") == fileListDigest(scene)) {
            "候选或复核所绑定的实际内容已改变。"
        }
        val reviewed = strings(json.getJSONArray("reviewedStates")).toSet()
        val reviewedTransitions = json.optJSONArray("reviewedTransitions")?.let { strings(it).toSet() } ?: emptySet()
        val visited = strings(json.getJSONArray("visitedEdges")).toSet()
        val history = strings(json.getJSONArray("history"))
        check(reviewedTransitions.all { id -> scene.assets.any { it.id == id && it.role == ViewerScene.Asset.ROLE_TRANSITION } } &&
            reviewed.all { id -> scene.states.any { it.id == id } } &&
            visited.all { id -> scene.edges.any { it.id == id } } && history.size <= MAX_HISTORY &&
            history.all { id -> scene.states.any { it.id == id } }) { "候选复核记录无效。" }
        val projectId = json.getString("projectId").also(::validId)
        val revision = json.getLong("projectRevision").also { check(it > 0) }
        return ReleaseCandidate(scene.releaseId, projectId, revision, scene, digest, reviewed,
            json.getBoolean("summaryReviewed"), json.getBoolean("fileListReviewed"), visited,
            json.getBoolean("completedPath"), optional(json, "traversalStateId"),
            json.getBoolean("traversalStarted"), json.getBoolean("traversalEnded"), history,
            optional(json, "traversalEndEdgeId"), reviewedTransitions)
    }

    private fun writeCandidate(location: File, candidate: ReleaseCandidate) {
        val json = JSONObject().apply {
            put("version", 1); put("projectId", candidate.projectId); put("projectRevision", candidate.projectRevision)
            put("contentDigest", candidate.contentDigest); put("fileListDigest", fileListDigest(candidate.scene))
            put("reviewedStates", JSONArray(candidate.reviewedStateIds.sorted()))
            put("reviewedTransitions", JSONArray(candidate.reviewedTransitionAssetIds.sorted()))
            put("summaryReviewed", candidate.summaryReviewed); put("fileListReviewed", candidate.fileListReviewed)
            put("visitedEdges", JSONArray(candidate.visitedEdgeIds.sorted())); put("completedPath", candidate.completedPath)
            put("traversalStateId", candidate.traversalStateId ?: JSONObject.NULL)
            put("traversalStarted", candidate.traversalStarted); put("traversalEnded", candidate.traversalEnded)
            put("history", JSONArray(candidate.traversalHistory))
            put("traversalEndEdgeId", candidate.traversalEndEdgeId ?: JSONObject.NULL)
        }
        atomicJson(File(location, "review.json"), json)
    }

    private suspend fun readReleaseDirectory(location: File, verifyAssets: Boolean = true): ReleaseSummary {
        check(location.parentFile == releases && location.isDirectory && location.canonicalFile == location.absoluteFile) {
            "本机成品不存在或路径无效。"
        }
        validId(location.name)
        val scene = readScene(File(location, "package"))
        verifyPackage(scene, File(location, "package"), decode = false, verifyAssets = verifyAssets)
        val json = readJson(File(location, "summary.json"))
        check(json.getInt("version") == 1 && scene.releaseId == location.name) { "本机成品记录无效。" }
        val origin = json.getString("origin")
        check(origin == "local" || origin == "imported") { "本机成品来源无效。" }
        val expected = summary(scene, origin, json.getLong("sealedAt"))
        check(json.getString("contentDigest") == expected.contentDigest && json.getLong("byteLength") == expected.byteLength) {
            "本机成品内容已改变，请重新导入。"
        }
        return expected
    }

    private fun summary(scene: ViewerScene, origin: String, sealedAt: Long) = ReleaseSummary(
        scene.releaseId, scene.title, ViewerPackageCodec.contentDigest(scene), sealedAt, scene.states.size,
        ViewerPackageCodec.fileList(scene).sumOf { it.byteLength } + ViewerPackageCodec.manifestBytes(scene).size, origin)

    private fun writeSummary(location: File, summary: ReleaseSummary) = atomicJson(File(location, "summary.json"), JSONObject().apply {
        put("version", 1); put("contentDigest", summary.contentDigest); put("sealedAt", summary.sealedAt)
        put("byteLength", summary.byteLength); put("origin", summary.origin)
    })

    private fun readScene(payload: File): ViewerScene {
        check(payload.canonicalFile == payload.absoluteFile && payload.isDirectory) { "成品目录缺失。" }
        val file = File(payload, "scene.json")
        check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() in 1..MAX_JSON_BYTES) { "成品描述无效。" }
        return ViewerPackageCodec.parseScene(file.readBytes())
    }

    private suspend fun verifyPackage(scene: ViewerScene, payload: File, decode: Boolean, verifyAssets: Boolean = true) {
        val sceneFile = File(payload, "scene.json")
        val manifestFile = File(payload, "manifest.json")
        val expectedScene = ViewerPackageCodec.writeScene(scene)
        val expectedManifest = ViewerPackageCodec.manifestBytes(scene)
        check(sceneFile.canonicalFile == sceneFile.absoluteFile && sceneFile.isFile &&
            sceneFile.length() == expectedScene.size.toLong() && sceneFile.readBytes().contentEquals(expectedScene) &&
            manifestFile.canonicalFile == manifestFile.absoluteFile && manifestFile.isFile &&
            manifestFile.length() == expectedManifest.size.toLong() && manifestFile.readBytes().contentEquals(expectedManifest)) {
            "实际成品描述或包文件清单已改变。"
        }
        if (verifyAssets) ViewerPackageCodec.validateDirectory(scene, payload, cancelCheck(), videoValidator())
        // validateDirectory already fully decoded every MP4 via its mandatory validator.
        // Image decoding remains explicit here; avoid decoding all clips twice per operation.
        if (decode) scene.assets.filter { !verifyAssets || it.role != ViewerScene.Asset.ROLE_TRANSITION }
            .forEach { ReleaseCompiler.verifyAsset(app, it, payload) }
    }

    private suspend fun videoValidator() = ReleaseCompiler.videoValidator(app, currentCoroutineContext())

    private suspend fun cancelCheck(): ViewerPackageCodec.CancelCheck {
        val owner = currentCoroutineContext()
        return ViewerPackageCodec.CancelCheck { owner.ensureActive() }
    }

    private suspend fun <T> locked(action: suspend () -> T): T = mutex.withLock {
        directory(root, privateRoot)
        listOf(candidates, releases, staging, exports).forEach { directory(it, root) }
        recoverStages()
        recoverMetadataParts()
        action()
    }

    private suspend fun <T> stage(action: suspend (File) -> T): T {
        val id = newId()
        val operation = child(staging, id)
        check(operation.mkdir()) { "无法建立成品暂存目录。" }
        writeSynced(File(operation, ".owner"), "$OWNER:$id".toByteArray(Charsets.US_ASCII))
        syncDirectory(operation)
        syncDirectory(staging)
        try { return action(operation) }
        finally {
            // Once a directory has been renamed into candidates/releases it no longer exists
            // here; do not infer failure from cancellation or touch the committed destination.
            runCatching { removeOwnedStage(operation) }
        }
    }

    private fun recoverStages() {
        staging.listFiles().orEmpty().forEach { operation -> runCatching { removeOwnedStage(operation) } }
    }

    private fun removeOwnedStage(operation: File) {
        validId(operation.name)
        if (!operation.exists()) return
        check(operation.parentFile == staging && operation.canonicalFile == operation.absoluteFile && operation.isDirectory)
        val marker = File(operation, ".owner")
        check(marker.canonicalFile == marker.absoluteFile)
        val completeMarker = marker.isFile && marker.length() <= 100 &&
            marker.readText(Charsets.US_ASCII) == "$OWNER:${operation.name}"
        // mkdir and the first marker write are not atomic together. Before that write completes,
        // only an empty UUID directory or its partially written regular marker can exist here.
        val interruptedCreation = operation.listFiles()?.all { it.name == ".owner" &&
            it.isFile && it.canonicalFile == it.absoluteFile && it.length() <= 100 } == true
        check(completeMarker || interruptedCreation) { "暂存目录不属于本次成品存储。" }
        deleteExactTree(operation)
        syncDirectory(staging)
    }

    /** Exact private metadata temp names only; never inspect/delete package or export files. */
    private fun recoverMetadataParts() {
        listOf(candidates, releases).forEach { parent ->
            parent.listFiles().orEmpty().forEach { location -> runCatching {
                validId(location.name)
                check(location.isDirectory && location.canonicalFile == location.absoluteFile)
                location.listFiles().orEmpty().filter { METADATA_PART.matches(it.name) }.forEach { part ->
                    check(part.parentFile == location && part.canonicalFile == part.absoluteFile && part.isFile)
                    check(part.delete() || !part.exists())
                }
            } }
        }
    }

    private suspend fun discardCandidateLocked(id: String) {
        val source = child(candidates, id)
        if (!source.exists()) return
        check(source.isDirectory && source.canonicalFile == source.absoluteFile)
        stage { operation ->
            Files.move(source.toPath(), File(operation, "deleted-candidate").toPath(), StandardCopyOption.ATOMIC_MOVE)
            runCatching { syncDirectory(candidates) }
        }
    }

    private fun deleteExactTree(file: File) {
        check(file.canonicalFile == file.absoluteFile) { "拒绝清理非本机成品路径。" }
        if (file.isDirectory) file.listFiles().orEmpty().forEach(::deleteExactTree)
        check(file.delete() || !file.exists()) { "本次成品暂存清理未完成。" }
    }

    private fun directory(file: File, parent: File): File {
        check(file.parentFile == parent && file.canonicalFile == file.absoluteFile) { "本机成品路径无效。" }
        if (!file.exists()) {
            check(file.mkdir()) { "无法建立本机成品目录。" }
            syncDirectory(parent)
        }
        check(file.isDirectory) { "本机成品目录无效。" }
        return file
    }

    private fun child(parent: File, id: String): File {
        validId(id)
        return File(parent, id).also { check(it.canonicalFile == it.absoluteFile) { "版本路径无效。" } }
    }

    private fun atomicJson(file: File, json: JSONObject) {
        val temporary = File(file.parentFile, ".${file.name}-${newId()}.part")
        try {
            writeSynced(temporary, json.toString().toByteArray(Charsets.UTF_8))
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            runCatching { syncDirectory(file.parentFile!!) }
        } finally { runCatching { if (temporary.exists()) temporary.delete() } }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        check(file.canonicalFile == file.absoluteFile)
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    }

    private fun readJson(file: File): JSONObject {
        check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() in 1..MAX_JSON_BYTES) {
            "本机成品记录缺失或过大。"
        }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun fileListDigest(scene: ViewerScene): String = MessageDigest.getInstance("SHA-256")
        .digest(ViewerPackageCodec.manifestBytes(scene)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun strings(array: JSONArray): List<String> = List(array.length()) { array.getString(it) }
    private fun optional(json: JSONObject, key: String): String? = if (json.isNull(key)) null else json.getString(key)

    companion object {
        private val mutex = Mutex()
        private const val OWNER = "tapscene-release-v1"
        private const val MAX_BYTES = 50L * 1024 * 1024
        private const val MAX_JSON_BYTES = 2L * 1024 * 1024
        private const val MAX_HISTORY = 10_000
        private val METADATA_PART = Regex("\\.(review|summary)\\.json-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part")
        private fun newId() = UUID.randomUUID().toString()
        private fun validId(id: String) {
            require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "版本标识无效。" }
        }
        private fun syncFile(file: File) {
            check(file.canonicalFile == file.absoluteFile)
            val descriptor = Os.open(file.path, OsConstants.O_RDONLY, 0)
            try {
                check(OsConstants.S_ISREG(Os.fstat(descriptor).st_mode))
                Os.fsync(descriptor)
            } finally { Os.close(descriptor) }
        }
        private fun syncDirectory(directory: File) {
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode))
                Os.fsync(descriptor)
            } finally { Os.close(descriptor) }
        }
    }
}
