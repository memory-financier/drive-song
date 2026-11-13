"""MusicProvider 어댑터 단위 테스트 (httpx.MockTransport, 네트워크·키 불필요).

응답 예시는 각 공급자 공개 문서의 형식을 따른다 (어댑터 모듈 docstring 참조).
"""

import asyncio
import json

import httpx
import pytest

from app.api.clips import probe_audio_duration
from app.providers.music.acestep import AceStepProvider
from app.providers.music.acestep_local import VOCAL_DIRECTION, AceStepLocalProvider
from app.providers.music.base import STYLE_PROMPTS, MusicStatus, ProviderError, Style
from app.providers.music.factory import make_music_provider
from app.providers.music.fake import FakeMusicProvider
from app.providers.music.minimax import MiniMaxMusicProvider
from app.providers.music.mureka import MurekaProvider
from app.settings import Settings

LYRICS = "[verse]\n강릉 가는 길\n[chorus]\n신나는 드라이브"


class Recorder:
    """요청을 기록하고 (method, url) 별로 미리 정한 응답을 돌려준다."""

    def __init__(self, routes: dict[tuple[str, str], httpx.Response]):
        self.routes = routes
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self.routes[(request.method, str(request.url))]

    def client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(transport=httpx.MockTransport(self))


def run(coro):
    return asyncio.run(coro)


# --- fal.ai: MiniMax -------------------------------------------------------

FAL = "https://queue.fal.run"


def fal_submit_response(endpoint: str) -> httpx.Response:
    base = f"{FAL}/{'/'.join(endpoint.split('/')[:2])}/requests/req_1"
    return httpx.Response(200, json={
        "request_id": "req_1",
        "response_url": base,
        "status_url": f"{base}/status",
        "cancel_url": f"{base}/cancel",
        "queue_position": 0,
    })


def test_minimax_submit_request_shape():
    rec = Recorder({("POST", f"{FAL}/fal-ai/minimax-music/v2.6"): fal_submit_response("fal-ai/minimax-music/v2.6")})
    provider = MiniMaxMusicProvider("k123", rec.client())

    assert run(provider.submit(LYRICS, "city_pop", 75)) == "req_1"
    req = rec.requests[0]
    assert req.headers["Authorization"] == "Key k123"
    body = json.loads(req.content)
    assert body == {
        "prompt": STYLE_PROMPTS[Style.CITY_POP],
        "lyrics": "[Verse]\n강릉 가는 길\n[Chorus]\n신나는 드라이브",
    }
    assert 10 <= len(body["prompt"]) <= 2000


def test_minimax_poll_pending_then_succeeded():
    status_url = f"{FAL}/fal-ai/minimax-music/requests/req_1/status"
    result_url = f"{FAL}/fal-ai/minimax-music/requests/req_1"
    rec = Recorder({("GET", status_url): httpx.Response(202, json={"status": "IN_QUEUE", "queue_position": 2})})
    provider = MiniMaxMusicProvider("k", rec.client())
    assert run(provider.poll("req_1")).status == MusicStatus.PENDING

    rec.routes[("GET", status_url)] = httpx.Response(202, json={"status": "IN_PROGRESS", "logs": []})
    assert run(provider.poll("req_1")).status == MusicStatus.PENDING

    rec.routes[("GET", status_url)] = httpx.Response(
        200, json={"status": "COMPLETED", "logs": [], "metrics": {"inference_time": 40.1}}
    )
    rec.routes[("GET", result_url)] = httpx.Response(
        200, json={"audio": {"url": "https://v3b.fal.media/files/b/kangaroo/x_output.mp3"}}
    )
    result = run(provider.poll("req_1"))
    assert result.status == MusicStatus.SUCCEEDED
    assert result.audio_url == "https://v3b.fal.media/files/b/kangaroo/x_output.mp3"
    assert result.chorus_start_sec is None
    # 요청 경로에 하위 경로(v2.6)를 넣지 않는다 (fal 문서: 넣으면 405)
    assert all("v2.6" not in str(r.url) for r in rec.requests)
    assert all(r.headers["Authorization"] == "Key k" for r in rec.requests)


def test_fal_completed_with_error_is_failed():
    rec = Recorder({("GET", f"{FAL}/fal-ai/minimax-music/requests/req_1/status"): httpx.Response(
        200, json={"status": "COMPLETED", "error": "content policy violation", "error_type": "safety"}
    )})
    result = run(MiniMaxMusicProvider("k", rec.client()).poll("req_1"))
    assert result.status == MusicStatus.FAILED
    assert "content policy violation" in result.error


def test_fal_result_http_error_is_failed():
    rec = Recorder({
        ("GET", f"{FAL}/fal-ai/minimax-music/requests/req_1/status"): httpx.Response(200, json={"status": "COMPLETED"}),
        ("GET", f"{FAL}/fal-ai/minimax-music/requests/req_1"): httpx.Response(422, json={"detail": "bad lyrics"}),
    })
    result = run(MiniMaxMusicProvider("k", rec.client()).poll("req_1"))
    assert result.status == MusicStatus.FAILED
    assert "422" in result.error


def test_fal_submit_http_error_raises():
    rec = Recorder({("POST", f"{FAL}/fal-ai/minimax-music/v2.6"): httpx.Response(401, json={"detail": "bad key"})})
    with pytest.raises(httpx.HTTPStatusError):
        run(MiniMaxMusicProvider("k", rec.client()).submit(LYRICS, "kids", 60))


# --- fal.ai: ACE-Step ------------------------------------------------------

def test_acestep_submit_and_poll():
    rec = Recorder({
        ("POST", f"{FAL}/fal-ai/ace-step"): fal_submit_response("fal-ai/ace-step"),
        ("GET", f"{FAL}/fal-ai/ace-step/requests/req_1/status"): httpx.Response(200, json={"status": "COMPLETED"}),
        ("GET", f"{FAL}/fal-ai/ace-step/requests/req_1"): httpx.Response(200, json={
            "audio": {"url": "https://storage.googleapis.com/falserverless/example_outputs/ace-step-text-to-audio.wav",
                      "content_type": "audio/wav", "file_name": "out.wav", "file_size": 123},
            "seed": 42,
            "tags": "lofi, hiphop",
            "lyrics": "[inst]",
        }),
    })
    provider = AceStepProvider("k", rec.client())
    assert run(provider.submit(LYRICS, "kids", 75)) == "req_1"
    body = json.loads(rec.requests[0].content)
    assert body["lyrics"] == LYRICS  # ACE-Step 은 소문자 태그 그대로
    assert body["duration"] == 75
    assert "children's song" in body["tags"]

    result = run(provider.poll("req_1"))
    assert result.status == MusicStatus.SUCCEEDED
    assert result.audio_url.endswith(".wav")


# --- 로컬 ACE-Step 1.5 API ---------------------------------------------------

LOCAL = "http://127.0.0.1:8001"


def local_query(status: int, result: list | None = None, task_id: str = "t1") -> httpx.Response:
    return httpx.Response(200, json={"data": [{"task_id": task_id, "status": status,
                                               "result": json.dumps(result or [])}], "code": 200})


def test_acestep_local_submit_request_shape():
    rec = Recorder({("POST", f"{LOCAL}/release_task"): httpx.Response(200, json={
        "data": {"task_id": "t1", "status": "queued", "queue_position": 1}, "code": 200})})
    provider = AceStepLocalProvider(LOCAL + "/", rec.client(), thinking=True, inference_steps=12)

    assert run(provider.submit(LYRICS, "city_pop", 75)) == "t1"

    body = json.loads(rec.requests[0].content)
    assert body == {
        "prompt": f"{STYLE_PROMPTS[Style.CITY_POP]}, {VOCAL_DIRECTION}", "lyrics": LYRICS, "audio_duration": 75,
        "vocal_language": "ko",
        "batch_size": 1, "inference_steps": 12, "thinking": True, "audio_format": "mp3",
    }


def test_acestep_local_poll_pending_then_succeeded_with_absolute_audio_url():
    file = "/v1/audio?path=C%3A%5Ctmp%5Cabc.mp3"
    rec = Recorder({("POST", f"{LOCAL}/query_result"): local_query(0)})
    provider = AceStepLocalProvider(LOCAL, rec.client())

    assert run(provider.poll("t1")).status == MusicStatus.PENDING
    assert json.loads(rec.requests[0].content) == {"task_id_list": ["t1"]}

    rec.routes[("POST", f"{LOCAL}/query_result")] = local_query(1, [{"file": file, "status": 1, "metas": {"bpm": 100}}])
    result = run(provider.poll("t1"))
    assert result.status == MusicStatus.SUCCEEDED
    assert result.audio_url == LOCAL + file
    assert result.chorus_end_sec is None  # 섹션 시각 없음 → 믹서 40% 규칙


def test_acestep_local_failed_and_missing_file():
    rec = Recorder({("POST", f"{LOCAL}/query_result"): local_query(2, [{"file": "", "error": "CUDA out of memory"}])})
    provider = AceStepLocalProvider(LOCAL, rec.client())
    failed = run(provider.poll("t1"))
    assert (failed.status, failed.error) == (MusicStatus.FAILED, "acestep_local: CUDA out of memory")

    rec.routes[("POST", f"{LOCAL}/query_result")] = local_query(1, [{"file": ""}])
    assert run(provider.poll("t1")).status == MusicStatus.FAILED


def test_acestep_local_unknown_task_stays_pending():
    # ACE-Step 서버가 재시작돼 잡을 모르면 status 0 / 빈 목록 → 워커 제한 시간으로 끝난다
    rec = Recorder({("POST", f"{LOCAL}/query_result"): httpx.Response(200, json={"data": [], "code": 200})})
    assert run(AceStepLocalProvider(LOCAL, rec.client()).poll("gone")).status == MusicStatus.PENDING


def test_acestep_local_submit_without_task_id_raises():
    rec = Recorder({("POST", f"{LOCAL}/release_task"): httpx.Response(200, json={"data": {}, "code": 200})})
    with pytest.raises(ProviderError):
        run(AceStepLocalProvider(LOCAL, rec.client()).submit(LYRICS, "kids", 75))


@pytest.mark.parametrize("style", list(Style))
def test_every_style_maps_for_all_adapters(style):
    from app.providers.music.acestep import STYLE_TAGS

    assert STYLE_PROMPTS[style] and STYLE_TAGS[style]
    assert len(STYLE_PROMPTS[style]) <= 1024  # Mureka prompt 최대 길이


# --- Mureka ----------------------------------------------------------------

MUREKA = "https://api.mureka.ai"


def test_mureka_submit_request_shape():
    rec = Recorder({("POST", f"{MUREKA}/v1/song/generate"): httpx.Response(200, json={
        "id": "1436211", "created_at": 1677610602, "finished_at": 1677610652,
        "model": "mureka-7.6", "status": "preparing",
    })})
    provider = MurekaProvider("mk", rec.client())
    assert run(provider.submit(LYRICS, "acoustic", 75)) == "1436211"
    req = rec.requests[0]
    assert req.headers["Authorization"] == "Bearer mk"
    body = json.loads(req.content)
    assert body["model"] == "auto"
    assert body["n"] == 1
    assert body["prompt"] == STYLE_PROMPTS[Style.ACOUSTIC]
    assert body["lyrics"].startswith("[Verse]")


@pytest.mark.parametrize("status", ["preparing", "queued", "running", "streaming"])
def test_mureka_pending_statuses(status):
    rec = Recorder({("GET", f"{MUREKA}/v1/song/query/1436211"): httpx.Response(200, json={
        "id": "1436211", "created_at": 1677610602, "model": "mureka-7.6", "status": status,
    })})
    assert run(MurekaProvider("mk", rec.client()).poll("1436211")).status == MusicStatus.PENDING


def test_mureka_succeeded_with_chorus_timing():
    rec = Recorder({("GET", f"{MUREKA}/v1/song/query/1436211"): httpx.Response(200, json={
        "id": "1436211", "created_at": 1677610602, "finished_at": 1677610652,
        "model": "mureka-7.6", "status": "succeeded",
        "choices": [{
            "index": 0, "id": "s1",
            "url": "https://cdn.mureka.ai/s1.mp3",
            "flac_url": "https://cdn.mureka.ai/s1.flac",
            "duration": 78000,
            "lyrics_sections": [
                {"section_type": "intro", "start": 0, "end": 4000},
                {"section_type": "verse", "start": 4000, "end": 21500},
                {"section_type": "chorus", "start": 21500, "end": 38250, "lines": []},
                {"section_type": "chorus", "start": 60000, "end": 76000},
            ],
        }],
    })})
    result = run(MurekaProvider("mk", rec.client()).poll("1436211"))
    assert result.status == MusicStatus.SUCCEEDED
    assert result.audio_url == "https://cdn.mureka.ai/s1.mp3"
    assert result.chorus_start_sec == 21.5  # 첫 후렴
    assert result.chorus_end_sec == 38.25


def test_mureka_succeeded_without_sections():
    rec = Recorder({("GET", f"{MUREKA}/v1/song/query/9"): httpx.Response(200, json={
        "id": "9", "status": "succeeded", "choices": [{"index": 0, "url": "https://cdn.mureka.ai/9.mp3"}],
    })})
    result = run(MurekaProvider("mk", rec.client()).poll("9"))
    assert result.status == MusicStatus.SUCCEEDED
    assert result.chorus_start_sec is None and result.chorus_end_sec is None


@pytest.mark.parametrize("status,reason", [("failed", "lyrics rejected"), ("timeouted", None), ("cancelled", None)])
def test_mureka_failed_statuses(status, reason):
    payload = {"id": "9", "status": status}
    if reason:
        payload["failed_reason"] = reason
    rec = Recorder({("GET", f"{MUREKA}/v1/song/query/9"): httpx.Response(200, json=payload)})
    result = run(MurekaProvider("mk", rec.client()).poll("9"))
    assert result.status == MusicStatus.FAILED
    assert (reason or status) in result.error


# --- factory / fake --------------------------------------------------------

def test_factory(tmp_path):
    client = httpx.AsyncClient()
    s = Settings(_env_file=None, data_dir=tmp_path)
    assert s.music_provider == "fake"
    assert isinstance(make_music_provider("fake", s, client), FakeMusicProvider)
    for name in ("minimax", "acestep", "mureka"):
        with pytest.raises(ValueError, match="required"):
            make_music_provider(name, s, client)
    keyed = Settings(_env_file=None, data_dir=tmp_path, fal_key="f", mureka_api_key="m")
    assert isinstance(make_music_provider("minimax", keyed, client), MiniMaxMusicProvider)
    assert isinstance(make_music_provider("acestep", keyed, client), AceStepProvider)
    assert isinstance(make_music_provider("mureka", keyed, client), MurekaProvider)
    with pytest.raises(ValueError, match="unknown"):
        make_music_provider("suno", s, client)
    # 로컬 ACE-Step 은 키가 필요 없다
    local = make_music_provider("acestep_local", s, client)
    assert isinstance(local, AceStepLocalProvider)
    assert (local._base, local._thinking, local._steps) == ("http://127.0.0.1:8001", False, 8)


def test_settings_read_acestep_local_options_from_env(monkeypatch, tmp_path):
    monkeypatch.setenv("MUSIC_PROVIDER", "acestep_local")
    monkeypatch.setenv("ACESTEP_LOCAL_URL", "http://gpu-box:8001")
    monkeypatch.setenv("ACESTEP_LOCAL_THINKING", "true")
    s = Settings(_env_file=None, data_dir=tmp_path)
    assert (s.music_provider, s.acestep_local_url, s.acestep_local_thinking) == (
        "acestep_local", "http://gpu-box:8001", True)


def test_settings_read_provider_and_keys_from_env(monkeypatch, tmp_path):
    monkeypatch.setenv("MUSIC_PROVIDER", "mureka")
    monkeypatch.setenv("MUREKA_API_KEY", "secret")
    monkeypatch.setenv("FAL_KEY", "fal-secret")
    s = Settings(_env_file=None, data_dir=tmp_path)
    assert (s.music_provider, s.mureka_api_key, s.fal_key) == ("mureka", "secret", "fal-secret")


def test_fake_provider_returns_decodable_song_after_delay(tmp_path):
    from pathlib import Path
    from urllib.parse import urlparse
    from urllib.request import url2pathname

    provider = FakeMusicProvider(tmp_path, delay_sec=0.2)

    async def scenario():
        job_id = await provider.submit(LYRICS, "kids", 61)
        first = await provider.poll(job_id)
        await asyncio.sleep(0.25)
        return first, await provider.poll(job_id)

    first, done = run(scenario())
    assert first.status == MusicStatus.PENDING
    assert done.status == MusicStatus.SUCCEEDED
    path = Path(url2pathname(urlparse(done.audio_url).path))
    assert path.exists()
    assert abs(probe_audio_duration(path) - 61) < 0.5
    assert done.chorus_start_sec < done.chorus_end_sec < 61
