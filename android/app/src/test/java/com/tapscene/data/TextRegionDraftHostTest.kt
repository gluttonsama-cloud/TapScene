package com.tapscene.data

import com.tapscene.media.OpaqueMask
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Production codec and native SQLite checks; no OCR engine, network or device claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TextRegionDraftHostTest {
    @Before fun configureSqlite() = HostSqlite.configure()

    @Test fun codecV1CompatibilityAndStrictBoundFormRoundTrip() {
        HostProjectFixture().use { fixture ->
            val draft = fixture.suggestionDraft().let { it.copy(pendingForm = it.pendingForm!!.copy(
                label = "  识别后修改\n未完成  ", left = "-", top = "1..2", right = "", bottom = "NaN")) }
            val encoded = EditorDraftCodec.encode(draft)
            check(JSONObject(encoded).getInt("version") == 2)
            check(EditorDraftCodec.decode(encoded) == draft)
            val sourceJson = JSONObject(encoded).getJSONObject("pendingForm").getJSONObject("textRegionSource")
            check(sourceJson.keys().asSequence().toSet() == setOf("projectId", "stepId", "assetId", "sha256", "width", "height"))
            val legacy = JSONObject(encoded).apply {
                put("version", 1)
                getJSONObject("pendingForm").remove("textRegionSource")
            }
            check(EditorDraftCodec.decode(legacy.toString()) == draft.copy(pendingForm = draft.pendingForm!!.copy(textRegionSource = null)))
            legacy.put("pendingForm", JSONObject.NULL)
            check(EditorDraftCodec.decode(legacy.toString()) == draft.copy(pendingForm = null))
            rejects { EditorDraftCodec.decode(JSONObject(encoded).apply { put("version", 1) }.toString()) }
            rejects { EditorDraftCodec.decode(JSONObject(encoded).apply {
                getJSONObject("pendingForm").remove("textRegionSource")
            }.toString()) }
            rejects { EditorDraftCodec.decode(JSONObject(encoded).apply {
                getJSONObject("pendingForm").getJSONObject("textRegionSource").put("rawOcr", "must not be retained")
            }.toString()) }
            rejects { EditorDraftCodec.decode(JSONObject(encoded).apply {
                getJSONObject("pendingForm").getJSONObject("textRegionSource").put("width", 4_294_967_297L)
            }.toString()) }
        }
    }

    @Test fun rejectsMalformedOrMisownedSuggestionWithoutReplacingDraft() {
        HostProjectFixture().use { fixture ->
            val store = fixture.store
            val draft = fixture.suggestionDraft()
            val form = draft.pendingForm!!
            val source = form.textRegionSource!!
            val session = store.beginEditorDraftSession(fixture.project)
            check(store.writeEditorDraft(fixture.project, fixture.a, session, draft))
            rejects { source.copy(projectId = "not-a-uuid") }
            rejects { source.copy(sha256 = "A".repeat(64)) }
            rejects { source.copy(sha256 = "abc") }
            rejects { source.copy(width = 0) }
            rejects { source.copy(height = -1) }
            rejects { source.copy(width = 12_000_001) }
            rejects { form.copy(kind = EditorFormKind.NAME) }
            rejects { form.copy(kind = EditorFormKind.NEXT_ACTION) }
            rejects { form.copy(objectId = null) }
            rejects { form.copy(edgeId = null) }
            listOf(source.copy(projectId = fixture.id()), source.copy(stepId = fixture.b)).forEach { other ->
                rejects { store.writeEditorDraft(fixture.project, fixture.a, session,
                    draft.copy(pendingForm = form.copy(textRegionSource = other))) }
            }
            val original = draft.base.hotspots.single()
            rejects { store.writeEditorDraft(fixture.project, fixture.a, session,
                draft.copy(pendingForm = form.copy(objectId = original.id, edgeId = original.edgeId))) }
            check(store.readEditorDrafts(fixture.project) == mapOf(fixture.a to draft))
            // A corrupted on-disk owner is reported without deleting or silently rebinding it.
            val wrongOwner = EditorDraftCodec.encode(draft.copy(pendingForm = form.copy(textRegionSource = source.copy(stepId = fixture.b))))
            fixture.database { execSQL("UPDATE editor_drafts SET draft_json=? WHERE project_id=? AND state_id=?",
                arrayOf(wrongOwner, fixture.project, fixture.a)) }
            check(runCatching { store.readEditorDrafts(fixture.project) }.exceptionOrNull() is IllegalStateException)
            fixture.database { rawQuery("SELECT draft_json FROM editor_drafts WHERE project_id=? AND state_id=?",
                arrayOf(fixture.project, fixture.a)).use { check(it.moveToFirst() && it.getString(0) == wrongOwner) } }
        }
    }

    @Test fun sourceMismatchAndMissingBytesPreserveFormalAndRecoveryState() {
        HostProjectFixture().use { fixture ->
            val file = fixture.installSafePixels()
            val bytes = file.readBytes()
            val draft = fixture.suggestionDraft()
            val source = draft.pendingForm!!.textRegionSource!!
            val session = fixture.store.beginEditorDraftSession(fixture.project)
            check(fixture.store.writeEditorDraft(fixture.project, fixture.a, session, draft))
            val before = fixture.snapshot()
            val step = before.steps.single { it.id == fixture.a }
            listOf(source.copy(projectId = fixture.id()), source.copy(stepId = fixture.b),
                source.copy(assetId = fixture.id()), source.copy(sha256 = "b".repeat(64)),
                source.copy(width = 2), source.copy(height = 2)).forEach { stale ->
                check(!stale.matches(before.project, step))
                imageChanged { fixture.saveSuggestion(draft, session, stale) }
                check(fixture.snapshot() == before)
                check(fixture.store.readEditorDrafts(fixture.project) == mapOf(fixture.a to draft))
            }
            check(file.delete())
            imageChanged { fixture.saveSuggestion(draft, session, source) }
            file.writeBytes(bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            imageChanged { fixture.saveSuggestion(draft, session, source) }
            check(fixture.snapshot() == before)
            check(fixture.store.readEditorDrafts(fixture.project) == mapOf(fixture.a to draft))

            // Reopened input keeps its old source after the official image changes.
            fixture.database { execSQL("UPDATE local_assets SET sha256=? WHERE project_id=? AND asset_id=?",
                arrayOf(sha256(file.readBytes()), fixture.project, step.asset.id)) }
            val changed = fixture.snapshot()
            fixture.closeStore(fixture.store)
            val reopened = fixture.newStore()
            check(reopened.readEditorDrafts(fixture.project) == mapOf(fixture.a to draft))
            imageChanged { fixture.saveSuggestion(draft, session, source, store = reopened) }
            check(reopened.readProject(fixture.project) == changed)
            check(reopened.readEditorDrafts(fixture.project) == mapOf(fixture.a to draft))
        }
    }

    @Test fun finalSaveCannotBypassPersistedSourceAndAllowsExplicitCancellation() {
        HostProjectFixture().use { fixture ->
            val file = fixture.installSafePixels()
            val draft = fixture.suggestionDraft()
            val session = fixture.store.beginEditorDraftSession(fixture.project)
            check(fixture.store.writeEditorDraft(fixture.project, fixture.a, session, draft))
            val before = fixture.snapshot()
            val stale = draft.copy(pendingForm = draft.pendingForm!!.copy(
                textRegionSource = draft.pendingForm!!.textRegionSource!!.copy(assetId = fixture.id())))
            check(fixture.store.writeEditorDraft(fixture.project, fixture.a, session, stale))
            // Supplying the current image cannot strip a different persisted suggestion source.
            imageChanged { fixture.saveSuggestion(draft, session, draft.pendingForm!!.textRegionSource) }
            check(fixture.store.readEditorDrafts(fixture.project) == mapOf(fixture.a to stale))
            check(fixture.store.writeEditorDraft(fixture.project, fixture.a, session, draft))
            check(file.delete())
            // Omit the new parameter deliberately: the durable form is still authoritative.
            imageChanged { fixture.saveSuggestion(draft, session, null) }
            check(fixture.snapshot() == before)
            check(fixture.store.readEditorDrafts(fixture.project) == mapOf(fixture.a to draft))
            // Explicit cancellation must be staged before an ordinary hand-authored save.
            val cancelled = draft.copy(pendingForm = null)
            check(fixture.store.writeEditorDraft(fixture.project, fixture.a, session, cancelled))
            val saved = fixture.store.saveStepDraft(fixture.project, fixture.a, cancelled.edit.title,
                cancelled.edit.description, false, cancelled.edit.hotspots, expectedRevision = before.project.revision,
                editorDraftSession = session)
            check(saved.steps.single { it.id == fixture.a }.title == cancelled.edit.title)
            check(fixture.store.readEditorDrafts(fixture.project).isEmpty())
        }
    }

    @Test fun textRevisionKeepsSourceValidAndSuccessClearsOnlyThisDraft() {
        HostProjectFixture().use { fixture ->
            fixture.installSafePixels()
            val draft = fixture.suggestionDraft()
            val source = draft.pendingForm!!.textRegionSource!!
            val other = fixture.draft(fixture.b, "Other pending input")
            val session = fixture.store.beginEditorDraftSession(fixture.project)
            check(fixture.store.writeEditorDraft(fixture.project, fixture.a, session, draft))
            check(fixture.store.writeEditorDraft(fixture.project, fixture.b, session, other))
            val revisionOnly = fixture.store.renameProject(fixture.project, "A harmless text revision")
            check(revisionOnly.project.revision > draft.baseRevision)
            check(source.matches(revisionOnly.project, revisionOnly.steps.single { it.id == fixture.a }))
            val rebased = draft.copy(baseRevision = revisionOnly.project.revision)
            check(fixture.store.writeEditorDraft(fixture.project, fixture.a, session, rebased))
            // The omitted-argument fallback also permits a valid new hotspot.
            val saved = fixture.saveSuggestion(rebased, session, null)
            val step = saved.steps.single { it.id == fixture.a }
            check(step.hotspots.size == draft.edit.hotspots.size + 1)
            check(step.hotspots.single { it.id == draft.pendingForm!!.objectId }.label == "Reviewed suggestion")
            check(step.asset == revisionOnly.steps.single { it.id == fixture.a }.asset)
            check(fixture.store.readEditorDrafts(fixture.project) == mapOf(fixture.b to other))
        }
    }

    private fun HostProjectFixture.suggestionDraft(): StoredEditorDraft {
        val current = snapshot()
        val step = current.steps.single { it.id == a }
        return draft(a, "Pending title").copy(pendingForm = EditorPendingForm(EditorFormKind.HOTSPOT,
            objectId = id(), edgeId = id(), label = "Reviewed suggestion", left = "10", top = "20", right = "60", bottom = "80",
            targetStepId = b, textRegionSource = TextRegionSourceBinding(project, a, step.asset.id,
                step.asset.sha256, step.asset.width, step.asset.height)))
    }

    private fun HostProjectFixture.saveSuggestion(draft: StoredEditorDraft, session: Long,
        source: TextRegionSourceBinding?, store: ProjectStore = this.store): ProjectSnapshot {
        val form = draft.pendingForm!!
        val hotspot = ProjectHotspot(form.objectId!!, form.label, OpaqueMask(.1f, .2f, .6f, .8f), b, null, form.edgeId!!)
        return store.saveStepDraft(project, a, draft.edit.title, draft.edit.description, false,
            draft.edit.hotspots + hotspot, expectedRevision = draft.baseRevision, editorDraftSession = session,
            textRegionSource = source)
    }

    private fun HostProjectFixture.installSafePixels(): File {
        val step = snapshot().steps.single { it.id == a }
        val pixels = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aPioAAAAASUVORK5CYII=")
        val file = File(root, step.asset.privateRelativePath)
        check(file.parentFile!!.mkdirs())
        file.writeBytes(pixels)
        database { execSQL("UPDATE local_assets SET sha256=?,byte_length=? WHERE project_id=? AND asset_id=?",
            arrayOf(sha256(pixels), pixels.size, project, step.asset.id)) }
        return file
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) { "Invalid source was accepted" } }
    private fun imageChanged(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is IllegalStateException && failure.message == TextRegionSourceBinding.IMAGE_CHANGED) {
            "Expected preserved-input image error, got $failure"
        }
    }
}
