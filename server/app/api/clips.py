import json
import re
import secrets
import subprocess
from datetime import datetime
from enum import StrEnum
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, Response, UploadFile, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.api.auth import current_device
from app.api.members import get_owned_member
from app.api.schemas import CamelModel
from app.db import get_session
from app.models import Clip, Device, Member

router = APIRouter(prefix="/v1/clips", tags=["clips"], dependencies=[Depends(current_device)])

# 설계 §8: 구성원별 슬롯마다 최대 3개, 클립당 최대 5초
MAX_CLIPS_PER_SLOT = 3
MAX_CLIP_SEC = 5.0
DURATION_TOLERANCE_SEC = 0.3  # 인코더 패딩 등으로 약간 넘는 경우 허용
MAX_CLIP_BYTES = 2 * 1024 * 1024  # 5초 무압축 WAV(48kHz 16bit 스테레오 ≈ 0.94MB)도 들어가는 크기


class Slot(StrEnum):
    INTRO = "INTRO"
    CALL = "CALL"
    OUTRO = "OUTRO"


class ClipOut(CamelModel):
    clip_id: str
    member_id: str
    slot: Slot
    duration_sec: float
    created_at: datetime


def probe_audio_duration(path: Path) -> float | None:
    """오디오 스트림이 있으면 길이(초), 없거나 읽을 수 없으면 None."""
    result = subprocess.run(
        [
            "ffprobe", "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "stream=codec_type:format=duration",
            "-of", "json",
            str(path),
        ],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    info = json.loads(result.stdout or "{}")
    try:
        duration = float(info["format"]["duration"])
    except (KeyError, TypeError, ValueError):
        return None
    return duration if info.get("streams") else None


def _save_upload(upload: UploadFile, dest: Path) -> bool:
    """파일을 저장한다. MAX_CLIP_BYTES를 넘으면 지우고 False."""
    size = 0
    with dest.open("wb") as out:
        while chunk := upload.file.read(64 * 1024):
            size += len(chunk)
            if size > MAX_CLIP_BYTES:
                break
            out.write(chunk)
    if size > MAX_CLIP_BYTES:
        dest.unlink(missing_ok=True)
        return False
    return True


@router.post("", status_code=status.HTTP_201_CREATED, response_model=ClipOut)
def upload_clip(
    request: Request,
    member_id: str = Form(alias="memberId"),
    slot: Slot = Form(),
    audio: UploadFile = File(),
    device: Device = Depends(current_device),
    session: Session = Depends(get_session),
) -> ClipOut:
    member = get_owned_member(session, device, member_id)
    count = session.scalar(
        select(func.count()).select_from(Clip).where(Clip.member_id == member.id, Clip.slot == slot)
    )
    if count >= MAX_CLIPS_PER_SLOT:
        raise HTTPException(
            status.HTTP_409_CONFLICT, detail=f"max {MAX_CLIPS_PER_SLOT} clips per slot"
        )

    data_dir: Path = request.app.state.settings.data_dir
    clip_id = f"clip_{secrets.token_hex(8)}"
    suffix = Path(audio.filename or "").suffix.lower()
    if not re.fullmatch(r"\.[a-z0-9]{1,5}", suffix):
        suffix = ""
    rel_path = f"clips/{clip_id}{suffix}"
    dest = data_dir / rel_path
    dest.parent.mkdir(parents=True, exist_ok=True)

    if not _save_upload(audio, dest):
        raise HTTPException(status.HTTP_413_CONTENT_TOO_LARGE, detail="audio file too large")
    duration = probe_audio_duration(dest)
    if duration is None:
        dest.unlink(missing_ok=True)
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="unreadable audio file")
    if duration > MAX_CLIP_SEC + DURATION_TOLERANCE_SEC:
        dest.unlink(missing_ok=True)
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST, detail=f"clip longer than {MAX_CLIP_SEC:g} seconds"
        )

    clip = Clip(id=clip_id, member_id=member.id, slot=slot, path=rel_path, duration_sec=duration)
    session.add(clip)
    session.commit()
    return ClipOut(
        clip_id=clip.id,
        member_id=clip.member_id,
        slot=slot,
        duration_sec=round(duration, 3),
        created_at=clip.created_at,
    )


@router.delete("/{clip_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_clip(
    clip_id: str,
    request: Request,
    device: Device = Depends(current_device),
    session: Session = Depends(get_session),
) -> Response:
    clip = session.scalar(
        select(Clip).join(Member).where(Clip.id == clip_id, Member.device_id == device.id)
    )
    if clip is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="clip not found")
    path = clip.path
    session.delete(clip)
    session.commit()
    (request.app.state.settings.data_dir / path).unlink(missing_ok=True)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
