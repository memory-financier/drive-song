"""v0.4 대화 → 가족 목소리 노래 (설계 §7.5, §8.3, §9.1, T17)."""

import asyncio
import json
import subprocess
import threading
import time
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, inspect, text

from app.api.clips import probe_audio_duration
from app.db import Base, add_missing_columns
from app.jobs.models import Job
from app.jobs.worker import conversation_lines, plan_moments
from app.main import create_app
from app.models import ConversationChunk, Member
from app.providers.llm.base import LlmError, LyricsInput, build_prompt, fit_conversation
from app.providers.llm.fake import FakeLlm
from app.providers.llm.factory import make_llm_provider
from app.providers.llm.ollama import OllamaLlm
from app.providers.music.base import Style
from app.providers.voice.base import Analysis, MemberVoice, Singer, SpokenSegment, Sung
from app.providers.voice.fake import FAKE_LINES, FakeVoice
from app.providers.voice.factory import make_voice_providers
from app.providers.voice.voicelab import VoiceLab
from app.settings import Settings
from conftest import add_member, register, upload

TRIP = "trip_20261104_0900"


def _tone(path: Path, seconds: float, freq: int = 220) -> Path:
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"sine=frequency={freq}:duration={seconds}",
         "-ac", "1", "-ar", "16000", "-c:a", "aac", "-b:a", "32k", str(path)],
        check=True,
    )
    return path


@pytest.fixture(scope="session")
def talk(tmp_path_factory) -> dict[str, Path]:
    d = tmp_path_factory.mktemp("talk")
    return {"long": _tone(d / "talk.m4a", 12), "short": _tone(d / "short.m4a", 6), "huge": _tone(d / "huge.m4a", 306)}


@pytest.fixture
def make_client(data_dir):
    opened = []

    def _make(**overrides):
        values = dict(_env_file=None, data_dir=data_dir, fake_music_delay_sec=0.2,
                      job_poll_interval_sec=0.05, job_timeout_sec=20)
        values.update(overrides)
        c = TestClient(create_app(Settings(**values)))
        c.__enter__()
        opened.append(c)
        return c

    yield _make
    for c in opened:
        c.__exit__(None, None, None)


@pytest.fixture
def cclient(make_client):
    return make_client()


def enriched(passengers, trip_id=TRIP) -> dict:
    return {
        "tripId": trip_id, "startedAt": "2026-11-04T09:00:00+09:00", "origin": {"lat": 37.56, "lng": 126.98},
        "distanceKm": 237.4, "durationMin": 180, "avgSpeedKmh": 79, "maxSpeedKmh": 110, "timeOfDay": "MORNING",
        "passengers": list(passengers), "originName": "서울 중구", "destinationName": "강릉시",
        "placeNames": ["서울 중구", "평창군", "강릉시"], "weather": {"description": "맑음", "temperatureC": 11.0},
    }


def conv_job(passengers, **overrides) -> dict:
    body = {"tripId": TRIP, "style": "road_trip", "durationSec": 60, "conversation": True,
            "enrichedTrip": enriched(passengers)}
    body.update(overrides)
    return body


def put_chunk(client, h, passengers, file: Path, seq=0, trip_id=TRIP):
    with file.open("rb") as f:
        return client.post(f"/v1/trips/{trip_id}/conversation", data={"seq": str(seq), "passengers": passengers},
                           files={"audio": (file.name, f, "audio/mp4")}, headers=h)


def family(client, h, audio_files, voice=("엄마", "첫째")) -> dict[str, str]:
    """엄마·아빠·첫째: 모두 대화 녹음 동의, voice 에 든 사람만 목소리 변환 동의. 등록 클립 하나씩."""
    ids = {}
    for nick in ("엄마", "아빠", "첫째"):
        child = nick == "첫째"
        mid = add_member(client, h, nickname=nick, isChild=child, guardianConsent=child,
                         conversationConsent=True, voiceConversionConsent=nick in voice)
        assert upload(client, h, mid, "CALL", audio_files["short"]).status_code == 201
        ids[nick] = mid
    return ids


def wait_status(client, h, job_id, statuses, timeout=30.0) -> dict:
    deadline = time.monotonic() + timeout
    while True:
        body = client.get(f"/v1/song-jobs/{job_id}", headers=h).json()
        if body["status"] in statuses:
            return body
        assert time.monotonic() < deadline, f"stuck in {body}"
        time.sleep(0.02)


def chunks_left(client) -> int:
    with client.app.state.session_factory() as s:
        return len(s.query(ConversationChunk).all())


class RecordingLlm(FakeLlm):
    def __init__(self, external=False, fail=False):
        self.external = external
        self.fail = fail
        self.inputs: list[LyricsInput] = []

    async def write_lyrics(self, inp):
        self.inputs.append(inp)
        if self.fail:
            raise LlmError("down")
        return await super().write_lyrics(inp)


def use(client, *, llm=None, analyzer=None, converter=None):
    worker = client.app.state.worker
    if llm:
        worker.llm = llm
    if analyzer:
        worker.analyzer = analyzer
    if converter:
        worker.converter = converter


# --- DB: 기존 DB 에 새 열 추가 --------------------------------------------------------

def test_add_missing_columns_upgrades_an_old_database(tmp_path):
    engine = create_engine(f"sqlite:///{(tmp_path / 'old.db').as_posix()}")
    Base.metadata.create_all(engine)
    with engine.begin() as conn:  # v0.3 DB 흉내: 새 열이 없는 테이블
        conn.execute(text("INSERT INTO devices (id, token_hash, created_at) VALUES ('dev_1', 'h', '2026-11-01')"))
        conn.execute(text("INSERT INTO members (id, device_id, nickname, is_child, voice_consent, guardian_consent, "
                          "conversation_consent, voice_conversion_consent, created_at) "
                          "VALUES ('mem_1', 'dev_1', '엄마', 0, 1, 0, 0, 0, '2026-11-01')"))
        for col in ("conversation_consent", "voice_conversion_consent", "voice_ref_path"):
            conn.execute(text(f"ALTER TABLE members DROP COLUMN {col}"))
        conn.execute(text("ALTER TABLE jobs DROP COLUMN moments"))
    added = add_missing_columns(engine)
    assert set(added) == {"members.conversation_consent", "members.voice_conversion_consent",
                          "members.voice_ref_path", "jobs.moments"}
    assert add_missing_columns(engine) == []
    names = {c["name"] for c in inspect(engine).get_columns("members")}
    assert {"conversation_consent", "voice_conversion_consent", "voice_ref_path"} <= names
    with engine.connect() as conn:  # 기존 행은 동의 false
        assert conn.execute(text("SELECT conversation_consent FROM members")).scalar() == 0


# --- 구성원 동의 (§8.3) ---------------------------------------------------------------

def test_member_consents_default_false_and_patch(cclient, data_dir):
    h = register(cclient)
    res = cclient.post("/v1/members", headers=h, json={
        "nickname": "아빠", "isChild": False, "voiceConsent": True, "guardianConsent": False})
    body = res.json()
    assert body["conversationConsent"] is False and body["voiceConversionConsent"] is False
    mid = body["memberId"]

    res = cclient.patch(f"/v1/members/{mid}", headers=h, json={"conversationConsent": True})
    assert res.status_code == 200
    assert res.json()["conversationConsent"] is True and res.json()["voiceConversionConsent"] is False
    res = cclient.patch(f"/v1/members/{mid}", headers=h, json={"voiceConversionConsent": True})
    assert res.json()["conversationConsent"] is True and res.json()["voiceConversionConsent"] is True

    other = register(cclient)
    assert cclient.patch(f"/v1/members/{mid}", headers=other, json={"conversationConsent": False}).status_code == 404
    assert cclient.patch(f"/v1/members/{mid}", headers=h, json={"conversationConsent": "maybe"}).status_code == 422


def _store_ref(client, data_dir, mid) -> Path:
    path = data_dir / "voices" / f"{mid}.wav"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"ref")
    with client.app.state.session_factory() as s:
        m = s.get(Member, mid)
        m.voice_ref_path, m.voice_ref_sec, m.voice_ref_f0_hz = f"voices/{mid}.wav", 20.0, 210.0
        s.commit()
    return path


def test_revoking_voice_conversion_or_deleting_member_deletes_reference(cclient, data_dir):
    h = register(cclient)
    a = add_member(cclient, h, nickname="엄마", conversationConsent=True, voiceConversionConsent=True)
    b = add_member(cclient, h, nickname="아빠", conversationConsent=True, voiceConversionConsent=True)
    ref_a, ref_b = _store_ref(cclient, data_dir, a), _store_ref(cclient, data_dir, b)

    cclient.patch(f"/v1/members/{a}", headers=h, json={"conversationConsent": False})
    assert ref_a.exists()  # 대화 동의 철회만으로는 참고 목소리를 지우지 않는다
    cclient.patch(f"/v1/members/{a}", headers=h, json={"voiceConversionConsent": False})
    assert not ref_a.exists()
    with cclient.app.state.session_factory() as s:
        m = s.get(Member, a)
        assert m.voice_ref_path is None and m.voice_ref_sec is None and m.voice_ref_f0_hz is None

    assert cclient.delete(f"/v1/members/{b}", headers=h).status_code == 204
    assert not ref_b.exists()


# --- 대화 업로드·삭제 (§9.1) ----------------------------------------------------------

def test_upload_requires_every_passenger_consent(cclient, talk):
    h = register(cclient)
    yes = add_member(cclient, h, nickname="엄마", conversationConsent=True)
    no = add_member(cclient, h, nickname="아빠")
    res = put_chunk(cclient, h, [yes, no], talk["long"])
    assert res.status_code == 400 and "conversationConsent" in res.json()["detail"]
    assert put_chunk(cclient, h, [yes, "mem_nobody"], talk["long"]).status_code == 404
    other = register(cclient)
    assert put_chunk(cclient, other, [yes], talk["long"]).status_code == 404  # 남의 구성원
    assert put_chunk(cclient, h, [], talk["long"]).status_code == 422
    assert chunks_left(cclient) == 0

    res = put_chunk(cclient, h, [yes], talk["long"], seq=3)
    assert res.status_code == 201, res.text
    assert res.json()["tripId"] == TRIP and res.json()["seq"] == 3
    assert abs(res.json()["durationSec"] - 12) < 0.3


def test_upload_rejects_bad_audio_and_replaces_same_seq(cclient, data_dir, talk, tmp_path):
    h = register(cclient)
    mid = add_member(cclient, h, conversationConsent=True)
    junk = tmp_path / "junk.m4a"
    junk.write_bytes(b"not audio")
    assert put_chunk(cclient, h, [mid], junk).status_code == 400
    assert put_chunk(cclient, h, [mid], talk["huge"]).status_code == 400  # 5분 넘는 조각
    assert put_chunk(cclient, h, [mid], talk["long"], seq=0).status_code == 201
    first = list((data_dir / "conversations").iterdir())
    assert put_chunk(cclient, h, [mid], talk["short"], seq=0).status_code == 201  # 재시도는 바꿔 넣는다
    files = list((data_dir / "conversations").iterdir())
    assert len(files) == 1 and files != first
    with cclient.app.state.session_factory() as s:
        (chunk,) = s.query(ConversationChunk).all()
        assert abs(chunk.duration_sec - 6) < 0.3


def test_delete_conversation_removes_chunks_of_that_trip_only(cclient, data_dir, talk):
    h = register(cclient)
    mid = add_member(cclient, h, conversationConsent=True)
    put_chunk(cclient, h, [mid], talk["long"], seq=0)
    put_chunk(cclient, h, [mid], talk["long"], seq=1)
    put_chunk(cclient, h, [mid], talk["long"], seq=0, trip_id="trip_other")
    assert cclient.delete(f"/v1/trips/{TRIP}/conversation", headers=h).status_code == 204
    assert chunks_left(cclient) == 1
    assert len(list((data_dir / "conversations").iterdir())) == 1
    assert cclient.delete(f"/v1/trips/{TRIP}/conversation", headers=h).status_code == 204  # 없어도 204
    other = register(cclient)
    cclient.delete("/v1/trips/trip_other/conversation", headers=other)
    assert chunks_left(cclient) == 1  # 다른 디바이스는 못 지운다

    # 설정 > 모든 데이터 삭제 (DELETE /v1/songs) 는 대화 조각도 지운다
    assert cclient.delete("/v1/songs", headers=h).status_code == 204
    assert chunks_left(cclient) == 0 and list((data_dir / "conversations").iterdir()) == []


# --- 잡 요청 검증 ---------------------------------------------------------------------

def test_conversation_job_validation(cclient):
    h = register(cclient)
    mid = add_member(cclient, h, conversationConsent=True)
    base = {"tripId": TRIP, "style": "kids", "durationSec": 60}
    assert cclient.post("/v1/song-jobs", headers=h, json=base).status_code == 422  # 가사도 대화도 없음
    assert cclient.post("/v1/song-jobs", headers=h, json={**base, "conversation": True}).status_code == 422
    mismatch = conv_job([mid], enrichedTrip=enriched([mid], trip_id="trip_x"))
    assert cclient.post("/v1/song-jobs", headers=h, json=mismatch).status_code == 422
    assert cclient.post("/v1/song-jobs", headers=h, json=conv_job(["mem_nobody"])).status_code == 404

    res = cclient.post("/v1/song-jobs", headers=h, json=conv_job([mid]))
    assert res.status_code == 201, res.text
    assert res.json()["title"] == "강릉시 가는 길" and res.json()["lyrics"] is None
    with cclient.app.state.session_factory() as s:
        job = s.get(Job, res.json()["jobId"])
        assert job.conversation and job.passengers == [mid]
        assert "origin" not in job.lyrics_input and "waypoints" not in job.lyrics_input  # 좌표는 저장하지 않는다
        assert job.lyrics_input["destination_name"] == "강릉시"


# --- 전체 흐름 (fake 분석·변환) ------------------------------------------------------

def test_full_conversation_flow_reaches_ready(cclient, data_dir, audio_files, talk):
    h = register(cclient)
    ids = family(cclient, h, audio_files)
    passengers = [ids["엄마"], ids["아빠"], ids["첫째"]]
    llm, voice = RecordingLlm(), FakeVoice()
    use(cclient, llm=llm, analyzer=voice, converter=voice)
    assert put_chunk(cclient, h, passengers, talk["long"], seq=0).status_code == 201
    assert put_chunk(cclient, h, passengers, talk["short"], seq=1).status_code == 201

    res = cclient.post("/v1/song-jobs", headers=h, json=conv_job(passengers))
    assert res.status_code == 201, res.text
    done = wait_status(cclient, h, res.json()["jobId"], {"READY", "FAILED"})
    assert done["status"] == "READY", done

    # 분석: 조각 2개(순번 순서), 대화 동의한 탑승자 전원의 등록 클립
    (audio, enroll, _), = voice.analyze_calls
    assert len(audio) == 2 and set(enroll) == set(passengers)
    # 가사: 대화 "호칭: 말" 이 로컬 LLM 프롬프트로 들어가고 가사에 첫 말이 들어간다
    (inp,) = llm.inputs
    assert inp.conversation == [f"엄마: {FAKE_LINES[0]}", f"아빠: {FAKE_LINES[1]}", f"첫째: {FAKE_LINES[2]}"]
    assert inp.family_nicknames == ["엄마", "아빠", "첫째"] and inp.destination_name == "강릉시"
    assert FAKE_LINES[0] in done["lyrics"]
    # 목소리: 변환 동의한 엄마·첫째만 부른다. 참고 목소리는 voices/ 에 보관
    assert done["singers"] == [ids["엄마"], ids["첫째"]]
    (_, singers, _, _), = voice.sing_calls
    assert [s.member_id for s in singers] == [ids["엄마"], ids["첫째"]]
    with cclient.app.state.session_factory() as s:
        mom, dad = s.get(Member, ids["엄마"]), s.get(Member, ids["아빠"])
        assert mom.voice_ref_path == f"voices/{ids['엄마']}.wav" and mom.voice_ref_f0_hz == 200.0
        assert (data_dir / mom.voice_ref_path).exists()
        assert dad.voice_ref_path is None  # 변환 동의 없음 → 보관하지 않음
        job = s.get(Job, done["jobId"])
        assert [m["slot"] for m in job.moments] == ["INTRO", "CALL", "OUTRO"]
        assert [m["memberId"] for m in job.moments] == passengers
    # 한마디 3개 믹싱: INTRO 2초 지연 + 곡 60초 + OUTRO 3초
    out = data_dir / "song.m4a"
    out.write_bytes(cclient.get(done["audioUrl"], headers=h).content)
    assert abs(probe_audio_duration(out) - 65) < 0.6
    # 원본 대화·작업 폴더 삭제
    assert chunks_left(cclient) == 0 and list((data_dir / "conversations").iterdir()) == []
    assert not (data_dir / "work" / done["jobId"]).exists()
    # 노래가 시작된 주행의 뒤늦은 조각은 받지 않는다
    assert put_chunk(cclient, h, passengers, talk["short"], seq=2).status_code == 409


def test_clip_plan_slots_win_over_moments(cclient, data_dir, audio_files, talk):
    h = register(cclient)
    ids = family(cclient, h, audio_files, voice=())
    passengers = [ids["엄마"], ids["아빠"], ids["첫째"]]
    intro = upload(cclient, h, ids["아빠"], "INTRO", audio_files["short"]).json()["clipId"]
    put_chunk(cclient, h, passengers, talk["long"])
    res = cclient.post("/v1/song-jobs", headers=h,
                       json=conv_job(passengers, clipPlan=[{"slot": "INTRO", "clipId": intro}]))
    done = wait_status(cclient, h, res.json()["jobId"], {"READY", "FAILED"})
    assert done["status"] == "READY" and done["singers"] == []
    with cclient.app.state.session_factory() as s:
        job = s.get(Job, done["jobId"])
        # 아빠는 INTRO 클립이 있으니 한마디는 엄마·첫째가 먼저 (가족 모두가 노래에 담기게)
        assert [(m["slot"], m["memberId"]) for m in job.moments] == [("CALL", ids["엄마"]), ("OUTRO", ids["첫째"])]


def test_statuses_pass_through_analyzing_and_converting(cclient, audio_files, talk):
    h = register(cclient)
    ids = family(cclient, h, audio_files)
    passengers = list(ids.values())
    gates = {"analyze": threading.Event(), "sing": threading.Event()}

    class GatedVoice(FakeVoice):
        async def analyze(self, *a):
            await asyncio.to_thread(gates["analyze"].wait, 10)
            return await super().analyze(*a)

        async def sing(self, *a):
            await asyncio.to_thread(gates["sing"].wait, 10)
            return await super().sing(*a)

    voice = GatedVoice()
    use(cclient, analyzer=voice, converter=voice)
    put_chunk(cclient, h, passengers, talk["long"])
    job_id = cclient.post("/v1/song-jobs", headers=h, json=conv_job(passengers)).json()["jobId"]
    assert wait_status(cclient, h, job_id, {"ANALYZING"})["lyrics"] is None
    gates["analyze"].set()
    converting = wait_status(cclient, h, job_id, {"CONVERTING"})
    assert converting["lyrics"] and converting["singers"] == []  # 부른 사람은 READY 때 알려 준다
    gates["sing"].set()
    assert wait_status(cclient, h, job_id, {"READY"})["singers"] == [ids["엄마"], ids["첫째"]]


# --- 대체 경로 (§7.5) -----------------------------------------------------------------

class BrokenVoice(FakeVoice):
    def __init__(self, analyze=False, sing=False):
        super().__init__()
        self.broken = {"analyze": analyze, "sing": sing}

    async def analyze(self, *a):
        if self.broken["analyze"]:
            raise httpx.ConnectError("voice-lab down")
        return await super().analyze(*a)

    async def sing(self, *a):
        if self.broken["sing"]:
            raise httpx.ReadTimeout("seed-vc stuck")
        return await super().sing(*a)


def test_analysis_failure_falls_back_to_trip_lyrics_and_deletes_conversation(cclient, data_dir, audio_files, talk):
    h = register(cclient)
    ids = family(cclient, h, audio_files)
    llm = RecordingLlm()
    use(cclient, llm=llm, analyzer=BrokenVoice(analyze=True))
    put_chunk(cclient, h, list(ids.values()), talk["long"])
    job_id = cclient.post("/v1/song-jobs", headers=h, json=conv_job(list(ids.values()))).json()["jobId"]
    done = wait_status(cclient, h, job_id, {"READY", "FAILED"})
    assert done["status"] == "READY" and done["singers"] == []  # 참고 목소리가 없으니 AI 가수
    assert llm.inputs[0].conversation == []
    assert chunks_left(cclient) == 0 and list((data_dir / "conversations").iterdir()) == []


def test_conversion_failure_keeps_ai_singer(cclient, audio_files, talk):
    h = register(cclient)
    ids = family(cclient, h, audio_files)
    voice = BrokenVoice(sing=True)
    use(cclient, analyzer=voice, converter=voice)
    put_chunk(cclient, h, list(ids.values()), talk["long"])
    job_id = cclient.post("/v1/song-jobs", headers=h, json=conv_job(list(ids.values()))).json()["jobId"]
    done = wait_status(cclient, h, job_id, {"READY", "FAILED"})
    assert done["status"] == "READY" and done["singers"] == []


def test_vocal_phrases_reach_the_mixer(cclient, audio_files, talk, monkeypatch):
    """변환이 알려 준 노래 구간이 믹서로 간다 (CALL 을 노래 없는 곳에, §7.4)."""
    h = register(cclient)
    ids = family(cclient, h, audio_files)

    class PhraseVoice(FakeVoice):
        async def sing(self, song_path, singers, out_path, work_dir):
            sung = await super().sing(song_path, singers, out_path, work_dir)
            return Sung(sung.out_path, sung.singers, [(19.5, 23.4)])

    seen = []

    def spy_mix(song, clips, chorus_start, chorus_end, out, vocal_phrases=None):
        seen.append(vocal_phrases)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(b"mixed")

    monkeypatch.setattr("app.jobs.worker.mix_song", spy_mix)
    voice = PhraseVoice()
    use(cclient, analyzer=voice, converter=voice)
    put_chunk(cclient, h, list(ids.values()), talk["long"])
    job_id = cclient.post("/v1/song-jobs", headers=h, json=conv_job(list(ids.values()))).json()["jobId"]
    assert wait_status(cclient, h, job_id, {"READY", "FAILED"})["status"] == "READY"
    assert seen == [[(19.5, 23.4)]]


def test_no_conversation_uploaded_still_makes_a_song(cclient, audio_files):
    h = register(cclient)
    ids = family(cclient, h, audio_files)
    voice = FakeVoice()
    use(cclient, analyzer=voice, converter=voice)
    job_id = cclient.post("/v1/song-jobs", headers=h, json=conv_job(list(ids.values()))).json()["jobId"]
    done = wait_status(cclient, h, job_id, {"READY", "FAILED"})
    assert done["status"] == "READY" and "강릉시" in done["lyrics"]
    assert voice.analyze_calls == [] and done["singers"] == []


def test_stored_reference_lets_a_quiet_member_sing_and_longer_one_wins(cclient, data_dir, audio_files, talk):
    """이번 대화가 짧아도 보관된 참고 목소리로 부른다. 같거나 긴 참고 목소리만 보관본을 바꾼다."""
    h = register(cclient)
    mom = add_member(cclient, h, nickname="엄마", conversationConsent=True, voiceConversionConsent=True)
    upload(cclient, h, mom, "CALL", audio_files["short"])
    stored = _store_ref(cclient, data_dir, mom)  # 20초 보관본

    class ShortRef(FakeVoice):
        def __init__(self, sec):
            super().__init__()
            self.sec = sec

        async def analyze(self, audio, enroll, out_dir):
            ref = out_dir / "refs" / f"{mom}.wav"
            ref.parent.mkdir(parents=True, exist_ok=True)
            ref.write_bytes(b"new")
            return Analysis(12.0, [], [MemberVoice(mom, ref_path=ref, ref_sec=self.sec, ref_f0_hz=180.0)])

    for sec, expected in ((12.0, b"ref"), (25.0, b"new")):
        voice = ShortRef(sec)
        use(cclient, analyzer=voice, converter=voice)
        trip = f"trip_{int(sec)}"
        put_chunk(cclient, h, [mom], talk["long"], trip_id=trip)
        body = conv_job([mom], tripId=trip, enrichedTrip=enriched([mom], trip_id=trip))
        done = wait_status(cclient, h, cclient.post("/v1/song-jobs", headers=h, json=body).json()["jobId"],
                           {"READY", "FAILED"})
        assert done["status"] == "READY" and done["singers"] == [mom]
        assert stored.read_bytes() == expected
    with cclient.app.state.session_factory() as s:
        assert s.get(Member, mom).voice_ref_sec == 25.0


def test_external_llm_never_gets_the_conversation(cclient, audio_files, talk):
    h = register(cclient)
    ids = family(cclient, h, audio_files)
    llm = RecordingLlm(external=True)
    use(cclient, llm=llm)
    put_chunk(cclient, h, list(ids.values()), talk["long"])
    job_id = cclient.post("/v1/song-jobs", headers=h, json=conv_job(list(ids.values()))).json()["jobId"]
    assert wait_status(cclient, h, job_id, {"READY", "FAILED"})["status"] == "READY"
    assert llm.inputs[0].conversation == []


def test_lyrics_failure_fails_job_and_deletes_conversation(cclient, audio_files, talk):
    h = register(cclient)
    ids = family(cclient, h, audio_files)
    llm = RecordingLlm(fail=True)
    use(cclient, llm=llm)
    put_chunk(cclient, h, list(ids.values()), talk["long"])
    job_id = cclient.post("/v1/song-jobs", headers=h, json=conv_job(list(ids.values()))).json()["jobId"]
    done = wait_status(cclient, h, job_id, {"READY", "FAILED"})
    assert done["status"] == "FAILED" and done["error"] == "lyrics generation failed"
    assert len(llm.inputs) == 2  # 1회 재요청
    assert chunks_left(cclient) == 0


def test_given_lyrics_are_kept_in_conversation_job(cclient, audio_files, talk):
    h = register(cclient)
    ids = family(cclient, h, audio_files)
    llm = RecordingLlm()
    use(cclient, llm=llm)
    put_chunk(cclient, h, list(ids.values()), talk["long"])
    lyrics = "[verse]\n앱이 쓴 가사"
    job_id = cclient.post("/v1/song-jobs", headers=h, json=conv_job(list(ids.values()), lyrics=lyrics)).json()["jobId"]
    done = wait_status(cclient, h, job_id, {"READY", "FAILED"})
    assert done["status"] == "READY" and done["lyrics"] == lyrics and llm.inputs == []


# --- 순수 함수 ------------------------------------------------------------------------

def test_plan_moments_fills_free_slots_in_order():
    m = [("a", Path("a.wav")), ("b", Path("b.wav")), ("c", Path("c.wav"))]
    assert [x["slot"] for x in plan_moments(set(), m)] == ["INTRO", "CALL", "OUTRO"]
    assert plan_moments({"INTRO", "OUTRO"}, m) == [{"slot": "CALL", "memberId": "a", "path": "a.wav"}]
    assert plan_moments({"INTRO", "CALL", "OUTRO"}, m) == []
    assert [x["memberId"] for x in plan_moments(set(), m[:1])] == ["a"]


def test_conversation_lines_drop_unknown_and_empty():
    a = Analysis(10, [SpokenSegment(0, 1, "m1", " 바다다 "), SpokenSegment(1, 2, None, "누구지"),
                      SpokenSegment(2, 3, "m2", "  "), SpokenSegment(3, 4, "m3", "동의 안 한 사람")])
    assert conversation_lines(a, {"m1": "엄마", "m2": "아빠"}) == ["엄마: 바다다"]


def test_fit_conversation_keeps_order_and_budget():
    lines = [f"엄마: {i:03d} 번째 말" for i in range(500)]
    fit = fit_conversation(lines, 2000)
    assert sum(len(x) + 1 for x in fit) <= 2000 and len(fit) > 100
    assert fit == sorted(fit) and fit[0] == lines[0]
    assert fit_conversation(lines[:3]) == lines[:3]


def test_build_prompt_with_conversation():
    inp = LyricsInput("서울 중구", "강릉시", ["서울 중구", "강릉시"], "MORNING", None, 180, 237.4, Style.ROAD_TRIP,
                      ["엄마", "첫째"], ["엄마: 바다 보러 가자!", "첫째: 호두과자!"])
    prompt = build_prompt(inp)
    assert "오늘의 드라이브 정보와 차 안에서 나눈 가족 대화로" in prompt
    assert "[차 안 대화]\n엄마: 바다 보러 가자!\n첫째: 호두과자!\n\n[규칙]" in prompt
    assert "- 대화 속 인상적인 말을 최소 2개 그대로 또는 살짝 바꿔 넣기" in prompt
    plain = build_prompt(LyricsInput("서울 중구", "강릉시", [], "MORNING", None, 180, 237.4, Style.ROAD_TRIP))
    assert "대화" not in plain


# --- 어댑터 (MockTransport) ------------------------------------------------------------

def test_ollama_request_and_errors():
    seen = []

    def respond(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json={"message": {"role": "assistant", "content": " [verse]\n가사 \n"}})

    llm = OllamaLlm("http://ollama:11434/", "gemma3:12b", httpx.AsyncClient(transport=httpx.MockTransport(respond)))
    assert llm.external is False
    inp = LyricsInput(None, "강릉시", [], "MORNING", None, 10, 3.0, Style.KIDS, [], ["엄마: 안녕"])
    assert asyncio.run(llm.write_lyrics(inp)) == "[verse]\n가사"
    (body,) = seen
    assert body["model"] == "gemma3:12b" and body["stream"] is False and body["keep_alive"] == 0
    assert body["options"]["num_ctx"] == 8192 and "엄마: 안녕" in body["messages"][0]["content"]

    for respond in (lambda r: httpx.Response(500, text="boom"), lambda r: httpx.Response(200, json={"x": 1}),
                    lambda r: httpx.Response(200, json={"message": {"content": "  "}})):
        bad = OllamaLlm("http://o", "m", httpx.AsyncClient(transport=httpx.MockTransport(respond)))
        with pytest.raises(LlmError):
            asyncio.run(bad.write_lyrics(inp))


def test_provider_factories():
    client = httpx.AsyncClient()
    assert isinstance(make_llm_provider(Settings(_env_file=None, llm_provider="ollama"), client), OllamaLlm)
    analyzer, converter = make_voice_providers(Settings(_env_file=None), client)
    assert isinstance(analyzer, FakeVoice) and analyzer is converter
    analyzer, _ = make_voice_providers(Settings(_env_file=None, voice_provider="voicelab"), client)
    assert isinstance(analyzer, VoiceLab)
    with pytest.raises(ValueError):
        make_voice_providers(Settings(_env_file=None, voice_provider="gpu"), client)


def test_voicelab_request_and_response_mapping(tmp_path):
    seen = {}

    def respond(request):
        seen[request.url.path] = json.loads(request.content)
        if request.url.path == "/v1/analyze":
            return httpx.Response(200, json={
                "durationSec": 40.0,
                "segments": [{"start": 0.0, "end": 2.0, "memberId": "mem_a", "score": 0.7, "text": "안녕"},
                             {"start": 3.0, "end": 4.0, "memberId": None, "score": 0.2, "text": "누구"}],
                "members": [{"memberId": "mem_a", "voicedSec": 15.0, "momentPath": "C:/w/moments/mem_a.wav",
                             "momentText": "안녕", "refPath": "C:/w/refs/mem_a.wav", "refSec": 15.0, "refF0Hz": 210.5},
                            {"memberId": "mem_b", "voicedSec": 0.0, "momentPath": None, "momentText": None,
                             "refPath": None, "refSec": 0.0, "refF0Hz": None}],
            })
        return httpx.Response(200, json={
            "outPath": "C:/w/sung.wav", "durationSec": 60.0, "singerF0Hz": 220.0,
            "singers": [{"memberId": "mem_a", "shiftSemitones": 0, "phrases": 5, "seconds": 30.0},
                        {"memberId": "mem_b", "shiftSemitones": -12, "phrases": 0, "seconds": 0.0}],
            "vocalPhrases": [[19.5, 23.4], [24.0, 28.1]]})

    lab = VoiceLab("http://lab:8002", httpx.AsyncClient(transport=httpx.MockTransport(respond)))
    a = asyncio.run(lab.analyze([tmp_path / "c0.m4a"], {"mem_a": [tmp_path / "e.m4a"]}, tmp_path / "out"))
    req = seen["/v1/analyze"]
    assert req["audioPaths"] == [str((tmp_path / "c0.m4a").resolve())]
    assert req["members"] == [{"memberId": "mem_a", "enrollPaths": [str((tmp_path / "e.m4a").resolve())]}]
    assert req["outDir"] == str((tmp_path / "out").resolve())
    assert a.segments[1].member_id is None and a.members[0].ref_path == Path("C:/w/refs/mem_a.wav")
    assert a.members[0].ref_f0_hz == 210.5 and a.members[1].moment_path is None

    sung = asyncio.run(lab.sing(tmp_path / "s.mp3", [Singer("mem_a", tmp_path / "r.wav", 210.5)],
                                tmp_path / "sung.wav", tmp_path / "work"))
    req = seen["/v1/sing"]
    assert req["singers"] == [{"memberId": "mem_a", "refPath": str((tmp_path / "r.wav").resolve()), "refF0Hz": 210.5}]
    assert sung.out_path == Path("C:/w/sung.wav") and sung.singers == ["mem_a"]  # 구절 0개는 부르지 않은 것
    assert sung.vocal_phrases == [(19.5, 23.4), (24.0, 28.1)]
