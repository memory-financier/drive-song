"""GPU 없이 전체 흐름을 확인하는 대화 분석·변환 (테스트·데모용). ffmpeg 만 쓴다.

- 분석: 구성원마다 고정 문장 하나, 한마디 = 첫 조각 앞 3초, 참고 목소리 = 첫 조각 앞 30초 (대화가 10초 이상일 때)
- 변환: 노래를 그대로 복사하고 모두가 불렀다고 돌려준다
"""

import asyncio
import shutil
import subprocess
from pathlib import Path

from app.api.clips import probe_audio_duration
from app.providers.voice.base import Analysis, MemberVoice, Singer, SpokenSegment, Sung

FAKE_LINES = ["바다 보러 가자!", "휴게소에서 호두과자 먹자", "노래 크게 틀어 줘", "창밖 좀 봐 산이 예쁘다"]
REF_MIN_SEC = 10.0


def _cut(src: Path, dest: Path, seconds: float) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(src), "-t", f"{seconds:g}", "-ac", "1", str(dest)],
                   check=True, capture_output=True)
    return dest


class FakeVoice:
    name = "fake"

    def __init__(self):
        self.analyze_calls: list[tuple] = []
        self.sing_calls: list[tuple] = []

    async def analyze(self, audio_paths: list[Path], enroll: dict[str, list[Path]], out_dir: Path) -> Analysis:
        self.analyze_calls.append((audio_paths, enroll, out_dir))
        total = sum(probe_audio_duration(p) or 0 for p in audio_paths)
        first = audio_paths[0]
        segments, members = [], []
        for i, mid in enumerate(enroll):
            text = FAKE_LINES[i % len(FAKE_LINES)]
            segments.append(SpokenSegment(i * 3.0, i * 3.0 + 2.5, mid, text))
            moment = await asyncio.to_thread(_cut, first, out_dir / "moments" / f"{mid}.wav", 3)
            ref = None
            if total >= REF_MIN_SEC:
                ref = await asyncio.to_thread(_cut, first, out_dir / "refs" / f"{mid}.wav", 30)
            members.append(MemberVoice(
                member_id=mid, voiced_sec=2.5, moment_path=moment, moment_text=text,
                ref_path=ref, ref_sec=min(total, 30.0) if ref else 0.0, ref_f0_hz=200.0 if ref else None,
            ))
        return Analysis(round(total, 2), segments, members)

    async def sing(self, song_path: Path, singers: list[Singer], out_path: Path, work_dir: Path) -> Sung:
        self.sing_calls.append((song_path, singers, out_path, work_dir))
        out = out_path.with_suffix(song_path.suffix)
        out.parent.mkdir(parents=True, exist_ok=True)
        await asyncio.to_thread(shutil.copyfile, song_path, out)
        return Sung(out, [s.member_id for s in singers])
