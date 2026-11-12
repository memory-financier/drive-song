"""가사 생성 LLM 공통 부분 (설계 §7.2, §7.5 4단계): 입력, 프롬프트, 출력 검사, 1회 재요청."""

import logging
import re
from dataclasses import dataclass, field
from typing import Protocol

from app.providers.geo.weather import Weather
from app.providers.music.base import Style

STYLE_KO: dict[Style, str] = {
    Style.KIDS: "동요",
    Style.CITY_POP: "시티팝",
    Style.ACOUSTIC: "어쿠스틱",
    Style.ROAD_TRIP: "신나는 로드트립",
}

TIME_OF_DAY_KO = {
    "DAWN": "새벽",
    "MORNING": "아침",
    "AFTERNOON": "오후",
    "EVENING": "저녁",
    "NIGHT": "밤",
}

# §7.2 구조: [verse] 4줄 / [chorus] 4줄. 60~90초 곡에서 끝까지 불리는 길이 (2026-11-11 ACE-Step 시험:
# 네 섹션은 90초에도 마지막 후렴을 못 부름, 두 섹션은 75초에 다 부름)
SECTIONS = ("verse", "chorus")
LINES_PER_SECTION = 4
UNKNOWN = "알 수 없음"
ATTEMPTS = 2  # 처음 1회 + 재시도 1회
# 프롬프트에 넣는 대화 상한 (글자). 로컬 LLM 문맥 길이 안에 들어가게 (§7.5)
MAX_CONVERSATION_CHARS = 2000

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class LyricsInput:
    origin_name: str | None
    destination_name: str | None
    place_names: list[str]
    time_of_day: str  # DAWN | MORNING | AFTERNOON | EVENING | NIGHT
    weather: Weather | None
    duration_min: int
    distance_km: float
    style: Style
    family_nicknames: list[str] = field(default_factory=list)
    # 차 안 대화 "호칭: 말" (시간 순서, §7.5). 외부 LLM 에는 넣지 않는다 (§8.3)
    conversation: list[str] = field(default_factory=list)


class LlmProvider(Protocol):
    name: str
    # 외부 서비스로 보내는 공급자인가. 대화는 외부로 보내지 않는다 (§8.3). 이 값이 없는 공급자는 외부로 본다
    external: bool

    async def write_lyrics(self, inp: LyricsInput) -> str:
        """가사 원문을 돌려준다 (검사 전). 응답을 받지 못하면 LlmError."""
        ...


class LlmError(Exception):
    """LLM 이 가사를 돌려주지 않았을 때 (거절, 빈 응답 등)."""


def weather_text(weather: Weather | None) -> str:
    return f"{weather.description}, {round(weather.temperature_c)}°C" if weather else UNKNOWN


def fit_conversation(lines: list[str], max_chars: int = MAX_CONVERSATION_CHARS) -> list[str]:
    """길면 시간 순서를 지키며 고르게 솎아 max_chars 안으로 줄인다."""
    def size(ls: list[str]) -> int:
        return sum(len(line) + 1 for line in ls)

    if size(lines) <= max_chars:
        return lines
    keep = max(1, len(lines) * max_chars // size(lines))
    picked = [lines[i * len(lines) // keep] for i in range(keep)]
    while picked and size(picked) > max_chars:
        picked.pop()
    return picked


def build_prompt(inp: LyricsInput) -> str:
    """§7.2 프롬프트 템플릿. 스타일은 고른 프리셋 이름으로 채운다. 대화가 있으면 대화 부분과 규칙을 더한다 (§7.5)."""
    family = ", ".join(inp.family_nicknames) or "없음"
    # 함께 탄 가족이 없으면 호칭 규칙은 지킬 수 없으므로 뺀다
    name_rule = "지명 최소 2개, 가족 호칭 최소 1번 포함" if inp.family_nicknames else "지명 최소 2개 포함"
    talk = fit_conversation(inp.conversation)
    source = "드라이브 정보와 차 안에서 나눈 가족 대화" if talk else "드라이브 정보"
    talk_part = "\n\n[차 안 대화]\n" + "\n".join(talk) if talk else ""
    talk_rule = "\n- 대화 속 인상적인 말을 최소 2개 그대로 또는 살짝 바꿔 넣기" if talk else ""
    return f"""너는 가족 여행 노래 작사가야. 아래 오늘의 {source}로 한국어 {STYLE_KO[inp.style]} 스타일 가사를 써줘.

[드라이브 정보]
- 출발: {inp.origin_name or UNKNOWN}, 도착: {inp.destination_name or UNKNOWN}
- 지나온 곳: {", ".join(inp.place_names) or UNKNOWN}
- 시간대: {TIME_OF_DAY_KO[inp.time_of_day]}, 날씨: {weather_text(inp.weather)}
- 이동 시간: {inp.duration_min}분, 거리: {round(inp.distance_km, 1)}km
- 함께 탄 가족: {family}{talk_part}

[규칙]
- 구조: [verse] 4줄 + [chorus] 4줄, 모두 8줄로 끝 (2절·두 번째 후렴·bridge 쓰지 않기)
- 한 줄 12음절 이내, 쉬운 단어, 아이가 따라 부를 수 있게{talk_rule}
- {name_rule}
- 실존 노래 가사·멜로디 인용 금지
- 출력은 섹션 태그가 포함된 가사 텍스트만 (설명·제목·코드 펜스 없이)

{OUTPUT_FORMAT}"""


# gemma3:12b 는 "두 섹션만"이라고만 하면 습관처럼 2절까지 쓴다 (첫 응답 형식 통과 2/6). 틀을 보여 주면 6/6 (2026-11-11)
OUTPUT_FORMAT = "[출력 형식] 아래 틀 그대로, 태그 2개와 가사 8줄만:\n" + "\n".join(
    f"[{name}]\n" + "\n".join(f"{i}줄" for i in range(1, LINES_PER_SECTION + 1)) for name in SECTIONS)


# 번호 붙은 태그([Verse 1])는 번호를 떼고 받는다 (gemma3 가 늘 이렇게 쓴다, 2026-11-04)
_TAG = re.compile(r"^\[([A-Za-z]+)(?:\s*\d+)?\]$")
# 가사 줄에 허용하는 글자: 한글, 영문·숫자·ASCII 문장부호, 자주 쓰는 기호. 그 밖(태국어 등 다른 문자)이 섞이면 재요청
_FOREIGN = re.compile(r"[^가-힣ᄀ-ᇿ㄰-㆏\x20-\x7E…·~♪♬“”‘’]")


def validate_lyrics(text: str) -> str | None:
    """§7.2 구조를 지키면 정리한 가사("[verse]\\n...\\n[chorus]\\n...")를, 아니면 None.

    검사: 첫 줄이 섹션 태그(앞에 설명 문장 없음), 태그 순서 verse/chorus,
    섹션마다 4줄(뒤에 붙은 설명 문장도 여기서 걸린다), 태그가 아닌 [..] 줄이나 ``` 없음,
    한글·영문 밖의 문자 없음. 음절 수(12음절)는 검사하지 않는다. 전체를 감싼 코드 펜스 한 겹은 벗긴다.
    """
    lines = [line.strip() for line in text.strip().splitlines() if line.strip()]
    if len(lines) >= 2 and lines[0].startswith("```") and lines[-1] == "```":
        lines = lines[1:-1]
    sections: list[tuple[str, list[str]]] = []
    for line in lines:
        tag = _TAG.match(line)
        if tag:
            sections.append((tag.group(1).lower(), []))
        elif not sections or line.startswith(("[", "```")) or _FOREIGN.search(line):
            return None
        else:
            sections[-1][1].append(line)
    if tuple(name for name, _ in sections) != SECTIONS:
        return None
    if any(len(body) != LINES_PER_SECTION for _, body in sections):
        return None
    return "\n".join(f"[{name}]\n" + "\n".join(body) for name, body in sections)


async def write_valid_lyrics(llm: LlmProvider, inp: LyricsInput) -> str | None:
    """§7.2 구조를 지킨 가사. 출력이 틀리거나 LlmError 면 한 번 더 요청하고, 그래도 안 되면 None.
    시간 상한은 부르는 쪽이 건다 (/v1/lyrics 180초, 잡 워커)."""
    for attempt in range(1, ATTEMPTS + 1):
        try:
            text = await llm.write_lyrics(inp)
        except LlmError as e:
            log.warning("lyrics attempt %d: %s", attempt, e)
            continue
        lyrics = validate_lyrics(text)
        if lyrics:
            return lyrics
        log.warning("lyrics attempt %d: output does not follow the section structure", attempt)
    return None
