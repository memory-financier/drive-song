"""날씨 조회 (설계 §6.1).

- OpenMeteoWeather: https://open-meteo.com/en/docs (키 불필요). hourly temperature_2m, weather_code 를
  start_hour=end_hour (GMT) 로 한 시간만 받는다. 조회 가능 범위는 대략 오늘 -92일 ~ +16일이고
  범위 밖이면 400 {"error": true, "reason": ...} (2026-10 실제 응답 확인).
- FakeWeather: 고정값 (테스트·데모용)
"""

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Protocol

import httpx

# WMO weather interpretation code -> 한국어 (Open-Meteo 문서의 코드표)
WMO_KO: dict[int, str] = {
    0: "맑음",
    1: "대체로 맑음",
    2: "구름 조금",
    3: "흐림",
    45: "안개",
    48: "서리 안개",
    51: "약한 이슬비",
    53: "이슬비",
    55: "강한 이슬비",
    56: "약한 어는 이슬비",
    57: "강한 어는 이슬비",
    61: "약한 비",
    63: "비",
    65: "강한 비",
    66: "약한 어는 비",
    67: "강한 어는 비",
    71: "약한 눈",
    73: "눈",
    75: "강한 눈",
    77: "싸락눈",
    80: "약한 소나기",
    81: "소나기",
    82: "강한 소나기",
    85: "약한 눈 소나기",
    86: "강한 눈 소나기",
    95: "뇌우",
    96: "약한 우박을 동반한 뇌우",
    97: "강한 뇌우",
    99: "강한 우박을 동반한 뇌우",
}


@dataclass(frozen=True)
class Weather:
    description: str  # 한국어 (예: "맑음")
    temperature_c: float


class WeatherProvider(Protocol):
    name: str

    async def at(self, lat: float, lng: float, when: datetime) -> Weather | None:
        """when 시각(가장 가까운 정시) 날씨. 데이터가 없으면 None, 통신 오류는 예외."""
        ...


def nearest_hour_utc(when: datetime) -> datetime:
    utc = when.astimezone(timezone.utc)
    return (utc + timedelta(minutes=30)).replace(minute=0, second=0, microsecond=0)


class OpenMeteoWeather:
    name = "open-meteo"
    url = "https://api.open-meteo.com/v1/forecast"

    def __init__(self, client: httpx.AsyncClient):
        self.client = client

    async def at(self, lat: float, lng: float, when: datetime) -> Weather | None:
        hour = nearest_hour_utc(when).strftime("%Y-%m-%dT%H:%M")
        res = await self.client.get(
            self.url,
            params={
                "latitude": round(lat, 4),
                "longitude": round(lng, 4),
                "hourly": "temperature_2m,weather_code",
                "start_hour": hour,
                "end_hour": hour,
                "timezone": "GMT",
            },
        )
        res.raise_for_status()
        hourly = res.json().get("hourly") or {}
        temps, codes = hourly.get("temperature_2m") or [], hourly.get("weather_code") or []
        if not temps or not codes or temps[0] is None or codes[0] is None:
            return None
        code = int(codes[0])
        return Weather(WMO_KO.get(code, "알 수 없음"), float(temps[0]))


class FakeWeather:
    name = "fake"

    async def at(self, lat: float, lng: float, when: datetime) -> Weather | None:
        return Weather("맑음", 15.0)
