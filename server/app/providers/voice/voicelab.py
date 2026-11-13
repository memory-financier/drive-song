"""voice-lab GPU 서비스 어댑터 (../voice-lab, T16). 기본 http://127.0.0.1:8002.

같은 PC 에서 돌고 파일 경로(절대 경로)를 주고받는다. 다른 PC 로 나누려면 업로드 방식이 필요하다 [확인 필요].
시간 (11/03): 분석 + 3명 변환 약 3분. 긴 주행 대화는 분석이 더 걸리므로 요청 상한을 넉넉히 둔다.
"""

from pathlib import Path

import httpx

from app.providers.voice.base import Analysis, MemberVoice, Singer, SpokenSegment, Sung

REQUEST_TIMEOUT_SEC = 900.0


def _path(value: str | None) -> Path | None:
    return Path(value) if value else None


class VoiceLab:
    name = "voicelab"

    def __init__(self, url: str, client: httpx.AsyncClient):
        self.url = url.rstrip("/")
        self.client = client

    async def _post(self, path: str, body: dict) -> dict:
        res = await self.client.post(f"{self.url}{path}", json=body, timeout=REQUEST_TIMEOUT_SEC)
        res.raise_for_status()
        return res.json()

    async def analyze(self, audio_paths: list[Path], enroll: dict[str, list[Path]], out_dir: Path) -> Analysis:
        data = await self._post("/v1/analyze", {
            "audioPaths": [str(p.resolve()) for p in audio_paths],
            "members": [{"memberId": mid, "enrollPaths": [str(p.resolve()) for p in paths]}
                        for mid, paths in enroll.items()],
            "outDir": str(out_dir.resolve()),
        })
        return Analysis(
            duration_sec=data["durationSec"],
            segments=[SpokenSegment(s["start"], s["end"], s["memberId"], s["text"]) for s in data["segments"]],
            members=[
                MemberVoice(
                    member_id=m["memberId"],
                    voiced_sec=m["voicedSec"],
                    moment_path=_path(m["momentPath"]),
                    moment_text=m["momentText"],
                    ref_path=_path(m["refPath"]),
                    ref_sec=m["refSec"],
                    ref_f0_hz=m["refF0Hz"],
                )
                for m in data["members"]
            ],
        )

    async def sing(self, song_path: Path, singers: list[Singer], out_path: Path, work_dir: Path, *,
                   candidates: list[Path] = (), lyrics: str | None = None) -> Sung:
        data = await self._post("/v1/sing", {
            "songPaths": [str(p.resolve()) for p in (song_path, *candidates)],
            "lyrics": lyrics,
            "singers": [{"memberId": s.member_id, "refPath": str(s.ref_path.resolve()), "refF0Hz": s.ref_f0_hz}
                        for s in singers],
            "outPath": str(out_path.resolve()),
            "workDir": str(work_dir.resolve()),
        })
        return Sung(Path(data["outPath"]), [s["memberId"] for s in data["singers"] if s["phrases"] > 0],
                    [(s, e) for s, e in data.get("vocalPhrases") or []], data.get("picked") or 0)
