import httpx

from app.providers.geo.reverse_geocode import FakeGeocoder, KakaoGeocoder, NominatimGeocoder, ReverseGeocoder
from app.providers.geo.weather import FakeWeather, OpenMeteoWeather, WeatherProvider
from app.settings import Settings

GEO_PROVIDER_NAMES = ("fake", "live")


def make_geo_providers(
    settings: Settings, client: httpx.AsyncClient
) -> tuple[ReverseGeocoder, WeatherProvider]:
    """settings.geo_provider: fake | live.
    live: 역지오코딩은 KAKAO_REST_API_KEY 가 있으면 Kakao, 없으면 Nominatim. 날씨는 Open-Meteo."""
    if settings.geo_provider == "fake":
        return FakeGeocoder(), FakeWeather()
    if settings.geo_provider == "live":
        geocoder: ReverseGeocoder = (
            KakaoGeocoder(settings.kakao_rest_api_key, client)
            if settings.kakao_rest_api_key
            else NominatimGeocoder(client, settings.nominatim_user_agent)
        )
        return geocoder, OpenMeteoWeather(client)
    raise ValueError(
        f"unknown geo provider '{settings.geo_provider}' (choose one of {', '.join(GEO_PROVIDER_NAMES)})"
    )
