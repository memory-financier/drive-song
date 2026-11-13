from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """환경 변수 (접두어 VOICELAB_). 설계 §7.5."""

    model_config = SettingsConfigDict(env_prefix="VOICELAB_", env_file=".env", extra="ignore")

    device: str = "cuda"
    # 받아 적기 (Whisper, transformers)
    asr_model: str = "openai/whisper-large-v3-turbo"
    # 화자 임베딩 (SpeechBrain ECAPA). Windows 는 심볼릭 링크 대신 복사해 둔다
    speaker_model: str = "speechbrain/spkrec-ecapa-voxceleb"
    models_dir: Path = Path(".models")
    # 등록 클립과의 코사인 유사도가 이보다 낮은 발화는 '모르는 사람' [확인 필요] 실제 가족 대화로 조정
    match_threshold: float = 0.45
    # Seed-VC (GPL-3.0): 우리 코드에서 import 하지 않고 별도 설치본의 CLI 만 부른다
    seedvc_dir: Path = Path.home() / "ai" / "seed-vc"
    seedvc_python: Path = Path.home() / "ai" / "seed-vc" / ".venv" / "Scripts" / "python.exe"
    # 확산 단계. 30 단계는 반주가 단출한 곡(어쿠스틱)에서 기계음이 들렸다 → 60 (2026-11-13 듣기 비교, 변환 28초 → 40초/명).
    # 계산 정밀도(fp16 → fp32)는 같은 난수에서 결과 차이가 0.14 dB 뿐이고 시간만 두 배라 fp16 그대로
    seedvc_steps: int = 60
