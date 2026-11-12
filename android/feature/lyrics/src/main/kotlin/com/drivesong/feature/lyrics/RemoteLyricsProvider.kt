package com.drivesong.feature.lyrics

import com.drivesong.core.network.DriveSongApi
import com.drivesong.core.network.dto.LyricsRequest
import javax.inject.Inject

/** 서버 경로: POST /v1/lyrics → 서버의 외부 LLM (설계 §7.2, §9). */
class RemoteLyricsProvider @Inject constructor(private val api: DriveSongApi) : LyricsProvider {
    override suspend fun generate(input: LyricsInput): Result<Lyrics> = catchingFailures {
        val dto = api.generateLyrics(LyricsRequest(input.trip, input.familyNicknames, input.style.wire))
        Lyrics(dto.lyrics, dto.title)
    }
}
