import httpx

from app.providers.music.acestep import AceStepProvider
from app.providers.music.acestep_local import AceStepLocalProvider
from app.providers.music.base import MusicProvider
from app.providers.music.fake import FakeMusicProvider
from app.providers.music.minimax import MiniMaxMusicProvider
from app.providers.music.mureka import MurekaProvider
from app.settings import Settings

PROVIDER_NAMES = ("fake", "minimax", "mureka", "acestep", "acestep_local")


def make_music_provider(name: str, settings: Settings, client: httpx.AsyncClient) -> MusicProvider:
    """settings.music_provider 이름으로 어댑터를 만든다. 키가 없으면 ValueError."""
    if name == "fake":
        return FakeMusicProvider(settings.data_dir, settings.fake_music_delay_sec)
    if name == "acestep_local":
        return AceStepLocalProvider(
            settings.acestep_local_url,
            client,
            thinking=settings.acestep_local_thinking,
            inference_steps=settings.acestep_local_steps,
        )
    if name in ("minimax", "acestep"):
        if not settings.fal_key:
            raise ValueError(f"FAL_KEY is required for music provider '{name}'")
        cls = MiniMaxMusicProvider if name == "minimax" else AceStepProvider
        return cls(settings.fal_key, client)
    if name == "mureka":
        if not settings.mureka_api_key:
            raise ValueError("MUREKA_API_KEY is required for music provider 'mureka'")
        return MurekaProvider(settings.mureka_api_key, client)
    raise ValueError(f"unknown music provider '{name}' (choose one of {', '.join(PROVIDER_NAMES)})")
