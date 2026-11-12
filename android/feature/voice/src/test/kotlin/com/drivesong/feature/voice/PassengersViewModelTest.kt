package com.drivesong.feature.voice

import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.conversation.ConversationRepository
import com.drivesong.core.data.voice.VoiceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PassengersViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher().also { Dispatchers.setMain(it) }
    private val db = inMemoryDatabase()
    private val settings = FakeSettings()
    private val api = FakeApi()
    private val repo = VoiceRepository(db.voiceDao(), api, settings, ApplicationProvider.getApplicationContext())
    private val conversation = ConversationRepository(api, ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `starts from the last selection and saves toggles`() = runTest(dispatcher) {
        val mom = repo.addMember("엄마", false, voiceConsent = true, guardianConsent = false).id
        val kid = repo.addMember("첫째", true, voiceConsent = true, guardianConsent = true).id
        settings.selectedPassengerIds.value = setOf(mom) // 지난 주행의 선택
        val vm = PassengersViewModel(repo, settings, conversation)
        val collectors = backgroundScope.launch {
            launch { vm.members.collect {} }
            launch { vm.selected.collect {} }
        }
        runCurrent()

        assertEquals(listOf("엄마", "첫째"), vm.members.value.map { it.nickname })
        assertEquals(setOf(mom), vm.selected.value)

        vm.toggle(kid)
        runCurrent()
        assertEquals(setOf(mom, kid), settings.selectedPassengerIds.value)

        vm.toggle(mom)
        runCurrent()
        assertEquals(setOf(kid), settings.selectedPassengerIds.value)
        assertEquals(setOf(kid), vm.selected.value)
        collectors.cancel()
    }

    @Test
    fun `conversation capture can be turned on only when every passenger agreed`() = runTest(dispatcher) {
        val mom = repo.addMember("엄마", false, voiceConsent = true, guardianConsent = false, conversationConsent = true).id
        val dad = repo.addMember("아빠", false, voiceConsent = true, guardianConsent = false).id
        settings.selectedPassengerIds.value = setOf(mom, dad)
        val vm = PassengersViewModel(repo, settings, conversation)
        val collector = backgroundScope.launch { vm.canCapture.collect {} }
        runCurrent()

        assertFalse(vm.canCapture.value) // 아빠가 대화 녹음에 동의하지 않았다
        vm.setCapture(true)
        runCurrent()
        assertFalse(conversation.armed.value)

        vm.toggle(dad) // 엄마만 탄다
        runCurrent()
        assertTrue(vm.canCapture.value)
        vm.setCapture(true)
        runCurrent()
        assertTrue(conversation.armed.value)

        vm.toggle(dad) // 동의하지 않은 사람이 다시 타면 바로 꺼진다
        runCurrent()
        assertFalse(conversation.armed.value)
        collector.cancel()
    }
}
