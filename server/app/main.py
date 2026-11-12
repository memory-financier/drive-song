from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI
from sqlalchemy.orm import sessionmaker

from app import models  # noqa: F401  (devices, members, clips 테이블 등록)
from app.api import clips, conversations, devices, legal, lyrics, members, song_jobs, trips
from app.db import Base, add_missing_columns, make_engine
from app.jobs import models as job_models  # noqa: F401  (jobs, songs 테이블 등록)
from app.jobs.worker import JobWorker
from app.providers.geo.factory import make_geo_providers
from app.providers.llm.factory import make_llm_provider
from app.settings import Settings


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()
    engine = make_engine(settings.resolved_database_url)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        settings.data_dir.mkdir(parents=True, exist_ok=True)
        # MVP: 마이그레이션 도구 없이 시작 시 테이블 생성, 기존 테이블에는 새 열만 추가
        Base.metadata.create_all(engine)
        add_missing_columns(engine)
        await app.state.worker.start()  # 끝나지 않은 잡 이어서 처리
        yield
        await app.state.worker.stop()
        await app.state.http.aclose()
        engine.dispose()

    app = FastAPI(title="Drive Song Server", version="0.1.0", lifespan=lifespan)
    app.state.settings = settings
    # expire_on_commit=False: 커밋 후에도 응답에 쓸 값(타임존 포함 createdAt 등)을 유지
    app.state.session_factory = sessionmaker(engine, expire_on_commit=False)
    app.state.worker = JobWorker(settings, app.state.session_factory)
    # 지명·날씨·가사 공급자 (키 누락·잘못된 이름이면 여기서 ValueError). 테스트는 app.state 에서 바꾼다.
    app.state.http = httpx.AsyncClient(timeout=10)
    app.state.geocoder, app.state.weather = make_geo_providers(settings, app.state.http)
    app.state.llm = make_llm_provider(settings, app.state.http)
    app.state.worker.llm = app.state.llm  # 대화 잡의 가사 (§7.5 4단계)

    app.include_router(devices.router)
    app.include_router(members.router)
    app.include_router(clips.router)
    app.include_router(song_jobs.router)
    app.include_router(trips.router)
    app.include_router(conversations.router)
    app.include_router(lyrics.router)
    app.include_router(legal.router)
    return app


app = create_app()
