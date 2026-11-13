package com.drivesong.feature.voice.demo

import android.content.Context
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.model.ClipSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

/**
 * 데모 가족 (설계 T19): `family.json` 의 구성원을 모든 동의(아이는 보호자 동의)와 함께 등록하고, 등록 클립(음성 ID)을
 * 올린 뒤 이번 주행 탑승자로 고른다. 데모 대화(feature:trip DemoChunkRecorder)와 같은 화자라 서버가 누가 말했는지
 * 알아보고 가족이 노래를 나눠 부른다.
 *
 * 가족이 직접 녹음한 데모(`assets/demo-family/`, git 밖 android/demo-private 에서 앱 모듈이 넣음)가 있으면 그것을,
 * 없으면 합성 음성 데모(`assets/demo/`: 공개 음성 Zeroth-Korean CC BY 4.0 화자를 본떠 CosyVoice 3 로 합성한 가상의 가족)를 쓴다.
 * 같은 호칭의 가족이 있으면 지우고 새로 만든다 (화면에서 확인을 받는다). 서버 오류는 예외 (다시 누르면 처음부터).
 */
class DemoFamilyLoader @Inject constructor(
    private val voice: VoiceRepository,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) {
    suspend fun load() {
        val dir = demoDir(context)
        val members = withContext(Dispatchers.IO) { parse(context.assets.open("$dir/family.json").bufferedReader().use { it.readText() }) }
        val existing = voice.members.first()
        val ids = members.map { member ->
            existing.filter { it.nickname == member.nickname }.forEach { voice.deleteMember(it.id) }
            val added = voice.addMember(
                member.nickname, member.isChild, voiceConsent = true, guardianConsent = member.isChild,
                conversationConsent = true, voiceConversionConsent = true,
            )
            val file = voice.newRecordingFile()
            withContext(Dispatchers.IO) {
                context.assets.open("$dir/${member.enroll}").use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            // 녹음 시각 0: 데모 시계(시나리오 시각)와 실제 시각이 달라 "이번 주행 녹음" 으로 잘못 치지 않게.
            // 대화 노래의 세 슬롯은 대화 속 한마디로 채워진다 (설계 §7.5)
            voice.upload(voice.addClip(added.id, ClipSlot.CALL, file, recordedAtMs = 0))
            added.id
        }
        settings.setSelectedPassengerIds(ids.toSet())
    }

    private class DemoMember(val nickname: String, val isChild: Boolean, val enroll: String)

    companion object {
        const val FAMILY_DIR = "demo-family"
        const val PUBLIC_DIR = "demo"

        /** 가족이 직접 녹음한 데모가 APK 에 있으면 그 폴더, 아니면 합성 음성 데모. */
        fun demoDir(context: Context): String =
            if (context.assets.list(FAMILY_DIR)?.contains("family.json") == true) FAMILY_DIR else PUBLIC_DIR

        private fun parse(json: String): List<DemoMember> {
            val array = JSONObject(json).getJSONArray("members")
            return (0 until array.length()).map { i ->
                val m = array.getJSONObject(i)
                DemoMember(m.getString("nickname"), m.getBoolean("isChild"), m.getString("enroll"))
            }
        }
    }
}
