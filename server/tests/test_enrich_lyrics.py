"""T5: /v1/trips/enrich, /v1/lyrics 와 지명·날씨·LLM 어댑터.

외부 API 는 모두 MockTransport 로 대신한다 (응답 형식은 각 모듈 docstring 의 문서·실제 응답 기준).
live 마커 테스트만 실제 Open-Meteo·Nominatim 을 부른다: python -m pytest -m live -s
"""

import asyncio
import json
import time
from datetime import datetime, timedelta, timezone

import anthropic
import httpx
import httpx2
import pytest
from fastapi.testclient import TestClient

from app.api import lyrics as lyrics_api
from app.api import trips as trips_api
from app.api.trips import EnrichedTrip, TripSummary, enrich_trip, sample_evenly
from app.main import create_app
from app.providers.geo.factory import make_geo_providers
from app.providers.geo.reverse_geocode import (
    FakeGeocoder,
    KakaoGeocoder,
    NominatimGeocoder,
    Region,
    make_region,
    nominatim_region,
)
from app.providers.geo.weather import FakeWeather, OpenMeteoWeather, Weather
from app.providers.llm.base import LlmError, LyricsInput, build_prompt, validate_lyrics
from app.providers.llm.claude import FALLBACK_BETA, ClaudeLlm
from app.providers.llm.factory import make_llm_provider
from app.providers.music.base import Style
from app.settings import Settings
from conftest import register

# 서울 시청 -> 강릉 (영동고속도로 경유) 주요 지점. 그 사이를 직선 보간해 waypoints 를 만든다
ROUTE_KEYPOINTS = [
    (37.5665, 126.9780),  # 서울 중구
    (37.5301, 127.1238),  # 서울 강동구
    (37.5393, 127.2149),  # 하남시
    (37.4917, 127.4875),  # 양평군
    (37.4917, 127.9852),  # 횡성군
    (37.5600, 128.4000),  # 평창군
    (37.6800, 128.7200),  # 평창군 대관령
    (37.7519, 128.8761),  # 강릉시
]


def seoul_gangneung_waypoints(per_leg: int = 6) -> list[dict]:
    points = []
    for (a_lat, a_lng), (b_lat, b_lng) in zip(ROUTE_KEYPOINTS, ROUTE_KEYPOINTS[1:]):
        for i in range(1, per_leg + 1):
            t = i / (per_leg + 1)
            points.append({"lat": a_lat + (b_lat - a_lat) * t, "lng": a_lng + (b_lng - a_lng) * t})
    return points


def sample_trip(**overrides) -> dict:
    """§6.3 TripSummary JSON (앱 kotlinx.serialization 출력과 같은 camelCase·ISO-8601)."""
    trip = {
        "tripId": "trip_20261101_0930",
        "startedAt": "2026-11-01T00:30:00Z",
        "endedAt": "2026-11-01T03:30:00Z",
        "etaAt": None,
        "origin": {"lat": 37.5665, "lng": 126.9780},
        "destination": {"lat": 37.7519, "lng": 128.8761},
        "destinationName": None,
        "waypoints": seoul_gangneung_waypoints(),
        "distanceKm": 237.4,
        "durationMin": 180,
        "avgSpeedKmh": 79.1,
        "maxSpeedKmh": 110.0,
        "timeOfDay": "MORNING",
        "passengers": ["mem_1", "mem_2"],
    }
    trip.update(overrides)
    return trip


def run(coro):
    return asyncio.run(coro)


# --- 순수 함수 ---------------------------------------------------------------


def test_sample_evenly_keeps_ends_and_order():
    assert sample_evenly(list(range(10)), 4) == [0, 3, 6, 9]
    assert sample_evenly([1, 2], 5) == [1, 2]
    assert sample_evenly(list(range(50)), 8)[0] == 0
    assert sample_evenly(list(range(50)), 8)[-1] == 49


@pytest.mark.parametrize(
    "province, district, name",
    [
        ("서울특별시", "중구", "서울 중구"),
        ("서울", "중구", "서울 중구"),  # Kakao 가 짧은 이름을 줄 경우
        ("부산광역시", "기장군", "부산 기장군"),
        ("세종특별자치시", "", "세종"),
        ("강원특별자치도", "강릉시", "강릉시"),
        ("경기도", "수원시 장안구", "수원시"),  # 일반구는 시 단위로
    ],
)
def test_region_names(province, district, name):
    assert make_region(province, district).name == name


def test_nominatim_address_mapping():
    # 2026-10 실제 Nominatim 응답의 address (accept-language=ko)
    assert nominatim_region({"suburb": "명동", "borough": "중구", "city": "서울특별시"}) == Region("서울특별시", "중구")
    assert nominatim_region({"city": "강릉시", "province": "강원특별자치도"}) == Region("강원특별자치도", "강릉시")
    assert nominatim_region({"county": "양평군", "province": "경기도"}) == Region("경기도", "양평군")
    assert nominatim_region(
        {"city_district": "팔달구", "city": "수원시", "province": "경기도"}
    ) == Region("경기도", "수원시")
    assert nominatim_region({"quarter": "보람동", "city": "세종특별자치시"}).name == "세종"
    assert nominatim_region({"country": "대한민국"}) is None


LYRICS_INPUT = LyricsInput(
    origin_name="서울 중구",
    destination_name="강릉시",
    place_names=["서울 중구", "양평군", "평창군", "강릉시"],
    time_of_day="MORNING",
    weather=Weather("맑음", 11.4),
    duration_min=180,
    distance_km=237.44,
    style=Style.CITY_POP,
    family_nicknames=["아빠", "콩이"],
)


def test_build_prompt_fills_template():
    prompt = build_prompt(LYRICS_INPUT)
    assert prompt.startswith("너는 가족 여행 노래 작사가야.")
    assert "한국어 시티팝 스타일 가사" in prompt
    assert "- 출발: 서울 중구, 도착: 강릉시" in prompt
    assert "- 지나온 곳: 서울 중구, 양평군, 평창군, 강릉시" in prompt
    assert "- 시간대: 아침, 날씨: 맑음, 11°C" in prompt
    assert "- 이동 시간: 180분, 거리: 237.4km" in prompt
    assert "- 함께 탄 가족: 아빠, 콩이" in prompt
    assert "가족 호칭 최소 1번 포함" in prompt
    assert "[verse] 4줄 + [chorus] 4줄, 모두 8줄로 끝 (2절·두 번째 후렴·bridge 쓰지 않기)" in prompt
    assert "- 출력은 섹션 태그가 포함된 가사 텍스트만 (설명·제목·코드 펜스 없이)" in prompt
    assert "추임새·의성어(랄랄라" in prompt
    assert prompt.endswith("태그 2개와 가사 8줄만:\n[verse]\n1줄\n2줄\n3줄\n4줄\n[chorus]\n1줄\n2줄\n3줄\n4줄")


def test_build_prompt_without_family_or_weather():
    inp = LyricsInput(None, None, [], "NIGHT", None, 10, 3.0, Style.KIDS)
    prompt = build_prompt(inp)
    assert "한국어 동요 스타일" in prompt
    assert "- 출발: 알 수 없음, 도착: 알 수 없음" in prompt
    assert "날씨: 알 수 없음" in prompt
    assert "- 함께 탄 가족: 없음" in prompt
    assert "가족 호칭" not in prompt


VALID = "\n".join(["[verse]", "a1", "a2", "a3", "a4", "[chorus]", "b1", "b2", "b3", "b4"])


def test_validate_lyrics_accepts_and_normalizes():
    assert validate_lyrics(VALID) == VALID
    messy = "\n" + VALID.replace("[verse]", "[Verse]").replace("a2", "  a2  \n") + "\n\n"
    assert validate_lyrics(messy) == VALID
    # gemma3 처럼 번호를 붙인 태그는 번호를 뗀다
    numbered = VALID.replace("[verse]", "[Verse 1]").replace("[chorus]", "[Chorus 1]")
    assert validate_lyrics(numbered) == VALID
    assert validate_lyrics("```\n" + VALID + "\n```") == VALID  # 전체를 감싼 코드 펜스 한 겹은 벗긴다
    assert validate_lyrics("```text\n" + VALID + "\n```") == VALID
    assert validate_lyrics(VALID.replace("a1", "♪ “바다” 가자~!")) == VALID.replace("a1", "♪ “바다” 가자~!")
    # 낱말 속 "나라", "오우" 같은 글자는 추임새가 아니다
    assert validate_lyrics(VALID.replace("a1", "우리나라 바다로 오우 가요")) is not None


@pytest.mark.parametrize(
    "text",
    [
        "물론이죠! 가사입니다.\n" + VALID,  # 앞 설명
        VALID + "\n즐거운 여행 되세요!",  # 뒤 설명 (마지막 섹션 5줄)
        VALID.replace("[chorus]", "```\n[chorus]", 1),  # 가사 중간의 코드 펜스
        VALID + "\n[bridge]\nd1\nd2\nd3\nd4",  # 시키지 않은 섹션
        VALID.replace("[chorus]", "[bridge]", 1),  # 다른 태그
        VALID.replace("a4\n", ""),  # 3줄 섹션
        VALID.replace("b4", "경포해변 달려가요 랄랄라"),  # 추임새 (가족 목소리가 가사 대신 부르게 된다)
        VALID.replace("a2", "오오 신나는 드라이브"),
        VALID.replace("a3", "la la la 바다로"),
        "\n".join(VALID.splitlines()[:5]),  # 섹션 1개뿐
        VALID + "\n[verse]\nc1\nc2\nc3\nc4\n[chorus]\nb1\nb2\nb3\nb4",  # 예전 네 섹션 (2절은 60~90초 곡에서 불리지 않음)
        VALID.replace("[verse]", "[verse one]", 1),
        VALID.replace("b3", "첫째 ร้อง 크게"),  # 다른 문자 섞임 (gemma3 출력에서 실제로 나옴)
        VALID.replace("b3", "老虎 노래"),
        "",
    ],
)
def test_validate_lyrics_rejects(text):
    assert validate_lyrics(text) is None


def test_fake_llm_output_is_valid_and_uses_trip_data():
    text = run(make_llm_provider(Settings(_env_file=None)).write_lyrics(LYRICS_INPUT))
    lyrics = validate_lyrics(text)
    assert lyrics is not None
    assert "서울 중구" in lyrics and "강릉시" in lyrics and "양평군" in lyrics
    assert "아빠" in lyrics and "맑음" in lyrics


# --- 어댑터 (MockTransport) ---------------------------------------------------


class Recorder:
    def __init__(self, respond):
        self.respond = respond
        self.requests: list = []

    def __call__(self, request):
        self.requests.append(request)
        return self.respond(request)


def test_kakao_request_and_parse():
    body = {
        "meta": {"total_count": 2},
        "documents": [
            {"region_type": "B", "address_name": "경기도 성남시 분당구 삼평동", "region_1depth_name": "경기도",
             "region_2depth_name": "성남시 분당구", "region_3depth_name": "삼평동", "region_4depth_name": "",
             "code": "4113510900", "x": 127.10459896729914, "y": 37.40269721785548},
            {"region_type": "H", "address_name": "경기도 성남시 분당구 삼평동", "region_1depth_name": "경기도",
             "region_2depth_name": "성남시 분당구", "region_3depth_name": "삼평동", "region_4depth_name": "",
             "code": "4113565500", "x": 127.1163593869371, "y": 37.40612091848614},
        ],
    }
    rec = Recorder(lambda r: httpx.Response(200, json=body))
    geocoder = KakaoGeocoder("kk", httpx.AsyncClient(transport=httpx.MockTransport(rec)))

    region = run(geocoder.region(37.4012191, 127.1086228))

    assert region == Region("경기도", "성남시") and region.name == "성남시"
    req = rec.requests[0]
    assert req.url.host == "dapi.kakao.com" and req.url.path == "/v2/local/geo/coord2regioncode.json"
    assert req.url.params["x"] == "127.1086228" and req.url.params["y"] == "37.4012191"
    assert req.headers["Authorization"] == "KakaoAK kk"


def test_kakao_sea_returns_none_and_http_error_raises():
    geocoder = KakaoGeocoder("kk", httpx.AsyncClient(transport=httpx.MockTransport(
        lambda r: httpx.Response(200, json={"meta": {"total_count": 0}, "documents": []}))))
    assert run(geocoder.region(37.0, 130.0)) is None
    geocoder = KakaoGeocoder("bad", httpx.AsyncClient(transport=httpx.MockTransport(
        lambda r: httpx.Response(401, json={"errorType": "AccessDeniedError"}))))
    with pytest.raises(httpx.HTTPStatusError):
        run(geocoder.region(37.0, 127.0))


def nominatim_response(request):
    lat = float(request.url.params["lat"])
    if lat > 40:
        return httpx.Response(200, json={"error": "Unable to geocode"})
    return httpx.Response(200, json={
        "place_id": 1, "name": "강릉시", "addresstype": "city",
        "address": {"city": "강릉시", "province": "강원특별자치도", "country": "대한민국", "country_code": "kr"},
    })


def test_nominatim_policy_params_cache_and_rate_limit():
    rec = Recorder(nominatim_response)
    geocoder = NominatimGeocoder(httpx.AsyncClient(transport=httpx.MockTransport(rec)), "DriveSong-test/1.0")
    geocoder.min_interval_sec = 0.3  # 테스트 시간을 줄이려고 간격만 줄인다 (기본 1.0)

    async def go():
        first = await geocoder.region(37.75191, 128.87612)
        cached = await geocoder.region(37.75189, 128.87608)  # 소수 3자리 반올림이 같으면 캐시
        t0 = time.monotonic()
        sea = await geocoder.region(41.0, 131.0)
        return first, cached, sea, time.monotonic() - t0

    first, cached, sea, waited = run(go())
    assert first == cached == Region("강원특별자치도", "강릉시")
    assert sea is None
    assert len(rec.requests) == 2  # 캐시된 두 번째 호출은 요청하지 않음
    assert waited >= 0.25  # 직전 요청 후 최소 간격을 기다림
    params = rec.requests[0].url.params
    assert params["accept-language"] == "ko" and params["format"] == "jsonv2"
    assert params["lat"] == "37.752" and params["lon"] == "128.876"
    assert rec.requests[0].headers["User-Agent"] == "DriveSong-test/1.0"


def test_nominatim_default_interval_is_one_second():
    assert NominatimGeocoder.min_interval_sec >= 1.0


def test_open_meteo_request_and_parse():
    rec = Recorder(lambda r: httpx.Response(200, json={
        "latitude": 37.75, "longitude": 128.875, "utc_offset_seconds": 0, "timezone": "GMT",
        "hourly_units": {"time": "iso8601", "temperature_2m": "°C", "weather_code": "wmo code"},
        "hourly": {"time": ["2026-11-01T04:00"], "temperature_2m": [11.4], "weather_code": [61]},
    }))
    weather = OpenMeteoWeather(httpx.AsyncClient(transport=httpx.MockTransport(rec)))

    # 13:31 KST = 04:31 UTC -> 가장 가까운 정시 05:00 UTC
    kst = timezone(timedelta(hours=9))
    result = run(weather.at(37.7519, 128.8761, datetime(2026, 11, 1, 13, 31, tzinfo=kst)))

    assert result == Weather("약한 비", 11.4)
    params = rec.requests[0].url.params
    assert rec.requests[0].url.host == "api.open-meteo.com" and rec.requests[0].url.path == "/v1/forecast"
    assert params["hourly"] == "temperature_2m,weather_code"
    assert params["start_hour"] == params["end_hour"] == "2026-11-01T05:00"
    assert params["timezone"] == "GMT"


def test_open_meteo_out_of_range_raises():
    weather = OpenMeteoWeather(httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(
        400, json={"reason": "Parameter 'start_hour' is out of allowed range", "error": True}))))
    with pytest.raises(httpx.HTTPStatusError):
        run(weather.at(37.0, 127.0, datetime(2020, 1, 1, tzinfo=timezone.utc)))


def claude_message(text: str | None, stop_reason: str = "end_turn", stop_details=None) -> dict:
    content = [{"type": "thinking", "thinking": "", "signature": "sig"}]
    if text is not None:
        content.append({"type": "text", "text": text})
    return {
        "id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
        "content": content, "stop_reason": stop_reason, "stop_sequence": None, "stop_details": stop_details,
        "usage": {"input_tokens": 300, "output_tokens": 120},
    }


def claude_with(respond) -> tuple[ClaudeLlm, Recorder]:
    rec = Recorder(respond)
    http_client = anthropic.DefaultAsyncHttpxClient(transport=httpx2.MockTransport(rec))
    return ClaudeLlm("sk-test", "claude-opus-5-5", http_client=http_client), rec


def test_claude_request_shape_and_text():
    llm, rec = claude_with(lambda r: httpx2.Response(200, json=claude_message(VALID)))

    assert run(llm.write_lyrics(LYRICS_INPUT)) == VALID
    req = rec.requests[0]
    assert req.method == "POST" and str(req.url) == "https://api.anthropic.com/v1/messages"
    assert req.headers["authorization"] == "Bearer sk-test"  # anthropic SDK 2.x 는 키를 Bearer 로 보낸다
    assert FALLBACK_BETA in req.headers["anthropic-beta"]
    body = json.loads(req.content)
    assert body["model"] == "claude-opus-5-5"
    assert body["fallbacks"] == "default"
    assert body["output_config"] == {"effort": "medium"}
    assert "thinking" not in body and "temperature" not in body
    assert body["messages"] == [{"role": "user", "content": build_prompt(LYRICS_INPUT)}]


def test_claude_refusal_and_errors_raise_llm_error():
    llm, _ = claude_with(lambda r: httpx2.Response(200, json=claude_message(
        None, "refusal", {"type": "refusal", "category": None, "explanation": None})))
    with pytest.raises(LlmError, match="declined"):
        run(llm.write_lyrics(LYRICS_INPUT))

    llm, rec = claude_with(lambda r: httpx2.Response(
        401, json={"type": "error", "error": {"type": "authentication_error", "message": "invalid x-api-key"}}))
    with pytest.raises(LlmError, match="API error"):
        run(llm.write_lyrics(LYRICS_INPUT))
    assert len(rec.requests) == 1  # 401 은 SDK 가 재시도하지 않음


# --- 공급자 선택 ------------------------------------------------------------------


def test_provider_selection():
    client = httpx.AsyncClient()
    geocoder, weather = make_geo_providers(Settings(_env_file=None), client)
    assert isinstance(geocoder, FakeGeocoder) and isinstance(weather, FakeWeather)
    geocoder, weather = make_geo_providers(Settings(_env_file=None, geo_provider="live"), client)
    assert isinstance(geocoder, NominatimGeocoder) and isinstance(weather, OpenMeteoWeather)
    geocoder, _ = make_geo_providers(
        Settings(_env_file=None, geo_provider="live", kakao_rest_api_key="kk"), client)
    assert isinstance(geocoder, KakaoGeocoder)
    with pytest.raises(ValueError):
        make_geo_providers(Settings(_env_file=None, geo_provider="google"), client)

    assert make_llm_provider(Settings(_env_file=None)).name == "fake"
    with pytest.raises(ValueError, match="ANTHROPIC_API_KEY"):
        make_llm_provider(Settings(_env_file=None, llm_provider="claude", anthropic_api_key=None))
    claude = make_llm_provider(Settings(_env_file=None, llm_provider="claude", anthropic_api_key="k",
                                        claude_model="claude-sonnet-5-5"))
    assert isinstance(claude, ClaudeLlm) and claude.model == "claude-sonnet-5-5"
    with pytest.raises(ValueError):
        make_llm_provider(Settings(_env_file=None, llm_provider="gpt"))


# --- API (fake 공급자) ------------------------------------------------------------


def test_enrich_and_lyrics_seoul_to_gangneung(client):
    """T5 완료 기준: 샘플 TripSummary -> 지명·날씨가 붙은 EnrichedTrip -> 가사 (fake LLM)."""
    headers = register(client)

    res = client.post("/v1/trips/enrich", json=sample_trip(), headers=headers)
    assert res.status_code == 200, res.text
    trip = res.json()
    assert trip["originName"] == "서울 중구"
    assert trip["destinationName"] == "강릉시"
    assert 3 <= len(trip["placeNames"]) <= 5
    assert trip["placeNames"][0] == "서울 중구" and trip["placeNames"][-1] == "강릉시"
    assert len(set(trip["placeNames"])) == len(trip["placeNames"])
    assert trip["weather"] == {"description": "맑음", "temperatureC": 15.0}
    # 요약 필드는 그대로 돌려준다
    assert trip["startedAt"] == "2026-11-01T00:30:00Z" and trip["timeOfDay"] == "MORNING"
    assert trip["passengers"] == ["mem_1", "mem_2"]

    res = client.post(
        "/v1/lyrics",
        json={"enrichedTrip": trip, "familyNicknames": ["아빠", "콩이"], "style": "city_pop"},
        headers=headers,
    )
    assert res.status_code == 200, res.text
    out = res.json()
    assert validate_lyrics(out["lyrics"]) == out["lyrics"]
    assert "아빠" in out["lyrics"] and "강릉시" in out["lyrics"]
    assert out["title"] == "강릉시 가는 길"


def test_enrich_keeps_provided_destination_name(client):
    headers = register(client)
    res = client.post("/v1/trips/enrich", json=sample_trip(destinationName="강릉 중앙시장"), headers=headers)
    assert res.json()["destinationName"] == "강릉 중앙시장"


def test_enrich_without_destination_uses_last_waypoint(client):
    headers = register(client)
    res = client.post(
        "/v1/trips/enrich",
        json=sample_trip(destination=None, endedAt=None, etaAt="2026-11-01T03:30:00Z"),
        headers=headers,
    )
    assert res.status_code == 200
    assert res.json()["destinationName"] == "강릉시"


def test_short_trip_has_fewer_place_names(client):
    headers = register(client)
    res = client.post(
        "/v1/trips/enrich",
        json=sample_trip(destination={"lat": 37.5651, "lng": 126.9895}, waypoints=[], distanceKm=1.2),
        headers=headers,
    )
    assert res.json()["placeNames"] == ["서울 중구"]


class FailingGeo:
    name = "failing"

    async def region(self, lat, lng):
        raise httpx.ConnectError("down")

    async def at(self, lat, lng, when):
        raise httpx.ConnectError("down")


def test_enrich_survives_provider_failures(client):
    headers = register(client)
    client.app.state.geocoder = client.app.state.weather = FailingGeo()
    res = client.post("/v1/trips/enrich", json=sample_trip(), headers=headers)
    assert res.status_code == 200
    trip = res.json()
    assert trip["originName"] is None and trip["destinationName"] is None
    assert trip["placeNames"] == [] and trip["weather"] is None


def test_enrich_validation_and_auth(client):
    assert client.post("/v1/trips/enrich", json=sample_trip()).status_code == 401
    headers = register(client)
    too_many = [{"lat": 37.5, "lng": 127.0}] * 51
    assert client.post("/v1/trips/enrich", json=sample_trip(waypoints=too_many), headers=headers).status_code == 422
    naive = sample_trip(startedAt="2026-11-01T09:30:00")
    assert client.post("/v1/trips/enrich", json=naive, headers=headers).status_code == 422
    assert client.post(
        "/v1/trips/enrich", json=sample_trip(timeOfDay="LUNCH"), headers=headers).status_code == 422
    offset = sample_trip(startedAt="2026-11-01T09:30:00+09:00")
    assert client.post("/v1/trips/enrich", json=offset, headers=headers).json()["startedAt"] == "2026-11-01T09:30:00+09:00"


class ScriptedLlm:
    name = "scripted"

    def __init__(self, *outputs):
        self.outputs = list(outputs)
        self.calls = 0

    async def write_lyrics(self, inp):
        self.calls += 1
        out = self.outputs.pop(0)
        if isinstance(out, Exception):
            raise out
        return out


def enriched_json() -> dict:
    trip = run(enrich_trip(TripSummary.model_validate(sample_trip()), FakeGeocoder(), FakeWeather()))
    return trip.model_dump(by_alias=True, mode="json")


@pytest.mark.parametrize(
    "outputs, status, calls",
    [
        (["설명입니다\n" + VALID, VALID], 200, 2),  # 첫 출력이 잘못되면 한 번 더
        ([LlmError("declined"), VALID], 200, 2),
        (["잘못된 출력", "또 잘못된 출력"], 502, 2),  # 재시도는 한 번만
        ([VALID], 200, 1),
    ],
)
def test_lyrics_retry_once(client, outputs, status, calls):
    headers = register(client)
    llm = ScriptedLlm(*outputs)
    client.app.state.llm = llm
    res = client.post(
        "/v1/lyrics",
        json={"enrichedTrip": enriched_json(), "familyNicknames": ["엄마"], "style": "kids"},
        headers=headers,
    )
    assert res.status_code == status, res.text
    assert llm.calls == calls
    if status == 200:
        assert res.json()["lyrics"] == VALID


def test_lyrics_request_validation(client):
    headers = register(client)
    body = {"enrichedTrip": enriched_json(), "familyNicknames": [], "style": "jazz"}
    assert client.post("/v1/lyrics", json=body, headers=headers).status_code == 422
    body["style"] = "acoustic"
    res = client.post("/v1/lyrics", json=body, headers=headers)  # 가족 없이도 생성
    assert res.status_code == 200 and "우리 가족" in res.json()["lyrics"]


def test_lyrics_through_mocked_claude(data_dir):
    """llm_provider=claude 로 서버를 띄우고 Messages API 만 MockTransport 로 대신한다."""
    app = create_app(Settings(_env_file=None, data_dir=data_dir, llm_provider="claude", anthropic_api_key="k"))
    llm, rec = claude_with(lambda r: httpx2.Response(200, json=claude_message("[Verse]\n" + VALID[8:])))
    app.state.llm = llm
    with TestClient(app) as c:
        headers = register(c)
        res = c.post(
            "/v1/lyrics",
            json={"enrichedTrip": enriched_json(), "familyNicknames": ["아빠"], "style": "road_trip"},
            headers=headers,
        )
    assert res.status_code == 200, res.text
    assert res.json()["lyrics"] == VALID  # 태그는 소문자로 정리
    prompt = json.loads(rec.requests[0].content)["messages"][0]["content"]
    assert "한국어 신나는 로드트립 스타일" in prompt and "강릉시" in prompt and "아빠" in prompt


# --- 시간 상한 (앱 read 타임아웃 5분보다 먼저 응답) -------------------------------------


class HangingLlm:
    """첫 호출은 잘못된 출력을 바로 돌려주고, 재시도는 끝나지 않는다."""

    name = "hanging"

    def __init__(self):
        self.calls = 0
        self.cancelled = False

    async def write_lyrics(self, inp):
        self.calls += 1
        if self.calls == 1:
            return "잘못된 출력"
        try:
            await asyncio.sleep(60)
        except asyncio.CancelledError:
            self.cancelled = True
            raise
        return VALID


def test_lyrics_deadline_covers_retries_and_returns_504(client, monkeypatch):
    monkeypatch.setattr(lyrics_api, "DEADLINE_SEC", 0.3)
    headers = register(client)
    llm = HangingLlm()
    client.app.state.llm = llm
    t0 = time.monotonic()
    res = client.post(
        "/v1/lyrics",
        json={"enrichedTrip": enriched_json(), "familyNicknames": [], "style": "kids"},
        headers=headers,
    )
    assert time.monotonic() - t0 < 5
    assert res.status_code == 504
    assert res.json() == {"detail": "lyrics generation timed out"}
    assert llm.calls == 2 and llm.cancelled  # 진행 중인 재시도 호출은 취소된다


def test_lyrics_deadline_is_well_below_app_read_timeout():
    assert lyrics_api.DEADLINE_SEC <= 200  # 앱 read 타임아웃 300초
    assert trips_api.GEOCODE_DEADLINE_SEC + trips_api.WEATHER_DEADLINE_SEC <= 60


class HangingWeather:
    name = "hanging"

    async def at(self, lat, lng, when):
        await asyncio.sleep(60)


def test_enrich_bounded_when_nominatim_queue_and_weather_are_slow(monkeypatch):
    """Nominatim 은 서버 전체에서 초당 1건으로 줄을 선다. 상한 안에 끝난 지점만 쓰고 200 으로 응답한다."""
    monkeypatch.setattr(trips_api, "GEOCODE_DEADLINE_SEC", 0.5)
    monkeypatch.setattr(trips_api, "WEATHER_DEADLINE_SEC", 0.2)
    rec = Recorder(nominatim_response)
    geocoder = NominatimGeocoder(httpx.AsyncClient(transport=httpx.MockTransport(rec)), "DriveSong-test/1.0")
    geocoder.min_interval_sec = 0.2  # 8개 지점이면 1.4초 이상 걸린다
    summary = TripSummary.model_validate(sample_trip())

    async def go():
        t0 = time.monotonic()
        slow = await enrich_trip(summary, geocoder, HangingWeather())
        elapsed, requested = time.monotonic() - t0, len(rec.requests)
        # 취소된 지점이 Nominatim 잠금을 쥐고 있지 않아야 다음 요청이 정상 동작한다
        geocoder.min_interval_sec = 0
        monkeypatch.setattr(trips_api, "GEOCODE_DEADLINE_SEC", 5.0)
        again = await enrich_trip(summary, geocoder, FakeWeather())
        return slow, elapsed, requested, again

    slow, elapsed, requested, again = run(go())
    assert elapsed < 1.5
    assert 1 <= requested < 8  # 나머지 지점은 Nominatim 에 요청하지 않고 취소됨
    assert slow.origin_name == "강릉시"  # 첫 지점은 상한 안에 끝남 (mock 은 모두 강릉시)
    assert slow.destination_name is None  # 마지막 지점은 대기열에서 취소됨
    assert slow.weather is None
    assert again.destination_name == "강릉시" and again.weather is not None


# --- live (기본 건너뜀): python -m pytest -m live -s ------------------------------


@pytest.mark.live
def test_live_open_meteo_and_nominatim():
    async def go() -> EnrichedTrip:
        now = datetime.now(timezone.utc)
        summary = TripSummary.model_validate(sample_trip(
            startedAt=(now - timedelta(hours=3)).isoformat(), endedAt=now.isoformat()))
        async with httpx.AsyncClient(timeout=20) as http:
            settings = Settings(_env_file=None, geo_provider="live")
            geocoder, weather = make_geo_providers(settings, http)
            assert isinstance(geocoder, NominatimGeocoder)
            t0 = time.monotonic()
            trip = await enrich_trip(summary, geocoder, weather)
            print(f"\nenrich took {time.monotonic() - t0:.1f}s")
            return trip

    trip = run(go())
    print(json.dumps(trip.model_dump(by_alias=True, mode="json", exclude={"waypoints"}), ensure_ascii=False, indent=1))
    inp = LyricsInput(trip.origin_name, trip.destination_name, trip.place_names, trip.time_of_day.value,
                      Weather(trip.weather.description, trip.weather.temperature_c) if trip.weather else None,
                      trip.duration_min, trip.distance_km, Style.ROAD_TRIP, ["아빠", "콩이"])
    print(run(make_llm_provider(Settings(_env_file=None)).write_lyrics(inp)))
    assert trip.origin_name and trip.destination_name
    assert 3 <= len(trip.place_names) <= 5
    assert trip.weather is not None
