package com.drivesong.feature.lyrics.di

import com.drivesong.feature.lyrics.FallbackLyricsProvider
import com.drivesong.feature.lyrics.LyricsProvider
import com.drivesong.feature.lyrics.RemoteLyricsProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object LyricsModule {
    /**
     * 설계 §7.2 1순위는 Gleo AI LLM SDK 이지만 SDK(Pleos 개발자 계정 필요)를 확보하지 못해 구현하지 않았다.
     * [확인 필요] 공모전 기간 사용 승인, 서버 모드 registerApp 필요 여부 (설계 §13-1).
     * SDK 를 확보하면 GleoLlmLyricsProvider 를 만들어 이 목록의 맨 앞에 넣는다.
     */
    @Provides
    fun lyricsProvider(remote: RemoteLyricsProvider): LyricsProvider = FallbackLyricsProvider(listOf(remote))
}
