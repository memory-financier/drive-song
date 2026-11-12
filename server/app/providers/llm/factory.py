import httpx

from app.providers.llm.base import LlmProvider
from app.providers.llm.claude import ClaudeLlm
from app.providers.llm.fake import FakeLlm
from app.providers.llm.ollama import OllamaLlm
from app.settings import Settings

LLM_PROVIDER_NAMES = ("fake", "claude", "ollama")


def make_llm_provider(settings: Settings, client: httpx.AsyncClient | None = None) -> LlmProvider:
    """settings.llm_provider 이름으로 만든다. 키가 없거나 이름이 틀리면 ValueError."""
    if settings.llm_provider == "fake":
        return FakeLlm()
    if settings.llm_provider == "claude":
        if not settings.anthropic_api_key:
            raise ValueError("ANTHROPIC_API_KEY is required for llm provider 'claude'")
        return ClaudeLlm(settings.anthropic_api_key, settings.claude_model)
    if settings.llm_provider == "ollama":
        return OllamaLlm(settings.ollama_url, settings.ollama_model, client or httpx.AsyncClient())
    raise ValueError(
        f"unknown llm provider '{settings.llm_provider}' (choose one of {', '.join(LLM_PROVIDER_NAMES)})"
    )
