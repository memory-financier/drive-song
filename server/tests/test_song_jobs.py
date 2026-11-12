"""/v1/song-jobs, /v1/songs/{id}/audio 와 잡 워커 테스트."""

import secrets
import threading
import time
from datetime import timedelta

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api.clips import probe_audio_duration
from app.db import utcnow
from app.jobs.models import Job, Song
from app.main import create_app
from app.mixer import mix
from app.providers.music.base import MusicResult, MusicStatus
from app.settings import Settings
from conftest import add_member, register, upload
from test_mixer import integrated_lufs

LYRICS = "[verse]\n서울에서 강릉까지\n[chorus]\n오늘의 드라이브 송"


def make_settings(data_dir, **overrides) -> Settings:
    values = dict(
        _env_file=None,
        data_dir=data_dir,
        fake_music_delay_sec=0.3,
        job_poll_interval_sec=0.05,
        job_timeout_sec=20,
    )
    values.update(overrides)
    return Settings(**values)


@pytest.fixture
def make_client(data_dir):
    """설정을 바꿔 앱을 띄운다. 반환된 TestClient 는 테스트 끝에 닫힌다."""
    opened = []

    def _make(providers=None, **overrides):
        app = create_app(make_settings(data_dir, **overrides))
        app.state.worker.providers.update(providers or {})
        c = TestClient(app)
        c.__enter__()
        opened.append(c)
        return c

    yield _make
    for c in opened:
        c.__exit__(None, None, None)


@pytest.fixture
def sclient(make_client):
    return make_client()


def job_body(**overrides) -> dict:
    body = {"tripId": "trip_20261101_0930", "lyrics": LYRICS, "style": "city_pop", "durationSec": 60}
    body.update(overrides)
    return body


def wait_status(client, headers, job_id, statuses, timeout=30.0) -> dict:
    deadline = time.monotonic() + timeout
    while True:
        res = client.get(f"/v1/song-jobs/{job_id}", headers=headers)
        assert res.status_code == 200, res.text
        body = res.json()
        if body["status"] in statuses:
            return body
        assert time.monotonic() < deadline, f"stuck in {body}"
        time.sleep(0.02)


class ScriptedProvider:
    """테스트용 공급자. poll 결과를 바깥(테스트 스레드)에서 정한다."""

    name = "fake"

    def __init__(self, result: MusicResult | None = None, submit_error: Exception | None = None):
        self.result = result or MusicResult(MusicStatus.PENDING)
        self.submit_error = submit_error
        self.submitted: list[tuple] = []
        self.polls = 0

    async def submit(self, lyrics, style, duration_sec):
        if self.submit_error:
            raise self.submit_error
        self.submitted.append((lyrics, style, duration_sec))
        return "prov_1"

    async def poll(self, provider_job_id):
        assert provider_job_id == "prov_1"
        self.polls += 1
        return self.result


@pytest.fixture(scope="session")
def song_file(tmp_path_factory):
    from app.providers.music.fake import generate_fake_song

    path = tmp_path_factory.mktemp("song") / "song.mp3"
    generate_fake_song(path, 60)
    return path


# --- 전체 흐름 ---------------------------------------------------------------

def test_full_flow_with_fake_provider_reaches_ready(sclient, data_dir, audio_files):
    h = register(sclient)
    member = add_member(sclient, h)
    clip_ids = {
        slot: upload(sclient, h, member, slot, audio_files["short"]).json()["clipId"]
        for slot in ("INTRO", "CALL", "OUTRO")
    }
    plan = [{"slot": slot, "clipId": cid} for slot, cid in clip_ids.items()]

    res = sclient.post("/v1/song-jobs", json=job_body(clipPlan=plan, title="강릉 가는 길"), headers=h)
    assert res.status_code == 201, res.text
    created = res.json()
    assert created == {
        "jobId": created["jobId"], "status": "QUEUED", "songId": None,
        "audioUrl": None, "title": "강릉 가는 길", "error": None, "lyrics": LYRICS, "singers": [],
    }
    assert created["jobId"].startswith("job_")

    done = wait_status(sclient, h, created["jobId"], {"READY", "FAILED"})
    assert done["status"] == "READY", done
    assert done["songId"].startswith("song_")
    assert done["audioUrl"] == f"/v1/songs/{done['songId']}/audio"
    assert done["error"] is None
    assert set(done) == {"jobId", "status", "songId", "audioUrl", "title", "error", "lyrics", "singers"}
    assert done["singers"] == []  # 대화 잡이 아니면 AI 가수 (v0.2 흐름)

    audio = sclient.get(done["audioUrl"], headers=h)
    assert audio.status_code == 200
    assert audio.headers["content-type"] == "audio/mp4"
    out = data_dir / "downloaded.m4a"
    out.write_bytes(audio.content)
    # 클립 3개가 믹싱됨 (설계 §7.4): INTRO 2초 + 곡 60초 + OUTRO 2초, 마스터 -14 LUFS
    assert abs(probe_audio_duration(out) - 64) < 0.5
    assert abs(integrated_lufs(out) - (-14)) <= 1

    with sclient.app.state.session_factory() as s:
        job = s.get(Job, created["jobId"])
        song = s.get(Song, done["songId"])
        assert job.provider == "fake" and job.provider_job_id.startswith("fake_")
        assert job.clip_plan == plan
        assert song.job_id == job.id and song.title == "강릉 가는 길"
    assert list((data_dir / "songs" / "raw").iterdir()) == []  # 원본 다운로드 파일은 정리


def test_title_defaults_when_missing(sclient):
    h = register(sclient)
    res = sclient.post("/v1/song-jobs", json=job_body(), headers=h)
    assert res.status_code == 201
    assert res.json()["title"] == "오늘의 드라이브 송"


# --- 상태 전이 ---------------------------------------------------------------

def test_state_transitions_and_mixer_input(make_client, monkeypatch, audio_files, song_file):
    provider = ScriptedProvider()
    mix_started, mix_release = threading.Event(), threading.Event()
    mix_calls = []

    def gated_mix(song, clips, chorus_start, chorus_end, out, vocal_phrases=None):
        mix_calls.append((clips, chorus_start, chorus_end))
        mix_started.set()
        assert mix_release.wait(10)
        mix.mix_song(song, clips, chorus_start, chorus_end, out, vocal_phrases)

    monkeypatch.setattr("app.jobs.worker.mix_song", gated_mix)
    client = make_client(providers={"fake": provider})
    h = register(client)
    keep = add_member(client, h, nickname="엄마")
    gone = add_member(client, h, nickname="아이", isChild=True, guardianConsent=True)
    intro = upload(client, h, keep, "INTRO", audio_files["short"]).json()["clipId"]
    call = upload(client, h, gone, "CALL", audio_files["short"]).json()["clipId"]

    res = client.post("/v1/song-jobs", headers=h, json=job_body(
        style="kids", durationSec=90,
        clipPlan=[{"slot": "INTRO", "clipId": intro}, {"slot": "CALL", "clipId": call}],
    ))
    assert res.json()["status"] == "QUEUED"
    job_id = res.json()["jobId"]

    body = wait_status(client, h, job_id, {"GENERATING"})
    assert body["songId"] is None and body["audioUrl"] is None
    deadline = time.monotonic() + 10
    while provider.polls < 2:  # 생성 중에는 계속 폴링하며 GENERATING 유지
        assert time.monotonic() < deadline
        time.sleep(0.02)
    assert wait_status(client, h, job_id, {"GENERATING"})["status"] == "GENERATING"
    assert provider.submitted == [(LYRICS, "kids", 90)]

    # 잡 생성 후 구성원이 삭제되면 그 클립은 믹싱에서 빠진다
    assert client.delete(f"/v1/members/{gone}", headers=h).status_code == 204
    provider.result = MusicResult(
        MusicStatus.SUCCEEDED, audio_url=song_file.resolve().as_uri(),
        chorus_start_sec=12.0, chorus_end_sec=24.5,
    )
    assert mix_started.wait(10)
    assert wait_status(client, h, job_id, {"MIXING"})["songId"] is None

    mix_release.set()
    done = wait_status(client, h, job_id, {"READY", "FAILED"})
    assert done["status"] == "READY", done
    clips, chorus_start, chorus_end = mix_calls[0]
    assert [(c.slot, c.path.exists()) for c in clips] == [("INTRO", True)]
    assert (chorus_start, chorus_end) == (12.0, 24.5)


def test_provider_failure_marks_job_failed(make_client):
    provider = ScriptedProvider(MusicResult(MusicStatus.FAILED, error="mureka: lyrics rejected"))
    client = make_client(providers={"fake": provider})
    h = register(client)
    job_id = client.post("/v1/song-jobs", json=job_body(), headers=h).json()["jobId"]
    done = wait_status(client, h, job_id, {"READY", "FAILED"})
    assert done["status"] == "FAILED"
    assert done["error"] == "mureka: lyrics rejected"
    assert done["songId"] is None and done["audioUrl"] is None


def test_submit_error_marks_job_failed(make_client):
    request = httpx.Request("POST", "https://api.mureka.ai/v1/song/generate")
    error = httpx.HTTPStatusError("401 Unauthorized", request=request, response=httpx.Response(401, request=request))
    client = make_client(providers={"fake": ScriptedProvider(submit_error=error)})
    h = register(client)
    job_id = client.post("/v1/song-jobs", json=job_body(), headers=h).json()["jobId"]
    done = wait_status(client, h, job_id, {"READY", "FAILED"})
    assert done["status"] == "FAILED"
    assert "401" in done["error"]


def test_timeout_marks_job_failed(make_client):
    provider = ScriptedProvider()  # 계속 pending
    client = make_client(providers={"fake": provider}, job_timeout_sec=0.3)
    h = register(client)
    job_id = client.post("/v1/song-jobs", json=job_body(), headers=h).json()["jobId"]
    done = wait_status(client, h, job_id, {"READY", "FAILED"})
    assert done["status"] == "FAILED"
    assert "timed out" in done["error"]
    assert provider.polls >= 2


def test_transient_poll_errors_are_retried(make_client, song_file):
    class Flaky(ScriptedProvider):
        async def poll(self, provider_job_id):
            self.polls += 1
            if self.polls < 3:
                raise httpx.ConnectError("connection reset")
            return MusicResult(MusicStatus.SUCCEEDED, audio_url=song_file.resolve().as_uri())

    client = make_client(providers={"fake": Flaky()})
    h = register(client)
    job_id = client.post("/v1/song-jobs", json=job_body(), headers=h).json()["jobId"]
    assert wait_status(client, h, job_id, {"READY", "FAILED"})["status"] == "READY"


def test_http_audio_url_is_downloaded(make_client, song_file):
    url = "https://v3b.fal.media/files/b/kangaroo/x_output.mp3"
    provider = ScriptedProvider(MusicResult(MusicStatus.SUCCEEDED, audio_url=url))
    client = make_client(providers={"fake": provider})
    seen = []

    def serve(request: httpx.Request) -> httpx.Response:
        seen.append(str(request.url))
        return httpx.Response(200, content=song_file.read_bytes())

    client.app.state.worker.client = httpx.AsyncClient(transport=httpx.MockTransport(serve))
    h = register(client)
    job_id = client.post("/v1/song-jobs", json=job_body(), headers=h).json()["jobId"]
    done = wait_status(client, h, job_id, {"READY", "FAILED"})
    assert done["status"] == "READY", done
    assert seen == [url]


def test_download_http_error_marks_job_failed(make_client):
    provider = ScriptedProvider(MusicResult(MusicStatus.SUCCEEDED, audio_url="https://cdn.example/gone.mp3"))
    client = make_client(providers={"fake": provider})
    client.app.state.worker.client = httpx.AsyncClient(
        transport=httpx.MockTransport(lambda r: httpx.Response(404))
    )
    h = register(client)
    job_id = client.post("/v1/song-jobs", json=job_body(), headers=h).json()["jobId"]
    done = wait_status(client, h, job_id, {"READY", "FAILED"})
    assert done["status"] == "FAILED"
    assert "404" in done["error"]


# --- 서버 재시작 --------------------------------------------------------------

def test_unfinished_jobs_resume_after_restart(data_dir):
    # 1차 기동: 테이블과 디바이스만 만든다
    first = create_app(make_settings(data_dir))
    with TestClient(first) as c:
        device_id = c.post("/v1/devices").json()["deviceId"]

    # 재시작 전(서버가 죽기 직전) 상태를 DB에 직접 만든다
    app = create_app(make_settings(data_dir))
    with app.state.session_factory() as s:
        now = utcnow()

        def job(status, **kw):
            j = Job(id=f"job_{secrets.token_hex(8)}", device_id=device_id, trip_id="t", lyrics=LYRICS,
                    style="kids", duration_sec=60, clip_plan=[], status=status, title="t", provider="fake", **kw)
            s.add(j)
            return j.id

        queued = job("QUEUED")
        # 제출까지 끝난 잡: 같은 공급자 잡 id 로 폴링을 이어간다 (다시 제출하지 않음)
        generating = job("GENERATING", provider_job_id=f"fake_60_{time.time():.3f}", submitted_at=now)
        mixing = job("MIXING", provider_job_id=f"fake_60_{time.time() - 10:.3f}", submitted_at=now)
        # 제한 시간이 지났고 공급자도 아직 pending 인 잡 -> FAILED
        # (fake 잡 id 의 제출 시각을 미래로 둬서 pending 이 계속되게 한다)
        stale = job("GENERATING", provider_job_id=f"fake_60_{time.time() + 600:.3f}",
                    submitted_at=now - timedelta(seconds=600))
        s.commit()

    with TestClient(app) as c:
        statuses = {}
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            with app.state.session_factory() as s:
                statuses = {j: s.get(Job, j) for j in (queued, generating, mixing, stale)}
                if all(j.status in ("READY", "FAILED") for j in statuses.values()):
                    break
            time.sleep(0.05)
        assert {k: v.status for k, v in statuses.items()} == {
            queued: "READY", generating: "READY", mixing: "READY", stale: "FAILED",
        }
        assert "timed out" in statuses[stale].error
        assert statuses[generating].provider_job_id.startswith("fake_60_")


# --- 검증 / 권한 -------------------------------------------------------------

def test_foreign_clip_rejected(sclient, audio_files):
    a, b = register(sclient), register(sclient)
    member_b = add_member(sclient, b)
    clip_b = upload(sclient, b, member_b, "INTRO", audio_files["short"]).json()["clipId"]

    res = sclient.post("/v1/song-jobs", headers=a,
                       json=job_body(clipPlan=[{"slot": "INTRO", "clipId": clip_b}]))
    assert res.status_code == 404
    res = sclient.post("/v1/song-jobs", headers=a,
                       json=job_body(clipPlan=[{"slot": "INTRO", "clipId": "clip_nope"}]))
    assert res.status_code == 404
    with sclient.app.state.session_factory() as s:
        assert s.query(Job).count() == 0


@pytest.mark.parametrize("override", [
    {"style": "jazz"},
    {"durationSec": 59},
    {"durationSec": 91},
    {"lyrics": "   "},
    {"tripId": ""},
    {"clipPlan": [{"slot": "INTRO", "clipId": "c1"}, {"slot": "INTRO", "clipId": "c2"}]},
    {"clipPlan": [{"slot": "BRIDGE", "clipId": "c1"}]},
])
def test_invalid_body_is_422(sclient, override):
    h = register(sclient)
    assert sclient.post("/v1/song-jobs", json=job_body(**override), headers=h).status_code == 422


def test_all_styles_accepted(sclient):
    h = register(sclient)
    for style in ("kids", "city_pop", "acoustic", "road_trip"):
        assert sclient.post("/v1/song-jobs", json=job_body(style=style), headers=h).status_code == 201


def test_jobs_and_songs_are_private_to_device(sclient):
    a, b = register(sclient), register(sclient)
    job_id = sclient.post("/v1/song-jobs", json=job_body(), headers=a).json()["jobId"]
    assert sclient.get(f"/v1/song-jobs/{job_id}", headers=b).status_code == 404
    assert sclient.get("/v1/song-jobs/job_nope", headers=a).status_code == 404
    done = wait_status(sclient, a, job_id, {"READY", "FAILED"})
    assert sclient.get(done["audioUrl"], headers=b).status_code == 404
    assert sclient.get("/v1/songs/song_nope/audio", headers=a).status_code == 404
    assert sclient.get(done["audioUrl"]).status_code == 401
    assert sclient.post("/v1/song-jobs", json=job_body()).status_code == 401


def test_audio_supports_range_requests(sclient):
    h = register(sclient)
    job_id = sclient.post("/v1/song-jobs", json=job_body(), headers=h).json()["jobId"]
    url = wait_status(sclient, h, job_id, {"READY", "FAILED"})["audioUrl"]

    full = sclient.get(url, headers=h)
    total = len(full.content)
    assert full.headers["accept-ranges"] == "bytes"
    assert int(full.headers["content-length"]) == total > 1000

    part = sclient.get(url, headers={**h, "Range": "bytes=0-99"})
    assert part.status_code == 206
    assert part.headers["content-range"] == f"bytes 0-99/{total}"
    assert part.content == full.content[:100]

    tail = sclient.get(url, headers={**h, "Range": f"bytes={total - 50}-"})
    assert tail.status_code == 206
    assert tail.content == full.content[-50:]

    bad = sclient.get(url, headers={**h, "Range": f"bytes={total + 10}-"})
    assert bad.status_code == 416


def test_audio_stream_does_not_hold_db_connections(sclient):
    """응답 전송 중에는 풀 커넥션을 잡고 있지 않는다 (느린 다운로드 여럿이 풀을 고갈시키지 않도록)."""
    h = register(sclient)
    job_id = sclient.post("/v1/song-jobs", json=job_body(), headers=h).json()["jobId"]
    url = wait_status(sclient, h, job_id, {"READY", "FAILED"})["audioUrl"]
    app = sclient.app
    pool = app.state.session_factory.kw["bind"].pool
    seen = []

    async def probe(scope, receive, send):
        async def recording_send(message):
            if message["type"] in ("http.response.start", "http.response.body"):
                seen.append(pool.checkedout())
            await send(message)

        await app(scope, receive, recording_send)

    res = TestClient(probe).get(url, headers=h)  # lifespan 은 sclient 가 이미 실행 중
    assert res.status_code == 200
    assert seen and set(seen) == {0}


# --- 삭제 (설계 §8.1, 개인정보 처리방침 §4/§6) ----------------------------------

def test_delete_song_removes_audio_job_and_file(sclient, data_dir, audio_files):
    a, b = register(sclient), register(sclient)
    member = add_member(sclient, a, nickname="아이", isChild=True, guardianConsent=True)
    call = upload(sclient, a, member, "CALL", audio_files["short"]).json()["clipId"]
    job_id = sclient.post("/v1/song-jobs", headers=a,
                          json=job_body(clipPlan=[{"slot": "CALL", "clipId": call}])).json()["jobId"]
    done = wait_status(sclient, a, job_id, {"READY", "FAILED"})
    assert done["status"] == "READY", done
    song_id = done["songId"]
    # 구성원을 지워도 이미 믹싱된 노래는 남는다 -> 노래 삭제로 지운다
    assert sclient.delete(f"/v1/members/{member}", headers=a).status_code == 204
    assert sclient.get(done["audioUrl"], headers=a).status_code == 200

    assert sclient.delete(f"/v1/songs/{song_id}").status_code == 401
    assert sclient.delete(f"/v1/songs/{song_id}", headers=b).status_code == 404  # 다른 디바이스
    assert sclient.delete(f"/v1/songs/{song_id}", headers=a).status_code == 204

    assert sclient.get(done["audioUrl"], headers=a).status_code == 404
    assert sclient.get(f"/v1/song-jobs/{job_id}", headers=a).status_code == 404
    assert sclient.delete(f"/v1/songs/{song_id}", headers=a).status_code == 404
    assert list((data_dir / "songs").glob("*.m4a")) == []
    with sclient.app.state.session_factory() as s:
        assert s.get(Song, song_id) is None
        assert s.get(Job, job_id) is None  # 가사(구성원 별명 포함)도 삭제


def test_delete_all_songs_removes_device_data_and_stops_running_job(make_client, monkeypatch, data_dir, song_file):
    def fast_mix(song, clips, chorus_start, chorus_end, out, vocal_phrases=None):
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(b"mixed")

    monkeypatch.setattr("app.jobs.worker.mix_song", fast_mix)
    provider = ScriptedProvider()  # pending 으로 시작
    client = make_client(providers={"fake": provider})
    a, b = register(client), register(client)

    def ready(headers):
        provider.result = MusicResult(MusicStatus.SUCCEEDED, audio_url=song_file.resolve().as_uri())
        job_id = client.post("/v1/song-jobs", json=job_body(), headers=headers).json()["jobId"]
        return wait_status(client, headers, job_id, {"READY", "FAILED"})

    mine, theirs = ready(a), ready(b)
    assert mine["status"] == theirs["status"] == "READY"
    provider.result = MusicResult(MusicStatus.FAILED, error="nope")
    failed = client.post("/v1/song-jobs", json=job_body(), headers=a).json()["jobId"]
    assert wait_status(client, a, failed, {"READY", "FAILED"})["status"] == "FAILED"
    provider.result = MusicResult(MusicStatus.PENDING)
    running = client.post("/v1/song-jobs", json=job_body(), headers=a).json()["jobId"]
    wait_status(client, a, running, {"GENERATING"})

    assert client.delete("/v1/songs").status_code == 401
    assert client.delete("/v1/songs", headers=a).status_code == 204

    assert client.get(mine["audioUrl"], headers=a).status_code == 404
    for job_id in (mine["jobId"], failed, running):
        assert client.get(f"/v1/song-jobs/{job_id}", headers=a).status_code == 404
    # 다른 디바이스의 노래는 그대로
    assert client.get(theirs["audioUrl"], headers=b).status_code == 200

    # 진행 중이던 잡은 공급자가 끝나도 노래를 만들지 않고 조용히 멈춘다
    provider.result = MusicResult(MusicStatus.SUCCEEDED, audio_url=song_file.resolve().as_uri())
    worker = client.app.state.worker
    deadline = time.monotonic() + 60
    while worker._tasks:
        assert time.monotonic() < deadline
        time.sleep(0.02)
    with client.app.state.session_factory() as s:
        assert [job.id for job in s.query(Job)] == [theirs["jobId"]]
        assert [song.id for song in s.query(Song)] == [theirs["songId"]]
    assert [p.name for p in (data_dir / "songs").glob("*.m4a")] == [f"{theirs['songId']}.m4a"]
    assert client.delete("/v1/songs", headers=a).status_code == 204  # 비어 있어도 204


def test_job_deleted_during_mixing_leaves_no_song(make_client, monkeypatch, data_dir, song_file):
    mix_started, mix_release = threading.Event(), threading.Event()

    def gated_mix(song, clips, chorus_start, chorus_end, out, vocal_phrases=None):
        mix_started.set()
        assert mix_release.wait(10)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(b"mixed")  # 실제 믹싱은 느리고 여기서는 필요 없다

    monkeypatch.setattr("app.jobs.worker.mix_song", gated_mix)
    provider = ScriptedProvider(MusicResult(MusicStatus.SUCCEEDED, audio_url=song_file.resolve().as_uri()))
    client = make_client(providers={"fake": provider})
    h = register(client)
    client.post("/v1/song-jobs", json=job_body(), headers=h)
    assert mix_started.wait(10)
    assert client.delete("/v1/songs", headers=h).status_code == 204
    mix_release.set()

    worker = client.app.state.worker
    deadline = time.monotonic() + 60
    while worker._tasks:
        assert time.monotonic() < deadline
        time.sleep(0.02)
    with client.app.state.session_factory() as s:
        assert s.query(Song).count() == 0 and s.query(Job).count() == 0
    assert list((data_dir / "songs").glob("*.m4a")) == []
