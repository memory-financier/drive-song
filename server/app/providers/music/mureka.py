"""Mureka API.

문서: https://platform.mureka.ai/docs/ (OpenAPI 스키마 SongGenerateReq / SongTask / Song / LyricsSection)
- POST https://api.mureka.ai/v1/song/generate  {lyrics (필수, 최대 5000자), model (필수), prompt (최대 1024자), n}
  -> SongTask {"id", "created_at", "model", "status": "preparing", ...}
- GET  https://api.mureka.ai/v1/song/query/{task_id} -> SongTask
  status: preparing | queued | running | streaming | succeeded | failed | timeouted | cancelled
  실패 시 failed_reason, 성공 시 choices[] = Song {url, flac_url, duration(ms),
  lyrics_sections[] {section_type: intro|verse|pre-chorus|chorus|..., start(ms), end(ms)}}
- 인증: Authorization: Bearer $MUREKA_API_KEY
곡 길이를 지정하는 입력이 없다. [확인 필요] 실제 생성 길이 (60~90초 요구, §7.3).
"""

import httpx

from app.providers.music.base import (
    STYLE_PROMPTS,
    MusicResult,
    MusicStatus,
    ProviderError,
    Style,
    title_case_section_tags,
)

API_BASE = "https://api.mureka.ai"
# "auto" = 최신 일반 모델. 설계 §7.3 가격은 V9.5 기준 ("mureka-9.5" 로 고정 가능).
MODEL = "auto"
PENDING_STATUSES = {"preparing", "queued", "running", "streaming"}


class MurekaProvider:
    name = "mureka"

    def __init__(self, api_key: str, client: httpx.AsyncClient):
        self._client = client
        self._headers = {"Authorization": f"Bearer {api_key}"}

    async def submit(self, lyrics: str, style: str, duration_sec: int) -> str:
        res = await self._client.post(
            f"{API_BASE}/v1/song/generate",
            json={
                "lyrics": title_case_section_tags(lyrics),
                "model": MODEL,
                "prompt": STYLE_PROMPTS[Style(style)],
                # 기본 2곡(곡 수만큼 과금). 1곡만 생성. [확인 필요] n=1 허용 여부 (문서: "Defaults to 2, maximum 3")
                "n": 1,
            },
            headers=self._headers,
        )
        res.raise_for_status()
        task_id = res.json().get("id")
        if not task_id:
            raise ProviderError("mureka: no id in generate response")
        return str(task_id)

    async def poll(self, provider_job_id: str) -> MusicResult:
        res = await self._client.get(
            f"{API_BASE}/v1/song/query/{provider_job_id}", headers=self._headers
        )
        res.raise_for_status()
        task = res.json()
        status = task.get("status")
        if status in PENDING_STATUSES:
            return MusicResult(MusicStatus.PENDING)
        if status != "succeeded":
            reason = task.get("failed_reason") or status
            return MusicResult(MusicStatus.FAILED, error=f"mureka: {reason}")

        choices = task.get("choices") or []
        url = choices[0].get("url") if choices else None
        if not url:
            return MusicResult(MusicStatus.FAILED, error="mureka: no song url in result")
        chorus = next(
            (s for s in choices[0].get("lyrics_sections") or [] if s.get("section_type") == "chorus"),
            None,
        )
        return MusicResult(
            MusicStatus.SUCCEEDED,
            audio_url=url,
            chorus_start_sec=chorus["start"] / 1000 if chorus else None,
            chorus_end_sec=chorus["end"] / 1000 if chorus else None,
        )
