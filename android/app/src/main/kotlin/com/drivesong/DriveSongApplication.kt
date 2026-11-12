package com.drivesong

import android.app.Application
import com.drivesong.feature.player.SongPlayer
import com.drivesong.light.LightShowDirector
import com.drivesong.feature.song.SongOrchestrator
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DriveSongApplication : Application() {

    @Inject lateinit var songOrchestrator: Lazy<SongOrchestrator>

    @Inject lateinit var songPlayer: Lazy<SongPlayer>

    @Inject lateinit var lightShow: Lazy<LightShowDirector>

    /**
     * 노래 생성 오케스트레이터 (설계 §7.1) 를 프로세스 수명 동안 돌린다. 화면이 없어도 계속 동작한다.
     *
     * 주행 기록 Foreground Service 스코프가 아니라 여기서 띄우는 이유:
     * - 주행 이벤트는 replay 가 없다. Application.onCreate 는 같은 프로세스의 서비스보다 먼저 실행되고,
     *   그래도 놓친 진행 중 주행은 SongOrchestrator.run 이 구독 시점의 TripState 로 이어받는다.
     * - 도착 후 사용자가 주행 기록을 꺼도(서비스 종료) 만들던 노래는 끝까지 받아야 한다.
     * - 모듈 방향이 feature:song → feature:trip 이라 서비스가 오케스트레이터를 직접 가질 수 없다.
     * 프로세스를 살려 두는 것은 주행 기록 Foreground Service 다 (주행이 끝나도 사용자가 끌 때까지 대기 상태로 남는다).
     * 주행 이벤트가 없을 때 오케스트레이터는 구독만 하고 쉬므로 CPU 를 쓰지 않는다 (설계 §2).
     * 콜드 스타트를 가볍게 하려고 객체 생성(Lazy.get)도 메인 스레드 밖에서 한다.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            val orchestrator = songOrchestrator.get()
            // 도착 시 자동 재생 (설계 §7.1). autoPlay 는 replay 가 없으므로 run 보다 먼저 구독한다.
            launch(start = CoroutineStart.UNDISPATCHED) {
                orchestrator.autoPlay.collect { songPlayer.get().play(listOf(it)) }
            }
            orchestrator.run()
        }
        // 노래에 맞춘 실내 조명 (설계 §6.5). 재생 상태가 바뀔 때만 일한다.
        appScope.launch { lightShow.get().run() }
    }
}
