import hashlib

from fastapi import Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db import get_session
from app.models import Device


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def current_device(
    request: Request,
    # 인증용 세션은 이 함수가 끝나면 바로 닫는다. 요청 범위로 두면 응답(파일 스트리밍 포함)을
    # 다 보낼 때까지 풀 커넥션을 잡고 있다. 반환된 Device 는 분리된 객체로 id 만 쓴다.
    session: Session = Depends(get_session, scope="function"),
) -> Device:
    """`Authorization: Bearer <token>` 으로 디바이스를 식별한다."""
    scheme, _, token = request.headers.get("Authorization", "").partition(" ")
    device = None
    if scheme.lower() == "bearer" and token:
        device = session.scalar(select(Device).where(Device.token_hash == hash_token(token)))
    if device is None:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED,
            detail="invalid or missing device token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return device
