"""POST /v1/trips/enrich: TripSummary -> EnrichedTrip (설계 §6.3, §9).

좌표는 요청 처리에만 쓰고 DB·파일에 저장하지 않는다 (§8.1). Nominatim 을 쓸 때는 반올림한 좌표 -> 지역
캐시가 프로세스 메모리에만 남는다 (재시작 시 사라짐).
외부 서비스가 실패하면 그 값만 비우고(지명 None/빈 목록, weather None) 200 으로 응답한다.
느린 경우도 같다: 역지오코딩은 GEOCODE_DEADLINE_SEC, 날씨는 WEATHER_DEADLINE_SEC 안에 끝난 값만 쓴다.
(Nominatim 은 서버 전체에서 초당 1건으로 줄을 서므로 동시 요청이 많으면 대기가 끝없이 길어질 수 있다.)
"""

import asyncio
import logging
from datetime import timedelta
from enum import StrEnum
from typing import TypeVar

from fastapi import APIRouter, Depends, Request
from pydantic import AwareDatetime, Field

from app.api.auth import current_device
from app.api.schemas import CamelModel
from app.providers.geo.reverse_geocode import Region, ReverseGeocoder
from app.providers.geo.weather import Weather, WeatherProvider

log = logging.getLogger(__name__)

router = APIRouter(prefix="/v1/trips", tags=["trips"], dependencies=[Depends(current_device)])

MAX_WAYPOINTS = 50  # §6.3: 앱이 최대 50개로 다운샘플링해서 보낸다
GEOCODE_POINTS = 8  # 역지오코딩할 경로 위 지점 수 (출발·도착 포함). Nominatim 은 초당 1건이라 약 8초
MAX_PLACE_NAMES = 5
# 앱 read 타임아웃 5분(§4.1)보다 충분히 짧게. 혼자 쓰는 Nominatim 8건(약 8~12초)은 넉넉히 들어간다
GEOCODE_DEADLINE_SEC = 30.0
WEATHER_DEADLINE_SEC = 15.0


class TimeOfDay(StrEnum):
    DAWN = "DAWN"
    MORNING = "MORNING"
    AFTERNOON = "AFTERNOON"
    EVENING = "EVENING"
    NIGHT = "NIGHT"


class GeoPoint(CamelModel):
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)


class TripSummary(CamelModel):
    """§6.3. 시각은 ISO-8601 (타임존 필수)."""

    trip_id: str = Field(min_length=1, max_length=64)
    started_at: AwareDatetime
    ended_at: AwareDatetime | None = None  # 진행 중이면 null (ETA 트리거 시)
    eta_at: AwareDatetime | None = None
    origin: GeoPoint
    destination: GeoPoint | None = None
    destination_name: str | None = Field(default=None, max_length=100)  # NaviHelper 제공 시
    waypoints: list[GeoPoint] = Field(default_factory=list, max_length=MAX_WAYPOINTS)
    distance_km: float = Field(ge=0)
    duration_min: int = Field(ge=0)
    avg_speed_kmh: float = Field(ge=0)
    max_speed_kmh: float = Field(ge=0)
    time_of_day: TimeOfDay
    passengers: list[str] = Field(default_factory=list)  # 이번 주행에 탄 가족 구성원 id


class WeatherOut(CamelModel):
    description: str  # 한국어 (예: "맑음")
    temperature_c: float


class EnrichedTrip(TripSummary):
    origin_name: str | None = None
    # destinationName 은 TripSummary 에 이미 있다: 요청 값이 있으면 그대로, 없으면 역지오코딩 결과
    place_names: list[str] = Field(default_factory=list)  # 경로 순서, 시/군/구 단위로 중복 제거, 최대 5개
    weather: WeatherOut | None = None  # 도착 지점·도착 시각 기준


T = TypeVar("T")


def sample_evenly(items: list[T], n: int) -> list[T]:
    """처음과 끝을 포함해 순서대로 고르게 n개 고른다."""
    if len(items) <= n:
        return list(items)
    if n == 1:
        return [items[0]]
    return [items[round(i * (len(items) - 1) / (n - 1))] for i in range(n)]


async def _region(geocoder: ReverseGeocoder, p: GeoPoint) -> Region | None:
    try:
        return await geocoder.region(p.lat, p.lng)
    except Exception:  # 외부 서비스 실패는 그 지점만 건너뛴다
        log.warning("reverse geocoding failed (%s)", geocoder.name, exc_info=True)
        return None


async def enrich_trip(
    summary: TripSummary, geocoder: ReverseGeocoder, weather_provider: WeatherProvider
) -> EnrichedTrip:
    route = [summary.origin, *summary.waypoints]
    if summary.destination:
        route.append(summary.destination)
    points = sample_evenly(route, GEOCODE_POINTS)
    # 상한 안에 끝난 지점만 쓰고 나머지는 취소한다 (Nominatim 대기열에서도 빠진다)
    tasks = [asyncio.create_task(_region(geocoder, p)) for p in points]
    done, pending = await asyncio.wait(tasks, timeout=GEOCODE_DEADLINE_SEC)
    for task in pending:
        task.cancel()
    await asyncio.gather(*pending, return_exceptions=True)
    if pending:
        log.warning("reverse geocoding: %d of %d points timed out (%s)", len(pending), len(tasks), geocoder.name)
    regions = [task.result() if task in done else None for task in tasks]

    unique: list[Region] = []
    for region in regions:
        if region and region not in unique:
            unique.append(region)
    place_names = [r.name for r in sample_evenly(unique, MAX_PLACE_NAMES)]

    # 노래는 도착 순간에 듣는다: 도착 지점(없으면 경로 마지막 지점), 도착 시각(없으면 ETA, 둘 다 없으면 시작+이동시간)
    when = summary.ended_at or summary.eta_at or summary.started_at + timedelta(minutes=summary.duration_min)
    weather: Weather | None = None
    try:
        async with asyncio.timeout(WEATHER_DEADLINE_SEC):
            weather = await weather_provider.at(route[-1].lat, route[-1].lng, when)
    except Exception:  # TimeoutError 포함
        log.warning("weather lookup failed (%s)", weather_provider.name, exc_info=True)

    fields = summary.model_dump()
    if not fields["destination_name"]:
        fields["destination_name"] = regions[-1].name if regions[-1] else None
    return EnrichedTrip(
        **fields,
        origin_name=regions[0].name if regions[0] else None,
        place_names=place_names,
        weather=WeatherOut(description=weather.description, temperature_c=weather.temperature_c)
        if weather
        else None,
    )


@router.post("/enrich", response_model=EnrichedTrip)
async def enrich(body: TripSummary, request: Request) -> EnrichedTrip:
    state = request.app.state
    return await enrich_trip(body, state.geocoder, state.weather)
