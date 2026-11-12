package com.drivesong.feature.voice

import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.db.UploadState
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.model.ClipPlanMode
import com.drivesong.core.model.ClipSlot
import com.drivesong.core.vehicle.restriction.UxRestrictionsMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MembersViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher().also { Dispatchers.setMain(it) }
    private val db = inMemoryDatabase()
    private val api = FakeApi()
    private val settings = FakeSettings()
    private val restriction = FakeRestrictionSource()
    private val recorder = FakeRecorder { dispatcher.scheduler.currentTime }
    private val player = FakePlayer()
    private val repo = VoiceRepository(db.voiceDao(), api, settings, ApplicationProvider.getApplicationContext())
    private val vm = MembersViewModel(repo, settings, UxRestrictionsMonitor(restriction, settings), recorder, player)

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun TestScope.addMember(nickname: String = "엄마"): String {
        vm.addMember(nickname, isChild = false, voiceConsent = true, guardianConsent = false) {}
        runCurrent()
        return vm.uiState.value.members.single { it.member.nickname == nickname }.member.id
    }

    private val state get() = vm.uiState.value

    @Test
    fun `record button is disabled while restricted and recording cannot start`() = test {
        val id = addMember()
        assertTrue(state.canRecord(id, ClipSlot.INTRO))

        restriction.isRestricted.value = true
        runCurrent()

        assertTrue(state.restricted)
        assertFalse(state.canRecord(id, ClipSlot.INTRO))
        vm.startRecording(id, ClipSlot.INTRO)
        assertEquals(0, recorder.starts)
        assertEquals(RecordingState.Idle, state.recording)

        restriction.isRestricted.value = false
        runCurrent()
        assertTrue(state.canRecord(id, ClipSlot.INTRO))
    }

    @Test
    fun `restriction starting mid-recording stops the recording`() = test {
        val id = addMember()
        vm.startRecording(id, ClipSlot.CALL)
        runCurrent()
        assertTrue(state.recording is RecordingState.Recording)
        advanceTimeBy(2_000)

        restriction.isRestricted.value = true
        runCurrent()

        assertEquals(listOf(2_000L), recorder.stoppedAt)
        assertEquals(RecordingState.Draft::class, state.recording::class)
        advanceTimeBy(10_000) // 자동 정지 타이머도 취소됐다
        assertEquals(1, recorder.stoppedAt.size)
    }

    @Test
    fun `recording stops automatically at 5 seconds`() = test {
        val id = addMember()
        vm.startRecording(id, ClipSlot.INTRO)
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(state.recording is RecordingState.Recording)

        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf(5_000L), recorder.stoppedAt)
        val draft = state.recording as RecordingState.Draft
        assertEquals(id, draft.memberId)
        assertEquals(ClipSlot.INTRO, draft.slot)
    }

    @Test
    fun `while recording no other recording can start`() = test {
        val id = addMember()
        vm.startRecording(id, ClipSlot.INTRO)
        runCurrent()
        assertFalse(state.canRecord(id, ClipSlot.CALL))
        vm.startRecording(id, ClipSlot.CALL)
        assertEquals(1, recorder.starts)
    }

    @Test
    fun `preview then save uploads the clip`() = test {
        val id = addMember()
        vm.startRecording(id, ClipSlot.OUTRO)
        vm.stopRecording()
        val draft = state.recording as RecordingState.Draft

        vm.previewDraft()
        vm.saveDraft()
        runCurrent()

        assertEquals(listOf(draft.file), player.played)
        assertEquals(RecordingState.Idle, state.recording)
        assertEquals(listOf(id to ClipSlot.OUTRO), api.uploads)
        val clip = state.members.single().clips(ClipSlot.OUTRO).single()
        assertEquals(UploadState.UPLOADED, clip.uploadState)
        assertEquals(draft.file.absolutePath, clip.localPath)
        assertTrue(state.uploading.isEmpty())
    }

    @Test
    fun `discard deletes the draft file`() = test {
        val id = addMember()
        vm.startRecording(id, ClipSlot.OUTRO)
        vm.stopRecording()
        val draft = state.recording as RecordingState.Draft

        vm.discardDraft()
        runCurrent()

        assertFalse(draft.file.exists())
        assertEquals(RecordingState.Idle, state.recording)
        assertTrue(state.members.single().clips.isEmpty())
    }

    @Test
    fun `failed upload shows a message and can be retried`() = test {
        val id = addMember()
        api.failUploads = true
        recordAndSave(id, ClipSlot.INTRO)
        val failed = state.members.single().clips.single()
        assertEquals(UploadState.FAILED, failed.uploadState)
        assertEquals(R.string.voice_error_upload, state.message)

        api.failUploads = false
        vm.retryUpload(failed)
        runCurrent()

        assertEquals(UploadState.UPLOADED, state.members.single().clips.single().uploadState)
    }

    @Test
    fun `recording is disabled once a slot has 3 clips`() = test {
        val id = addMember()
        repeat(3) { recordAndSave(id, ClipSlot.CALL) }

        assertEquals(3, state.members.single().clips(ClipSlot.CALL).size)
        assertFalse(state.canRecord(id, ClipSlot.CALL))
        assertTrue(state.canRecord(id, ClipSlot.INTRO))
        vm.startRecording(id, ClipSlot.CALL)
        assertEquals(3, recorder.starts)
    }

    @Test
    fun `too short recording is dropped`() = test {
        val id = addMember()
        recorder.stopResult = false
        vm.startRecording(id, ClipSlot.INTRO)
        val file = recorder.recording!!
        vm.stopRecording()
        runCurrent()

        assertEquals(RecordingState.Idle, state.recording)
        assertFalse(file.exists())
        assertEquals(R.string.voice_error_too_short, state.message)
    }

    @Test
    fun `deleting a member deletes it on the server, removes its files and passenger selection`() = test {
        val mom = addMember("엄마")
        val dad = addMember("아빠")
        recordAndSave(mom, ClipSlot.INTRO)
        val file = File(state.members.first().clips.single().localPath)
        settings.selectedPassengerIds.value = setOf(mom, dad)

        vm.deleteMember(mom)
        runCurrent()

        assertEquals(listOf(mom), api.deletedMembers)
        assertFalse(file.exists())
        assertEquals(listOf("아빠"), state.members.map { it.member.nickname })
        assertEquals(setOf(dad), settings.selectedPassengerIds.value)
        assertFalse(state.busy)
    }

    @Test
    fun `deleting a member with an unsaved draft discards it so others can record`() = test {
        val mom = addMember("엄마")
        val kid = addMember("첫째")
        vm.startRecording(mom, ClipSlot.INTRO)
        vm.stopRecording()
        val draft = state.recording as RecordingState.Draft
        assertTrue(draft.file.exists())

        vm.deleteMember(mom)
        runCurrent()

        assertEquals(RecordingState.Idle, state.recording)
        assertFalse(draft.file.exists())
        assertTrue(state.canRecord(kid, ClipSlot.INTRO))
    }

    @Test
    fun `deleting a member while it is recording stops and discards the recording`() = test {
        val mom = addMember("엄마")
        vm.startRecording(mom, ClipSlot.INTRO)
        val recording = state.recording as RecordingState.Recording

        vm.deleteMember(mom)
        runCurrent()
        advanceTimeBy(10_000) // 자동 정지 타이머가 지운 구성원의 초안을 만들지 않는다

        assertEquals(RecordingState.Idle, state.recording)
        assertFalse(recording.file.exists())
        assertEquals(null, recorder.recording)
    }

    @Test
    fun `an unsaved draft file is deleted when the screen goes away`() = test {
        val mom = addMember("엄마")
        vm.startRecording(mom, ClipSlot.CALL)
        vm.stopRecording()
        val draft = state.recording as RecordingState.Draft

        MembersViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm)

        assertFalse(draft.file.exists())
    }

    @Test
    fun `server error on delete keeps the member and shows a message`() = test {
        val mom = addMember("엄마")
        api.failDeletes = true

        vm.deleteMember(mom)
        runCurrent()

        assertEquals(1, state.members.size)
        assertEquals(R.string.voice_error_server, state.message)
        vm.dismissMessage()
        assertNull(state.message)
    }

    @Test
    fun `deleting a clip removes it from the server and the device`() = test {
        val id = addMember()
        recordAndSave(id, ClipSlot.CALL)
        val clip = state.members.single().clips.single()

        vm.deleteClip(clip)
        runCurrent()

        assertEquals(listOf(clip.serverId), api.deletedClips)
        assertFalse(File(clip.localPath).exists())
        assertTrue(state.members.single().clips.isEmpty())
    }

    @Test
    fun `clip plan mode setting is saved`() = test {
        vm.setClipPlanMode(ClipPlanMode.IN_ORDER)
        runCurrent()
        assertEquals(ClipPlanMode.IN_ORDER, settings.clipPlanMode.value)
        assertEquals(ClipPlanMode.IN_ORDER, state.clipPlanMode)
    }

    @Test
    fun `optional consents are registered and can be changed later`() = test {
        vm.addMember("아빠", isChild = false, voiceConsent = true, guardianConsent = false,
            conversationConsent = true, voiceConversionConsent = false) {}
        runCurrent()
        val dad = state.members.single().member
        assertTrue(dad.conversationConsent)
        assertFalse(dad.voiceConversionConsent)

        vm.setConsents(dad.id, conversationConsent = true, voiceConversionConsent = true)
        runCurrent()
        assertTrue(state.members.single().member.voiceConversionConsent)
        assertEquals(1, api.consentUpdates.size)

        api.failDeletes = true // 서버 오류면 로컬도 바꾸지 않고 안내
        vm.setConsents(dad.id, conversationConsent = false, voiceConversionConsent = false)
        runCurrent()
        assertTrue(state.members.single().member.conversationConsent)
        assertEquals(R.string.voice_error_server, state.message)
    }

    private fun TestScope.recordAndSave(memberId: String, slot: ClipSlot) {
        vm.startRecording(memberId, slot)
        vm.stopRecording()
        vm.saveDraft()
        runCurrent()
    }
}
