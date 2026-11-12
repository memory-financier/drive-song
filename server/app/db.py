from collections.abc import Iterator
from datetime import datetime, timezone

from fastapi import Request
from sqlalchemy import create_engine, inspect, text
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from sqlalchemy.schema import CreateColumn


class Base(DeclarativeBase):
    pass


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def make_engine(url: str) -> Engine:
    connect_args = {"check_same_thread": False} if url.startswith("sqlite") else {}
    return create_engine(url, connect_args=connect_args)


def add_missing_columns(engine: Engine) -> list[str]:
    """create_all 은 이미 있는 테이블에 새 열을 넣지 않는다. 모델에 새로 생긴 열만 ALTER TABLE ADD COLUMN 한다
    (MVP 는 마이그레이션 도구가 없다). 새 NOT NULL 열은 server_default 가 있어야 한다. 추가한 "테이블.열" 목록."""
    added = []
    existing = inspect(engine)
    with engine.begin() as conn:
        for table in Base.metadata.sorted_tables:
            if not existing.has_table(table.name):
                continue
            have = {c["name"] for c in existing.get_columns(table.name)}
            for column in table.columns:
                if column.name not in have:
                    ddl = CreateColumn(column).compile(dialect=engine.dialect)
                    conn.execute(text(f"ALTER TABLE {table.name} ADD COLUMN {ddl}"))
                    added.append(f"{table.name}.{column.name}")
    return added


def get_session(request: Request) -> Iterator[Session]:
    session_factory: sessionmaker[Session] = request.app.state.session_factory
    with session_factory() as session:
        yield session
