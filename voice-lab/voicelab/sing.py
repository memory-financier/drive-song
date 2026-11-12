"""가족 목소리로 나눠 부르기 (설계 §7.5 7·8·9단계).

1) Demucs 로 보컬/반주 분리  2) 부를 사람마다 Seed-VC 로 보컬 전체를 그 사람 목소리로 변환
   (옥타브 이동 = round(log2(그 사람 말소리 ÷ 가수)) × 12 → 반주와 키 유지)
3) 원래 보컬을 무음 기준 구절로 나눠 돌아가며 배정, 경계는 교차 페이드  4) 반주와 합친다.
Seed-VC 는 GPL-3.0 이라 import 하지 않고 별도 설치본의 inference.py 를 별도 프로세스로 부른다.
"""

import math
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from voicelab import audio as au
from voicelab.analyze import median_f0

OUT_SR = 44_100
FADE_SEC = 0.15  # 구절 경계 교차 페이드 (숨소리·잔향이 끊기지 않게)


@dataclass
class SingerInput:
    member_id: str
    ref_path: str
    ref_f0_hz: float | None = None


@dataclass
class SingerResult:
    member_id: str
    shift_semitones: int
    phrases: int
    seconds: float


@dataclass
class SingResult:
    out_path: str
    duration_sec: float
    singer_f0_hz: float | None
    singers: list[SingerResult] = field(default_factory=list)
    # 원래 보컬이 노래하는 구간 [시작, 끝] (곡 기준 초). 서버 믹서가 대화 한마디를 노래 없는 곳에 넣을 때 쓴다
    vocal_phrases: list[list[float]] = field(default_factory=list)


# --- 순수 함수 (GPU 없이 테스트) ------------------------------------------------

def octave_shift(member_f0: float | None, singer_f0: float | None, limit: int = 24) -> int:
    """반주와 키가 맞도록 옥타브(12반음) 단위로만 옮긴다."""
    if not member_f0 or not singer_f0:
        return 0
    shift = 12 * round(math.log2(member_f0 / singer_f0))
    return max(-limit, min(limit, shift))


def assign_phrases(phrases: list[tuple[float, float]], singer_ids: list[str]) -> list[tuple[float, float, str]]:
    """구절마다 돌아가며 배정 (첫 구절은 첫 사람)."""
    if not singer_ids:
        return []
    return [(s, e, singer_ids[i % len(singer_ids)]) for i, (s, e) in enumerate(phrases)]


def phrase_weights(assignment: list[tuple[float, float, str]], singer_ids: list[str], n: int, sr: int,
                   fade: float = FADE_SEC) -> dict[str, np.ndarray]:
    """사람마다 0~1 가중치 곡선. 구절 앞뒤로 fade 만큼 넓혀 선형으로 오르내린다. 합은 1 을 넘지 않는다."""
    w = {sid: np.zeros(n, dtype=np.float32) for sid in singer_ids}
    f = max(1, int(fade * sr))
    for start, end, sid in assignment:
        a, b = int(start * sr), int(end * sr)
        lo, hi = max(0, a - f), min(n, b + f)
        if lo >= hi:
            continue
        curve = np.ones(hi - lo, dtype=np.float32)
        ramp_in = min(f, a - lo) if a > lo else 0
        ramp_out = min(f, hi - b) if hi > b else 0
        if ramp_in:
            curve[:ramp_in] = np.linspace(0, 1, ramp_in, endpoint=False)
        if ramp_out:
            curve[-ramp_out:] = np.linspace(1, 0, ramp_out)
        w[sid][lo:hi] = np.maximum(w[sid][lo:hi], curve)
    total = np.maximum(1.0, sum(w.values())) if w else None
    return {sid: v / total for sid, v in w.items()}


def compose(tracks: dict[str, np.ndarray], weights: dict[str, np.ndarray]) -> np.ndarray:
    """(n, 2) 트랙들을 가중치로 섞는다."""
    n = min(len(t) for t in tracks.values())
    out = np.zeros((n, 2), dtype=np.float32)
    for sid, t in tracks.items():
        out += t[:n] * weights[sid][:n, None]
    return out


def fit_length(x: np.ndarray, n: int) -> np.ndarray:
    if len(x) >= n:
        return x[:n]
    pad = np.zeros((n - len(x),) + x.shape[1:], dtype=x.dtype)
    return np.concatenate([x, pad])


# --- 모델·외부 도구를 쓰는 부분 ------------------------------------------------

class Singer:
    def __init__(self, settings):
        self.s = settings

    def separate(self, song_path: str | Path, work: Path) -> tuple[Path, Path]:
        """Demucs htdemucs 두 갈래 (보컬 / 반주)."""
        subprocess.run([sys.executable, "-m", "demucs", "--two-stems", "vocals", "-o", str(work / "sep"),
                        str(song_path)], check=True, capture_output=True)
        stem = work / "sep" / "htdemucs" / Path(song_path).stem
        return stem / "vocals.wav", stem / "no_vocals.wav"

    def convert(self, vocals: Path, ref_path: str, shift: int, out_dir: Path) -> Path:
        """Seed-VC 노래 변환 (f0 조건). 결과 파일 하나를 돌려준다."""
        out_dir.mkdir(parents=True, exist_ok=True)
        subprocess.run([
            str(self.s.seedvc_python), "inference.py", "--source", str(vocals), "--target", str(ref_path),
            "--output", str(out_dir), "--diffusion-steps", str(self.s.seedvc_steps), "--f0-condition", "True",
            "--auto-f0-adjust", "False", "--semi-tone-shift", str(shift), "--fp16", "True",
        ], cwd=self.s.seedvc_dir, check=True, capture_output=True)
        return max(out_dir.glob("*.wav"), key=lambda p: p.stat().st_mtime)

    def sing(self, song_path: str, singers: list[SingerInput], out_path: str, work_dir: str) -> SingResult:
        work = Path(work_dir)
        vocals_path, accomp_path = self.separate(song_path, work)
        vocals = au.load(vocals_path, OUT_SR, channels=2)
        accomp = au.load(accomp_path, OUT_SR, channels=2)
        n = len(vocals)
        mono16 = au.load(vocals_path)
        singer_f0 = median_f0(mono16, au.SR)

        tracks: dict[str, np.ndarray] = {}
        shifts: dict[str, int] = {}
        for sg in singers:
            shifts[sg.member_id] = octave_shift(sg.ref_f0_hz, singer_f0)
            converted = self.convert(vocals_path, sg.ref_path, shifts[sg.member_id], work / "vc" / sg.member_id)
            tracks[sg.member_id] = fit_length(au.load(converted, OUT_SR, channels=2), n)

        ids = [sg.member_id for sg in singers]
        phrases = au.voiced_segments(mono16, au.SR, rel_db=30.0, min_gap=0.3, min_len=0.3)
        assignment = assign_phrases(phrases, ids)
        if tracks:
            sung = compose(tracks, phrase_weights(assignment, ids, n, OUT_SR))
        else:
            sung = vocals  # 부를 사람이 없으면 AI 가수 그대로 (§7.5 대체 경로)
        mix = fit_length(accomp, n) + sung
        peak = float(np.max(np.abs(mix))) or 1.0
        mix = mix * min(1.0, 0.89 / peak)  # -1 dBFS 아래로. 최종 음량은 서버 믹서(-14 LUFS)
        au.save(out_path, mix, OUT_SR)
        return SingResult(
            out_path=str(out_path), duration_sec=round(n / OUT_SR, 2),
            singer_f0_hz=round(singer_f0, 1) if singer_f0 else None,
            singers=[SingerResult(sid, shifts[sid], sum(1 for a in assignment if a[2] == sid),
                                  round(sum(e - s for s, e, x in assignment if x == sid), 1)) for sid in ids],
            vocal_phrases=[[round(s, 2), round(e, 2)] for s, e in phrases],
        )
