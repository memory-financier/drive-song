"""대화 분석 (설계 §7.5 2·3·5단계): 발화 나누기 → 받아 적기 → 누가 말했나 → 한마디·참고 목소리 고르기.

무거운 모델(Whisper, ECAPA)은 처음 쓸 때 올리고 `unload()` 로 내린다 (GPU 16GB 를 ACE-Step·Ollama 와 나눠 씀).
"""

from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from voicelab import audio as au
from voicelab.audio import SR

MOMENT_MIN_SEC = 1.0
MOMENT_MAX_SEC = 4.5
REF_MAX_SEC = 30.0
REF_MIN_SEC = 10.0  # 이보다 짧으면 이번 노래를 부르지 않는다 (§7.5)


@dataclass
class Segment:
    start: float
    end: float
    member_id: str | None = None
    score: float = 0.0
    text: str = ""

    @property
    def duration(self) -> float:
        return self.end - self.start


@dataclass
class MemberResult:
    member_id: str
    voiced_sec: float = 0.0
    moment_path: str | None = None
    moment_text: str | None = None
    ref_path: str | None = None
    ref_sec: float = 0.0
    ref_f0_hz: float | None = None


@dataclass
class AnalysisResult:
    duration_sec: float
    segments: list[Segment] = field(default_factory=list)
    members: list[MemberResult] = field(default_factory=list)


# --- 순수 함수 (GPU 없이 테스트) ------------------------------------------------

def match_speaker(embedding: np.ndarray, enrolled: dict[str, np.ndarray], threshold: float) -> tuple[str | None, float]:
    """정규화된 임베딩끼리 코사인. 가장 높은 구성원, 기준 미만이면 (None, 점수)."""
    if not enrolled:
        return None, 0.0
    scores = {mid: float(np.dot(embedding, ref)) for mid, ref in enrolled.items()}
    best = max(scores, key=scores.get)
    return (best if scores[best] >= threshold else None), scores[best]


def pick_moment(segments: list[Segment], conv: np.ndarray, sr: int = SR) -> Segment | None:
    """한마디: 1~4.5초 발화 중 소리가 가장 큰 것. 없으면 1초 이상 중 가장 짧은 것을 4.5초로 자른다."""
    fit = [s for s in segments if MOMENT_MIN_SEC <= s.duration <= MOMENT_MAX_SEC]
    if fit:
        return max(fit, key=lambda s: au.rms_db(au.cut(conv, sr, s.start, s.end)))
    longer = [s for s in segments if s.duration >= MOMENT_MIN_SEC]
    if not longer:
        return None
    s = min(longer, key=lambda s: s.duration)
    return Segment(s.start, s.start + MOMENT_MAX_SEC, s.member_id, s.score, s.text)


def pick_reference(segments: list[Segment], max_sec: float = REF_MAX_SEC) -> list[Segment]:
    """참고 목소리: 등록 클립과 닮은(점수 높은) 발화부터 max_sec 까지. 마지막 발화는 잘라 맞춘다."""
    chosen, total = [], 0.0
    for s in sorted(segments, key=lambda s: s.score, reverse=True):
        if total >= max_sec:
            break
        take = min(s.duration, max_sec - total)
        chosen.append(Segment(s.start, s.start + take, s.member_id, s.score, s.text))
        total += take
    return sorted(chosen, key=lambda s: s.start)


def median_f0(audio: np.ndarray, sr: int, fmin: float = 65.0, fmax: float = 1000.0, max_sec: float = 30.0) -> float | None:
    """목소리 음높이 중앙값 (Hz, pYIN). 목소리가 없으면 None. 옥타브 맞춤(§7.5 8단계)에 쓴다.
    pYIN 이 느려서 가운데 max_sec 만 본다."""
    import librosa

    if len(audio) < sr // 2:
        return None
    if len(audio) > max_sec * sr:
        mid, half = len(audio) // 2, int(max_sec * sr / 2)
        audio = audio[mid - half:mid + half]
    f0, voiced, _ = librosa.pyin(audio.astype(np.float32), fmin=fmin, fmax=fmax, sr=sr, frame_length=2048)
    f0 = f0[voiced & np.isfinite(f0)]
    return float(np.exp(np.median(np.log(f0)))) if len(f0) else None


# --- 모델을 쓰는 분석기 ----------------------------------------------------------

class Analyzer:
    def __init__(self, settings):
        self.s = settings
        self._asr = None
        self._spk = None

    def _speaker_model(self):
        if self._spk is None:
            from speechbrain.inference.speaker import EncoderClassifier
            from speechbrain.utils.fetching import LocalStrategy

            self._spk = EncoderClassifier.from_hparams(
                self.s.speaker_model, savedir=str(Path(self.s.models_dir) / "ecapa"),
                run_opts={"device": self.s.device}, local_strategy=LocalStrategy.COPY,
            )
        return self._spk

    def _asr_pipe(self):
        if self._asr is None:
            import torch
            from transformers import pipeline

            self._asr = pipeline("automatic-speech-recognition", model=self.s.asr_model,
                                 torch_dtype=torch.float16, device=self.s.device)
        return self._asr

    def unload(self):
        self._asr = self._spk = None
        import gc

        import torch

        gc.collect()
        torch.cuda.empty_cache()

    def embed(self, audio: np.ndarray) -> np.ndarray:
        import torch

        with torch.no_grad():
            e = self._speaker_model().encode_batch(torch.from_numpy(audio).unsqueeze(0).to(self.s.device)).squeeze()
            e = torch.nn.functional.normalize(e, dim=0)
        return e.float().cpu().numpy()

    def transcribe(self, audio: np.ndarray) -> str:
        res = self._asr_pipe()({"raw": audio, "sampling_rate": SR},
                               generate_kwargs={"language": "korean", "task": "transcribe"})
        return res["text"].strip()

    def enroll(self, enroll_paths: dict[str, list[str]]) -> dict[str, np.ndarray]:
        """구성원마다 등록 클립 임베딩 평균 (정규화)."""
        out = {}
        for mid, paths in enroll_paths.items():
            embs = [self.embed(au.load(p)) for p in paths]
            if embs:
                m = np.mean(embs, axis=0)
                out[mid] = m / (np.linalg.norm(m) + 1e-9)
        return out

    def analyze(self, audio_paths: list[str], enroll_paths: dict[str, list[str]], out_dir: str | Path) -> AnalysisResult:
        out_dir = Path(out_dir)
        conv = np.concatenate([au.load(p) for p in audio_paths]) if audio_paths else np.zeros(0, np.float32)
        result = AnalysisResult(duration_sec=round(len(conv) / SR, 2))
        enrolled = self.enroll(enroll_paths)
        for start, end in au.voiced_segments(conv, SR):
            clip = au.cut(conv, SR, start, end)
            member, score = match_speaker(self.embed(clip), enrolled, self.s.match_threshold)
            result.segments.append(Segment(start, end, member, round(score, 3), self.transcribe(clip)))

        for mid in enroll_paths:
            mine = [s for s in result.segments if s.member_id == mid]
            m = MemberResult(mid, voiced_sec=round(sum(s.duration for s in mine), 2))
            moment = pick_moment(mine, conv)
            if moment:
                path = au.save(out_dir / "moments" / f"{mid}.wav", au.cut(conv, SR, moment.start, moment.end), SR)
                m.moment_path, m.moment_text = str(path), moment.text
            ref_segments = pick_reference(mine)
            if ref_segments:
                ref = np.concatenate([au.cut(conv, SR, s.start, s.end) for s in ref_segments])
                m.ref_sec = round(len(ref) / SR, 2)
                if m.ref_sec >= REF_MIN_SEC:
                    m.ref_path = str(au.save(out_dir / "refs" / f"{mid}.wav", ref, SR))
                    f0 = median_f0(ref, SR)
                    m.ref_f0_hz = round(f0, 1) if f0 else None
            result.members.append(m)
        return result
