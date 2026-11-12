"""Claude (Anthropic Messages API, 공식 Python SDK) 가사 생성.

- 모델은 settings.claude_model (기본 claude-opus-5-5).
- thinking 파라미터는 보내지 않는다 (Opus 5.5 는 adaptive 가 기본이며 끌 수 없다). 깊이는 effort 로만 조절.
- 안전 분류기 거절 시 서버 측 fallback (fallbacks="default", beta server-side-fallback-2026-07-01).
  최종 응답이 그래도 refusal 이면 LlmError.
"""

import anthropic

from app.providers.llm.base import LlmError, LyricsInput, build_prompt

FALLBACK_BETA = "server-side-fallback-2026-07-01"


class ClaudeLlm:
    name = "claude"
    external = True  # 대화는 보내지 않는다 (§8.3)

    def __init__(self, api_key: str, model: str, http_client: anthropic.DefaultAsyncHttpxClient | None = None):
        self.model = model
        # 테스트는 http_client 에 httpx2.MockTransport 를 넣는다. 재시도(429/5xx/연결 오류)는 SDK 기본값(2회).
        self.client = anthropic.AsyncAnthropic(api_key=api_key, timeout=120.0, http_client=http_client)

    async def write_lyrics(self, inp: LyricsInput) -> str:
        try:
            message = await self.client.beta.messages.create(
                model=self.model,
                max_tokens=16000,
                betas=[FALLBACK_BETA],
                fallbacks="default",
                # 짧은 창작 작업이라 medium (Opus 5.5 기본값과 같지만 명시)
                output_config={"effort": "medium"},
                messages=[{"role": "user", "content": build_prompt(inp)}],
            )
        except anthropic.APIError as e:  # 인증·한도·연결 등. 일시 오류는 SDK 가 이미 재시도했다
            raise LlmError(f"Claude API error: {e}") from e
        if message.stop_reason == "refusal":
            category = message.stop_details.category if message.stop_details else None
            raise LlmError(f"Claude declined the request (category={category})")
        text = "".join(block.text for block in message.content if block.type == "text").strip()
        if not text:
            raise LlmError(f"Claude returned no text (stop_reason={message.stop_reason})")
        return text
