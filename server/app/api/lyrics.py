"""POST /v1/lyrics: (fallback) EnrichedTrip -> 가사 (설계 §7.2, §9).

LLM 출력이 §7.2 구조가 아니거나 호출이 실패(LlmError)하면 한 번 더 요청한다. 두 번 모두 실패하면 502.
재시도를 포함한 전체 시간은 DEADLINE_SEC 로 자르고 504 를 돌려준다. 공급자 한 번의 최악 시간
(Claude 120초 x SDK 재시도)이 앱 read 타임아웃 5분(§4.1)을 넘을 수 있어서, 앱이 클라이언트 타임아웃 대신
JSON 오류를 받게 한다.
"""

import asyncio
import logging
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import Field, StringConstraints

from app.api.auth import current_device
from app.api.schemas import CamelModel
from app.api.trips import EnrichedTrip
from app.providers.geo.weather import Weather
from app.providers.llm.base import LyricsInput, write_valid_lyrics
from app.providers.music.base import Style

log = logging.getLogger(__name__)

router = APIRouter(prefix="/v1", tags=["lyrics"], dependencies=[Depends(current_device)])

DEADLINE_SEC = 180.0  # 재시도 포함 전체 상한. 앱 read 타임아웃 5분보다 충분히 짧게

Nickname = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=20)]


class LyricsIn(CamelModel):
    enriched_trip: EnrichedTrip
    family_nicknames: list[Nickname] = Field(default_factory=list, max_length=10)
    style: Style


class LyricsOut(CamelModel):
    lyrics: str  # "[verse]\n...\n[chorus]\n..." (§9 song-jobs 의 lyrics 로 그대로 쓴다)
    title: str | None  # 도착지 이름이 있으면 "<도착지> 가는 길"


def lyrics_input(body: LyricsIn) -> LyricsInput:
    return trip_lyrics_input(body.enriched_trip, body.family_nicknames, body.style)


def trip_lyrics_input(trip: EnrichedTrip, family_nicknames: list[str], style: Style) -> LyricsInput:
    """가사에 쓰는 주행 정보만 (좌표 없음). 대화 잡은 이것을 잡 행에 저장한다 (§8.1)."""
    return LyricsInput(
        origin_name=trip.origin_name,
        destination_name=trip.destination_name,
        place_names=trip.place_names,
        time_of_day=trip.time_of_day.value,
        weather=Weather(trip.weather.description, trip.weather.temperature_c) if trip.weather else None,
        duration_min=trip.duration_min,
        distance_km=trip.distance_km,
        style=style,
        family_nicknames=family_nicknames,
    )


@router.post("/lyrics", response_model=LyricsOut)
async def create_lyrics(body: LyricsIn, request: Request) -> LyricsOut:
    try:
        async with asyncio.timeout(DEADLINE_SEC):  # 진행 중인 공급자 호출은 취소된다
            lyrics = await write_valid_lyrics(request.app.state.llm, lyrics_input(body))
    except TimeoutError:
        log.warning("lyrics generation timed out after %gs", DEADLINE_SEC)
        raise HTTPException(status.HTTP_504_GATEWAY_TIMEOUT, detail="lyrics generation timed out") from None
    if lyrics is None:
        raise HTTPException(status.HTTP_502_BAD_GATEWAY, detail="lyrics generation failed")
    destination = body.enriched_trip.destination_name
    return LyricsOut(lyrics=lyrics, title=f"{destination} 가는 길" if destination else None)
