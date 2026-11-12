from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # 클립 파일과 기본 SQLite DB가 저장되는 디렉터리
    data_dir: Path = Path("data")
    # 비워두면 data_dir/drive_song.db (SQLite)
    database_url: str | None = None

    # 음악 생성 공급자 (설계 §7.3): fake | minimax | mureka | acestep | acestep_local
    # T1 벤치마크 결과가 나오기 전까지 기본값은 fake
    music_provider: str = "fake"
    fal_key: str | None = None  # minimax, acestep (fal.ai)
    mureka_api_key: str | None = None
    # 로컬 ACE-Step 1.5 API 서버 (acestep_local, 키 없음). thinking 을 켜면 곡 구성 계획으로 느려진다 (약 11초 → 24초)
    acestep_local_url: str = "http://127.0.0.1:8001"
    acestep_local_thinking: bool = False
    acestep_local_steps: int = 8
    # 공급자 상태 폴링 간격과, 제출 시점부터의 생성 제한 시간 (앱 쪽 제한 5분, §7.1·§9)
    job_poll_interval_sec: float = 5.0
    job_timeout_sec: float = 300.0
    # fake 공급자가 pending 을 반환하는 시간 (상태 전이 관찰용)
    fake_music_delay_sec: float = 3.0

    # 가사 LLM (설계 §7.2, §7.5): fake | claude | ollama. 대화는 외부 LLM(claude)에 보내지 않는다 (§8.3)
    llm_provider: str = "fake"
    anthropic_api_key: str | None = None
    claude_model: str = "claude-opus-5-5"
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_model: str = "gemma3:12b"
    # 대화 분석·목소리 변환 (설계 §7.5): fake | voicelab (같은 PC 의 voice-lab 서비스, 파일 경로로 주고받음)
    voice_provider: str = "fake"
    voicelab_url: str = "http://127.0.0.1:8002"
    # 지명·날씨 (설계 §6.1): fake | live
    # live: 역지오코딩은 KAKAO_REST_API_KEY 가 있으면 Kakao, 없으면 OSM Nominatim. 날씨는 Open-Meteo.
    geo_provider: str = "fake"
    kakao_rest_api_key: str | None = None
    # Nominatim 사용 정책: 앱을 식별할 수 있는 User-Agent (운영 시 연락처 포함 권장)
    nominatim_user_agent: str = "DriveSong-server/0.1 (Pleos Playground contest app)"

    @property
    def resolved_database_url(self) -> str:
        return self.database_url or f"sqlite:///{(self.data_dir / 'drive_song.db').as_posix()}"
