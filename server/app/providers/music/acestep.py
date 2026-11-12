"""ACE-Step (fal.ai 호스팅).

모델 페이지: https://fal.ai/models/fal-ai/ace-step ($0.0002/초 -> 75초 약 $0.015, 설계 §7.3)
입력: tags (쉼표 구분 장르 태그, 필수), lyrics ([verse]/[chorus] 태그), duration (초, 5-240)
출력: {"audio": {"url": "...wav", ...}, "seed": int, "tags": str, "lyrics": str}
"""

from typing import Any

from app.providers.music.base import Style
from app.providers.music.fal import FalQueueProvider

STYLE_TAGS: dict[Style, str] = {
    Style.KIDS: "children's song, korean, cheerful, bright, glockenspiel, piano, hand claps, kids vocal, 110 bpm",
    Style.CITY_POP: "city pop, korean, 80s, retro, synth bass, electric piano, female vocal, nostalgic, 104 bpm",
    Style.ACOUSTIC: "acoustic pop, korean, acoustic guitar, soft piano, male vocal, warm, heartfelt, 90 bpm",
    Style.ROAD_TRIP: "pop rock, korean, upbeat, energetic drums, electric guitar, sing-along, joyful vocal, 128 bpm",
}


class AceStepProvider(FalQueueProvider):
    name = "acestep"
    endpoint = "fal-ai/ace-step"

    def build_input(self, lyrics: str, style: str, duration_sec: int) -> dict[str, Any]:
        return {"tags": STYLE_TAGS[Style(style)], "lyrics": lyrics, "duration": duration_sec}
