from datetime import datetime

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, String, UniqueConstraint, false
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, utcnow


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    # 원본 토큰은 저장하지 않고 SHA-256 해시만 저장
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Member(Base):
    __tablename__ = "members"

    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)
    nickname: Mapped[str] = mapped_column(String(20))
    is_child: Mapped[bool] = mapped_column(Boolean)
    voice_consent: Mapped[bool] = mapped_column(Boolean)
    guardian_consent: Mapped[bool] = mapped_column(Boolean)
    # v0.4 (§8.3): 대화 녹음, 노래 목소리 변환. 선택 동의 (기존 행은 false)
    conversation_consent: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    voice_conversion_consent: Mapped[bool] = mapped_column(Boolean, default=False, server_default=false())
    # 노래 목소리 변환의 기준이 되는 참고 목소리 (대화에서 고른 ≤30초, data_dir 기준 상대 경로)
    voice_ref_path: Mapped[str | None] = mapped_column(String(255), nullable=True)
    voice_ref_sec: Mapped[float | None] = mapped_column(Float, nullable=True)
    voice_ref_f0_hz: Mapped[float | None] = mapped_column(Float, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class ConversationChunk(Base):
    """주행 중 대화 녹음 조각 (§7.5 1단계). 노래 잡의 분석이 끝나면 지운다."""

    __tablename__ = "conversation_chunks"
    __table_args__ = (UniqueConstraint("device_id", "trip_id", "seq"),)

    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)
    trip_id: Mapped[str] = mapped_column(String(64))
    seq: Mapped[int] = mapped_column(Integer)
    # data_dir 기준 상대 경로
    path: Mapped[str] = mapped_column(String(255))
    duration_sec: Mapped[float] = mapped_column(Float)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class Clip(Base):
    __tablename__ = "clips"

    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    member_id: Mapped[str] = mapped_column(ForeignKey("members.id"), index=True)
    slot: Mapped[str] = mapped_column(String(8))  # INTRO | CALL | OUTRO
    # data_dir 기준 상대 경로
    path: Mapped[str] = mapped_column(String(255))
    duration_sec: Mapped[float] = mapped_column(Float)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
