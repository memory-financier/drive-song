"""로컬 ACE-Step 1.5 REST API 어댑터 (설계 §7.3, docs/api-bench-report.md §5-1). 키·비용 없음 (자체 GPU).

API: https://github.com/ace-step/ACE-Step-1.5/blob/main/docs/en/API.md (`acestep-api`, 기본 127.0.0.1:8001)
- 제출: POST {base}/release_task {prompt, lyrics, audio_duration, vocal_language, batch_size, inference_steps,
        thinking, audio_format} -> {"data": {"task_id": "...", "status": "queued"}, "code": 200}
- 상태: POST {base}/query_result {"task_id_list": [id]}
        -> {"data": [{"task_id", "status": 0 | 1 | 2, "result": "<JSON 문자열: 결과 리스트>"}]}
        0 = 대기·생성 중, 1 = 성공, 2 = 실패(결과 항목의 "error"). 성공 결과의 "file" 은 서버 기준 상대 주소
        ("/v1/audio?path=...") 라 base 를 붙여 받는다.
- 섹션 시각은 주지 않는다 → 믹서가 CALL 을 40% 지점에 둔다. 가족 목소리 변환을 거치면 노래 없는 빈 구간으로 옮긴다 (§7.4). bpm·keyscale 은 metas 에 있다.
- 모르는 task_id (ACE-Step 서버 재시작 등) 는 status 0 으로 남는다 → 워커의 JOB_TIMEOUT 으로 실패한다.
- Windows 에서 ACE-Step 서버는 PYTHONUTF8=1 과 PATH 의 ffmpeg 가 필요하다 (§5-1).
"""

import json

import httpx

from app.providers.music.base import STYLE_PROMPTS, MusicResult, MusicStatus, ProviderError, Style

# 가족 목소리로 바꿔 부르기 좋게 리드 보컬 하나가 가사만 또렷하게. 허밍·애드리브·코러스가 있으면
# 가족 목소리가 가사 대신 그 소리에 쓰인다 (2026-11-12 시험 곡)
VOCAL_DIRECTION = "single lead vocal singing every lyric line clearly, no humming, no ad-libs, no backing vocals, short intro"


class AceStepLocalProvider:
    name = "acestep_local"

    def __init__(self, base_url: str, client: httpx.AsyncClient, *, thinking: bool = False, inference_steps: int = 8):
        self._base = base_url.rstrip("/")
        self._client = client
        self._thinking = thinking
        self._steps = inference_steps

    async def submit(self, lyrics: str, style: str, duration_sec: int) -> str:
        res = await self._client.post(f"{self._base}/release_task", json={
            "prompt": f"{STYLE_PROMPTS[Style(style)]}, {VOCAL_DIRECTION}",
            "lyrics": lyrics,  # [verse]/[chorus] 소문자 태그 그대로
            "audio_duration": duration_sec,
            "vocal_language": "ko",
            "batch_size": 1,
            "inference_steps": self._steps,
            "thinking": self._thinking,
            "audio_format": "mp3",
        })
        res.raise_for_status()
        task_id = (res.json().get("data") or {}).get("task_id")
        if not task_id:
            raise ProviderError(f"{self.name}: no task_id in submit response")
        return task_id

    async def poll(self, provider_job_id: str) -> MusicResult:
        res = await self._client.post(f"{self._base}/query_result", json={"task_id_list": [provider_job_id]})
        res.raise_for_status()
        item = next((i for i in res.json().get("data") or [] if i.get("task_id") == provider_job_id), None)
        if item is None or item.get("status") == 0:
            return MusicResult(MusicStatus.PENDING)
        results = json.loads(item.get("result") or "[]")
        first = results[0] if results else {}
        if item.get("status") != 1:
            return MusicResult(MusicStatus.FAILED, error=f"{self.name}: {first.get('error') or 'generation failed'}")
        file = first.get("file")
        if not file:
            return MusicResult(MusicStatus.FAILED, error=f"{self.name}: no file in result")
        return MusicResult(MusicStatus.SUCCEEDED, audio_url=f"{self._base}{file}" if file.startswith("/") else file)
