"""fal.ai 큐 API 공용 클라이언트 (minimax, acestep 어댑터가 사용).

문서: https://fal.ai/docs/model-apis/model-endpoints/queue
- 제출: POST https://queue.fal.run/{endpoint}  -> {"request_id", "status_url", "response_url", ...}
- 상태: GET  https://queue.fal.run/{owner}/{app}/requests/{id}/status
        -> {"status": "IN_QUEUE" | "IN_PROGRESS" | "COMPLETED", "error"?: "...", "error_type"?: "..."}
        실패한 요청도 COMPLETED 이며 error 필드가 있다.
- 결과: GET  https://queue.fal.run/{owner}/{app}/requests/{id}  -> 모델별 출력
- 인증: Authorization: Key $FAL_KEY
요청 경로에는 엔드포인트의 하위 경로(/v2.6 등)를 넣지 않는다 (문서: 넣으면 405).
"""

from typing import Any

import httpx

from app.providers.music.base import MusicResult, MusicStatus, ProviderError

QUEUE_BASE = "https://queue.fal.run"


class FalQueueProvider:
    name: str
    endpoint: str  # 예: "fal-ai/minimax-music/v2.6"

    def __init__(self, api_key: str, client: httpx.AsyncClient):
        self._client = client
        self._headers = {"Authorization": f"Key {api_key}"}

    def build_input(self, lyrics: str, style: str, duration_sec: int) -> dict[str, Any]:
        raise NotImplementedError

    @property
    def _requests_base(self) -> str:
        owner_app = "/".join(self.endpoint.split("/")[:2])
        return f"{QUEUE_BASE}/{owner_app}/requests"

    async def submit(self, lyrics: str, style: str, duration_sec: int) -> str:
        res = await self._client.post(
            f"{QUEUE_BASE}/{self.endpoint}",
            json=self.build_input(lyrics, style, duration_sec),
            headers=self._headers,
        )
        res.raise_for_status()
        request_id = res.json().get("request_id")
        if not request_id:
            raise ProviderError(f"{self.name}: no request_id in submit response")
        return request_id

    async def poll(self, provider_job_id: str) -> MusicResult:
        base = f"{self._requests_base}/{provider_job_id}"
        res = await self._client.get(f"{base}/status", headers=self._headers)
        res.raise_for_status()
        status = res.json()
        if status.get("status") != "COMPLETED":
            return MusicResult(MusicStatus.PENDING)
        if status.get("error"):
            return MusicResult(MusicStatus.FAILED, error=f"{self.name}: {status['error']}")

        res = await self._client.get(base, headers=self._headers)
        if res.is_error:
            # [확인 필요] 실패한 요청의 결과 조회 응답 형식 (문서에 없음). 본문을 그대로 오류로 남긴다.
            return MusicResult(
                MusicStatus.FAILED, error=f"{self.name}: result HTTP {res.status_code} {res.text[:200]}"
            )
        url = (res.json().get("audio") or {}).get("url")
        if not url:
            return MusicResult(MusicStatus.FAILED, error=f"{self.name}: no audio.url in result")
        return MusicResult(MusicStatus.SUCCEEDED, audio_url=url)
