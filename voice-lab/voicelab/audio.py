"""오디오 공용 함수: 읽기·쓰기, 무음 기준 발화 구간 (설계 §7.5 2·9단계). numpy 만 쓴다."""

import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 16_000  # 분석(받아 적기·화자) 샘플링 레이트


def load(path: str | Path, sr: int = SR, channels: int = 1) -> np.ndarray:
    """ffmpeg 로 아무 형식(m4a·mp3·wav)이나 float32 로 읽는다. channels=2 면 (n, 2)."""
    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-ac", str(channels), "-ar", str(sr), "-f", "f32le", "-"],
        capture_output=True, check=True,
    ).stdout
    audio = np.frombuffer(out, dtype=np.float32).copy()
    return audio.reshape(-1, channels) if channels > 1 else audio


def save(path: str | Path, audio: np.ndarray, sr: int) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    sf.write(path, audio, sr)
    return path


def frame_db(audio: np.ndarray, sr: int, frame: float = 0.03) -> np.ndarray:
    n = max(1, int(frame * sr))
    usable = len(audio) // n * n
    frames = audio[:usable].reshape(-1, n)
    return 20 * np.log10(np.sqrt(np.mean(frames ** 2, axis=1)) + 1e-9)


def voiced_segments(
    audio: np.ndarray,
    sr: int,
    *,
    frame: float = 0.03,
    rel_db: float = 35.0,
    floor_db: float = -60.0,
    min_gap: float = 0.35,
    min_len: float = 0.5,
) -> list[tuple[float, float]]:
    """말소리 구간 (초). 프레임 음량이 (최대 - rel_db) 와 floor_db 둘 다 넘으면 말소리.
    min_gap 보다 짧은 틈은 잇고, min_len 보다 짧은 구간은 버린다."""
    db = frame_db(audio, sr, frame)
    if len(db) == 0:
        return []
    voiced = (db > db.max() - rel_db) & (db > floor_db)
    segments: list[list[float]] = []
    start = None
    for i, v in enumerate(voiced):
        if v and start is None:
            start = i
        elif not v and start is not None:
            segments.append([start * frame, i * frame])
            start = None
    if start is not None:
        segments.append([start * frame, len(voiced) * frame])
    merged: list[list[float]] = []
    for s in segments:
        if merged and s[0] - merged[-1][1] < min_gap:
            merged[-1][1] = s[1]
        else:
            merged.append(s)
    return [(round(s, 3), round(e, 3)) for s, e in merged if e - s >= min_len]


def cut(audio: np.ndarray, sr: int, start: float, end: float) -> np.ndarray:
    return audio[max(0, int(start * sr)):max(0, int(end * sr))]


def rms_db(audio: np.ndarray) -> float:
    return float(20 * np.log10(np.sqrt(np.mean(audio ** 2)) + 1e-9)) if len(audio) else -200.0
