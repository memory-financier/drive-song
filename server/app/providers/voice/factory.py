import httpx

from app.providers.voice.base import ConversationAnalyzer, VoiceConverter
from app.providers.voice.fake import FakeVoice
from app.providers.voice.voicelab import VoiceLab
from app.settings import Settings

VOICE_PROVIDER_NAMES = ("fake", "voicelab")


def make_voice_providers(
    settings: Settings, client: httpx.AsyncClient
) -> tuple[ConversationAnalyzer, VoiceConverter]:
    """settings.voice_provider 로 (분석, 변환) 공급자를 만든다. 같은 서비스가 둘 다 맡는다. 이름이 틀리면 ValueError."""
    if settings.voice_provider == "fake":
        fake = FakeVoice()
        return fake, fake
    if settings.voice_provider == "voicelab":
        lab = VoiceLab(settings.voicelab_url, client)
        return lab, lab
    raise ValueError(
        f"unknown voice provider '{settings.voice_provider}' (choose one of {', '.join(VOICE_PROVIDER_NAMES)})"
    )
