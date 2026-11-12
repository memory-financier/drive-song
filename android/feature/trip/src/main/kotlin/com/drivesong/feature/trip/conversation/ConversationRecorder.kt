package com.drivesong.feature.trip.conversation

import android.util.Log
import com.drivesong.core.data.conversation.ConversationRepository
import com.drivesong.core.data.settings.SettingsRepository
import com.drivesong.core.data.voice.VoiceRepository
import com.drivesong.feature.trip.TripEvents
import com.drivesong.feature.trip.TripState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 노래 오케스트레이터가 쓰는 대화 담기 API (설계 §7.5 1단계). 구현: [ConversationRecorder]. */
interface ConversationCapture {
    /**
     * 노래 생성 시작: 이 주행의 녹음을 끝내고 남은 조각을 올린다 (올리지 못한 조각은 기기에서 지운다). 대화 담기는 꺼진다.
     * @return 서버에 올린 조각이 하나라도 있으면 true (대화 노래로 만든다).
     */
    suspend fun finish(tripId: String): Boolean

    /** 이 주행은 노래를 만들지 않는다 (실패, 다른 노래를 만드는 중): 대화를 기기·서버에서 지운다. */
    suspend fun discard(tripId: String)
}

/** 마이크 녹음 권한 (RECORD_AUDIO). 테스트는 람다로 바꾼다. */
fun interface MicPermission {
    fun granted(): Boolean
}

/**
 * '대화 담기' 녹음 (설계 §8.3, §7.5 1단계). [com.drivesong.feature.trip.TripRecorderService] 가 켜져 있는 동안 [run] 한다.
 *
 * - 대화 담기가 켜져 있고([ConversationRepository.armed]) 주행 중이면 마이크를 [CHUNK_MS] (5분) 조각으로 녹음한다.
 *   조각마다 녹음을 시작할 때 탑승자 전원의 대화 녹음 동의를 다시 확인한다 (한 명이라도 없으면 녹음하지 않음).
 * - 조각이 끝날 때마다 서버에 올리고 기기에서 지운다. 실패한 조각은 다음 조각 때, 마지막으로 [finish] 때 다시 올린다.
 * - 주행마다 한 번: 노래 생성이 시작되면 [finish] 로 끝나고 그 주행은 다시 녹음하지 않는다. 대화 담기도 꺼진다.
 * - 주행 중에 대화 담기를 끄면 (배너 '끄기', 데이터 삭제) 그 주행의 대화를 기기·서버에서 지운다. 다시 켜면 그 뒤부터 담는다.
 * - 상태는 메모리에만 둔다. 앞 프로세스가 남긴 조각 파일은 [run] 이 시작할 때 지운다.
 */
@Singleton
class ConversationRecorder @Inject constructor(
    private val trips: TripEvents,
    private val repo: ConversationRepository,
    private val voice: VoiceRepository,
    private val settings: SettingsRepository,
    private val recorder: ChunkRecorder,
    private val micPermission: MicPermission,
) : ConversationCapture {

    private class Segment(val tripId: String, val seq: Int, val file: File, val passengers: List<String>)

    private class Chunk(val seq: Int, val file: File, val passengers: List<String>)

    private val mutex = Mutex()
    private var current: Segment? = null
    private val nextSeq = mutableMapOf<String, Int>()
    private val pending = mutableMapOf<String, MutableList<Chunk>>() // 아직 올리지 못한 조각
    private val uploaded = mutableMapOf<String, Int>()
    private val touched = mutableSetOf<String>() // 한 번이라도 녹음한 주행
    private val finished = mutableSetOf<String>() // 더 녹음하지 않는 주행 (노래 시작·버림)

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() {
        mutex.withLock { repo.deleteLocalExcept(pending.keys + listOfNotNull(current?.tripId)) }
        coroutineScope {
            // 주행 중에 꺼지면 그 주행의 대화를 지운다 (노래를 시작한 주행은 이미 finished)
            launch { repo.armed.collect { armed -> if (!armed) discardUnfinished() } }
            combine(trips.state, repo.armed) { state, armed -> (state as? TripState.Recording)?.tripId?.takeIf { armed } }
                .distinctUntilChanged()
                .collectLatest { tripId -> if (tripId != null) record(tripId) }
        }
    }

    override suspend fun finish(tripId: String): Boolean {
        val any = mutex.withLock {
            finished += tripId
            if (current?.tripId == tripId) stopSegment()
            for (attempt in 1..FINISH_ATTEMPTS) {
                if (pending[tripId].isNullOrEmpty()) break
                if (attempt > 1) delay(RETRY_DELAY_MS)
                uploadPending(tripId)
            }
            pending.remove(tripId)
            repo.deleteLocal(tripId) // 올리지 못한 대화는 기기에 남기지 않는다
            (uploaded.remove(tripId) ?: 0) > 0
        }
        repo.setArmed(false) // 주행마다 한 번 (설계 §8.3)
        return any
    }

    override suspend fun discard(tripId: String) = clear(tripId, finish = true)

    private suspend fun discardUnfinished() {
        val trips = mutex.withLock { touched - finished }
        trips.forEach { clear(it, finish = false) }
    }

    private suspend fun clear(tripId: String, finish: Boolean) {
        val onServer = mutex.withLock {
            if (finish) finished += tripId
            if (current?.tripId == tripId) stopSegment()
            pending.remove(tripId)
            uploaded.remove(tripId)
            repo.deleteLocal(tripId)
            touched.remove(tripId)
        }
        if (!onServer) return
        try {
            repo.deleteOnServer(tripId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not delete the conversation of $tripId on the server", e) // [확인 필요] 서버 보관 기한
        }
    }

    private suspend fun record(tripId: String) {
        try {
            var recording = mutex.withLock { startSegment(tripId) }
            while (recording) {
                delay(CHUNK_MS)
                // 다음 조각을 먼저 시작하고 올린다 (올리는 동안에도 대화가 끊기지 않게)
                recording = mutex.withLock {
                    stopSegment()
                    startSegment(tripId).also { uploadPending(tripId) }
                }
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { stopSegment() } }
        }
    }

    /** 녹음할 수 있으면 새 조각을 시작한다. 잠금 안에서 부른다. */
    private suspend fun startSegment(tripId: String): Boolean {
        if (tripId in finished || current != null || !micPermission.granted()) return false
        val passengers = settings.selectedPassengerIds.first().sorted()
        val members = voice.members.first().associateBy { it.id }
        if (passengers.isEmpty() || passengers.any { members[it]?.conversationConsent != true }) {
            Log.i(TAG, "not every passenger agreed to conversation recording; not recording $tripId")
            return false
        }
        val seq = nextSeq[tripId] ?: 0
        val file = repo.chunkFile(tripId, seq)
        try {
            recorder.start(file)
        } catch (e: Exception) {
            Log.w(TAG, "microphone unavailable", e)
            file.delete()
            return false
        }
        nextSeq[tripId] = seq + 1
        touched += tripId
        current = Segment(tripId, seq, file, passengers)
        repo.setRecording(true)
        return true
    }

    /** 잠금 안에서 부른다. */
    private fun stopSegment() {
        val segment = current ?: return
        current = null
        repo.setRecording(false)
        if (recorder.stop()) {
            pending.getOrPut(segment.tripId) { mutableListOf() } += Chunk(segment.seq, segment.file, segment.passengers)
        } else {
            segment.file.delete() // 너무 짧아 데이터가 없음
        }
    }

    /** 순번 순서로 올린다. 하나가 실패하면 (대개 네트워크) 나머지는 다음에. 잠금 안에서 부른다. */
    private suspend fun uploadPending(tripId: String) {
        val chunks = pending[tripId] ?: return
        while (chunks.isNotEmpty()) {
            val chunk = chunks.first()
            try {
                repo.upload(tripId, chunk.seq, chunk.passengers, chunk.file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "upload of $tripId/${chunk.seq} failed", e)
                return
            }
            chunks.removeAt(0)
            uploaded[tripId] = (uploaded[tripId] ?: 0) + 1
        }
    }

    companion object {
        private const val TAG = "ConversationRecorder"

        /** 조각 길이 (설계 §7.5: 5분, AAC 모노 16kHz 32kbps ≈ 1.2MB). */
        const val CHUNK_MS = 5 * 60_000L
        const val FINISH_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L
    }
}
