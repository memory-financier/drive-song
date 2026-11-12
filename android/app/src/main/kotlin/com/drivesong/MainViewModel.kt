package com.drivesong

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivesong.core.data.conversation.ConversationRepository
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.vehicle.restriction.UxRestrictionsMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    restrictionsMonitor: UxRestrictionsMonitor,
    private val settings: SettingsRepository,
    private val conversation: ConversationRepository,
) : ViewModel() {

    /** 첫 값이 오기 전에는 안전하게 제한 상태로 본다. */
    val isRestricted: StateFlow<Boolean> = restrictionsMonitor.isRestricted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = true)

    val restrictionSimulation: StateFlow<Boolean> = settings.simulateDrivingRestriction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = false)

    /** 대화 담기 배너 (설계 §8.3): 켜져 있으면 모든 화면 위에, 녹음 중이면 "대화 담는 중". */
    val conversationOn: StateFlow<Boolean> = conversation.armed
    val conversationRecording: StateFlow<Boolean> = conversation.recording

    fun setRestrictionSimulation(enabled: Boolean) {
        viewModelScope.launch { settings.setSimulateDrivingRestriction(enabled) }
    }

    /** 배너 '끄기': 녹음을 멈추고 이번 주행의 대화를 기기·서버에서 지운다 (ConversationRecorder). */
    fun turnOffConversation() = conversation.setArmed(false)
}
