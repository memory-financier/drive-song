"""역지오코딩: 좌표 -> 시/군/구 단위 지역 (설계 §6.1 지명, §7.2 placeNames).

- KakaoGeocoder: Kakao Local coord2regioncode (KAKAO_REST_API_KEY 필요)
  문서: https://developers.kakao.com/docs/latest/ko/local/dev-guide (좌표로 행정구역정보 받기)
- NominatimGeocoder: OSM Nominatim reverse (키 불필요). 사용 정책을 따른다
  (https://operations.osmfoundation.org/policies/nominatim/): 식별 가능한 User-Agent,
  초당 1건 이하, 결과 캐시. 언어는 accept-language=ko.
- FakeGeocoder: 가장 가까운 미리 정한 지역 중심점 (키·네트워크 없이 테스트·데모용)
"""

import asyncio
import math
import time
from dataclasses import dataclass
from typing import Any, Protocol

import httpx

# 특별시·광역시·특별자치시: 구/군 이름만으로는 겹치므로("중구") 도시 이름을 앞에 붙인다
METRO_CITIES = ("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종")
METRO_SUFFIXES = ("특별자치시", "특별시", "광역시")


@dataclass(frozen=True)
class Region:
    """시/군/구 단위 지역. province 는 시·도, district 는 시·군·구 (일반구는 버린다)."""

    province: str
    district: str

    @property
    def name(self) -> str:
        """가사에 쓸 짧은 지명: 광역시 구는 "서울 중구", 도의 시·군은 "강릉시", 세종은 "세종"."""
        short = self.province
        for suffix in METRO_SUFFIXES:
            short = short.removesuffix(suffix)
        if short in METRO_CITIES:
            return f"{short} {self.district}" if self.district else short
        return self.district or self.province


def make_region(province: str | None, district: str | None) -> Region | None:
    province = (province or "").strip()
    # "수원시 장안구" -> "수원시" (시 단위로 묶는다)
    district = (district or "").strip().split(" ")[0] if district else ""
    if not province and not district:
        return None
    return Region(province, district)


class ReverseGeocoder(Protocol):
    name: str

    async def region(self, lat: float, lng: float) -> Region | None:
        """지역을 찾지 못하면(바다 등) None. 통신 오류는 예외."""
        ...


class KakaoGeocoder:
    name = "kakao"
    url = "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json"

    def __init__(self, api_key: str, client: httpx.AsyncClient):
        self.api_key = api_key
        self.client = client

    async def region(self, lat: float, lng: float) -> Region | None:
        res = await self.client.get(
            self.url,
            params={"x": lng, "y": lat},
            headers={"Authorization": f"KakaoAK {self.api_key}"},
        )
        res.raise_for_status()
        docs = res.json().get("documents") or []
        if not docs:
            return None
        # B(법정동)·H(행정동) 모두 시/군/구 단계는 같다.
        # [확인 필요] 서울 좌표의 region_1depth_name 이 "서울특별시"인지 "서울"인지 (둘 다 처리함)
        doc = docs[0]
        return make_region(doc.get("region_1depth_name"), doc.get("region_2depth_name"))


def nominatim_region(address: dict[str, Any]) -> Region | None:
    """Nominatim address -> Region.

    실제 응답(2026-10 확인, accept-language=ko):
    - 서울: {"city": "서울특별시", "borough": "중구", ...}
    - 강릉: {"province": "강원특별자치도", "city": "강릉시"}
    - 양평: {"province": "경기도", "county": "양평군"}
    - 수원: {"province": "경기도", "city": "수원시", "city_district": "팔달구"}
    - 세종: {"city": "세종특별자치시", ...}
    """
    if address.get("province"):
        return make_region(address["province"], address.get("city") or address.get("county"))
    return make_region(address.get("city"), address.get("borough") or address.get("county"))


class NominatimGeocoder:
    name = "nominatim"
    url = "https://nominatim.openstreetmap.org/reverse"
    min_interval_sec = 1.0  # 사용 정책: 초당 1건 이하 (서버 전체)
    cache_max = 10_000

    def __init__(self, client: httpx.AsyncClient, user_agent: str):
        self.client = client
        self.user_agent = user_agent
        self._lock = asyncio.Lock()
        self._last_request = 0.0
        # 좌표(소수 3자리, 약 100m) -> 지역. 메모리에만 두고 디스크에 저장하지 않는다 (§8.1)
        self._cache: dict[tuple[float, float], Region | None] = {}

    async def region(self, lat: float, lng: float) -> Region | None:
        key = (round(lat, 3), round(lng, 3))
        if key in self._cache:
            return self._cache[key]
        async with self._lock:
            if key in self._cache:  # 기다리는 동안 다른 요청이 채웠을 수 있다
                return self._cache[key]
            wait = self._last_request + self.min_interval_sec - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            try:
                res = await self.client.get(
                    self.url,
                    params={
                        "lat": key[0],
                        "lon": key[1],
                        "format": "jsonv2",
                        "zoom": 10,  # 시/군/구 수준
                        "addressdetails": 1,
                        "accept-language": "ko",
                    },
                    headers={"User-Agent": self.user_agent},
                )
            finally:
                self._last_request = time.monotonic()
        res.raise_for_status()
        body = res.json()
        # 바다 등 결과가 없으면 {"error": "Unable to geocode"}
        region = None if "error" in body else nominatim_region(body.get("address") or {})
        if len(self._cache) >= self.cache_max:
            self._cache.clear()
        self._cache[key] = region
        return region


# FakeGeocoder 가 쓰는 지역 중심점 (서울→강릉 데모 경로 주변)
FAKE_REGIONS: list[tuple[float, float, Region]] = [
    (37.5636, 126.9976, Region("서울특별시", "중구")),
    (37.5301, 127.1238, Region("서울특별시", "강동구")),
    (37.5393, 127.2149, Region("경기도", "하남시")),
    (37.4917, 127.4875, Region("경기도", "양평군")),
    (37.4917, 127.9852, Region("강원특별자치도", "횡성군")),
    (37.3422, 127.9202, Region("강원특별자치도", "원주시")),
    (37.3708, 128.3903, Region("강원특별자치도", "평창군")),
    (37.7519, 128.8761, Region("강원특별자치도", "강릉시")),
]


class FakeGeocoder:
    name = "fake"

    async def region(self, lat: float, lng: float) -> Region | None:
        return min(FAKE_REGIONS, key=lambda r: math.hypot(r[0] - lat, r[1] - lng))[2]
