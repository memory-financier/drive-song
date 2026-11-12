"""가족 클립 믹서 (설계 §7.4). ffmpeg/ffprobe 를 subprocess 로 부른다.

1. 클립 정리: 노이즈 게이트 -> 앞뒤 무음 제거 -> -16 LUFS 로 맞추는 게인
2. 배치: INTRO 는 곡 앞 0~2초 (곡 시작을 INTRO 길이만큼, 최대 2초 늦춤),
   CALL 은 첫 후렴 직후 (타이밍이 없으면 곡 길이 40%). 노래하는 구간을 알면(가족 목소리 변환 결과)
   그 지점에 가장 가까운 노래 없는 빈 구간으로 옮긴다. OUTRO 는 곡이 끝난 직후.
   클립이 음악과 겹치는 동안 음악을 -8dB ducking (페이드 150ms).
3. 마스터: 통합 라우드니스를 재고 -14 LUFS 가 되도록 게인 (필요할 때만 피크 리미터),
   AAC 192kbps m4a 출력. M/S 스테레오 탐색은 끈다: 양 채널이 거의 같은 곡(fake 공급자 곡 등)에서
   인코딩이 약 3배 느려져 데모(서울→강릉 마지막 10분 ≈ 20초) 안에 노래가 준비되지 않았다.
"""

import math
import re
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

from app.api.clips import probe_audio_duration

CLIP_LUFS = -16.0
MASTER_LUFS = -14.0
PEAK_LIMIT_DBFS = -1.0  # 마스터 게인 후 피크가 이 값을 넘을 때만 리미터를 건다
DUCK_DB = -8.0
DUCK_FADE_SEC = 0.15
INTRO_MAX_LEAD_SEC = 2.0
CALL_FALLBACK_RATIO = 0.4
CALL_GAP_MARGIN_SEC = 0.3  # CALL 을 노래 없는 곳에 넣을 때 앞뒤 노래와 띄우는 여유
RATE = 44100
# 클립 정리 필터. 게이트 임계값(-40dBFS 부근)·무음 기준은 실제 차량 녹음으로 다시 맞춰야 한다 [확인 필요]
_TRIM = "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.05"
CLIP_PREP_FILTER = f"agate=threshold=0.01:ratio=4:attack=5:release=100,{_TRIM},areverse,{_TRIM},areverse"
_FORMAT = f"aresample={RATE},aformat=sample_fmts=fltp:channel_layouts=stereo"


@dataclass(frozen=True)
class PlannedClip:
    slot: str  # INTRO | CALL | OUTRO
    path: Path  # 절대 경로


@dataclass(frozen=True)
class Placement:
    path: Path
    start_sec: float
    gain_db: float


@dataclass(frozen=True)
class MixPlan:
    song_start_sec: float
    clips: list[Placement]
    duck_windows: list[tuple[float, float]]  # 음악을 줄이는 (시작, 끝) 초, 출력 타임라인 기준


def _run(args: list[str]) -> str:
    """ffmpeg 를 실행하고 stderr 를 돌려준다."""
    res = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostats", "-y", *args],
        check=True, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return res.stderr


def measure_loudness(path: Path) -> tuple[float, float]:
    """(통합 라우드니스 LUFS, 트루 피크 dBTP). 무음이면 -inf.

    ebur128 요약을 쓴다 (loudnorm 측정은 내부 192kHz 업샘플링 때문에 약 5배 느리다). ebur128 은 무음을 -70 LUFS 로 낸다.
    """
    stderr = _run(["-i", str(path), "-af", "ebur128=peak=true:framelog=quiet", "-f", "null", "-"])
    loudness = float(re.findall(r"I:\s+(-?[\d.]+|-inf) LUFS", stderr)[-1])
    true_peak = float(re.findall(r"Peak:\s+(-?[\d.]+|-inf) dBFS", stderr)[-1])
    return (loudness if loudness > -70 else -math.inf), true_peak


@dataclass(frozen=True)
class PreparedClip:
    slot: str
    path: Path
    duration_sec: float
    gain_db: float


def prepare_clip(clip: PlannedClip, dest: Path) -> PreparedClip | None:
    """게이트·무음 제거한 wav 를 dest 에 쓰고, -16 LUFS 까지의 게인을 잰다. 남는 소리가 없으면 None."""
    _run(["-i", str(clip.path), "-vn", "-af", f"{CLIP_PREP_FILTER},{_FORMAT}", "-c:a", "pcm_f32le", str(dest)])
    duration = probe_audio_duration(dest)
    if not duration or duration < 0.05:
        return None
    loudness, _ = measure_loudness(dest)
    if not math.isfinite(loudness) or loudness <= -70:
        return None
    return PreparedClip(clip.slot, dest, duration, CLIP_LUFS - loudness)


def call_gap_start(
    target: float, clip_sec: float, vocal_phrases: list[tuple[float, float]], song_sec: float, not_before: float = 0.0,
) -> float | None:
    """노래하는 구간을 피해 clip 이 통째로(앞뒤 여유 포함) 들어가는 빈 구간에서 target 에 가장 가까운 시작 시각 (곡 기준 초).
    그런 빈 구간이 없으면 None."""
    m = CALL_GAP_MARGIN_SEC
    gaps, prev = [], 0.0
    for s, e in sorted(vocal_phrases):
        gaps.append((prev, s))
        prev = max(prev, e)
    gaps.append((prev, song_sec))
    best = None
    for gap_start, gap_end in gaps:
        lo, hi = max(gap_start + m, not_before), gap_end - m - clip_sec
        if lo > hi:
            continue
        start = min(max(target, lo), hi)
        if best is None or abs(start - target) < abs(best - target):
            best = start
    return best


def plan_mix(
    song_duration_sec: float,
    clips: list[PreparedClip],
    chorus_end_sec: float | None,
    vocal_phrases: list[tuple[float, float]] | None = None,
) -> MixPlan:
    by_slot = {c.slot: c for c in clips}
    intro, call, outro = by_slot.get("INTRO"), by_slot.get("CALL"), by_slot.get("OUTRO")
    song_start = min(intro.duration_sec, INTRO_MAX_LEAD_SEC) if intro else 0.0
    placements, windows = [], []

    if intro:
        placements.append(Placement(intro.path, 0.0, intro.gain_db))
        if intro.duration_sec > song_start:  # 2초보다 긴 INTRO 는 곡 앞부분과 겹친다
            windows.append((song_start, intro.duration_sec))
    if call:
        if chorus_end_sec is not None and 0 < chorus_end_sec < song_duration_sec:
            at = chorus_end_sec
        else:
            at = song_duration_sec * CALL_FALLBACK_RATIO
        if vocal_phrases:
            # 노래 구간을 알면 노래 없는 곳으로 옮긴다. INTRO 와도 겹치지 않게 (곡 기준으로 INTRO 끝 + 여유 뒤)
            intro_end = (intro.duration_sec - song_start + CALL_GAP_MARGIN_SEC) if intro else 0.0
            gap = call_gap_start(at, call.duration_sec, vocal_phrases, song_duration_sec, intro_end)
            if gap is not None:
                at = gap
        start = song_start + at
        placements.append(Placement(call.path, start, call.gain_db))
        windows.append((start, start + call.duration_sec))
    if outro:
        placements.append(Placement(outro.path, song_start + song_duration_sec, outro.gain_db))
    return MixPlan(song_start, placements, windows)


def duck_volume_expr(windows: list[tuple[float, float]]) -> str:
    """volume 필터(eval=frame)용 식. 창마다 0->1->0 사다리꼴 가중치 중 최댓값만큼 DUCK_DB 를 적용."""
    f = DUCK_FADE_SEC
    weights = [f"min(clip((t-{s - f:.3f})/{f},0,1),clip(({e + f:.3f}-t)/{f},0,1))" for s, e in windows]
    weight = weights[0]
    for w in weights[1:]:
        weight = f"max({weight},{w})"
    return f"pow(10,{DUCK_DB}*{weight}/20)"


def _render_premaster(song: Path, plan: MixPlan, dest: Path) -> None:
    inputs = ["-i", str(song)]
    music = f"[0:a]{_FORMAT},adelay=delays={round(plan.song_start_sec * 1000)}:all=1"
    if plan.duck_windows:
        music += f",volume=volume='{duck_volume_expr(plan.duck_windows)}':eval=frame"
    chains = [f"{music}[m]"]
    for i, p in enumerate(plan.clips, start=1):
        inputs += ["-i", str(p.path)]
        chains.append(f"[{i}:a]{_FORMAT},volume={p.gain_db:.2f}dB,adelay=delays={round(p.start_sec * 1000)}:all=1[c{i}]")
    if plan.clips:
        labels = "".join(f"[c{i}]" for i in range(1, len(plan.clips) + 1))
        chains.append(f"[m]{labels}amix=inputs={len(plan.clips) + 1}:duration=longest:normalize=0[out]")
    else:
        chains[0] = f"{music}[out]"
    # float wav: 합친 신호가 0dBFS 를 넘어도 마스터 게인 전까지 잘리지 않게
    _run([*inputs, "-filter_complex", ";".join(chains), "-map", "[out]", "-c:a", "pcm_f32le", str(dest)])


def _master(premaster: Path, out: Path) -> None:
    loudness, true_peak = measure_loudness(premaster)
    if not math.isfinite(loudness):
        raise ValueError("mixed audio is silent")
    gain = MASTER_LUFS - loudness
    af = f"volume={gain:.2f}dB"
    if true_peak + gain > PEAK_LIMIT_DBFS:
        af += f",alimiter=limit={10 ** (PEAK_LIMIT_DBFS / 20):.4f}:level=false:latency=true"
    _run(["-i", str(premaster), "-af", af, "-c:a", "aac", "-aac_ms", "0", "-b:a", "192k", "-movflags", "+faststart", str(out)])


def mix_song(
    song: Path,
    clips: list[PlannedClip],
    chorus_start_sec: float | None,
    chorus_end_sec: float | None,
    out: Path,
    vocal_phrases: list[tuple[float, float]] | None = None,
) -> None:
    """믹서 진입점 (설계 §7.4). 잡 워커가 이 함수 하나만 부른다.

    clips 는 슬롯마다 최대 1개. 없는 슬롯은 건너뛰고, 클립이 없으면 곡만 마스터링한다.
    CALL 위치는 첫 후렴 끝(chorus_end_sec)만 쓴다. chorus_start_sec 는 쓰지 않는다.
    vocal_phrases(노래하는 구간, 곡 기준 초)가 있으면 CALL 을 노래 없는 빈 구간으로 옮긴다.
    """
    song_duration = probe_audio_duration(song)
    if not song_duration:
        raise ValueError(f"cannot read song audio: {song.name}")
    with tempfile.TemporaryDirectory(prefix="mix_") as tmp:
        tmp_dir = Path(tmp)
        prepared = [prepare_clip(c, tmp_dir / f"clip_{c.slot}.wav") for c in clips]
        plan = plan_mix(song_duration, [p for p in prepared if p], chorus_end_sec, vocal_phrases)
        premaster = tmp_dir / "premaster.wav"
        _render_premaster(song, plan, premaster)
        _master(premaster, out)
