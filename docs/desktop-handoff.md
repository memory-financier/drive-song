# 데스크탑 이전 메모 (2026-11-03)

노트북(RTX 3050 Ti 4GB)에서 데스크탑(RTX 5070 Ti 16GB)으로 작업 환경을 옮김. 목적: 유료 API 없이 로컬 모델로 실제 노래·가사 생성.

## 결정 사항
- 현재 서버 기본값은 전부 `fake` 공급자 (키 없이 동작). 유료 키(fal, Mureka, Anthropic)는 쓰지 않기로 함.
- 음악: **ACE-Step 로컬 실행** (오픈소스, Apache 2.0)을 시험. 한국어 노래 품질이 최대 불확실성 [확인 필요].
- 가사: 로컬 LLM(Ollama, 한국어 가능 모델)과 Gemini 무료 등급 중 실측 후 선택. Gemini 무료 등급은 입력이 학습에 쓰일 수 있어 데모 데이터로만 사용.
- 지명·날씨: 기존 무키 경로(`GEO_PROVIDER=live`, Nominatim + Open-Meteo) 사용.

## 다음 작업 순서

> 2026-11-03: 개발 환경 설치·테스트 재확인 완료. 진행 목록은 `docs/goals.md` 4번으로 옮겼다.

1. ACE-Step 설치 → 한국어 샘플 3곡 생성, 품질·생성 시간 측정 → 사용 여부 판단.
   - RTX 50 시리즈(Blackwell)는 CUDA 12.8 이상 PyTorch 빌드(cu128) 필요.
2. 쓸 만하면 서버에 `MUSIC_PROVIDER=acestep_local` 어댑터 추가 + 테스트 (앱 코드 변경 없음).
3. 가사: Ollama 로컬 모델 vs Gemini 무료 등급 비교 → 어댑터 추가.
   - VRAM을 음악 모델과 나눠 쓰므로 순차 실행 또는 가사만 외부로.
4. 서울→강릉 데모 E2E 재검증 (`docs/demo-scenario.md`), `docs/design.md` 갱신.
