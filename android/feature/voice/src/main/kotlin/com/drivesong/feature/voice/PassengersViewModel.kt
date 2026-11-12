package com.drivesong.feature.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivesong.core.data.conversation.ConversationRepository
import com.drivesong.core.data.db.MemberEntity
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.data.voice.VoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 이번 주행 탑승자 (설계 §10). 선택은 바로 저장되고 다음 주행의 기본값이 된다.
 * TripRecorder 가 주행 시작 시 이 선택을 읽어 TripSummary.passengers 로 쓴다.
 * '대화 담기' (설계 §8.3): 탑승자가 있고 모두 대화 녹음에 동의했을 때만 켤 수 있다. 조건이 깨지면 바로 끈다.
 */
@HiltViewModel
class PassengersViewModel @Inject constructor(
    repo: VoiceRepository,
    private val settings: SettingsRepository,
    private val conversation: ConversationRepository,
) : ViewModel() {

    val members: StateFlow<List<MemberEntity>> = repo.members
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = emptyList())

    val selected: StateFlow<Set<String>> = settings.selectedPassengerIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = emptySet())

    private val eligible = combine(repo.members, settings.selectedPassengerIds) { members, selected ->
        selected.isNotEmpty() && selected.all { id -> members.find { it.id == id }?.conversationConsent == true }
    }

    /** 대화 담기를 켤 수 있다. */
    val canCapture: StateFlow<Boolean> = eligible
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = false)

    val captureOn: StateFlow<Boolean> = conversation.armed

    init {
        viewModelScope.launch { eligible.collect { if (!it) conversation.setArmed(false) } }
    }

    fun toggle(memberId: String) {
        viewModelScope.launch {
            val current = settings.selectedPassengerIds.first()
            settings.setSelectedPassengerIds(if (memberId in current) current - memberId else current + memberId)
        }
    }

    fun setCapture(on: Boolean) {
        viewModelScope.launch { conversation.setArmed(on && eligible.first()) }
    }
}
