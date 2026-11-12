import secrets

from fastapi import APIRouter, Depends, status
from sqlalchemy.orm import Session

from app.api.auth import hash_token
from app.api.schemas import CamelModel
from app.db import get_session
from app.models import Device

router = APIRouter(prefix="/v1/devices", tags=["devices"])


class DeviceOut(CamelModel):
    device_id: str
    token: str


@router.post("", status_code=status.HTTP_201_CREATED, response_model=DeviceOut)
def register_device(session: Session = Depends(get_session)) -> DeviceOut:
    token = secrets.token_urlsafe(32)
    device = Device(id=f"dev_{secrets.token_hex(8)}", token_hash=hash_token(token))
    session.add(device)
    session.commit()
    return DeviceOut(device_id=device.id, token=token)
