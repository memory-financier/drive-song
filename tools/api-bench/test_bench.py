"""벤치마크 스크립트 테스트 (네트워크·API 키 불필요). 실행: python -m pytest tools/api-bench -q"""

import asyncio
import csv
import subprocess

import httpx

import bench
from app.providers.llm.base import validate_lyrics
from app.settings import Settings


def no_key_settings(tmp_path, **overrides) -> Settings:
    values = dict(_env_file=None, data_dir=tmp_path / "fake", fake_music_delay_sec=0,
                  fal_key=None, mureka_api_key=None)
    return Settings(**{**values, **overrides})


def run_bench(settings, providers, tmp_path, transport=None):
    async def go():
        async with httpx.AsyncClient(transport=transport) as client:
            return await bench.run_bench(settings, providers, bench.load_samples(), tmp_path / "out",
                                         client=client, poll_interval=0, timeout=5)
    return asyncio.run(go())


def test_samples_follow_lyrics_rules():
    samples = bench.load_samples()
    assert [s.value for s in samples] == ["kids", "city_pop", "acoustic"]
    for text in samples.values():
        assert validate_lyrics(text) == text  # [verse]/[chorus]/[verse]/[chorus], 4줄씩 (§7.2)
        for line in text.splitlines():
            if not line.startswith("["):
                assert len(line.replace(" ", "")) <= 12, line  # 한 줄 12음절 이내


def test_without_keys_providers_are_skipped_and_fake_runs_full_path(tmp_path):
    result = run_bench(no_key_settings(tmp_path), ["minimax", "mureka", "acestep", "fake"], tmp_path)

    assert [s["provider"] for s in result["skipped"]] == ["minimax", "mureka", "acestep"]
    assert "FAL_KEY" in result["skipped"][0]["reason"]
    assert "MUREKA_API_KEY" in result["skipped"][1]["reason"]
    runs = result["runs"]
    assert [(r["provider"], r["style"], r["success"]) for r in runs] == [
        ("fake", "kids", True), ("fake", "city_pop", True), ("fake", "acoustic", True)]
    for r in runs:
        assert (tmp_path / "out" / r["audio_file"]).is_file()
        assert abs(r["audio_duration_sec"] - 75) < 1
        assert r["cost_usd"] == 0.0 and r["has_section_timing"] is True

    out = tmp_path / "out"
    bench.write_reports(result, out)
    with (out / "results.csv").open(encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 3
    assert rows[0]["korean_pronunciation_1to5"] == "" and rows[0]["audio_quality_1to5"] == ""
    md = (out / "results.md").read_text(encoding="utf-8")
    assert "## 건너뛴 공급자" in md and "| fake | 3 | 3 | 0.0 |" in md


def test_mureka_run_records_timing_cost_and_saves_audio(tmp_path):
    song = tmp_path / "song.mp3"
    subprocess.run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=d=2", str(song)], check=True)
    polls = []

    def handler(request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        if url == "https://api.mureka.ai/v1/song/generate":
            return httpx.Response(200, json={"id": "t1", "status": "preparing"})
        if url == "https://api.mureka.ai/v1/song/query/t1":
            polls.append(url)
            if len(polls) == 1:
                return httpx.Response(200, json={"id": "t1", "status": "running"})
            return httpx.Response(200, json={"id": "t1", "status": "succeeded", "choices": [{
                "url": "https://cdn.example/t1.mp3",
                "lyrics_sections": [{"section_type": "verse", "start": 0, "end": 20000},
                                    {"section_type": "chorus", "start": 20500, "end": 41000}]}]})
        if url == "https://cdn.example/t1.mp3":
            return httpx.Response(200, content=song.read_bytes())
        return httpx.Response(404)

    result = run_bench(no_key_settings(tmp_path, mureka_api_key="k"), ["mureka"], tmp_path,
                       httpx.MockTransport(handler))

    assert result["skipped"] == []
    r = result["runs"][0]
    assert r["success"] is True and r["error"] is None
    assert r["has_section_timing"] is True and r["chorus_start_sec"] == 20.5
    assert r["cost_usd"] == 0.225
    assert r["audio_file"] == "audio/mureka_kids_1.mp3" and abs(r["audio_duration_sec"] - 2) < 0.2
    assert r["generation_sec"] is not None


def test_provider_failures_are_recorded(tmp_path):
    def handler(request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        if url == "https://queue.fal.run/fal-ai/minimax-music/v2.6":
            return httpx.Response(401, json={"detail": "bad key"})
        if url == "https://queue.fal.run/fal-ai/ace-step":
            return httpx.Response(200, json={"request_id": "r1"})
        if url == "https://queue.fal.run/fal-ai/ace-step/requests/r1/status":
            return httpx.Response(200, json={"status": "COMPLETED", "error": "boom"})
        return httpx.Response(404)

    result = run_bench(no_key_settings(tmp_path, fal_key="k"), ["minimax", "acestep"], tmp_path,
                       httpx.MockTransport(handler))

    by_provider = {r["provider"]: r for r in result["runs"] if r["style"] == "kids"}
    assert by_provider["minimax"]["success"] is False
    assert "HTTPStatusError" in by_provider["minimax"]["error"]
    assert by_provider["acestep"]["error"] == "acestep: boom"
    assert by_provider["acestep"]["cost_usd"] is None and by_provider["acestep"]["generation_sec"] is not None
    assert len(result["runs"]) == 6
    md = bench.render_markdown(result)
    assert "| minimax | 3 | 0 | 1.0 |" in md
