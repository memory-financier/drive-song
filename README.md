# 오늘의 드라이브 송 (Drive Song)

차 안에서 가족이 나눈 대화가 **가족의 목소리로 부르는 노래**가 되어, 목적지에 도착하는 순간(기어 P) 흘러나오는 Pleos Connect 차량 앱.
현대자동차그룹 차량용 앱 개발 공모전(Pleos Playground) 출품작 · 팀 Memory Financier.

- 시연 영상: https://youtu.be/ISl_q5XDCFs (2분 28초) · 숏폼: https://youtube.com/shorts/zdrhlAbZZnA (58초)
- release APK: `apk/` (Pleos Connect 에뮬레이터용)
- 설계 문서: [`docs/design.md`](docs/design.md) · 기획서: [`docs/proposal/proposal.md`](docs/proposal/proposal.md)

## 어떻게 동작하나

```
차량 앱 (Android Automotive, Pleos Connect)        자체 서버 (FastAPI)              자체 GPU AI 서비스
 대화 담기: 5분 조각 녹음·업로드  ───────────────▶  대화 조각 임시 보관
 주행 기록: 기어·위치·도착 예정 시각
 도착 10분 전: 노래 만들기 요청    ───────────────▶  잡: 분석 → 가사 → 작곡   ─────▶  받아 적기·화자 구분 (Whisper, ECAPA)
                                                  → 목소리 변환 → 믹싱           가사 (Gemma 3 12B), 작곡 (ACE-Step 1.5)
 기어 P (도착): 자동 재생·실내 조명  ◀─────────────  완성된 노래                     보컬 분리 (Demucs), 목소리 변환 (Seed-VC)
```

- 차량 앱은 녹음·주행 기록·재생·화면만 맡고, 무거운 AI 처리는 모두 서버에서 한다. API 키는 앱에 넣지 않는다.
- 원본 대화는 분석 직후 지우고, 받아 적은 글은 저장하지 않는다. 가족 목소리·대화를 외부 AI 서비스로 보내지 않는다.
- Pleos SDK: Gleo Interface(차량 AI 비서 명령), SpeechToText("말로 하기"), Vehicle Light(도착 조명). 자세한 것은 기획서 5절.

## 폴더

| 폴더 | 내용 |
|---|---|
| `android/` | 차량 앱 (Kotlin, Jetpack Compose, AAOS). 모듈: `app`, `core:*`, `feature:*` |
| `server/` | API 서버 (Python FastAPI): 기기·가족·동의, 대화 조각, 노래 잡, 가사, 믹서 |
| `voice-lab/` | GPU AI 서비스: 받아 적기·화자 구분·한마디 고르기·보컬 분리·목소리 변환 |
| `tools/` | 공급자 벤치마크, 데모 대화 만들기, 홍보 영상 도구 |
| `docs/` | 설계, 기획서, 시험 기록, 데모 절차 |

## 빠르게 체험하기 (GPU·API 키 없이)

서버의 기본값은 키 없이 동작하는 fake 공급자다. **진짜 노래 대신 화음 반주와 데모 대화 일부가 나오지만**, 가족 등록부터 대화 담기, 도착 순간 자동 재생까지 앱의 흐름은 그대로 볼 수 있다. 실제 노래는 [GPU 구성](#실제-gpu-구성)에서 나온다.

**1. 서버** (Python 3.12 이상, `ffmpeg`·`ffprobe`가 PATH에 있어야 함)
```bash
cd server
pip install -e .
uvicorn app.main:app --host 127.0.0.1 --port 8000
```
`http://127.0.0.1:8000/docs`가 열리면 준비된 것이다.

**2. 앱** (같은 PC에서 Pleos Connect 에뮬레이터 실행)

개발·시험 환경: **Pleos Connect 에뮬레이터 2.1.0** (AVD `Pleos_Connect_v2.1.0`, Android 14 · API 34, Android Automotive, x86_64), 화면 규격 **2560×1440, 160dpi, 가로**. 앱은 분할 화면의 앱 영역(화면 오른쪽 약 2/3, 약 1672×1203)에 표시된다. 앱 패키지 `com.drivesong`, 에뮬레이터 사용자 10. 그 밖에 필요한 설정은 없다 (권한은 앱이 처음 쓸 때 묻는다).
```bash
adb install apk/MemoryFinancier_DriveSong_release.apk
```
앱은 `http://10.0.2.2:8000/`(에뮬레이터에서 본 이 PC)의 서버에 접속한다.

**3. 데모 주행** (서울 → 강릉 3시간을 약 9분으로 재생, 합성 음성 데모 가족)
1. **설정** → **데모 모드** 켜기 → **데모 가족 (합성 음성)**의 **불러오기**: 엄마·아빠·첫째가 등록되고 탑승자로 골라진다.
2. **탑승자** → **대화 담기 (이번 주행)** 켜기 (마이크 권한 허용). 에뮬레이터 마이크 대신 앱에 넣은 데모 대화를 쓴다.
3. **홈** → **주행 기록 시작** (위치·알림 권한 허용).
4. **설정** → **서울 → 강릉 (약 3시간)** 재생 → **홈**. 도착 10분 전에 노래 만들기가 시작되고, 도착(P단) 순간 자동 재생된다.

자세한 절차와 확인 방법: [`docs/demo-scenario.md`](docs/demo-scenario.md) 7절.
- 설치 직후 첫 실행에서 앱 화면이 비어 있으면 앱을 한 번 닫았다 다시 연다 (Pleos 에뮬레이터 2.1.0에서 한 번 겪음, `adb shell am force-stop --user 10 com.drivesong`).

## 실제 GPU 구성

이 저장소의 측정은 NVIDIA RTX 5070 Ti 16GB 한 장 기준이다 (대화 86초·3명, 후보 3곡 → 완성 약 3.5분).

1. **ACE-Step 1.5** (작곡, 8001): [ace-step/ACE-Step-1.5](https://github.com/ace-step/ACE-Step-1.5) 설치 후 `acestep-api --host 127.0.0.1 --port 8001`
2. **Ollama** (가사, 11434): `ollama pull gemma3:12b`
3. **voice-lab** (대화 분석·목소리 변환, 8002): [`voice-lab/README.md`](voice-lab/README.md). Seed-VC는 따로 설치하고 별도 프로세스로만 부른다 (GPL-3.0, 이 저장소에 포함하지 않음).
4. 서버를 위 세 서비스로:
   ```bash
   cd server
   MUSIC_PROVIDER=acestep_local LLM_PROVIDER=ollama VOICE_PROVIDER=voicelab uvicorn app.main:app --port 8000
   ```

설정 항목은 `server/.env.example`, 서버 API는 [`server/README.md`](server/README.md).

## 소스에서 빌드·테스트

```bash
# 서버 (165건)
cd server && pip install -e ".[dev]" && python -m pytest

# voice-lab, GPU 없이 도는 시험 (20건)
cd voice-lab && uv sync --extra dev && .venv/Scripts/python.exe -m pytest -q

# 앱 (JDK 17 필요, ANDROID_HOME 설정)
cd android
./gradlew assembleDebug    # 서버 주소 바꾸기: -PdriveSongApiBaseUrl=http://<PC IP>:8000/
./gradlew test
```

## 개인정보

- 동의 3종(목소리 클립·대화 녹음·노래 목소리 변환), 14세 미만은 보호자 동의. 대화 담기는 이번 주행만, 탑승자 전원이 동의했을 때만 켜진다.
- 처리방침·위치정보 이용약관: `server/static/legal/`.

## 출처·라이선스

| 구성 요소 | 라이선스 | 비고 |
|---|---|---|
| Whisper large-v3-turbo | MIT | 받아 적기 |
| ECAPA 화자 임베딩 (SpeechBrain) | Apache 2.0 | 화자 구분 |
| Gemma 3 12B | Gemma 이용약관 | 가사 |
| ACE-Step 1.5 | MIT | 작곡·노래 |
| Demucs | MIT | 보컬 분리 |
| Seed-VC | GPL-3.0 | 목소리 변환. 이 저장소에 포함하지 않고 별도 프로세스로 실행 |

**데모 음성**: 앱의 데모 가족·데모 대화(`android/feature/*/src/main/assets/demo/`)는 가상의 가족 목소리(합성 음성)다. [Zeroth-Korean](https://github.com/goodatlas/zeroth) (CC BY 4.0, Lucas Jo·Wonkyum Lee) 화자 3명의 발화를 목소리 샘플로 삼아 CosyVoice 3 (Apache-2.0)로 가족 대화 대본을 합성했다 (첫째는 샘플의 음높이·음색을 올림). 자세한 것은 `android/feature/trip/src/main/assets/demo/NOTICE.txt`.
