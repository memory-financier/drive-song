package com.drivesong.feature.voice.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.model.ClipSlot
import com.drivesong.feature.voice.FakeApi
import com.drivesong.feature.voice.FakeSettings
import com.drivesong.feature.voice.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** T19: 데모 가족 = 엄마·아빠·첫째, 모든 동의, 공개 음성 등록 클립, 이번 주행 탑승자. */
@RunWith(RobolectricTestRunner::class)
class DemoFamilyLoaderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = inMemoryDatabase()
    private val api = FakeApi()
    private val settings = FakeSettings()
    private val voice = VoiceRepository(db.voiceDao(), api, settings, context)
    private val loader = DemoFamilyLoader(voice, settings, context)

    @After
    fun tearDown() = db.close()

    @Test
    fun `registers the demo family with every consent and selects them as passengers`() = runTest {
        val oldMom = voice.addMember("엄마", false, voiceConsent = true, guardianConsent = false).id // 같은 호칭은 바꾼다
        val other = voice.addMember("둘째", true, voiceConsent = true, guardianConsent = true).id

        loader.load()

        val members = voice.members.first()
        assertEquals(listOf("둘째", "엄마", "아빠", "첫째"), members.map { it.nickname })
        assertEquals(listOf(oldMom), api.deletedMembers)
        val demo = members.filter { it.id != other }
        assertTrue(demo.all { it.conversationConsent && it.voiceConversionConsent && it.voiceConsent })
        assertTrue(demo.single { it.nickname == "첫째" }.let { it.isChild && it.guardianConsent })
        assertEquals(demo.map { it.id to ClipSlot.CALL }, api.uploads) // 등록 클립 = 음성 ID
        val clips = voice.clips.first()
        assertTrue(clips.all { java.io.File(it.localPath).length() > 10_000 })
        assertTrue(clips.all { it.recordedAtMs == 0L }) // 데모 시계와 무관하게 "이번 주행 녹음" 이 아니다
        assertEquals(demo.map { it.id }.toSet(), settings.selectedPassengerIds.value)
    }
}
