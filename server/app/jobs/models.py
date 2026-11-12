from datetime import datetime

from sqlalchemy import JSON, Boolean, DateTime, ForeignKey, Integer, String, Text, false, text
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, utcnow

# 잡 상태 (설계 §9, §9.1): QUEUED | ANALYZING | GENERATING | CONVERTING | MIXING | READY | FAILED


class Job(Base):
    __tablename__ = "jobs"

    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)
    trip_id: Mapped[str] = mapped_column(String(64))
    lyrics: Mapped[str] = mapped_column(Text)
    style: Mapped[str] = mapped_column(String(32))
    duration_sec: Mapped[int] = mapped_column(Integer)
    # [{"slot": "INTRO", "clipId": "clip_..."}, ...]
    clip_plan: Mapped[list] = mapped_column(JSON, default=list)
    status: Mapped[str] = mapped_column(String(16), default="QUEUED")
    title: Mapped[str] = mapped_column(String(100))
    # 잡 생성 시점의 settings.music_provider (재시작 후 같은 공급자로 이어서 폴링)
    provider: Mapped[str] = mapped_column(String(16))
    provider_job_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    # 공급자 제출 시각. 생성 제한 시간(job_timeout_sec)의 기준
    submitted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    song_id: Mapped[str | None] = mapped_column(String(40), nullable=True)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)
    # v0.4 대화 노래 (§7.5). lyrics 는 대화 잡이면 분석 뒤에 채운다 (그 전엔 "")
    conversation: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    # 가사 입력 (LyricsInput 필드). 좌표는 저장하지 않는다 (§8.1)
    lyrics_input: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    passengers: Mapped[list] = mapped_column(JSON, default=list, server_default=text("'[]'"))
    # 대화에서 고른 한마디: [{"slot", "memberId", "path"(data_dir 기준)}]. clipPlan 이 비운 슬롯만
    moments: Mapped[list] = mapped_column(JSON, default=list, server_default=text("'[]'"))
    # 부를 구성원 id. 변환이 끝나면 실제로 부른 사람으로 바뀐다
    singers: Mapped[list] = mapped_column(JSON, default=list, server_default=text("'[]'"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )


class Song(Base):
    __tablename__ = "songs"

    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)
    job_id: Mapped[str] = mapped_column(ForeignKey("jobs.id"), index=True)
    title: Mapped[str] = mapped_column(String(100))
    # data_dir 기준 상대 경로
    audio_path: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
