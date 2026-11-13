"""voice-lab HTTP API (설계 §7.5, T16). 기본 127.0.0.1:8002.

Drive Song 서버와 같은 PC 에서 돌고 **파일 경로**를 주고받는다 (서버 DATA_DIR 를 같이 본다).
다른 PC 로 나누려면 업로드 방식으로 바꿔야 한다 [확인 필요].
GPU 는 한 번에 한 작업만 쓴다 (asyncio.Lock).
"""

import asyncio
import logging
from dataclasses import asdict
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field

from voicelab.analyze import Analyzer
from voicelab.settings import Settings
from voicelab.sing import Singer, SingerInput


logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
log = logging.getLogger(__name__)


class MemberIn(BaseModel):
    memberId: str = Field(min_length=1)
    enrollPaths: list[str] = Field(min_length=1)


class AnalyzeRequest(BaseModel):
    audioPaths: list[str] = Field(min_length=1)
    members: list[MemberIn] = Field(min_length=1)
    outDir: str


class SingerIn(BaseModel):
    memberId: str = Field(min_length=1)
    refPath: str
    refF0Hz: float | None = None


class SingRequest(BaseModel):
    songPath: str | None = None
    songPaths: list[str] = []  # 후보 곡 여럿이면 가장 나은 하나로 부른다. 비면 songPath
    lyrics: str | None = None  # 있으면 가사 없는 구절을 빼고, 가사가 덜 빠진 후보를 고른다
    singers: list[SingerIn]
    outPath: str
    workDir: str


def _camel(d: dict) -> dict:
    def conv(k: str) -> str:
        head, *rest = k.split("_")
        return head + "".join(w.title() for w in rest)

    return {conv(k): ([_camel(i) if isinstance(i, dict) else i for i in v] if isinstance(v, list) else v)
            for k, v in d.items()}


def _require(paths: list[str]):
    missing = [p for p in paths if not Path(p).is_file()]
    if missing:
        raise HTTPException(400, f"file not found: {missing[0]}")


def create_app(analyzer=None, singer=None) -> FastAPI:
    settings = Settings()
    app = FastAPI(title="Drive Song voice-lab")
    app.state.analyzer = analyzer or Analyzer(settings)
    app.state.singer = singer or Singer(settings, app.state.analyzer)
    lock = asyncio.Lock()

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.post("/v1/analyze")
    async def analyze(req: AnalyzeRequest):
        _require(req.audioPaths + [p for m in req.members for p in m.enrollPaths])
        def run():
            try:
                return app.state.analyzer.analyze(
                    req.audioPaths, {m.memberId: m.enrollPaths for m in req.members}, req.outDir)
            finally:
                app.state.analyzer.unload()  # 다음 단계(가사·작곡·변환)에 GPU 메모리를 넘긴다

        async with lock:
            result = await run_in_threadpool(run)
        return _camel(asdict(result))

    @app.post("/v1/sing")
    async def sing(req: SingRequest):
        songs = req.songPaths or ([req.songPath] if req.songPath else [])
        if not songs:
            raise HTTPException(422, "songPath or songPaths is required")
        _require(songs + [s.refPath for s in req.singers])
        async with lock:
            result = await run_in_threadpool(
                app.state.singer.sing, songs,
                [SingerInput(s.memberId, s.refPath, s.refF0Hz) for s in req.singers], req.outPath, req.workDir,
                req.lyrics)
        log.info("sing: picked %d of %s, dropped %s, assignment %s", result.picked,
                 [(c.layered, c.coverage, c.score) for c in result.candidates], result.dropped_phrases,
                 result.assignment)
        return _camel(asdict(result))

    return app


app = create_app()
