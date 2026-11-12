"""대화 녹음 조각 업로드·삭제 (설계 §7.5 1단계, §8.3, §9.1).

앱은 '대화 담기' 중 5분 조각(AAC 모노 16kHz 32kbps, 약 1.2MB)을 올린다. 서버도 탑승자 전원의 대화 녹음 동의를 확인한다.
조각은 노래 잡의 대화 분석이 끝나면(성공·실패 모두) 지운다. 노래를 만들지 않은 주행은 앱이 DELETE 로 지운다.
그 주행의 대화 노래 잡이 생긴 뒤에 오는 조각은 409 로 거부한다 (쓰이지 않고 남는 대화를 만들지 않게).
"""

import re
import secrets
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, HTTPException, Path as PathParam, Request, Response, UploadFile, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.auth import current_device
from app.api.clips import probe_audio_duration
from app.api.members import get_owned_member
from app.api.schemas import CamelModel
from app.db import get_session
from app.jobs.models import Job
from app.models import ConversationChunk, Device

router = APIRouter(prefix="/v1/trips", tags=["conversation"], dependencies=[Depends(current_device)])

MAX_CHUNK_SEC = 300.0  # 5분 조각
DURATION_TOLERANCE_SEC = 5.0
MAX_CHUNK_BYTES = 4 * 1024 * 1024  # 5분 x 32kbps ≈ 1.2MB 의 3배 여유

TripId = Annotated[str, PathParam(min_length=1, max_length=64)]


class ChunkOut(CamelModel):
    trip_id: str
    seq: int
    duration_sec: float


def _save(upload: UploadFile, dest: Path) -> bool:
    size = 0
    with dest.open("wb") as out:
        while chunk := upload.file.read(64 * 1024):
            size += len(chunk)
            if size > MAX_CHUNK_BYTES:
                break
            out.write(chunk)
    if size > MAX_CHUNK_BYTES:
        dest.unlink(missing_ok=True)
        return False
    return True


def delete_chunks(session: Session, data_dir: Path, device_id: str, trip_id: str | None = None) -> int:
    """이 디바이스(trip_id 를 주면 그 주행만)의 조각 행과 파일을 지운다. 지운 개수."""
    query = select(ConversationChunk).where(ConversationChunk.device_id == device_id)
    if trip_id is not None:
        query = query.where(ConversationChunk.trip_id == trip_id)
    chunks = session.scalars(query).all()
    paths = [c.path for c in chunks]
    for c in chunks:
        session.delete(c)
    session.commit()
    for path in paths:
        (data_dir / path).unlink(missing_ok=True)
    return len(paths)


@router.post("/{trip_id}/conversation", status_code=status.HTTP_201_CREATED, response_model=ChunkOut)
def upload_chunk(
    trip_id: TripId,
    request: Request,
    seq: int = Form(ge=0),
    passengers: list[str] = Form(min_length=1),  # 이번 주행 탑승자 구성원 id (폼 필드 반복)
    audio: UploadFile = File(),
    device: Device = Depends(current_device),
    session: Session = Depends(get_session),
) -> ChunkOut:
    for member_id in passengers:
        member = get_owned_member(session, device, member_id)
        if not member.conversation_consent:
            raise HTTPException(
                status.HTTP_400_BAD_REQUEST, detail="conversationConsent is required for all passengers"
            )
    # 노래가 시작된 주행은 대화를 이미 분석했거나 분석 중이다. 뒤늦은 조각은 쓰이지 않고 남기만 하므로 받지 않는다
    started = session.scalar(select(Job.id).where(
        Job.device_id == device.id, Job.trip_id == trip_id, Job.conversation.is_(True)).limit(1))
    if started:
        raise HTTPException(status.HTTP_409_CONFLICT, detail="song already started for this trip")

    data_dir: Path = request.app.state.settings.data_dir
    chunk_id = f"conv_{secrets.token_hex(8)}"
    suffix = Path(audio.filename or "").suffix.lower()
    if not re.fullmatch(r"\.[a-z0-9]{1,5}", suffix):
        suffix = ""
    rel_path = f"conversations/{chunk_id}{suffix}"
    dest = data_dir / rel_path
    dest.parent.mkdir(parents=True, exist_ok=True)
    if not _save(audio, dest):
        raise HTTPException(status.HTTP_413_CONTENT_TOO_LARGE, detail="audio file too large")
    duration = probe_audio_duration(dest)
    if duration is None or duration > MAX_CHUNK_SEC + DURATION_TOLERANCE_SEC:
        dest.unlink(missing_ok=True)
        detail = "unreadable audio file" if duration is None else f"chunk longer than {MAX_CHUNK_SEC:g} seconds"
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail=detail)

    # 같은 순번을 다시 올리면(업로드 재시도) 바꿔 넣는다
    old = session.scalar(select(ConversationChunk).where(
        ConversationChunk.device_id == device.id, ConversationChunk.trip_id == trip_id, ConversationChunk.seq == seq))
    old_path = old.path if old else None
    if old:
        session.delete(old)
        session.flush()
    session.add(ConversationChunk(id=chunk_id, device_id=device.id, trip_id=trip_id, seq=seq,
                                  path=rel_path, duration_sec=duration))
    session.commit()
    if old_path:
        (data_dir / old_path).unlink(missing_ok=True)
    return ChunkOut(trip_id=trip_id, seq=seq, duration_sec=round(duration, 3))


@router.delete("/{trip_id}/conversation", status_code=status.HTTP_204_NO_CONTENT)
def delete_conversation(
    trip_id: TripId,
    request: Request,
    device: Device = Depends(current_device),
    session: Session = Depends(get_session),
) -> Response:
    """'대화 담기'를 끄고 지우기. 올린 조각이 없어도 204."""
    delete_chunks(session, request.app.state.settings.data_dir, device.id, trip_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
