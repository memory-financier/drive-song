"""HTTP API 모양 시험 (가짜 분석기·가수, GPU 없음)."""

from fastapi.testclient import TestClient

from voicelab.analyze import AnalysisResult, MemberResult, Segment
from voicelab.app import create_app
from voicelab.sing import Candidate, SingerResult, SingResult


class FakeAnalyzer:
    def __init__(self):
        self.calls, self.unloaded = [], 0

    def analyze(self, audio_paths, enroll, out_dir):
        self.calls.append((audio_paths, enroll, out_dir))
        return AnalysisResult(12.5, [Segment(0.5, 2.0, "mom", 0.8, "바다 언제 나와?")],
                              [MemberResult("mom", voiced_sec=1.5, ref_path="r.wav", ref_sec=11.0, ref_f0_hz=210.0)])

    def unload(self):
        self.unloaded += 1


class FakeSinger:
    def __init__(self):
        self.calls = []

    def sing(self, songs, singers, out, work, lyrics=None):
        self.calls.append((songs, singers, out, work, lyrics))
        return SingResult(out, 75.0, 221.0, [SingerResult(s.member_id, -12, 4, 20.5) for s in singers],
                          [[19.5, 23.4], [24.0, 28.1]], picked=len(songs) - 1,
                          candidates=[Candidate(0.5, 1.0, 0.5)] * len(songs), dropped_phrases=[[1.0, 3.4]])


def client(tmp_path):
    analyzer, singer = FakeAnalyzer(), FakeSinger()
    return TestClient(create_app(analyzer, singer)), analyzer, singer


def test_analyze_returns_camel_case_and_unloads_models(tmp_path):
    c, analyzer, _ = client(tmp_path)
    conv, clip = tmp_path / "c.m4a", tmp_path / "e.m4a"
    conv.write_bytes(b"x")
    clip.write_bytes(b"x")

    r = c.post("/v1/analyze", json={"audioPaths": [str(conv)], "members": [{"memberId": "mom", "enrollPaths": [str(clip)]}],
                                    "outDir": str(tmp_path / "out")})

    assert r.status_code == 200
    body = r.json()
    assert body["durationSec"] == 12.5
    assert body["segments"][0] == {"start": 0.5, "end": 2.0, "memberId": "mom", "score": 0.8, "text": "바다 언제 나와?"}
    assert body["members"][0]["refF0Hz"] == 210.0 and body["members"][0]["refSec"] == 11.0
    assert analyzer.calls[0][1] == {"mom": [str(clip)]}
    assert analyzer.unloaded == 1


def test_missing_files_are_400(tmp_path):
    c, analyzer, singer = client(tmp_path)
    r = c.post("/v1/analyze", json={"audioPaths": [str(tmp_path / "nope.m4a")],
                                    "members": [{"memberId": "mom", "enrollPaths": [str(tmp_path / "nope2.m4a")]}],
                                    "outDir": str(tmp_path)})
    assert r.status_code == 400 and "file not found" in r.json()["detail"]
    r = c.post("/v1/sing", json={"songPath": str(tmp_path / "s.mp3"), "singers": [], "outPath": "o", "workDir": "w"})
    assert r.status_code == 400
    assert analyzer.calls == [] and singer.calls == []


def test_sing_passes_singers_and_returns_result(tmp_path):
    c, _, singer = client(tmp_path)
    song, ref = tmp_path / "s.mp3", tmp_path / "r.wav"
    song.write_bytes(b"x")
    ref.write_bytes(b"x")

    r = c.post("/v1/sing", json={"songPath": str(song), "singers": [{"memberId": "dad", "refPath": str(ref), "refF0Hz": 126.0}],
                                 "outPath": str(tmp_path / "o.wav"), "workDir": str(tmp_path / "w")})

    assert r.status_code == 200
    assert r.json()["singers"] == [{"memberId": "dad", "shiftSemitones": -12, "phrases": 4, "seconds": 20.5}]
    assert r.json()["vocalPhrases"] == [[19.5, 23.4], [24.0, 28.1]]
    assert singer.calls[0][1][0].ref_f0_hz == 126.0
    assert singer.calls[0][0] == [str(song)] and singer.calls[0][4] is None


def test_sing_takes_candidate_songs_and_lyrics(tmp_path):
    c, _, singer = client(tmp_path)
    a, b = tmp_path / "a.mp3", tmp_path / "b.mp3"
    a.write_bytes(b"x")
    b.write_bytes(b"x")

    r = c.post("/v1/sing", json={"songPaths": [str(a), str(b)], "lyrics": "[verse]\n바다 보러 가자", "singers": [],
                                 "outPath": str(tmp_path / "o.wav"), "workDir": str(tmp_path / "w")})

    assert r.status_code == 200
    assert singer.calls[0][0] == [str(a), str(b)] and singer.calls[0][4] == "[verse]\n바다 보러 가자"
    body = r.json()
    assert body["picked"] == 1 and body["droppedPhrases"] == [[1.0, 3.4]]
    assert body["candidates"] == [{"layered": 0.5, "coverage": 1.0, "score": 0.5}] * 2
    assert c.post("/v1/sing", json={"singers": [], "outPath": "o", "workDir": "w"}).status_code == 422


def test_validation_errors_are_422(tmp_path):
    c, _, _ = client(tmp_path)
    assert c.post("/v1/analyze", json={"audioPaths": [], "members": [], "outDir": "x"}).status_code == 422
    assert c.get("/health").json() == {"status": "ok"}
