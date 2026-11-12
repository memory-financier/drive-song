"""API 키 없이 전체 흐름을 돌리기 위한 가짜 공급자.

요청한 길이의 화음 진행(I-vi-IV-V) mp3 를 ffmpeg 로 만들어 file:// URL 로 돌려준다.
상태를 따로 저장하지 않도록 잡 id 에 길이와 제출 시각을 넣는다 (서버 재시작 후에도 poll 가능).
"""

import asyncio
import subprocess
import time
import uuid
from pathlib import Path

from app.providers.music.base import MusicResult, MusicStatus

# 2초마다 바뀌는 근음 (A 기준 0, +9, +5, +7 반음), 8초 주기를 반복
_ROOT = "220*pow(2,if(lt(t,2),0,if(lt(t,4),9,if(lt(t,6),5,7)))/12)"
_BEAT = "(1-0.7*mod(t,0.5)*2)"  # 0.5초 박마다 감쇠
_CHORD = (
    f"0.25*{_BEAT}*(sin(2*PI*{_ROOT}*t)"
    f"+0.6*sin(2*PI*{_ROOT}*1.26*t)+0.5*sin(2*PI*{_ROOT}*1.498*t))"
)
_RATE = 44100


def generate_fake_song(dest: Path, duration_sec: int) -> None:
    tmp = dest.with_name(f"{dest.stem}.{uuid.uuid4().hex}.tmp.mp3")
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y",
            "-f", "lavfi", "-i", f"aevalsrc='{_CHORD}':s={_RATE}:d=8",
            "-af",
            f"aloop=loop=-1:size={8 * _RATE},atrim=duration={duration_sec},"
            f"afade=t=in:d=1,afade=t=out:st={max(duration_sec - 3, 0)}:d=3",
            "-c:a", "libmp3lame", "-b:a", "128k", str(tmp),
        ],
        check=True,
        capture_output=True,
    )
    tmp.replace(dest)  # 동시에 만들어도 완성된 파일만 보이게


class FakeMusicProvider:
    name = "fake"

    def __init__(self, data_dir: Path, delay_sec: float):
        self._dir = data_dir / "fake_music"
        self._delay_sec = delay_sec

    async def submit(self, lyrics: str, style: str, duration_sec: int) -> str:
        return f"fake_{duration_sec}_{time.time():.3f}"

    async def poll(self, provider_job_id: str) -> MusicResult:
        _, duration, submitted = provider_job_id.split("_")
        if time.time() < float(submitted) + self._delay_sec:
            return MusicResult(MusicStatus.PENDING)
        duration_sec = int(duration)
        path = self._dir / f"fake_{duration_sec}s.mp3"
        if not path.exists():
            self._dir.mkdir(parents=True, exist_ok=True)
            await asyncio.to_thread(generate_fake_song, path, duration_sec)
        # 가사 구조 verse/chorus/verse/chorus (§7.2) 를 흉내 낸 후렴 구간
        return MusicResult(
            MusicStatus.SUCCEEDED,
            audio_url=path.resolve().as_uri(),
            chorus_start_sec=round(duration_sec * 0.25, 1),
            chorus_end_sec=round(duration_sec * 0.5, 1),
        )
