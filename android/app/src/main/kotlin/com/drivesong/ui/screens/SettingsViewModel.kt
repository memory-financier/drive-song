package com.drivesong.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivesong.core.data.erase.AllDataDeleter
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.model.SongStyle
import com.drivesong.core.vehicle.demo.DemoDriveController
import com.drivesong.core.vehicle.demo.DemoScenario
import com.drivesong.feature.voice.demo.DemoFamilyLoader
import com.drivesong.feature.player.SongPlayer
import com.drivesong.feature.song.SongOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DeleteStatus { IDLE, DELETING, DONE, FAILED }

/** 설정 화면의 데모 모드·목적지 프리셋·노래 스타일·자동 재생·데이터 삭제 (설계 §6.1, §6.4, §7.1, §7.3, §8.1, §10). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val demoDrive: DemoDriveController,
    private val deleter: AllDataDeleter,
    private val player: SongPlayer,
    private val songOrchestrator: SongOrchestrator,
    private val demoFamily: DemoFamilyLoader,
) : ViewModel() {

    private val _deleteStatus = MutableStateFlow(DeleteStatus.IDLE)
    val deleteStatus: StateFlow<DeleteStatus> = _deleteStatus.asStateFlow()

    val demoMode: StateFlow<Boolean> = settings.demoMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = false)

    val playingScenario: StateFlow<DemoScenario?> = demoDrive.playing

    val destinationPresetId: StateFlow<String?> = settings.destinationPresetId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    val songStyle: StateFlow<SongStyle> = settings.songStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = SongStyle.CITY_POP)

    val autoPlay: StateFlow<Boolean> = settings.autoPlay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = true)

    /** 끄면 재생 중인 시나리오도 멈춰 시계가 시스템 시각으로 돌아간다. */
    fun setDemoMode(enabled: Boolean) {
        if (!enabled) demoDrive.stop()
        viewModelScope.launch { settings.setDemoMode(enabled) }
    }

    fun play(scenario: DemoScenario) = demoDrive.start(scenario)

    private val _demoFamilyStatus = MutableStateFlow(DeleteStatus.IDLE)

    /** 데모 가족 불러오기 상태 (IDLE · DELETING=불러오는 중 · DONE · FAILED). */
    val demoFamilyStatus: StateFlow<DeleteStatus> = _demoFamilyStatus.asStateFlow()

    /** 데모 가족 (T19): 엄마·아빠·첫째를 공개 음성 등록 클립과 모든 동의로 등록하고 탑승자로 고른다. */
    fun loadDemoFamily() {
        if (_demoFamilyStatus.value == DeleteStatus.DELETING) return
        _demoFamilyStatus.value = DeleteStatus.DELETING
        viewModelScope.launch {
            _demoFamilyStatus.value = try {
                demoFamily.load()
                DeleteStatus.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeleteStatus.FAILED
            }
        }
    }

    fun stop() = demoDrive.stop()

    fun setDestinationPreset(id: String?) {
        viewModelScope.launch { settings.setDestinationPresetId(id) }
    }

    fun setSongStyle(style: SongStyle) {
        viewModelScope.launch { settings.setSongStyle(style) }
    }

    fun setAutoPlay(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoPlay(enabled) }
    }

    /** 노래에 맞춰 실내 조명 바꾸기 (설계 §6.5). */
    val ambientLightShow: StateFlow<Boolean> = settings.ambientLightShow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = true)

    fun setAmbientLightShow(enabled: Boolean) {
        viewModelScope.launch { settings.setAmbientLightShow(enabled) }
    }

    /**
     * 재생을 멈추고(지울 노래 파일을 놓도록), 만들던 노래를 취소한 뒤(지운 잡을 재시도로 다시 만들지 않게) 모든 데이터를 지운다.
     * 서버 삭제가 실패하면 FAILED 로 다시 시도하게 한다.
     */
    fun deleteAllData() {
        if (_deleteStatus.value == DeleteStatus.DELETING) return
        _deleteStatus.value = DeleteStatus.DELETING
        player.stop()
        viewModelScope.launch {
            _deleteStatus.value = try {
                songOrchestrator.clearForDataDeletion { deleter.deleteAll() }
                DeleteStatus.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeleteStatus.FAILED
            }
        }
    }
}
