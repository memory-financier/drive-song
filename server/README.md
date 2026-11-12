# Drive Song 서버

FastAPI + SQLite + ffmpeg. 설계: `../docs/design.md` §4.2, §7.3, §8, §9.

## 로컬 실행

필요: Python 3.12+, `ffmpeg`/`ffprobe` (PATH).

```bash
cd server
pip install -e ".[dev]"
cp .env.example .env          # 선택
uvicorn app.main:app --reload --port 8000
```

- 데이터(클립 파일, SQLite)는 `DATA_DIR`(기본 `./data`)에 저장된다.
- API 문서: http://localhost:8000/docs
- 약관: http://localhost:8000/legal/privacy, http://localhost:8000/legal/location

## Docker

```bash
cd server
docker compose up --build
```

데이터는 `drive-song-data` 볼륨(`/data`)에 남는다. `.env`가 있으면 읽는다.

## 테스트

```bash
cd server
python -m pytest
```

테스트마다 임시 `DATA_DIR`과 SQLite 파일을 쓴다. 테스트용 음성은 ffmpeg로 만든다.

## API 사용 예 (curl)

```bash
TOKEN=$(curl -s -X POST localhost:8000/v1/devices | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -s -X POST localhost:8000/v1/members -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nickname":"아빠","isChild":false,"voiceConsent":true,"guardianConsent":false}'
curl -s -X POST localhost:8000/v1/clips -H "Authorization: Bearer $TOKEN" \
  -F memberId=<memberId> -F slot=INTRO -F audio=@hello.m4a
curl -s -X DELETE localhost:8000/v1/clips/<clipId> -H "Authorization: Bearer $TOKEN"
curl -s -X DELETE localhost:8000/v1/members/<memberId> -H "Authorization: Bearer $TOKEN"
```

| 응답 | 의미 |
|---|---|
| 401 | 토큰 없음/잘못됨 |
| 400 | 동의 누락(음성 수집 동의, 아동은 보호자 동의), 읽을 수 없는 오디오, 5초 초과 |
| 404 | 없는 리소스 또는 다른 디바이스의 리소스 |
| 409 | 구성원·슬롯별 클립 3개 초과 |
| 413 | 클립 파일 2MB 초과 |
| 422 | 요청 형식 오류(필드 누락, 잘못된 slot·style, durationSec 60~90 밖, 같은 slot 중복 등) |

## 노래 생성 잡

```bash
curl -s -X POST localhost:8000/v1/song-jobs -H "Authorization: Bearer $TOKEN"   -H "Content-Type: application/json"   -d '{"tripId":"trip_1","lyrics":"[verse]
...
[chorus]
...","style":"city_pop","durationSec":75,
       "clipPlan":[{"slot":"INTRO","clipId":"<clipId>"}],"title":"강릉 가는 길"}'
curl -s localhost:8000/v1/song-jobs/<jobId> -H "Authorization: Bearer $TOKEN"
curl -s -o song.m4a localhost:8000/v1/songs/<songId>/audio -H "Authorization: Bearer $TOKEN"
curl -s -X DELETE localhost:8000/v1/songs/<songId> -H "Authorization: Bearer $TOKEN"   # 노래 1곡 + 그 잡(가사)
curl -s -X DELETE localhost:8000/v1/songs -H "Authorization: Bearer $TOKEN"            # 이 디바이스의 모든 노래·잡 (데이터 삭제)
```

- `style`: `kids`(동요) | `city_pop`(시티팝) | `acoustic`(어쿠스틱) | `road_trip`(신나는 로드트립)
- `title`은 선택 (없으면 "오늘의 드라이브 송"). `clipPlan`의 클립은 이 디바이스 것이어야 한다 (아니면 404).
- 상태: `QUEUED -> GENERATING -> MIXING -> READY | FAILED`. 실패 이유는 `error`.
- 곡 파일은 AAC m4a, `Range` 요청(206)을 지원한다.
- 삭제는 둘 다 204. 진행 중인 잡도 지워지고 워커는 노래를 만들지 않고 멈춘다. 다른 디바이스의 노래는 404.
- 믹싱 (`app/mixer/mix.py`, §7.4): 클립은 게이트·앞뒤 무음 제거 후 -16 LUFS. INTRO는 곡 앞(곡 시작을 최대 2초 늦춤),
  CALL은 첫 후렴 끝(공급자 타이밍이 없으면 곡 40% 지점), OUTRO는 곡 끝 직후. 클립이 음악과 겹치면 음악 -8dB(페이드 150ms).
  마스터는 -14 LUFS, AAC 192kbps. 클립이 없으면 곡만 마스터링한다.
- 공급자는 `MUSIC_PROVIDER`로 고른다. 기본 `fake`는 ffmpeg로 만든 화음 진행 mp3를 돌려주므로 키 없이 전체 흐름이 동작한다.
  `minimax`/`acestep`은 `FAL_KEY`, `mureka`는 `MUREKA_API_KEY`가 필요하고, 없으면 서버가 시작하지 않는다.
- 서버 재시작 시 끝나지 않은 잡(QUEUED/GENERATING/MIXING)은 자동으로 이어서 처리한다.
  공급자에 이미 제출한 잡은 다시 제출하지 않고 저장된 공급자 잡 id로 폴링을 이어간다.
  제한 시간(`JOB_TIMEOUT_SEC`)은 처음 제출한 시각 기준이다.
- 마이그레이션 도구는 없다. 서버가 시작할 때 모델에 새로 생긴 열만 기존 DB에 `ALTER TABLE ADD COLUMN`으로 넣는다
  (`app/db.py` `add_missing_columns`). 열 이름·형식을 바꾸거나 지우는 변경은 처리하지 않는다.

## 지명·날씨, 가사 (fallback)

```bash
curl -s -X POST localhost:8000/v1/trips/enrich -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d @trip.json          # TripSummary (설계 §6.3) -> EnrichedTrip
curl -s -X POST localhost:8000/v1/lyrics -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"enrichedTrip": <EnrichedTrip>, "familyNicknames": ["아빠"], "style": "city_pop"}'   # -> {lyrics, title}
```

- `EnrichedTrip` = TripSummary 전체 + `originName`, `destinationName`(요청 값이 있으면 유지), `placeNames`
  (경로 위 8개 지점을 역지오코딩해 시/군/구 단위로 중복 제거, 경로 순서, 최대 5개. 짧은 주행은 더 적을 수 있다),
  `weather` (`{"description": "맑음", "temperatureC": 15.0}`, 도착 지점·도착 시각 기준, 못 구하면 null).
- 지명·날씨 조회가 실패해도 200으로 응답하고 그 값만 비운다. 좌표는 저장하지 않는다 (Nominatim 결과 캐시만 메모리에).
- `GEO_PROVIDER=live`에서 Nominatim은 초당 1건이라 enrich 한 번에 약 8초 걸린다 (Kakao 키가 있으면 Kakao 사용).
- `/v1/lyrics`는 §7.2 프롬프트로 가사를 만들고 구조(`[verse]`/`[chorus]`/`[verse]`/`[chorus]`, 각 4줄, 다른 문장 없음)를
  검사한다. 어긋나거나 LLM 호출이 실패하면 한 번 더 요청하고, 그래도 안 되면 502. `title`은 "<도착지> 가는 길".
- 실제 외부 API 스모크 테스트(기본 건너뜀): `python -m pytest -m live -s` (Open-Meteo, Nominatim).

## 대화 → 가족 목소리 노래 (v0.4, 설계 §7.5·§8.3·§9.1)

```bash
# 구성원 선택 동의 (기본 false). 변환 동의를 끄면 보관한 참고 목소리를 바로 지운다
curl -s -X PATCH localhost:8000/v1/members/<memberId> -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"conversationConsent": true, "voiceConversionConsent": true}'
# 대화 조각 (5분 이하, 4MiB 이하). passengers 는 탑승자마다 반복, 전원 대화 녹음 동의가 있어야 한다
curl -s -X POST localhost:8000/v1/trips/<tripId>/conversation -H "Authorization: Bearer $TOKEN" \
  -F seq=0 -F passengers=<memberId1> -F passengers=<memberId2> -F audio=@chunk0.m4a
curl -s -X DELETE localhost:8000/v1/trips/<tripId>/conversation -H "Authorization: Bearer $TOKEN"
# 대화 잡: lyrics 대신 conversation + enrichedTrip (탑승자 = enrichedTrip.passengers)
curl -s -X POST localhost:8000/v1/song-jobs -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"tripId":"<tripId>","style":"road_trip","durationSec":75,"conversation":true,"enrichedTrip":<EnrichedTrip>}'
```

- 잡 상태: `QUEUED → ANALYZING → GENERATING → CONVERTING → MIXING → READY | FAILED`. 응답에 `lyrics`(분석 뒤), `singers`(READY 때 부른 구성원).
- 공급자: `LLM_PROVIDER=ollama`(로컬 `gemma3:12b`), `VOICE_PROVIDER=voicelab`(같은 PC의 `../voice-lab`, 파일 경로로 주고받음).
  기본값(`fake`)은 GPU 없이 전체 흐름이 돈다. 외부 LLM(`claude`)에는 대화를 보내지 않고 주행 정보만으로 가사를 쓴다.
- 원본 대화는 분석이 끝나면(실패해도) 지운다. 그 주행의 대화 잡이 생긴 뒤 오는 조각은 409.
- 실제 GPU 서비스 E2E (2026-11-04): 공개 음성 대화 114초·화자 3명 → READY 167.6초 (분석+가사 23초, 작곡 12초, 3명 변환 130초, 믹싱 2초).

```bash
# 전체 로컬 GPU 구성으로 서버 띄우기 (ACE-Step 8001, Ollama 11434, voice-lab 8002 를 먼저 띄운다)
MUSIC_PROVIDER=acestep_local LLM_PROVIDER=ollama VOICE_PROVIDER=voicelab uvicorn app.main:app --port 8000
```
