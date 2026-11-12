import re
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol


class Style(StrEnum):
    """스타일 프리셋 (설계 §7.3). 사용자는 프리셋만 고른다."""

    KIDS = "kids"  # 동요
    CITY_POP = "city_pop"  # 시티팝
    ACOUSTIC = "acoustic"  # 어쿠스틱
    ROAD_TRIP = "road_trip"  # 신나는 로드트립


# 자연어 스타일 프롬프트 (minimax, mureka 공용). acestep 은 태그 형식을 따로 쓴다.
STYLE_PROMPTS: dict[Style, str] = {
    Style.KIDS: (
        "Korean children's song, bright and cheerful, simple catchy melody, "
        "glockenspiel, piano, hand claps, playful kids choir vocal, 110 BPM"
    ),
    Style.CITY_POP: (
        "Korean city pop, 80s retro, groovy synth bass, electric piano, "
        "warm female vocal, nostalgic urban drive, 104 BPM"
    ),
    Style.ACOUSTIC: (
        "Korean acoustic pop, warm acoustic guitar, soft piano, "
        "gentle male vocal, cozy and heartfelt, 90 BPM"
    ),
    Style.ROAD_TRIP: (
        "Korean upbeat road trip pop rock, energetic drums, bright electric guitar, "
        "sing-along chorus, joyful vocal, 128 BPM"
    ),
}


class MusicStatus(StrEnum):
    PENDING = "pending"
    SUCCEEDED = "succeeded"
    FAILED = "failed"


@dataclass(frozen=True)
class MusicResult:
    status: MusicStatus
    audio_url: str | None = None  # http(s):// 또는 file:// (fake)
    error: str | None = None
    # 첫 후렴 구간(초). 공급자가 주면 믹서가 CALL 클립 위치로 쓴다 (§7.4). 없으면 None.
    chorus_start_sec: float | None = None
    chorus_end_sec: float | None = None


class MusicProvider(Protocol):
    name: str

    async def submit(self, lyrics: str, style: str, duration_sec: int) -> str:
        """생성 요청을 보내고 공급자 잡 id 를 반환한다."""
        ...

    async def poll(self, provider_job_id: str) -> MusicResult: ...


class ProviderError(Exception):
    """공급자 응답이 예상과 다를 때."""


def title_case_section_tags(lyrics: str) -> str:
    """[verse] -> [Verse]. 가사 생성은 소문자 태그(§7.2), minimax/mureka 문서 예시는 첫 글자 대문자.
    [확인 필요] 공급자가 태그 대소문자를 구분하는지 문서에 명시되어 있지 않다."""
    return re.sub(r"\[([A-Za-z][A-Za-z -]*)\]", lambda m: f"[{m.group(1).title()}]", lyrics)
