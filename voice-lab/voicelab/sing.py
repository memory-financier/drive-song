"""가족 목소리로 나눠 부르기 (설계 §7.5 7·8·9단계).

0) 후보 곡이 여럿이면 모두 보컬을 분리해 보고 하나를 고른다: 리드 보컬이 덜 겹치고(한 목소리) 가사가 덜 빠진 곡.
   가사 없는 구절(전주·후주의 흥얼거림)은 가족 목소리로 부르지 않고 뺀다 (받아 적기가 있을 때)
1) Demucs 로 보컬/반주 분리  2) 부를 사람마다 Seed-VC 로 보컬 전체를 그 사람 목소리로 변환
   (옥타브 이동 = round(log2(그 사람 말소리 ÷ 가수)) × 12 → 반주와 키 유지)
3) 원래 보컬을 무음 기준 구절(가사 줄)로 나눠 돌아가며 배정. 짧은 조각(허밍·애드리브)은 차례 없이 이웃 줄에 붙이고,
   8초 넘게 이어지면 가장 조용한 곳에서 나눈다. 구절마다 원래 보컬 음량에 맞추고(±12 dB), 경계는 틈 안에서만 교차 페이드
4) 반주와 합친다.
Seed-VC 는 GPL-3.0 이라 import 하지 않고 별도 설치본의 inference.py 를 별도 프로세스로 부른다.
"""

import math
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from voicelab import audio as au
from voicelab.analyze import median_f0

OUT_SR = 44_100
FADE_SEC = 0.15  # 틈이 있는 구절 경계의 페이드 (숨소리·잔향이 끊기지 않게)
XFADE_SEC = 0.04  # 쉼 없이 붙은 경계(split_long)의 짧은 교차. 넓으면 두 사람이 같은 음절을 같이 부른다 (2026-11-12 곡)
MIN_LINE_SEC = 1.2  # 이보다 짧은 노래 조각은 차례를 받지 않는다 (가족 목소리가 허밍·애드리브에 쓰이지 않게)
MAX_LINE_SEC = 8.0  # 이보다 길게 이어지면 나눈다 (2026-11-12 곡: 후반 29초가 한 구간이라 한 사람이 다 불렀다)
GAIN_LIMIT_DB = 12.0  # 구절 음량 맞추기 한도 (변환이 거의 무음인 조각을 끝없이 키우지 않게)
# 후보 곡 고르기 (2026-11-13: 같은 가사 11곡에서 보컬이 겹친 시간 19~80%, 요청 문구로는 못 줄였다) [확인 필요: 기준값]
LAYER_WIN_SEC = 0.5  # 이 길이 창마다 본다
LAYER_CORR = 0.9  # 보컬 좌우 상관이 이보다 낮으면 겹친 보컬 (한 목소리가 가운데 있으면 0.95 이상)
COVER_WEIGHT = 0.5  # 후보 점수 = 겹친 비율 + COVER_WEIGHT × 빠진 가사 줄 비율 (낮을수록 좋다)
LINE_MATCH = 0.3  # 구절 글의 두 글자 묶음이 이 비율 이상 가사에 있으면 가사를 부른 구절
COVER_MATCH = 0.4  # 가사 줄의 두 글자 묶음이 이 비율 이상 들리면 불린 줄 (노래 받아 적기는 틀리는 글자가 많다)


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
class Candidate:
    layered: float  # 보컬이 겹친 시간 비율 (0~1)
    coverage: float  # 불린 가사 줄 비율 (0~1). 받아 적지 않았으면 1
    score: float  # 낮을수록 좋다


@dataclass
class SingResult:
    out_path: str
    duration_sec: float
    singer_f0_hz: float | None
    singers: list[SingerResult] = field(default_factory=list)
    # 가족이 부르는 구간 [시작, 끝] (곡 기준 초). 서버 믹서가 대화 한마디를 노래 없는 곳에 넣을 때 쓴다
    vocal_phrases: list[list[float]] = field(default_factory=list)
    picked: int = 0  # 고른 후보 (song_paths 의 순번)
    candidates: list[Candidate] = field(default_factory=list)
    dropped_phrases: list[list[float]] = field(default_factory=list)  # 가사가 없어 뺀 구절
    # 누가 어느 구간을 불렀는지 [시작, 끝, 구성원 id] (곡 기준 초, vocal_phrases 와 같은 순서). 영상 자막·확인용
    assignment: list[list] = field(default_factory=list)


# --- 순수 함수 (GPU 없이 테스트) ------------------------------------------------

def layered_fraction(vocals: np.ndarray, sr: int, win: float = LAYER_WIN_SEC, corr: float = LAYER_CORR,
                     rel_db: float = 25.0) -> float:
    """보컬 (n, 2) 이 한 목소리로 가운데 모이지 않는 시간의 비율: 노래하는 창 가운데 좌우 상관이 corr 보다 낮은 창.
    ACE-Step 은 리드 보컬을 두 겹(좌우가 다른 소리)으로 만드는 구간이 있고, 그대로 변환하면 두 사람이 같이 부르는 것처럼 들린다."""
    w = max(1, int(win * sr))
    usable = len(vocals) // w * w
    if usable == 0:
        return 0.0
    frames = vocals[:usable].reshape(-1, w, 2).astype(np.float64)
    left, right = frames[:, :, 0], frames[:, :, 1]
    power = np.mean(((left + right) / 2) ** 2, axis=1)
    sung = power > power.max() * 10 ** (-rel_db / 10)
    if not sung.any():
        return 0.0
    c = np.sum(left * right, axis=1) / (np.linalg.norm(left, axis=1) * np.linalg.norm(right, axis=1) + 1e-12)
    return float(np.mean(c[sung] < corr))


def _norm(text: str) -> str:
    return re.sub(r"[^0-9a-z가-힣]", "", text.lower())


def _bigrams(text: str) -> set[str]:
    t = _norm(text)
    return {t[i:i + 2] for i in range(len(t) - 1)}


def lyric_lines(lyrics: str) -> list[str]:
    """[verse] 같은 태그와 빈 줄을 뺀 가사 줄."""
    return [line for line in (raw.strip() for raw in lyrics.splitlines()) if line and not line.startswith("[")]


def _nearest(i: int, lines: list[int], phrases: list[tuple[float, float]]) -> int:
    s, e = phrases[i]
    return min(lines, key=lambda j: max(phrases[j][0] - e, s - phrases[j][1], 0.0))


def wordless(phrases: list[tuple[float, float]], texts: dict[int, str], lyrics: str,
             min_sec: float = MIN_LINE_SEC, match: float = LINE_MATCH) -> list[bool]:
    """구절마다 가사 없는 소리(허밍·애드리브)라 뺄지. texts 는 줄(min_sec 이상)의 순번 -> 받아 적은 글.
    - 서로 다른 글자가 2개 이하인 줄(후후후, 라라라, 헤에에, 빈 글)은 뺀다.
    - 가사와 맞는 첫 줄보다 앞·끝 줄보다 뒤에 있으면서 가사와 안 맞는 줄은 뺀다 (전주·후주의 흥얼거림).
      가사 줄 사이에 낀 줄은 받아 적기가 틀렸을 수 있어 남긴다.
    - 짧은 조각은 가까운 줄을 따른다. 모든 줄을 빼게 되면 (받아 적기 실패) 아무것도 빼지 않는다."""
    lines = [i for i, (s, e) in enumerate(phrases) if e - s >= min_sec and i in texts]
    if not lines:
        return [False] * len(phrases)
    ref = set().union(*(_bigrams(line) for line in lyric_lines(lyrics)))

    def hit(i: int) -> float:
        b = _bigrams(texts[i])
        return len(b & ref) / len(b) if b else 0.0

    repeated = {i: len(set(_norm(texts[i]))) <= 2 for i in lines}
    matched = [i for i in lines if not repeated[i] and hit(i) >= match]
    drop = {i: repeated[i] or (bool(matched) and (i < matched[0] or i > matched[-1]) and hit(i) < match)
            for i in lines}
    if all(drop.values()):
        return [False] * len(phrases)
    return [drop[i] if i in drop else drop[_nearest(i, lines, phrases)] for i in range(len(phrases))]


def lyric_coverage(texts: list[str], lyrics: str, match: float = COVER_MATCH) -> float:
    """가사 줄 가운데 노래에서 들리는 줄의 비율 (작곡 모델이 줄을 건너뛰기도 한다)."""
    lines = lyric_lines(lyrics)
    if not lines:
        return 1.0
    heard = set().union(*(_bigrams(t) for t in texts)) if texts else set()
    covered = 0
    for line in lines:
        b = _bigrams(line)
        covered += bool(b) and len(b & heard) / len(b) >= match
    return covered / len(lines)


def candidate_score(layered: float, coverage: float) -> float:
    return layered + COVER_WEIGHT * (1.0 - coverage)


def octave_shift(member_f0: float | None, singer_f0: float | None, limit: int = 24) -> int:
    """반주와 키가 맞도록 옥타브(12반음) 단위로만 옮긴다."""
    if not member_f0 or not singer_f0:
        return 0
    shift = 12 * round(math.log2(member_f0 / singer_f0))
    return max(-limit, min(limit, shift))


def split_long(phrases: list[tuple[float, float]], audio: np.ndarray, sr: int,
               max_sec: float = MAX_LINE_SEC, frame: float = 0.03) -> list[tuple[float, float]]:
    """쉼 없이 max_sec 보다 길게 이어지는 구간을 가운데 절반에서 가장 조용한 곳으로 나눈다 (한 사람이 몰아 부르지 않게)."""
    db = au.frame_db(audio, sr, frame)
    out: list[tuple[float, float]] = []
    todo = list(phrases)
    while todo:
        s, e = todo.pop(0)
        a, b = int((s + (e - s) / 4) / frame), int((e - (e - s) / 4) / frame)
        if e - s <= max_sec or b <= a or b > len(db):
            out.append((s, e))
            continue
        cut = round((a + int(np.argmin(db[a:b])) + 0.5) * frame, 3)  # 가장 조용한 프레임의 가운데
        todo[:0] = [(s, cut), (cut, e)]
    return out


def assign_phrases(phrases: list[tuple[float, float]], singer_ids: list[str],
                   min_sec: float = MIN_LINE_SEC) -> list[tuple[float, float, str]]:
    """가사 줄(구절)마다 돌아가며 배정 (첫 줄은 첫 사람).
    min_sec 보다 짧은 조각(허밍·애드리브·끝음)은 차례를 받지 않고 가까운 줄을 부르는 사람이 부른다."""
    if not singer_ids:
        return []
    lines = [i for i, (s, e) in enumerate(phrases) if e - s >= min_sec]
    if not lines:
        return [(s, e, singer_ids[i % len(singer_ids)]) for i, (s, e) in enumerate(phrases)]
    who = {i: singer_ids[k % len(singer_ids)] for k, i in enumerate(lines)}
    return [(s, e, who[i] if i in who else who[_nearest(i, lines, phrases)]) for i, (s, e) in enumerate(phrases)]


def phrase_gains(assignment: list[tuple[float, float, str]], tracks: dict[str, np.ndarray], reference: np.ndarray,
                 sr: int, limit_db: float = GAIN_LIMIT_DB) -> list[float]:
    """구절마다 부르는 사람 트랙을 원래 보컬의 그 구절 음량(RMS)에 맞추는 배율.
    Seed-VC 결과 음량은 기준 음성 음량을 따라간다 (2026-11-12 곡: 원래 가수 대비 엄마 -6 dB, 첫째 +8 dB)."""
    gains = []
    for start, end, sid in assignment:
        diff = au.rms_db(au.cut(reference, sr, start, end)) - au.rms_db(au.cut(tracks[sid], sr, start, end))
        gains.append(10 ** (float(np.clip(diff, -limit_db, limit_db)) / 20))
    return gains


def phrase_weights(assignment: list[tuple[float, float, str]], singer_ids: list[str], n: int, sr: int,
                   fade: float = FADE_SEC, gains: list[float] | None = None,
                   xfade: float = XFADE_SEC) -> dict[str, np.ndarray]:
    """사람마다 0~1 가중치 곡선 (assignment 는 시간 순). 합은 1 을 넘지 않는다.
    앞뒤 틈이 2×fade 이상이면 구절 밖으로 fade 만큼 넓혀 선형으로 오르내린다. 틈이 그보다 좁으면 그 틈 안에서만
    (xfade 보다 좁으면 틈 가운데 xfade 길이로) 앞사람이 내려가고 다음 사람이 올라간다
    → 앞사람 끝음·다음 사람 첫음을 두 사람이 같이 부르지 않는다.
    gains(구절마다 배율)를 주면 곱한다. 같은 사람 구절이 붙은 경계에선 배율도 교차 페이드."""
    w = {sid: np.zeros(n, dtype=np.float32) for sid in singer_ids}
    num = {sid: np.zeros(n, dtype=np.float32) for sid in singer_ids}
    den = {sid: np.zeros(n, dtype=np.float32) for sid in singer_ids}

    def handover(left: float, right: float) -> tuple[float, float]:
        mid, half = (left + right) / 2, max(right - left, xfade) / 2
        return mid - half, mid + half

    for k, (start, end, sid) in enumerate(assignment):
        prev_end = assignment[k - 1][1] if k else -math.inf
        next_start = assignment[k + 1][0] if k + 1 < len(assignment) else math.inf
        rise = (start - fade, start) if start - prev_end >= 2 * fade else handover(prev_end, start)
        fall = (end, end + fade) if next_start - end >= 2 * fade else handover(end, next_start)
        r0, r1, f0, f1 = (int(round(t * sr)) for t in (*rise, *fall))
        lo, hi = max(0, r0), min(n, f1)
        if lo >= hi:
            continue
        idx = np.arange(lo, hi)
        curve = np.clip(np.minimum((idx - r0) / max(1, r1 - r0), (f1 - idx) / max(1, f1 - f0)), 0, 1).astype(np.float32)
        w[sid][lo:hi] += curve  # 붙은 경계에선 내려가는 쪽 + 올라가는 쪽 = 1
        num[sid][lo:hi] += curve * (gains[k] if gains else 1.0)
        den[sid][lo:hi] += curve
    total = np.maximum(1.0, sum(w.values())) if w else None
    return {sid: v / total * np.where(den[sid] > 0, num[sid] / np.maximum(den[sid], 1e-9), 1.0)
            for sid, v in w.items()}


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
    def __init__(self, settings, asr=None):
        """asr: transcribe(audio16k) -> str 과 unload() 가 있는 받아 적기 (Analyzer). 없으면 가사 확인을 건너뛴다."""
        self.s = settings
        self.asr = asr

    def separate(self, song_paths: list[str | Path], work: Path) -> list[tuple[Path, Path]]:
        """Demucs htdemucs 두 갈래 (보컬 / 반주). 곡마다 (보컬, 반주). 파일 이름(확장자 제외)이 서로 달라야 한다."""
        subprocess.run([sys.executable, "-m", "demucs", "--two-stems", "vocals", "-o", str(work / "sep"),
                        *map(str, song_paths)], check=True, capture_output=True)
        stems = [work / "sep" / "htdemucs" / Path(p).stem for p in song_paths]
        return [(stem / "vocals.wav", stem / "no_vocals.wav") for stem in stems]

    def convert(self, vocals: Path, ref_path: str, shift: int, out_dir: Path) -> Path:
        """Seed-VC 노래 변환 (f0 조건). 결과 파일 하나를 돌려준다."""
        out_dir.mkdir(parents=True, exist_ok=True)
        subprocess.run([
            str(self.s.seedvc_python), "inference.py", "--source", str(vocals), "--target", str(ref_path),
            "--output", str(out_dir), "--diffusion-steps", str(self.s.seedvc_steps), "--f0-condition", "True",
            "--auto-f0-adjust", "False", "--semi-tone-shift", str(shift), "--fp16", "True",
        ], cwd=self.s.seedvc_dir, check=True, capture_output=True)
        return max(out_dir.glob("*.wav"), key=lambda p: p.stat().st_mtime)

    def look(self, vocals_path: Path, lyrics: str | None) -> dict:
        """후보 곡 하나를 살핀다: 구절, 뺄 구절, 겹친 보컬 비율, 불린 가사 비율."""
        vocals = au.load(vocals_path, OUT_SR, channels=2)
        mono16 = au.load(vocals_path)
        phrases = au.voiced_segments(mono16, au.SR, rel_db=30.0, min_gap=0.3, min_len=0.3)
        phrases = split_long(phrases, mono16, au.SR)
        layered = layered_fraction(vocals, OUT_SR)
        drop, coverage = [False] * len(phrases), 1.0
        if self.asr and lyrics:
            texts = {i: self.asr.transcribe(au.cut(mono16, au.SR, s, e))
                     for i, (s, e) in enumerate(phrases) if e - s >= MIN_LINE_SEC}
            drop = wordless(phrases, texts, lyrics)
            coverage = lyric_coverage([t for i, t in texts.items() if not drop[i]], lyrics)
        return {"vocals": vocals, "mono16": mono16, "phrases": phrases, "drop": drop,
                "score": Candidate(round(layered, 3), round(coverage, 3), round(candidate_score(layered, coverage), 3))}

    def sing(self, song_paths: list[str] | str, singers: list[SingerInput], out_path: str, work_dir: str,
             lyrics: str | None = None) -> SingResult:
        """song_paths 가 여럿이면 가장 나은 후보 하나로 부른다 (같은 점수면 앞의 것)."""
        paths = [song_paths] if isinstance(song_paths, (str, Path)) else list(song_paths)
        work = Path(work_dir)
        stems = self.separate(paths, work)
        looks = [self.look(vocals_path, lyrics) for vocals_path, _ in stems]
        if self.asr and lyrics:
            self.asr.unload()  # 변환(Seed-VC)에 GPU 메모리를 넘긴다
        picked = min(range(len(looks)), key=lambda i: looks[i]["score"].score)
        (vocals_path, accomp_path), look = stems[picked], looks[picked]
        vocals, mono16 = look["vocals"], look["mono16"]
        accomp = au.load(accomp_path, OUT_SR, channels=2)
        n = len(vocals)
        singer_f0 = median_f0(mono16, au.SR)

        tracks: dict[str, np.ndarray] = {}
        shifts: dict[str, int] = {}
        for sg in singers:
            shifts[sg.member_id] = octave_shift(sg.ref_f0_hz, singer_f0)
            converted = self.convert(vocals_path, sg.ref_path, shifts[sg.member_id], work / "vc" / sg.member_id)
            tracks[sg.member_id] = fit_length(au.load(converted, OUT_SR, channels=2), n)

        ids = [sg.member_id for sg in singers]
        phrases = [p for p, drop in zip(look["phrases"], look["drop"]) if not drop]
        assignment = assign_phrases(phrases, ids)
        if tracks:
            gains = phrase_gains(assignment, tracks, vocals, OUT_SR)
            sung = compose(tracks, phrase_weights(assignment, ids, n, OUT_SR, gains=gains))
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
            picked=picked, candidates=[lk["score"] for lk in looks],
            dropped_phrases=[[round(s, 2), round(e, 2)] for (s, e), drop in zip(look["phrases"], look["drop"]) if drop],
            assignment=[[round(s, 2), round(e, 2), sid] for s, e, sid in assignment],
        )
