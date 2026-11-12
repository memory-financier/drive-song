"""MiniMax Music (fal.ai 호스팅).

모델 페이지: https://fal.ai/models/fal-ai/minimax-music/v2.6 ($0.15/곡, 설계 §7.3 가격과 일치)
입력: prompt (스타일 설명, 10-2000자, 필수), lyrics (최대 3500자, [Verse]/[Chorus] 등 태그)
출력: {"audio": {"url": "...mp3"}}
곡 길이를 지정하는 입력이 없다. [확인 필요] 실제 생성 길이 (60~90초 요구, §7.3).
"""

from typing import Any

from app.providers.music.base import STYLE_PROMPTS, Style, title_case_section_tags
from app.providers.music.fal import FalQueueProvider


class MiniMaxMusicProvider(FalQueueProvider):
    name = "minimax"
    endpoint = "fal-ai/minimax-music/v2.6"

    def build_input(self, lyrics: str, style: str, duration_sec: int) -> dict[str, Any]:
        return {"prompt": STYLE_PROMPTS[Style(style)], "lyrics": title_case_section_tags(lyrics)}
