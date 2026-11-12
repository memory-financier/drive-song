package com.drivesong.feature.lyrics

import com.drivesong.core.model.SongStyle
import com.drivesong.core.network.dto.EnrichedTripDto
import kotlin.coroutines.cancellation.CancellationException

/** 가사 생성 (설계 §7.2). 실패는 [Result.failure] 로 돌려준다 (코루틴 취소는 그대로 던진다). */
interface LyricsProvider {
    suspend fun generate(input: LyricsInput): Result<Lyrics>
}

/** 서버에서 지명·날씨를 붙인 주행 + 가족 호칭 + 스타일 프리셋 (설계 §7.2 LyricsInput). */
data class LyricsInput(
    val trip: EnrichedTripDto,
    val familyNicknames: List<String>,
    val style: SongStyle,
)

/** [text] = 섹션 태그가 있는 가사. [title] = 노래 제목 후보 (없을 수 있음). */
data class Lyrics(val text: String, val title: String?)

/**
 * 앞의 공급자부터 차례로 시도해 처음 성공한 결과를 쓴다 (설계 §7.2: Gleo 실패·사용 불가 시 서버로 자동 전환).
 * 모두 실패하면 마지막 실패.
 */
class FallbackLyricsProvider(private val providers: List<LyricsProvider>) : LyricsProvider {
    init {
        require(providers.isNotEmpty())
    }

    override suspend fun generate(input: LyricsInput): Result<Lyrics> {
        var result: Result<Lyrics>? = null
        for (provider in providers) {
            result = provider.generate(input)
            if (result.isSuccess) return result
        }
        return checkNotNull(result)
    }
}

/** [runCatching] 과 같지만 코루틴 취소는 잡지 않는다. */
internal inline fun <T> catchingFailures(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
