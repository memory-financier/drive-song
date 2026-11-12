"""대화 분석·목소리 변환 공급자 (설계 §7.5 2·3·5·7·8·9단계).

서버는 이 두 인터페이스로만 부른다. 구현: voicelab (같은 PC 의 GPU 서비스, 파일 경로로 주고받음) | fake.
"""

from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class SpokenSegment:
    start: float
    end: float
    member_id: str | None  # 등록 클립과 맞지 않으면 None ('모르는 사람', 가사·한마디·참고 목소리에 쓰지 않음)
    text: str


@dataclass(frozen=True)
class MemberVoice:
    member_id: str
    voiced_sec: float = 0.0
    moment_path: Path | None = None  # 1~4.5초 한마디 (out_dir 아래)
    moment_text: str | None = None
    ref_path: Path | None = None  # 참고 목소리 ≤30초. 10초 미만이면 None
    ref_sec: float = 0.0
    ref_f0_hz: float | None = None  # 말소리 음높이 중앙값 (옥타브 맞춤)


@dataclass(frozen=True)
class Analysis:
    duration_sec: float
    segments: list[SpokenSegment] = field(default_factory=list)
    members: list[MemberVoice] = field(default_factory=list)


class ConversationAnalyzer(Protocol):
    name: str

    async def analyze(self, audio_paths: list[Path], enroll: dict[str, list[Path]], out_dir: Path) -> Analysis:
        """대화 조각(시간 순서)과 구성원별 등록 클립으로 받아 적기·화자 식별·한마디·참고 목소리 고르기."""
        ...


@dataclass(frozen=True)
class Singer:
    member_id: str
    ref_path: Path
    ref_f0_hz: float | None = None


@dataclass(frozen=True)
class Sung:
    out_path: Path
    singers: list[str]  # 실제로 구절을 부른 구성원 id
    # 노래하는 구간 (시작, 끝) 초, 원곡 기준. 믹서가 CALL 한마디를 노래 없는 곳에 넣는다 (§7.4). 모르면 빈 목록
    vocal_phrases: list[tuple[float, float]] = field(default_factory=list)


class VoiceConverter(Protocol):
    name: str

    async def sing(self, song_path: Path, singers: list[Singer], out_path: Path, work_dir: Path) -> Sung:
        """AI 가수 노래를 가족 목소리로 바꿔 구절마다 나눠 부르게 한다."""
        ...
