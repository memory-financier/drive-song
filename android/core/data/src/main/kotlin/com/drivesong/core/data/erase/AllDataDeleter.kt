package com.drivesong.core.data.erase

import com.drivesong.core.data.conversation.ConversationRepository
import com.drivesong.core.data.song.SongRepository
import com.drivesong.core.data.trip.TripRepository
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.core.network.DriveSongApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 설정 > 데이터 삭제 (설계 §8.1, §10).
 * 1. 가족 구성원: DELETE /v1/members/{id} (서버가 클립도 지운다) → 로컬 클립 파일·행.
 * 2. 노래: DELETE /v1/songs (서버의 노래·잡·가사) → 로컬 노래 (행 + 오디오 파일).
 * 3. 주행 기록 (기록 중인 주행만 남긴다).
 * 4. 클립 행이 없는 녹음 파일 (저장하지 않은 초안, 프로세스 종료로 남은 파일).
 * 5. 대화 담기 (설계 §8.3): 먼저 끄고, 기기의 조각 파일을 지운다 (서버 조각은 2의 DELETE /v1/songs 가 지운다).
 * 서버 삭제가 실패한 구성원은 다시 시도할 수 있게 로컬에 남기고, 나머지를 지운 뒤 첫 실패를 다시 던진다.
 * 서버 노래 삭제는 디바이스 단위라 실패해도 로컬 노래는 지운다 (다시 시도하면 다시 요청한다).
 */
class AllDataDeleter @Inject constructor(
    private val voice: VoiceRepository,
    private val songs: SongRepository,
    private val trips: TripRepository,
    private val api: DriveSongApi,
    private val conversation: ConversationRepository,
) {
    suspend fun deleteAll() {
        conversation.setArmed(false)
        var failure: Exception? = null
        for (member in voice.members.first()) {
            try {
                voice.deleteMember(member.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        try {
            api.deleteAllSongs()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (failure == null) failure = e
        }
        songs.deleteAll()
        trips.deleteTripsExceptRecording()
        voice.deleteOrphanFiles()
        conversation.deleteAllLocal()
        failure?.let { throw it }
    }
}
