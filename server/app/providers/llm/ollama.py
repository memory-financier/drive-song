"""Ollama 로컬 LLM 가사 생성 (v0.4, 설계 §7.5 4단계). 대화가 외부 서비스로 나가지 않는다 (§8.3).

POST {url}/api/chat (stream false).
- keep_alive 0: 응답 뒤 바로 GPU 에서 내려 작곡(ACE-Step)·목소리 변환(Seed-VC)에 메모리를 넘긴다 (16GB 공유).
- num_ctx 8192: 기본 문맥(2048~4096 토큰)은 대화를 넣은 프롬프트를 조용히 잘라 낸다.
- 시간: 11/03 시험에서 gemma3:12b 로 약 8초 (모델 올리는 시간 포함). 요청 상한 120초.
"""

import httpx

from app.providers.llm.base import LlmError, LyricsInput, build_prompt

REQUEST_TIMEOUT_SEC = 120.0


class OllamaLlm:
    name = "ollama"
    external = False

    def __init__(self, url: str, model: str, client: httpx.AsyncClient):
        self.url = url.rstrip("/")
        self.model = model
        self.client = client

    async def write_lyrics(self, inp: LyricsInput) -> str:
        body = {
            "model": self.model,
            "messages": [{"role": "user", "content": build_prompt(inp)}],
            "stream": False,
            "keep_alive": 0,
            "options": {"temperature": 0.8, "num_ctx": 8192},
        }
        try:
            res = await self.client.post(f"{self.url}/api/chat", json=body, timeout=REQUEST_TIMEOUT_SEC)
            res.raise_for_status()
            text = res.json()["message"]["content"].strip()
        except (httpx.HTTPError, KeyError, TypeError, ValueError) as e:
            raise LlmError(f"Ollama error: {e!r}") from e
        if not text:
            raise LlmError("Ollama returned no text")
        return text
