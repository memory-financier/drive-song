import secrets
from datetime import datetime
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from pydantic import StringConstraints
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.auth import current_device
from app.api.schemas import CamelModel
from app.db import get_session
from app.models import Clip, Device, Member

router = APIRouter(prefix="/v1/members", tags=["members"], dependencies=[Depends(current_device)])


class MemberIn(CamelModel):
    nickname: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=20)]
    is_child: bool
    voice_consent: bool
    guardian_consent: bool
    # v0.4 선택 동의 (§8.3)
    conversation_consent: bool = False
    voice_conversion_consent: bool = False


class MemberPatch(CamelModel):
    """보내지 않은 필드는 그대로 둔다."""

    conversation_consent: bool | None = None
    voice_conversion_consent: bool | None = None


class MemberOut(CamelModel):
    member_id: str
    nickname: str
    is_child: bool
    voice_consent: bool
    guardian_consent: bool
    conversation_consent: bool
    voice_conversion_consent: bool
    created_at: datetime


def _member_out(member: Member) -> MemberOut:
    return MemberOut(
        member_id=member.id,
        nickname=member.nickname,
        is_child=member.is_child,
        voice_consent=member.voice_consent,
        guardian_consent=member.guardian_consent,
        conversation_consent=member.conversation_consent,
        voice_conversion_consent=member.voice_conversion_consent,
        created_at=member.created_at,
    )


def get_owned_member(session: Session, device: Device, member_id: str) -> Member:
    """다른 디바이스의 구성원은 존재 여부도 드러내지 않도록 404."""
    member = session.get(Member, member_id)
    if member is None or member.device_id != device.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="member not found")
    return member


def clear_voice_ref(member: Member, data_dir: Path) -> None:
    """참고 목소리 삭제 (§8.3: 구성원 삭제·변환 동의 철회 시 즉시). 호출한 쪽이 커밋한다."""
    if member.voice_ref_path:
        (data_dir / member.voice_ref_path).unlink(missing_ok=True)
    member.voice_ref_path = member.voice_ref_sec = member.voice_ref_f0_hz = None


@router.post("", status_code=status.HTTP_201_CREATED, response_model=MemberOut)
def create_member(
    body: MemberIn,
    device: Device = Depends(current_device),
    session: Session = Depends(get_session),
) -> MemberOut:
    # 설계 §8.1: 음성 수집 동의 필수, 아동은 보호자 동의 필수 (선택 동의 §8.3 도 이 보호자 동의 아래에서 받는다)
    if not body.voice_consent:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="voiceConsent is required")
    if body.is_child and not body.guardian_consent:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST, detail="guardianConsent is required for a child member"
        )
    member = Member(
        id=f"mem_{secrets.token_hex(8)}",
        device_id=device.id,
        nickname=body.nickname,
        is_child=body.is_child,
        voice_consent=body.voice_consent,
        guardian_consent=body.guardian_consent,
        conversation_consent=body.conversation_consent,
        voice_conversion_consent=body.voice_conversion_consent,
    )
    session.add(member)
    session.commit()
    return _member_out(member)


@router.patch("/{member_id}", response_model=MemberOut)
def update_member(
    member_id: str,
    body: MemberPatch,
    request: Request,
    device: Device = Depends(current_device),
    session: Session = Depends(get_session),
) -> MemberOut:
    member = get_owned_member(session, device, member_id)
    if body.conversation_consent is not None:
        member.conversation_consent = body.conversation_consent
    if body.voice_conversion_consent is not None:
        member.voice_conversion_consent = body.voice_conversion_consent
        if not body.voice_conversion_consent:
            clear_voice_ref(member, request.app.state.settings.data_dir)
    session.commit()
    return _member_out(member)


@router.delete("/{member_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_member(
    member_id: str,
    request: Request,
    device: Device = Depends(current_device),
    session: Session = Depends(get_session),
) -> Response:
    # 설계 §8.1: 구성원 삭제 시 서버 클립(DB + 파일) 즉시 삭제. §8.3: 참고 목소리도
    member = get_owned_member(session, device, member_id)
    clips = session.scalars(select(Clip).where(Clip.member_id == member.id)).all()
    paths = [clip.path for clip in clips]
    data_dir = request.app.state.settings.data_dir
    clear_voice_ref(member, data_dir)
    for clip in clips:
        session.delete(clip)
    session.delete(member)
    session.commit()
    for path in paths:
        (data_dir / path).unlink(missing_ok=True)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
