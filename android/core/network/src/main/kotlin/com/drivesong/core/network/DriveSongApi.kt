package com.drivesong.core.network

import com.drivesong.core.model.TripSummary
import com.drivesong.core.network.dto.ClipDto
import com.drivesong.core.network.dto.ConversationChunkDto
import com.drivesong.core.network.dto.DeviceDto
import com.drivesong.core.network.dto.EnrichedTripDto
import com.drivesong.core.network.dto.LyricsDto
import com.drivesong.core.network.dto.LyricsRequest
import com.drivesong.core.network.dto.MemberConsentUpdate
import com.drivesong.core.network.dto.MemberDto
import com.drivesong.core.network.dto.MemberRequest
import com.drivesong.core.network.dto.SongJobDto
import com.drivesong.core.network.dto.SongJobRequest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * 자체 서버 API v1 (설계 §9). `POST v1/devices` 외에는 [com.drivesong.core.network.auth.AuthInterceptor]가
 * `Authorization: Bearer <token>` 을 붙인다. 실패 응답은 retrofit2.HttpException 으로 던져진다.
 * `/legal/privacy`, `/legal/location` 은 브라우저로 여는 HTML 페이지라 여기 두지 않는다.
 */
interface DriveSongApi {

    @POST("v1/devices")
    suspend fun registerDevice(): DeviceDto

    @POST("v1/members")
    suspend fun createMember(@Body body: MemberRequest): MemberDto

    @DELETE("v1/members/{id}")
    suspend fun deleteMember(@Path("id") memberId: String)

    /** 선택 동의 변경 (설계 §8.3, §9.1). */
    @PATCH("v1/members/{id}")
    suspend fun updateMember(@Path("id") memberId: String, @Body body: MemberConsentUpdate): MemberDto

    /**
     * 대화 녹음 조각 (설계 §7.5, §9.1). multipart: seq, passengers(탑승자 구성원 id 마다 하나), audio.
     * 서버도 탑승자 전원의 대화 녹음 동의를 확인한다 (없으면 400). 그 주행의 노래가 이미 시작됐으면 409.
     */
    @Multipart
    @POST("v1/trips/{tripId}/conversation")
    suspend fun uploadConversation(
        @Path("tripId") tripId: String,
        @Part("seq") seq: RequestBody,
        @Part passengers: List<MultipartBody.Part>,
        @Part audio: MultipartBody.Part,
    ): ConversationChunkDto

    /** 올린 대화 조각을 지운다 ('대화 담기' 끄기, 노래를 만들지 않은 주행). 없어도 204. */
    @DELETE("v1/trips/{tripId}/conversation")
    suspend fun deleteConversation(@Path("tripId") tripId: String)

    /** multipart: memberId, slot(INTRO/CALL/OUTRO), audio. 클립당 최대 5초 (설계 §8). */
    @Multipart
    @POST("v1/clips")
    suspend fun uploadClip(
        @Part("memberId") memberId: RequestBody,
        @Part("slot") slot: RequestBody,
        @Part audio: MultipartBody.Part,
    ): ClipDto

    @DELETE("v1/clips/{id}")
    suspend fun deleteClip(@Path("id") clipId: String)

    @POST("v1/trips/enrich")
    suspend fun enrichTrip(@Body trip: TripSummary): EnrichedTripDto

    /** 가사 생성 fallback (설계 §7.2). */
    @POST("v1/lyrics")
    suspend fun generateLyrics(@Body body: LyricsRequest): LyricsDto

    @POST("v1/song-jobs")
    suspend fun createSongJob(@Body body: SongJobRequest): SongJobDto

    @GET("v1/song-jobs/{id}")
    suspend fun getSongJob(@Path("id") jobId: String): SongJobDto

    @Streaming
    @GET("v1/songs/{id}/audio")
    suspend fun downloadSongAudio(@Path("id") songId: String): ResponseBody

    /** 설정 > 데이터 삭제: 이 디바이스의 모든 노래와 (진행 중인 것을 포함한) 잡을 서버에서 지운다. */
    @DELETE("v1/songs")
    suspend fun deleteAllSongs()
}
