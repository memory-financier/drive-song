"""믹서 (설계 §7.4, T4) 테스트. 샘플 곡과 클립은 ffmpeg 로 합성한다."""

import re
import subprocess
from pathlib import Path

import pytest

from app.api.clips import probe_audio_duration
from app.mixer.mix import PlannedClip, PreparedClip, call_gap_start, measure_loudness, mix_song, plan_mix
from app.providers.music.fake import generate_fake_song

SONG_SEC = 60


def _ffmpeg(*args: str) -> str:
    res = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostats", "-y", *args],
        check=True, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return res.stderr


def make_voice_clip(path: Path, voiced_sec: float, freq: int = 300, lead: float = 0.5, tail: float = 0.5) -> Path:
    """앞뒤 무음 + 약한 잡음 사이에 음절처럼 떨리는 목소리 대용 음을 넣은 m4a."""
    end = lead + voiced_sec
    expr = (
        f"if(between(t,{lead},{end}),0.3*sin(2*PI*{freq}*t)*(0.6+0.4*sin(2*PI*4*t)),0)"
        "+0.002*(random(0)*2-1)"
    )
    _ffmpeg("-f", "lavfi", "-i", f"aevalsrc='{expr}':s=48000:d={end + tail}", "-c:a", "aac", "-b:a", "96k", str(path))
    return path


def integrated_lufs(path: Path) -> float:
    """ffmpeg ebur128 로 잰 통합 라우드니스."""
    stderr = _ffmpeg("-i", str(path), "-af", "ebur128=framelog=quiet", "-f", "null", "-")
    return float(re.findall(r"Integrated loudness:\s+I:\s+(-?[\d.]+) LUFS", stderr)[-1])


def mean_volume(path: Path, start: float, end: float, band: str) -> float:
    """[start, end) 구간의 평균 레벨(dB). band 필터로 음악/클립 주파수만 남긴다."""
    stderr = _ffmpeg("-i", str(path), "-af", f"{band},atrim=start={start}:end={end},volumedetect", "-f", "null", "-")
    return float(re.search(r"mean_volume: (-?[\d.]+) dB", stderr).group(1))


LOW = ",".join(["lowpass=f=400"] * 4)  # 150Hz 음악만
HIGH = ",".join(["highpass=f=1500"] * 4)  # 3kHz 클립만


@pytest.fixture(scope="session")
def mix_inputs(tmp_path_factory) -> dict[str, Path]:
    d = tmp_path_factory.mktemp("mix_inputs")
    song = d / "song.mp3"
    generate_fake_song(song, SONG_SEC)
    return {
        "song": song,
        "intro": make_voice_clip(d / "intro.m4a", 1.5),
        "call": make_voice_clip(d / "call.m4a", 1.0, freq=400),
        "outro": make_voice_clip(d / "outro.m4a", 1.2, freq=250),
    }


def test_mix_with_three_clips(mix_inputs, tmp_path):
    out = tmp_path / "out.m4a"
    clips = [PlannedClip(slot, mix_inputs[slot.lower()]) for slot in ("INTRO", "CALL", "OUTRO")]
    mix_song(mix_inputs["song"], clips, 15.0, 30.0, out)

    assert out.exists()
    # 앞뒤 무음이 잘려 INTRO 1.5초 + 곡 60초 + OUTRO 1.2초
    assert abs(probe_audio_duration(out) - (1.5 + SONG_SEC + 1.2)) < 0.3
    assert abs(integrated_lufs(out) - (-14)) <= 1  # T4 완료 기준
    codec = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name,bit_rate",
         "-of", "csv=p=0", str(out)],
        capture_output=True, text=True, check=True,
    ).stdout.strip().split(",")
    assert codec[0] == "aac"
    assert abs(int(codec[1]) - 192_000) < 20_000


def test_zero_clips_only_masters_song(mix_inputs, tmp_path):
    out = tmp_path / "out.m4a"
    mix_song(mix_inputs["song"], [], None, None, out)
    assert abs(probe_audio_duration(out) - SONG_SEC) < 0.1
    assert abs(integrated_lufs(out) - (-14)) <= 1


def test_call_ducks_music_and_lands_after_chorus(tmp_path):
    # 음 높이로 분리할 수 있게: 음악은 150Hz 고정 사인, CALL 클립은 3kHz
    song = tmp_path / "steady.wav"
    _ffmpeg("-f", "lavfi", "-i", "sine=frequency=150:duration=30", "-af", "volume=0.3", str(song))
    call = make_voice_clip(tmp_path / "call.m4a", 1.5, freq=3000)
    out = tmp_path / "out.m4a"
    mix_song(song, [PlannedClip("CALL", call)], 8.0, 12.0, out)  # 첫 후렴 끝 = 12초

    music_before = mean_volume(out, 6.0, 11.5, LOW)
    music_during = mean_volume(out, 12.3, 13.3, LOW)
    music_after = mean_volume(out, 14.0, 20.0, LOW)
    assert abs((music_during - music_before) - (-8)) < 1.0
    assert abs(music_after - music_before) < 0.5

    clip_during = mean_volume(out, 12.3, 13.3, HIGH)
    clip_outside = mean_volume(out, 6.0, 11.5, HIGH)
    assert clip_during - clip_outside > 30


def test_silent_clip_is_skipped(mix_inputs, tmp_path):
    silent = tmp_path / "silent.m4a"
    _ffmpeg("-f", "lavfi", "-i", "anullsrc=r=48000:cl=mono", "-t", "2", "-c:a", "aac", str(silent))
    out = tmp_path / "out.m4a"
    mix_song(mix_inputs["song"], [PlannedClip("INTRO", silent)], None, None, out)
    assert abs(probe_audio_duration(out) - SONG_SEC) < 0.1


def test_measure_loudness_matches_ebur128_and_silence_is_minus_inf(mix_inputs, tmp_path):
    loudness, true_peak = measure_loudness(mix_inputs["song"])
    assert abs(loudness - integrated_lufs(mix_inputs["song"])) < 0.15
    assert -20 < true_peak < 0
    silent = tmp_path / "silent.wav"
    _ffmpeg("-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo", "-t", "2", str(silent))
    assert measure_loudness(silent) == (float("-inf"), float("-inf"))


def _prepared(slot: str, duration: float) -> PreparedClip:
    return PreparedClip(slot, Path(f"{slot}.wav"), duration, 0.0)


def test_plan_intro_delays_song_up_to_two_seconds():
    plan = plan_mix(60, [_prepared("INTRO", 1.2)], None)
    assert plan.song_start_sec == 1.2 and plan.duck_windows == []

    plan = plan_mix(60, [_prepared("INTRO", 3.0), _prepared("OUTRO", 1.0)], None)
    assert plan.song_start_sec == 2.0
    assert plan.duck_windows == [(2.0, 3.0)]  # 2초를 넘는 부분은 곡과 겹쳐 ducking
    assert [(p.path.name, p.start_sec) for p in plan.clips] == [("INTRO.wav", 0.0), ("OUTRO.wav", 62.0)]


@pytest.mark.parametrize("chorus_end, expected", [(30.0, 30.0), (None, 24.0), (75.0, 24.0), (0.0, 24.0)])
def test_plan_call_after_chorus_or_at_40_percent(chorus_end, expected):
    plan = plan_mix(60, [_prepared("CALL", 1.0)], chorus_end)
    assert plan.song_start_sec == 0.0
    assert [p.start_sec for p in plan.clips] == [expected]
    assert plan.duck_windows == [(expected, expected + 1.0)]


@pytest.mark.parametrize("target, clip, phrases, not_before, expected", [
    (30, 2, [(10, 20), (40, 50)], 0, 30),  # 목표가 이미 빈 구간 안이면 그대로
    (30, 2, [(10, 29), (31, 50)], 0, 50.3),  # 29~31 은 좁다 → 가까운 쪽 빈 구간(뒤) 앞머리
    (24, 2, [(10, 29), (31, 50)], 0, 7.7),  # 앞쪽이 더 가까우면 노래 시작 전에 끝나게
    (30, 5, [(0.5, 59.5)], 0, None),  # 들어갈 빈 구간이 없다
    (5, 2, [(10, 60)], 4, 5),
    (5, 2, [(10, 60)], 8, None),  # INTRO 와 겹치지 않는 자리가 없다
])
def test_call_gap_start(target, clip, phrases, not_before, expected):
    got = call_gap_start(target, clip, phrases, 60, not_before)
    assert got == (pytest.approx(expected) if expected is not None else None)


def test_plan_call_moves_out_of_singing():
    # 2026-11-11 실제 곡(75초, 1절+후렴): 40% 지점(30초)은 노래 중이라 노래가 시작되기 직전 빈 구간으로 옮긴다
    phrases = [(8.94, 9.87), (13.56, 14.58), (19.47, 23.4), (23.97, 28.08), (28.86, 32.76), (33.15, 36.81),
               (37.38, 41.46), (42.3, 51.24), (51.6, 52.98), (53.31, 55.29), (56.97, 60.03), (61.17, 63.84)]
    plan = plan_mix(75, [_prepared("INTRO", 4.5), _prepared("CALL", 3.5)], None, phrases)
    assert plan.song_start_sec == 2.0
    call = plan.clips[1].start_sec
    assert call == pytest.approx(2.0 + 19.47 - 0.3 - 3.5)
    assert plan.duck_windows[1] == (pytest.approx(call), pytest.approx(call + 3.5))


def test_plan_call_keeps_old_rule_without_a_gap():
    plan = plan_mix(60, [_prepared("CALL", 2.0)], None, [(0.5, 59.5)])
    assert [p.start_sec for p in plan.clips] == [24.0]
    # 빈 구간이 있으면 INTRO 로 늦춘 곡 시작(2초)을 더해 출력 시각이 된다
    plan = plan_mix(60, [_prepared("INTRO", 3.0), _prepared("CALL", 2.0)], None, [(5, 20), (25, 60)])
    assert [p.start_sec for p in plan.clips] == [0.0, pytest.approx(2.0 + 22.7)]


def test_plan_call_shifts_with_intro():
    plan = plan_mix(60, [_prepared("INTRO", 1.5), _prepared("CALL", 1.0)], 30.0)
    assert [p.start_sec for p in plan.clips] == [0.0, 31.5]
    assert plan.duck_windows == [(31.5, 32.5)]
