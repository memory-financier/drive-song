# 오늘의 드라이브 송 (Drive Song) — 설계 문서

> 현대자동차그룹 차량용 앱 개발 공모전(Pleos Playground) 출품용
> 문서 버전: v0.4 (2026-11-03) — **방향 전환: "차 안의 대화가 가족 목소리로 부르는 노래가 된다"** (§1, §7.5, §8.3, §11 T16~T20, §12).
> v0.3 (11/03): Pleos 에뮬레이터·도착 조명·음성 명령·로컬 ACE-Step. v0.2 (10/29): T1~T11 구현 + 휴대폰 에뮬레이터 E2E. v0.1 (10/29): 초안.
> 대상 독자: Claude Code 및 개발자 본인

---

## 0. Claude Code 작업 규칙

- 이 문서가 **단일 기준(source of truth)** 이다. 구현 중 문서와 다른 결정을 하면 이 문서의 해당 섹션도 함께 수정한다.
- `[확인 필요]` 표시가 붙은 항목은 **가정**이다. 실제 SDK 문서나 에뮬레이터로 확인하기 전에는 인터페이스 뒤에 숨기고, 대체 구현(fallback)을 함께 둔다.
- 외부 서비스(LLM, 음악 생성)는 모두 **Provider 인터페이스 + 어댑터** 구조로 만든다. 공급자를 바꿀 때 앱 코드는 건드리지 않는다.
- API 키, 시크릿은 **절대 APK에 넣지 않는다**. 모든 외부 API 호출은 자체 서버를 거친다.
- 각 Task(§11)는 완료 기준을 만족해야 끝난 것으로 본다. 작업 후 테스트를 실행하고 결과를 남긴다.
- 구현 현황과 실행 방법, 남은 미검증 항목은 §16에 모은다.

---

## 1. 개요

### 1.1 한 줄 설명
주행 중 **가족이 차 안에서 나눈 대화**를 담아, 그 이야기와 경로·지명·날씨 같은 차량 데이터로 가사를 쓰고, **가족의 목소리로 나눠 부르는 노래**를 만들어, **도착하는 순간** "오늘의 드라이브 송"을 들려주는 차량용 앱.

- v0.2까지의 흐름(차량 데이터 가사 + 미리 녹음한 5초 클립 삽입 + AI 가수)은 대화가 없거나 처리에 실패할 때의 **대체 경로**로 남는다.

### 1.2 공모전 맥락
| 항목 | 내용 |
|---|---|
| 공모 분야 | 패밀리·동승자 케어 + 인카 엔터테인먼트 |
| 참가 부문 | 일반(스타트업 포함) |
| 접수 마감 | 2026-11-15 (일) 23:59 KST — 기획서 필수, 데모 영상·구현물 선택 |
| 1차 결과 | 2026-11-23 |
| 2차 PT·시연 | 2026-12-03 ~ 12-04 (오프라인) |
| 제출처 | appdevchallenge@hyundai-partners.com |
| 공식 안내 | https://appdevchallenge.auto-hmg.io/guide (2026-11-03 확인) |
| 제출물 | **접수양식 DOCX = 기획서 (필수)**, 02 항목 1~8 작성 (`docs/proposal/`). 시연 영상(목업·프로토타입·화면 녹화 인정)·GitHub 저장소(소스 + release APK `apk/팀명_앱이름_release.apk`, 팀명 Memory Financier → `apk/MemoryFinancier_DriveSong_release.apk`)는 선택, 가산점. 숏폼(세로 30초~1분 릴스/쇼츠, `#HMG차량용앱개발공모전`)은 선택, 가산점 없음 |
| 1차 심사 항목 | 차량특화성, 창의성, **기술 활용성(Pleos SDK·API를 핵심 기능에 활용)**, 사업성(타깃, 예상 MAU, Retention). 배점 비공개 |
| 제공 SDK (FAQ) | Vehicle, NaviHelper, TextToSpeech, SpeechToText, FusedLocation. 2차 PT 대상은 PT 전까지 실제 동작 앱 필수 |
| 오리엔테이션 (10/28) | **Pleos Playground 활용 필수**, SDK를 핵심 기능에 연결. 생성형 AI는 기획·개발 전 단계 사용 가능하나 PT에서 로직을 직접 설명해야 함. 외부 API·퍼블릭 네트워크 사용 가능(Pleos SDK와 병행). **실차 시연 없음 → Pleos Connect 에뮬레이터(Virtual Console로 가상 차량 상태 조정)에서 검증**. 선정 시 12/19~1월 중순 전시 준비(소개 영상, APK 테스트) |

### 1.3 차별화 포인트 (심사 어필)
1. **차 안의 대화와 차량 데이터가 콘텐츠의 원재료** — 가족이 한 공간에 오래 머무는 차에서만 생기는 대화, 그리고 경로·도착 시각 같은 차량 데이터. 폰 앱으로는 만들 수 없는 경험.
2. **가족의 목소리로 부르는 노래** — 대화에서 모은 목소리로 AI 보컬을 가족 목소리로 바꿔 온 가족이 나눠 부른다. 대화 속 실제 한마디("와, 바다다!")도 그대로 들어간다.
3. **주행 중에도 쓸 수 있는 오디오 중심 앱** — Music/Audio 카테고리는 주행 중 일부 기능 허용. 대화 담기·음성 명령으로 화면 조작 없이 동작.
4. **도착 타이밍 연출** — 도착 예정 시각에 맞춰 생성을 시작해 도착 순간 재생, 실내 조명도 노래 색으로 (§6.5).

### 1.4 MVP 범위
- 포함: 주행 기록 + **대화 담기** → 대화 분석(받아 적기·화자 구분) → 대화·차량 데이터로 가사 → 노래 생성 → **가족 목소리로 변환해 나눠 부르기** + 대화 속 한마디 삽입 → 도착 시 재생, 노래 보관함, 음성 명령, 도착 조명.
- 대체 경로: 대화 없음·분석 실패 시 v0.2 흐름(등록 클립 + AI 가수).
- 제외(확장 기능으로 기획서에만 기재): 실시간 스트리밍 생성, 여러 차량 간 동기화, SNS 공유, 구성원별 목소리 모델 미세 조정, 후렴 가족 합창(시간이 남으면 MVP에 넣음).

---

## 2. 플랫폼 제약 (반드시 지킬 것)

| 제약 | 내용 | 설계 반영 |
|---|---|---|
| OS | Pleos Connect = AAOS 기반 | 표준 Android 앱(Kotlin) |
| 주행 제한 | `CarUxRestrictions`로 제한 상태 확인, 제한 기능 UI 축소·비활성 필수 | 녹음·설정·목록 탐색은 정차 시에만. 주행 중엔 재생 제어만 |
| 영상 | 주행 제한 중 영상 재생 시작/재개 금지 | 영상 요소 없음 |
| 애니메이션 | 주행 중 움직이는 시각 요소 최소화 | 주행 중 화면은 정적 |
| 텍스트 입력 | 최소화, 음성·프리셋 우선 | 키보드 입력 없음. 프리셋 선택 |
| CPU | 주행 중 비내비 앱 5% 미만, 정차 중 25% 이하 | 위치 샘플링 저빈도, 무거운 작업은 서버 |
| RAM | 400MB 이하 | 오디오 디코딩 외 대용량 메모리 사용 없음 |
| 오디오 | Pleos 오디오 포커스 정책 준수 | Media3 + 포커스 처리, 내비 음성 시 ducking |
| 화면 분할 | 한 화면에 앱 2개 동시 표시 가능 | 가변 레이아웃 (폭 대응) |
| 시작 속도 | 콜드 스타트 시 최소 초기화, 지연 로딩 | Hilt lazy 주입 |
| 심사 | 개인정보 처리방침, 위치정보 이용약관 URL 필수 | 서버에 정적 페이지로 제공 |

- 구현 메모: 주행 제한 판정은 **안전한 쪽 기본값**을 쓴다. 차량(automotive) 기기에서 Car 서비스에 연결하지 못하거나 첫 값이 오기 전에는 "제한 중"으로 본다. 폰 AVD 같은 비차량 기기는 항상 "제한 없음"이다. 테스트용으로 설정에 "주행 제한 시뮬레이션" 스위치가 있다.
- 구현 메모: 화면 전환 애니메이션은 제한 중 끈다. Material3 컴포넌트 자체 애니메이션(NavigationRail 선택 표시, Switch thumb)은 끄지 않았다 `[확인 필요]`.

---

## 3. 시스템 아키텍처

```
┌────────────────────────── 차량 (Pleos Connect / AAOS) ──────────────────────────┐
│                                                                                  │
│  ┌──────────────┐   ┌──────────────────┐   ┌──────────────────┐                  │
│  │ TripRecorder │──▶│  TripRepository  │──▶│ SongOrchestrator │──┐               │
│  │ (Foreground  │   │  (Room DB)       │   │ (트리거·상태관리) │  │               │
│  │  Service)    │   └──────────────────┘   └──────────────────┘  │               │
│  └──────┬───────┘                                   │            │               │
│         │ 위치·속도·경로·ETA                          │ 가사       │ 잡 요청/폴링   │
│  ┌──────▼─────────────────────┐          ┌──────────▼────────┐   │               │
│  │ Vehicle SDK / NaviHelper   │          │ LyricsProvider    │   │               │
│  │ / Android Location         │          │ (Gleo LLM | 서버) │   │               │
│  └────────────────────────────┘          └───────────────────┘   │               │
│                                                                  │               │
│  ┌──────────────┐   ┌───────────────────┐   ┌────────────────┐   │               │
│  │ VoiceClip    │   │ PlaybackService   │   │ UI (Compose)   │   │               │
│  │ Recorder     │   │ (Media3 Session)  │   │ + UxRestriction│   │               │
│  └──────┬───────┘   └───────────────────┘   └────────────────┘   │               │
└─────────┼────────────────────────────────────────────────────────┼───────────────┘
          │ 클립 업로드                                              │ HTTPS
┌─────────▼──────────────────────── 자체 서버 (FastAPI) ─────────────▼───────────────┐
│  /clips   /song-jobs   /lyrics(fallback)   /legal/*                              │
│  ┌───────────────┐  ┌──────────────────┐  ┌───────────────┐  ┌────────────────┐  │
│  │ JobQueue      │─▶│ MusicProvider    │─▶│ Mixer         │─▶│ Object Storage │  │
│  │ (async worker)│  │ MiniMax|Mureka|  │  │ (ffmpeg:      │  │ (곡·클립 파일)  │  │
│  │               │  │ ACE-Step 어댑터   │  │  ducking·정규화)│ └────────────────┘  │
│  └───────────────┘  └──────────────────┘  └───────────────┘                      │
│  ┌──────────────────┐  ┌─────────────────┐                                       │
│  │ LlmProvider      │  │ Geo/Weather     │                                       │
│  │ (가사 fallback)  │  │ (역지오코딩·날씨) │                                       │
│  └──────────────────┘  └─────────────────┘                                       │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 역할 분담 원칙
- **차량 앱**: 데이터 수집, 사용자 경험, 재생. 가벼워야 함.
- **서버**: 외부 API 호출, 음악 생성 대기, 오디오 믹싱, 파일 저장. 무거운 일은 전부 여기.

### 3.2 구현과 그림의 차이 (v0.2)
- TripRecorder는 엔진(`TripRecorder`, @Singleton, feature:trip)과 이를 띄우는 Android 서비스(`TripRecorderService`)로 나뉜다. SongOrchestrator·UI는 엔진의 `TripEvents` 인터페이스를 주입받고 서비스에 바인딩하지 않는다.
- Vehicle SDK·NaviHelper SDK는 연동하지 않았다. 표준 AAOS/Android 대체 구현과 데모용 Fake만 있다 (§6.1, §6.4).
- LyricsProvider는 서버 경로만 있다. Gleo LLM은 미구현 (§7.2).
- 서버 라우터는 `/v1/devices`, `/v1/members`, `/v1/clips`, `/v1/trips/enrich`, `/v1/lyrics`, `/v1/song-jobs`, `/v1/songs`, `/legal/*` (§9).
- Object Storage 대신 서버 로컬 디스크(`DATA_DIR`)를 쓴다.
- 주행 제한(`UxRestrictionsMonitor`)은 Car 서비스의 실제 값과 설정 "주행 제한 시뮬레이션" 스위치만 본다 (둘 중 하나라도 제한이면 제한). **데모 모드의 차속·기어는 제한 상태를 바꾸지 않는다** → 데모 영상에서 주행 중 화면을 보이려면 시뮬레이션 스위치를 켠다 (`docs/demo-scenario.md`).

---

## 4. 기술 스택

### 4.1 Android 앱
| 영역 | 선택 (실제 버전) | 비고 |
|---|---|---|
| 언어 | Kotlin 2.0.21, JVM 타깃 17 | 빌드 JDK **17 필수** (JDK 21에서는 core:model이 Java 21 / Kotlin 17 대상 불일치로 컴파일 실패, 2026-11-03 확인). Android Studio 내장 jbr은 버전이 다를 수 있다 (데스크탑은 JDK 25) → Temurin 17 |
| 빌드 | AGP 8.6.1, Gradle 8.7, KSP 2.0.21-1.0.28 | 공통 설정은 루트 `build.gradle.kts`의 `subprojects{}` (convention plugin 없음). 버전은 `gradle/libs.versions.toml` |
| SDK | compileSdk 35, targetSdk 35, **minSdk 29** | minSdk 29는 양산 AAOS 최저 버전대 기준. `[확인 필요]` Pleos Connect API 레벨 |
| UI | Jetpack Compose (BOM 2024.10.01), Material3, Navigation Compose 2.8.3 | Pleos 공식 샘플과 동일. 아이콘은 material-icons-core + 직접 넣은 벡터(`PlayerIcons.kt`) |
| DI | Hilt 2.52 (+ hilt-navigation-compose 1.2.0) | 무거운 의존성은 `dagger.Lazy` |
| 비동기 | Coroutines 1.9.0 + Flow | |
| 로컬 DB | Room 2.6.1 (DB 버전 4) | 주행 기록, 구성원·클립, 곡 목록. MVP는 `exportSchema=false`, `fallbackToDestructiveMigration()` (출시 전 마이그레이션 필요) |
| 설정 저장 | DataStore Preferences 1.1.1 | 설정은 core:data `SettingsRepository`, 디바이스 토큰은 core:network 전용 DataStore 파일 |
| 네트워크 | Retrofit 2.11.0 + OkHttp 4.12.0 + kotlinx.serialization 1.7.3 | 타임아웃: connect 15초, write 60초, **read 5분**, call 6분 (가사·enrich가 OkHttp 기본 10초보다 오래 걸림). 서버 쪽 상한은 `/v1/lyrics` 180초, `/v1/trips/enrich` 약 45초 (§9). 인증은 §9 |
| 재생 | Media3 1.4.1 **MediaLibraryService** (ExoPlayer + media3-session) | AAOS 시스템 미디어 UI가 곡 목록을 탐색·재생할 수 있도록 MediaLibraryService로 결정. 시스템 UI 연동 실제 동작은 `[확인 필요]` (§13-5) |
| 녹음 | `MediaRecorder` (MIC, MPEG_4/AAC 모노 44.1kHz 96kbps, m4a) | 미리듣기는 `MediaPlayer`. `[확인 필요]` 차량 마이크 접근, RECORD_AUDIO 권한 동작 |
| 백그라운드 | Foreground Service (location 타입) `TripRecorderService` | 홈에서 수동 시작 (부팅·앱 실행 시 자동 시작 없음), `START_NOT_STICKY` |
| 차량 데이터 | `CarPropertyManager`(android.car, `uses-library required=false`), `LocationManager` | Pleos Vehicle/NaviHelper SDK는 미연동. §6 참조 |
| 테스트 | 순수 JVM: **JUnit5** (Jupiter 5.11.3). Android 의존 테스트: **Robolectric 4.14.1 (sdk=34) + JUnit4 4.13.2** (junit-vintage-engine으로 JUnit Platform에서 실행). Turbine 1.2.0, MockK 1.13.13, MockWebServer, coroutines-test | Robolectric SDK 35는 JDK 21이 필요해 sdk=34 고정. Compose UI Test는 쓰지 않았다 (UI 테스트 없음) |
| 실행 환경 | Android Studio + Pleos Connect Emulator(AVD) | Pleos 에뮬레이터는 아직 설치·실행하지 않았다 (§16) |

- 개발 서버 주소: 기본 `http://10.0.2.2:8000/` (에뮬레이터에서 본 호스트 PC). `-PdriveSongApiBaseUrl=...` 로 재정의한다. 평문 HTTP는 디버그 빌드에서 모든 호스트 허용(실기기 + LAN 서버용), 릴리스는 10.0.2.2/localhost만 허용하고 그 외는 HTTPS.
- 모듈 의존 예외: core:vehicle → core:data (주행 제한 시뮬레이션·데모 설정), core:data → core:network (VoiceRepository가 API 호출).

### 4.2 서버
| 영역 | 선택 |
|---|---|
| 언어/프레임워크 | Python 3.12+ + FastAPI (≥0.115), uvicorn. Docker 이미지는 `python:3.12-slim`, 로컬 테스트는 Python 3.13에서 실행 |
| 작업 큐 | `asyncio` 잡마다 별도 태스크 + SQLite jobs 테이블이 기준 (동시 실행 제한 없음) / 확장: Redis + RQ |
| DB | SQLite + SQLAlchemy 2.0 (MVP) → PostgreSQL. 마이그레이션 도구 없음, 시작 시 `create_all` |
| 오디오 처리 | ffmpeg/ffprobe를 **subprocess로 직접 호출** (pydub은 Python 3.13에서 동작하지 않아 쓰지 않음). ffmpeg 5.x 이상 필요 (`amix normalize`, `alimiter latency`) |
| 외부 HTTP | httpx (음악·지오 어댑터), `anthropic` SDK ≥2.3 (가사 LLM) |
| 파일 저장 | 로컬 디스크 `DATA_DIR` (MVP) → S3 호환 스토리지 |
| 배포 | Docker 단일 컨테이너 + `docker-compose.yml` (Fly.io / Render / 개인 서버 중 택1). Docker는 아직 빌드·실행하지 않았다 |
| 설정 | `.env` (pydantic-settings), 예시는 `server/.env.example` |

**환경 변수**

| 변수 | 기본 | 설명 |
|---|---|---|
| `DATA_DIR` | `data` (Docker `/data`) | 클립·곡 파일, 기본 SQLite 위치 |
| `DATABASE_URL` | `sqlite:///<DATA_DIR>/drive_song.db` | |
| `MUSIC_PROVIDER` | `fake` | `fake` \| `minimax` \| `mureka` \| `acestep` \| `acestep_local` |
| `ACESTEP_LOCAL_URL` / `ACESTEP_LOCAL_THINKING` / `ACESTEP_LOCAL_STEPS` | `http://127.0.0.1:8001` / `false` / `8` | 로컬 ACE-Step 1.5 API 서버 (키 없음, §7.3) |
| `FAL_KEY` / `MUREKA_API_KEY` | — | 고른 공급자의 키가 없으면 서버가 시작하지 않는다 |
| `JOB_POLL_INTERVAL_SEC` / `JOB_TIMEOUT_SEC` | 5 / 300 | 공급자 폴링 간격, 공급자 제출 시각부터의 제한 시간 (QUEUED 시간은 포함 안 함) |
| `SONG_CANDIDATES` | 1 | 가족이 부를 곡(대화 노래)의 후보 곡 수. 변환 단계가 하나를 고른다 (§7.5 9단계). 자체 GPU(`acestep_local`)면 3 권장 (곡당 약 11초), 유료 공급자는 곡 수만큼 과금된다 |
| `FAKE_MUSIC_DELAY_SEC` | 3 | fake 공급자가 pending으로 머무는 시간 |
| `LLM_PROVIDER` | `fake` | `fake` \| `claude` (`ANTHROPIC_API_KEY`, `CLAUDE_MODEL` 기본 `claude-opus-5-5`) |
| `GEO_PROVIDER` | `fake` | `fake` \| `live` (`KAKAO_REST_API_KEY`가 있으면 Kakao, 없으면 Nominatim. `NOMINATIM_USER_AGENT`) |

**잡 워커 동작**
- 상태: `QUEUED → GENERATING → MIXING → READY | FAILED`.
- 서버 재시작 시 끝나지 않은 잡(QUEUED/GENERATING/MIXING)을 모두 이어서 처리한다. 공급자 잡 id가 저장된 잡은 다시 제출하지 않고 폴링을 이어가며, 제한 시간은 처음 제출 시각 기준이다.
- 폴링 중 `httpx.HTTPError`는 일시 오류로 보고 제한 시간까지 재시도한다. 제출 오류·공급자 실패·다운로드 오류·믹서 오류는 즉시 FAILED, 메시지는 `error`로 돌려준다.
- 처리 중 잡 행이 삭제되면(§9 `DELETE /v1/songs`) 워커는 노래를 만들지 않고 조용히 멈추며, 이미 만든 믹싱 파일은 지운다.
- 워커의 DB 호출은 이벤트 루프 위에서 동기로 실행된다 (스레드 이전은 하지 않음). 곡 오디오 응답은 파일 전송 전에 DB 세션을 닫는다.

---

## 5. 저장소 구조

실제 구조 (2026-10-29, docs는 2026-11-03).

```
Drive Song/
├── CLAUDE.md                  # Claude Code용 요약 규칙 (이 문서 §0 요약 + 빌드 명령)
├── docs/
│   ├── design.md              # 이 문서
│   ├── goals.md               # 목표 체크리스트 (Claude Code '목표' 패널에 표시)
│   ├── api-bench-report.md    # T1 결과 (잠정, 미실행)
│   ├── demo-scenario.md       # T12 데모 영상 촬영 절차 (서버 데모 설정, 준비, 촬영, 알려진 제약)
│   ├── desktop-handoff.md     # 데스크탑 이전 메모 (로컬 ACE-Step/LLM 계획)
│   ├── conversation-song-trial.md  # v0.4 대화 → 가족 목소리 노래 시험 기록 (§7.5)
│   └── proposal/              # 공모전 기획서: proposal.md (02 항목 1~8 초안), official-form/ (공식 접수양식 DOCX)
├── android/
│   ├── build.gradle.kts       # 모든 모듈 공통 설정 (SDK, Java/Kotlin 17, JUnit Platform)
│   ├── settings.gradle.kts
│   ├── gradle/libs.versions.toml
│   ├── app/                   # Application(오케스트레이터·자동 재생 시작), MainActivity, NavHost,
│   │                          # 홈/재생/설정 화면 (ui/screens), 주행 제한 게이트, 경로 커버(RouteCover)
│   ├── core/
│   │   ├── model/             # 도메인 모델 (순수 Kotlin): TripSummary(+Builder), GeoPoint, RouteInfo, Gear,
│   │   │                      # SongStyle, ClipSlot, ClipPlanner, TripEndReason
│   │   ├── data/              # Room(db/), Trip·Voice·Song Repository, DataStore 설정, AllDataDeleter(erase/)
│   │   ├── network/           # Retrofit API, DTO, AuthInterceptor·디바이스 토큰(auth/)
│   │   └── vehicle/           # §6.4 인터페이스, car/(CarPropertyManager), location/(LocationManager),
│   │                          # navigation/(목적지 프리셋 ETA), demo/(시나리오 재생·Fake·전환), restriction/(UxRestrictions)
│   │                          # assets/scenarios/*.json, tools/gen_scenarios.py
│   └── feature/
│       ├── trip/              # TripRecorder 엔진 + TripRecorderService + TripEvents
│       ├── lyrics/            # LyricsProvider, RemoteLyricsProvider, FallbackLyricsProvider
│       ├── song/              # SongOrchestrator, SongNotifier
│       ├── voice/             # 가족·녹음 화면, 탑승자 화면, VoiceRecorder, ClipPlayer
│       ├── player/            # PlaybackService(MediaLibraryService), SongPlayer, PlaybackReporter
│       └── library/           # 노래 보관함 UI
├── server/
│   ├── app/
│   │   ├── main.py
│   │   ├── settings.py
│   │   ├── db.py              # Base, engine, 세션 의존성
│   │   ├── models.py          # devices, members, clips
│   │   ├── api/               # 라우터: devices, members, clips, trips(enrich), conversations(v0.4), lyrics, song_jobs(+songs), legal
│   │   │                      # + auth.py(Bearer 의존성), schemas.py(camelCase 기반 모델)
│   │   ├── providers/
│   │   │   ├── music/         # base.py, fal.py(공용 큐 클라이언트), minimax.py, mureka.py, acestep.py, acestep_local.py, fake.py, factory.py
│   │   │   ├── llm/           # base.py(프롬프트·검증), claude.py, ollama.py(v0.4), fake.py, factory.py
│   │   │   ├── voice/         # (v0.4) base.py(ConversationAnalyzer·VoiceConverter), voicelab.py, fake.py, factory.py
│   │   │   └── geo/           # reverse_geocode.py(Kakao/Nominatim/Fake), weather.py(Open-Meteo/Fake), factory.py
│   │   ├── mixer/             # mix.py (ffmpeg)
│   │   └── jobs/              # worker.py, models.py (jobs, songs)
│   ├── tests/
│   ├── static/legal/          # privacy.html, location-terms.html
│   ├── Dockerfile
│   ├── docker-compose.yml
│   ├── .env.example
│   ├── README.md
│   └── pyproject.toml
├── voice-lab/                 # v0.4 GPU 서비스 (§7.5, T16): 대화 분석·가족 목소리 노래 변환, FastAPI 8002
│   ├── voicelab/              # audio.py(읽기·발화 분할), analyze.py(받아 적기·화자·한마디·참고 목소리),
│   │                          # sing.py(Demucs·Seed-VC 호출·옥타브·나눠 부르기), app.py(API), settings.py
│   ├── tests/                 # GPU 없이 도는 로직·API 시험
│   ├── eval/trial.py          # 완료 기준 확인 (Zeroth-Korean 대화 → 분석·노래)
│   └── pyproject.toml, README.md
└── tools/
    └── api-bench/             # 한국어 가사 → 노래 API 비교: bench.py, samples/*.txt, test_bench.py, README.md
```

- Room 엔티티·DAO와 Repository는 모두 core:data에 있다 (DB가 하나이므로). feature 모듈은 UI·서비스·도메인 로직만 갖는다.
- 정차 전용 화면 게이트는 각 화면이 아니라 app의 NavHost에서 한 번에 적용한다.

---

## 6. 차량 데이터 수집 (TripRecorder)

### 6.1 데이터 소스와 대체 경로

| 데이터 | 1순위 | 대체 | 상태 (v0.2 구현) |
|---|---|---|---|
| 현재 위치 | Fused Location SDK | Android `LocationManager` / Play Services Fused | **`LocationManager.GPS_PROVIDER`** (`AndroidLocationDataSource`). 5초 간격으로 받아 Kotlin에서 §6.2 규칙으로 거른다. 정확한(FINE) 위치 권한 필수. `[확인 필요]` Pleos의 GPS_PROVIDER 유무 |
| 차속 | Vehicle SDK (Vehicle State) | `CarPropertyManager` `PERF_VEHICLE_SPEED` | **`CarPropertyManager` `PERF_VEHICLE_SPEED`** (SENSOR_RATE_NORMAL). Vehicle SDK 미연동. `CAR_SPEED`는 런타임 권한으로 요청 `[확인 필요]` |
| 목적지 / 경로 | NaviHelper SDK | 사용자가 앱에서 목적지 프리셋 선택 | **설정의 목적지 프리셋** (없음, 강릉 경포해변, 속초해수욕장, 부산 해운대; 좌표는 대략값). 한 주행에만 적용: 실제 모드에서 주행이 PARKED/STOPPED로 끝나면 해제 (NAVIGATION_ENDED·데모 모드에서는 유지). `[확인 필요]` NaviHelper 반환 필드 |
| 도착 예정 시각(ETA) / 남은 거리 | NaviHelper SDK | 위치·속도 기반 자체 추정, 또는 "주차(P단) 전환 시 생성" | **`PresetNavigationDataSource`**: 남은 거리 = 직선거리 × 1.3 (`ROAD_DETOUR_FACTOR`, `[확인 필요]`), ETA = 남은 거리 ÷ 최근 10분 위치 기반 평균 속도. 5km/h 미만이면 ETA 없음. 도착해도 경로를 null로 바꾸지 않는다 (도착 신호는 P단). 프리셋이 없으면 "주행 종료 시 생성" 경로 |
| 기어 상태(P단) | Vehicle SDK | `CarPropertyManager` `GEAR_SELECTION` | **`CarPropertyManager` `GEAR_SELECTION`** (ONCHANGE). `CAR_POWERTRAIN`은 normal 권한으로 가정해 요청하지 않음 `[확인 필요]` |
| 날씨 | 서버에서 외부 날씨 API | — | Open-Meteo (키 불필요). 도착지(없으면 마지막 경유지)의 endedAt → etaAt → startedAt+durationMin 시각, 가장 가까운 정시 값. 조회 가능 범위(약 -92 ~ +16일) 밖이면 null |
| 지명 | 서버에서 역지오코딩 | — | `KAKAO_REST_API_KEY`가 있으면 Kakao coord2regioncode, 없으면 OSM Nominatim (초당 1건). 실패해도 enrich는 200으로 응답하고 해당 값만 비운다 |

- (2026-11-03, SDK AAR 직접 확인) Vehicle SDK v2.3.2(`ai.pleos.playground:Vehicle`)에는 문서 목록에 없던 **`Gear`(`registerGearSelection`, `VehicleGear.GEAR_PARK/DRIVE/...`)가 있고, 차속 인터페이스는 없다**. 기어는 Vehicle SDK로 옮길 수 있고, 차속은 `CarPropertyManager`(또는 FusedLocation 속도)를 유지한다. 그 밖에 Brake(브레이크 페달), Odometer, Light(앰비언트 라이트, §6.5) 등. 권한은 조회 `pleos.car.permission.CAR_*`, 제어 `CONTROL_CAR_*`. NaviHelper SDK v3.0.5 소개 문서는 목적지 설정·경로·경유지·"주행 상태 이벤트"만 명시하고 ETA·남은 거리는 명시하지 않는다 `[확인 필요]` API Reference.
- Pleos SDK는 공개 Maven 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받는다 (인증 불필요, 2026-11-03 확인): Vehicle 2.3.2, NaviHelper 3.0.5, SpeechToText 3.3.1, TextToSpeech 3.3.1, FusedLocation 1.1.3. Playground 콘솔 문서상 STT·TTS·FusedLocation·Vehicle Control은 "준비 중" 표시 `[확인 필요]` 실제 동작은 에뮬레이터에서 확인.
- 권한: 주행 기록 시작 시 ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, CAR_SPEED, POST_NOTIFICATIONS(API 33+)를 요청한다. **대략적(COARSE) 위치만 있으면 기록을 시작하지 않는다** ("정확한 위치 권한이 있어야 주행을 기록할 수 있어요").
- 권한이 없는 실제 소스는 경고 로그만 남기고 아무것도 내보내지 않는다.

### 6.2 수집 정책 (CPU 5% 미만 유지)
- 위치: 30초 간격 또는 500m 이동 시 기록 (먼저 오는 쪽). 첫 위치는 바로 기록. **주행 기록 중에만** 위치·경로(내비) 구독 (대기 중에는 GPS·경로 구독 없음).
- 속도: 1분 단위 최고/평균만 집계 (원시 샘플 저장 안 함). 구현: 시계 분 단위 버킷, 그 분에 받은 샘플의 최대·산술 평균 (시간 가중 아님). 새 분은 마지막 속도로 시작한다 (같은 속도면 이벤트가 오지 않으므로).
- 역지오코딩은 **앱에서 하지 않음**. 좌표 묶음을 서버로 보내 서버에서 처리.
- 주행 시작 판정 (v0.2 추가): 기어 D/R/N 또는 속도 5km/h 이상 (기어 데이터가 없는 기기 대비).
- 주행 종료 판정: P단 전환 + 5분 이상 정차, 또는 NaviHelper 안내 종료.
  - P단 5분: P가 5분 연속 유지되면 종료(PARKED). `endedAt`은 감지 시각이 아니라 P 전환 시각. 입력마다 + 30초 주기로 검사 (실차 감지 지연 ≤ 30초). UNKNOWN 기어는 타이머를 리셋하지 않고 D/R/N은 리셋한다.
  - 안내 종료: 주행 중 활성 경로가 non-null → null로 바뀌면 즉시 종료(NAVIGATION_ENDED). 이후 P단을 볼 때까지 새 주행을 시작하지 않는다 (안내 취소로 주행이 쪼개지지 않게). 목적지 프리셋을 주행 중 "없음"으로 바꿔도 이 규칙으로 끝난다.
  - 서비스 정지·취소 시 진행 중 주행은 STOPPED로 종료·저장한다.
- 저장: 위치 점(`trip_points`)과 분 단위 속도 버킷을 Room에 저장하고, 종료 시 TripSummary를 JSON(`trips.summaryJson`)으로 저장한다. 위치를 한 번도 받지 못한 주행은 요약 없이 종료된다.
- 주행 id: `trip_yyyyMMdd_HHmmss_<4 hex>` (Clock 시간대 기준. 같은 데모를 같은 날 반복하면 시작 시각이 같아 접미사를 붙임).
- 좌표 보존: 30일이 지난 원시 위치 점 삭제는 §8.1.
- `[확인 필요]` 실제 CPU 사용량 미측정 (§16).

### 6.3 TripSummary (서버로 보내는 요약)
```kotlin
@Serializable
data class TripSummary(
    val tripId: String,
    val startedAt: Instant,
    val endedAt: Instant?,              // 진행 중이면 null (ETA 트리거 시)
    val etaAt: Instant?,
    val origin: GeoPoint,
    val destination: GeoPoint?,
    val destinationName: String?,       // NaviHelper 제공 시
    val waypoints: List<GeoPoint>,      // 다운샘플링 (최대 50개)
    val distanceKm: Double,
    val durationMin: Int,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val timeOfDay: TimeOfDay,           // DAWN, MORNING, AFTERNOON, EVENING, NIGHT
    val passengers: List<String>,       // 이번 주행에 탄 가족 구성원 id (사용자가 선택)
)
```
- 구현: core:model에 있다. `Instant`는 `java.time.Instant` + ISO-8601 직렬화(시간대 포함 필수, 서버는 시간대 없는 값을 422로 거부). `GeoPoint`는 `{lat, lng}` (서버와 일치 확인).
- 값 정의: `avgSpeedKmh` = 총거리 ÷ 정차 포함 총시간. `maxSpeedKmh` = 속도 샘플 최대값. 거리·속도는 0.1 단위 반올림. `timeOfDay`는 `startedAt`의 현지 시각 (DAWN 04–06, MORNING 07–11, AFTERNOON 12–16, EVENING 17–20, NIGHT 21–03). `waypoints`는 처음·끝을 포함한 균등 간격 인덱스.
- `passengers`는 탑승자 화면에서 마지막으로 고른 값(설정 `selectedPassengerIds`)을 주행 시작 시 한 번 읽는다.

### 6.4 Vehicle 래퍼 인터페이스
```kotlin
interface VehicleDataSource {
    val speedKmh: Flow<Float>
    val gear: Flow<Gear>
}
interface NavigationDataSource {
    val activeRoute: Flow<RouteInfo?>   // 목적지, ETA, 남은 거리
}
interface LocationDataSource {
    fun locations(intervalMs: Long, minDistanceM: Float): Flow<GeoPoint>
}
```
- 각각 `Pleos*` 실구현과 `Fake*` 구현을 둔다. Fake는 미리 정의된 경로 시나리오(예: 서울→강릉 3시간)를 빠르게 재생하는 **데모 모드**로도 쓴다.
- v0.2 구현:
  - `Pleos*` 구현은 없다. 실제 구현은 `CarPropertyVehicleDataSource`, `PresetNavigationDataSource`, `AndroidLocationDataSource` (§6.1). Pleos SDK를 쓸 수 있게 되면 `VehicleModule`에서 바인딩만 바꾼다.
  - 데모 전환: Hilt는 인터페이스마다 `DemoSwitching*`을 주입하고, 이 클래스가 설정 `demoMode`에 따라 Fake/실제 구현을 `flatMapLatest`로 고른다 (둘 다 `dagger.Lazy`). 설정을 바꾸면 수집 중인 Flow도 바로 소스가 바뀐다.
  - 가상 시계: `ScenarioClock`(@Singleton `java.time.Clock`)이 시나리오 재생 중(도착 후 고정 시각 포함, `DemoDriveController.stop()` 전까지)은 시나리오 시각을, 그 외에는 시스템 시각을 준다. 주행 기록·ETA·곡 생성 시각이 모두 이 Clock을 쓴다.
  - 시나리오: `assets/scenarios/seoul_gangneung.json` (09:30 출발, 서울시청→경부→신갈JC→영동고속도로→강릉IC→경포해변, 점 사이 직선거리 합 214km), `neighborhood_mart.json` (동네 마트, 17:30 출발). 오늘 날짜·기기 시간대 기준으로 시작하므로 주행 시각이 실제 시각보다 늦을 수 있다. 형식: `{id, name, destinationName, startLocalTime, parkHoldSec, points:[{t, lat, lng, speedKmh, gear, label}]}` (`tools/gen_scenarios.py`로 생성, 좌표는 대략값).
  - 두 시나리오 모두 끝에 P단을 360초 유지해 "P단 5분" 종료 규칙이 시나리오 안에서 동작한다. 데모에서는 Fake 경로가 도착 시 null이 되므로 실제로는 NAVIGATION_ENDED로 먼저 끝난다.
  - 재생 속도: 100ms 틱, **300배속**, 도착 전 마지막 10분(600초)만 **30배속**. 서울→강릉 약 54.5초, 마트 약 19초. 마지막 10분을 느리게 하는 이유는 ETA ≤ 10분 트리거 뒤 도착까지 실제 시간 약 20초를 확보해 도착 전에 READY가 되게 하기 위함 (§7.1).
  - 재생 정지·다른 시나리오 시작 시: 현재 위치에서 P단·0km/h·경로 null 프레임을 내보내 1초 유지한 뒤 초기화/다음 시나리오를 시작한다. 그래서 진행 중 주행이 정지 시각에 끝나고 다음 시나리오는 새 주행이 된다.
  - 데모 모드는 주행 제한 상태를 움직이지 않는다 (§3.2). 제한 화면은 "주행 제한 시뮬레이션" 스위치로 따로 켠다.

### 6.5 도착 조명 연출 (Vehicle SDK Light, v0.3 추가)
- 노래가 재생되는 동안 실내 앰비언트 라이트를 노래 스타일 색으로 바꾸고, 재생이 멈추면(일시정지·끝·목록 비움) 원래 상태로 되돌린다.
- **정차 중에만**: 주행 제한 중(`UxRestrictionsMonitor`)이면 바꾸지 않고, 바꾼 상태에서 제한이 시작되면 바로 되돌린다 (주행 중 시각 자극 최소화, §2). 도착(P단) 자동 재생이 대표 장면.
- 설정 "노래에 맞춰 실내 조명 바꾸기" (기본 켬).
- 색 (RGB): 동요 `#FFC83D`, 시티팝 `#B84DFF`, 어쿠스틱 `#FF8A3D`, 신나는 로드트립 `#00AAD2`. 색은 재생 시점의 설정 스타일을 쓴다 (노래 행에 스타일을 저장하지 않음).
- 인터페이스 `AmbientLightController { apply(colorRgb): Boolean; restore() }` (core:vehicle). 구현 `PleosAmbientLightController`: `Vehicle(context).light`의 `checkAmbientLightBrightnessColorCapabilities`로 지원 좌석 영역을 얻어, 영역마다 현재 스위치·색을 기억한 뒤 `setAmbientLightSwitch(ON)` + `setAmbientLightBrightnessColor(color)`. Pleos Vehicle 서비스가 없거나(휴대폰 AVD) 권한·기능이 없으면 아무것도 하지 않는다 (false). 호출마다 제한 시간 3초.
- 권한: `pleos.car.permission.CAR_LIGHTS`, `CONTROL_CAR_LIGHTS` (주행 기록 시작 때 함께 요청). `[확인 필요]` 보호 수준, 색 int의 의미(RGB 가정), 에뮬레이터에서 실제로 바뀌는지.

---

## 7. 노래 생성 파이프라인

### 7.1 상태 머신 (SongOrchestrator)
```
IDLE
 └─(주행 시작)──────────────▶ RECORDING_TRIP
RECORDING_TRIP
 ├─(ETA ≤ 10분)─────────────▶ GENERATING_LYRICS
 └─(ETA 없음 & 주행 종료)────▶ GENERATING_LYRICS
GENERATING_LYRICS ─(완료)───▶ SUBMITTING_JOB ─(jobId)─▶ WAITING_FOR_SONG
WAITING_FOR_SONG
 ├─(완료)──────────────────▶ READY
 └─(실패/타임아웃 5분)──────▶ FAILED  (재시도 1회 후 사용자 알림)
READY ─(도착 판정)──────────▶ PLAYING
PLAYING ─(종료)─────────────▶ ARCHIVED
```
- 도착 전에 READY가 되면 도착 판정(P단 전환)까지 대기 후 자동 재생.
- 도착 후에도 WAITING이면 "노래가 곧 완성돼요" 상태를 표시하고 완료 즉시 알림.
- 자동 재생은 설정에서 끌 수 있다.

**v0.2 구현 세부**
- 트리거: ETA 판정은 `EtaUpdated` 이벤트의 `route.etaAt - event.at ≤ 10분` (데모에서는 시나리오 시계 기준). "주행 종료" 트리거는 ETA를 본 적이 있어도 RECORDING_TRIP 상태에서 주행이 끝나면 항상 발동한다 (ETA가 10분 아래로 내려가지 않은 채 안내가 취소된 경우 포함).
- GENERATING_LYRICS 단계에서 먼저 `POST /v1/trips/enrich`로 EnrichedTrip을 만든 뒤 LyricsProvider를 부른다 (§7.2).
- 잡 요청: `durationSec`은 75초 고정, 스타일은 설정값, `clipPlan`은 §8의 클립 선택 결과, `title`은 가사 응답의 제목. 곡 파일은 `GET /v1/songs/{songId}/audio`로 받아 `filesDir/songs/<songId>.m4a`에 저장한다 (응답의 `audioUrl`은 쓰지 않음). 곡 제목 = 잡의 title → 가사 title → "오늘의 드라이브 송".
- 폴링·재시도: 5초 간격 폴링, 잡 제출부터 5분(코루틴 시간) 제한. 폴링 중 네트워크 오류는 시도를 끝내지 않는다. 재시도 1회는 시도 전체(가사 → 잡 → 다운로드) 단위이며 잡 FAILED·타임아웃·다운로드/네트워크 오류를 모두 센다. 이미 성공한 결과(가사, READY 잡)는 재시도에서 다시 쓴다. 두 번 실패하면 FAILED + 실패 알림.
- 도착 판정: P단 전환(`Arrived`), 또는 PARKED/STOPPED 종료. NAVIGATION_ENDED는 이어지는 P단을 기다린다. 생성이 시작된 뒤의 P단만 세며(RECORDING_TRIP 중 휴게소 P단은 무시, 생성 시작 후 휴게소 P단은 도착으로 봄), 주행당 한 번만 처리한다.
- 자동 재생: 도착 시 READY면 `autoPlay` 이벤트를 낸다 (설정 "도착하면 자동 재생", 기본 켬). 오케스트레이터는 직접 재생하지 않고, `DriveSongApplication`이 이 이벤트를 받아 MediaController로 그 곡만 재생한다 (재생 화면은 열지 않음). 도착 후 READY가 되면 자동 재생 없이 알림만 보낸다. "노래가 곧 완성돼요"는 도착 후 GENERATING_LYRICS·SUBMITTING_JOB·WAITING_FOR_SONG 동안 표시한다.
- PLAYING/ARCHIVED: PlaybackService가 `PlaybackReporter`로 재생 시작·종료를 알리고, 오케스트레이터는 자기가 만든 곡만 반영한다. 종료 = 끝까지 재생, 다른 곡으로 이동, 재생 목록 비움, 서비스 종료 (일시정지는 종료 아님).
- 한 번에 한 곡: 생성 중에 시작된 새 주행은 노래를 만들지 않는다. READY/PLAYING/ARCHIVED/FAILED 상태에서 새 주행이 시작되면 상태를 RECORDING_TRIP으로 되돌린다 (이전 곡은 Room에 남음).
- 알림: 채널 `song_status`, 완성 2001 / 실패 2002. 알림 권한이 없으면 조용히 건너뛴다.
- 수명: 오케스트레이터는 앱 프로세스 싱글턴으로 `DriveSongApplication.onCreate`에서 IO 스레드로 시작한다. 주행 서비스가 끝난 뒤 프로세스가 죽으면 생성 중인 곡은 앱에서 이어받지 못한다 (서버 잡은 남음) `[확인 필요]`.
- 데이터 삭제 (`SongOrchestrator.clearForDataDeletion`, §8.1): 만들던 노래의 코루틴을 `cancelAndJoin`으로 멈춘 뒤 삭제를 실행한다. 취소이므로 재시도·실패 알림·추가 폴링이 없다 (서버에서 지운 잡을 재시도로 다시 만들지 않게). 삭제 중 상태는 IDLE이라 주행 이벤트로 새 노래를 시작하지 않는다. 끝나면 IDLE로 돌아가되, 기록 중인 주행(삭제에서 남긴다)의 노래가 아직 시작되지 않았으면 RECORDING_TRIP으로 이어 가 ETA·주행 종료 트리거로 노래를 만든다. 노래를 이미 시작한 주행(생성 중·READY·PLAYING·ARCHIVED·FAILED)은 다시 만들지 않는다 (주행당 한 번).
- 데모 타이밍: ETA ≤ 10분 트리거부터 도착까지 실제 시간은 약 20초다. 휴대폰 에뮬레이터 실측(fake 공급자): 믹서 수정(커밋 747f771, §7.4) 전에는 서버 잡이 26.6초 걸려 도착 약 10초 뒤 READY(자동 재생 없음, 알림만). 수정 후 잡 13~15초 → 기본 서버 설정에서는 앱의 세 번째 폴링(생성 시작 후 약 15초)에 READY, 도착보다 약 4초 앞. `FAKE_MUSIC_DELAY_SEC=0 JOB_POLL_INTERVAL_SEC=1`이면 첫째~둘째 폴링에 READY, 여유 약 10~15초. 촬영 절차와 서버 설정은 `docs/demo-scenario.md`. **실제 Claude 가사 + 실제 음악 공급자 + live 지오로는 이 시간 안에 끝나지 않는다** (도착 후 "노래가 곧 완성돼요" → 완성 알림 경로).

### 7.2 가사 생성 (LyricsProvider)
```kotlin
interface LyricsProvider {
    suspend fun generate(input: LyricsInput): Result<Lyrics>
}
// 구현: GleoLlmLyricsProvider (Gleo AI LLM SDK generateContent)  ← 미구현
//       RemoteLyricsProvider (서버 /lyrics → 외부 LLM)
//       FallbackLyricsProvider(list): 앞 구현이 실패하면 다음 구현으로
```
- 1순위 Gleo LLM SDK. `[확인 필요]` 공모전 기간 사용 승인 여부, 서버 모드 `registerApp` 필요 여부, 한국어 가사 품질.
- 실패하거나 사용할 수 없으면 서버 경로로 자동 전환.
- v0.2: Gleo는 Pleos 계정이 필요해 **구현하지 않았다**. `LyricsModule`이 `FallbackLyricsProvider(listOf(RemoteLyricsProvider))`를 바인딩하며, Gleo 구현은 이 목록 맨 앞에 넣는다. `Lyrics`는 `{text, title?}`.

**LyricsInput**: 서버에서 지명·날씨를 붙인 `EnrichedTrip` + 가족 구성원 호칭 목록 + 스타일 프리셋.
- 앱의 `LyricsInput.trip`은 네트워크 DTO(`EnrichedTripDto`)를 그대로 쓴다 (서버로 다시 보내므로 별도 도메인 타입 없음). 호칭은 이번 주행 탑승자의 nickname.

**서버 LLM (fallback 경로)**: `LlmProvider` 프로토콜(v0.4: `external` 속성, 외부 서비스면 대화를 보내지 않음, 없으면 외부로 봄), 구현은 `ollama`(v0.4, 로컬 `gemma3:12b`, `/api/chat`, `keep_alive: 0`으로 응답 뒤 GPU에서 내림, `num_ctx` 8192, 요청 상한 120초, 키 없음)와 `claude`(Anthropic 공식 SDK, 모델 `CLAUDE_MODEL` 기본 `claude-opus-5-5`, effort `medium`, thinking 파라미터 없음, 요청 타임아웃 120초, SDK 기본 재시도 2회 (엔드포인트 전체 상한 180초가 따로 있다, §9), 서버 측 거절 fallback `fallbacks="default"` + beta `server-side-fallback-2026-07-01`)와 `fake`(키 없이 고정 형식 가사).

**프롬프트 템플릿 (구현)**
```
너는 가족 여행 노래 작사가야. 아래 오늘의 드라이브 정보로 한국어 {style} 스타일 가사를 써줘.

[드라이브 정보]
- 출발: {originName}, 도착: {destinationName}
- 지나온 곳: {placeNames}
- 시간대: {timeOfDay}, 날씨: {weather}
- 이동 시간: {durationMin}분, 거리: {distanceKm}km
- 함께 탄 가족: {familyNicknames}

[규칙]
- 구조: [verse] 4줄 + [chorus] 4줄, 모두 8줄로 끝 (2절·두 번째 후렴·bridge 쓰지 않기)
- 한 줄 12음절 이내, 쉬운 단어, 아이가 따라 부를 수 있게
- 추임새·의성어(랄랄라, 라라라, 나나나, 오오, 우우, 예예) 쓰지 않기. 가족이 한 줄씩 나눠 부르니 모든 줄을 뜻 있는 말로 (v0.5, 2026-11-12)
- 지명 최소 2개, 가족 호칭 최소 1번 포함
- 실존 노래 가사·멜로디 인용 금지
- 출력은 섹션 태그가 포함된 가사 텍스트만 (설명·제목·코드 펜스 없이)

[출력 형식] 아래 틀 그대로, 태그 2개와 가사 8줄만:
[verse]
1줄 … 4줄
[chorus]
1줄 … 4줄
```
- (2026-11-11) **두 섹션(8줄)으로 줄였다.** 예전 네 섹션(16줄)은 60~90초 곡에 다 들어가지 않는다: 로컬 ACE-Step 75초 곡은 1절·후렴만 부르고 나머지는 허밍, 90초(최대)로 늘려도 마지막 후렴을 못 불렀다. 두 섹션은 75초에 2번 모두 8줄을 다 불렀다. gemma3:12b는 "두 섹션만"이라고만 하면 습관처럼 2절까지 써서 첫 응답 형식 통과가 2/6이었고, 출력 형식 틀을 보여 주니 6/6이 됐다.
- (v0.4) 대화가 있으면 첫 문장이 "오늘의 드라이브 정보와 차 안에서 나눈 가족 대화로"가 되고, 드라이브 정보 뒤에 `[차 안 대화]`("호칭: 말", 시간 순서, 2000자를 넘으면 고르게 솎음)와 규칙 "대화 속 인상적인 말을 최소 2개 그대로 또는 살짝 바꿔 넣기"가 붙는다 (§7.5). 괄호 두 개(섹션 제한, 코드 펜스 없이)는 2026-11-04 gemma3:12b가 대화 프롬프트에서 `[Bridge]`와 코드 펜스를 붙여 6번 중 6번 실패해서 넣었다 (넣은 뒤 6/6 통과).
- `{style}`: 고른 프리셋 이름 (동요 | 시티팝 | 어쿠스틱 | 신나는 로드트립). `{timeOfDay}`는 한국어(새벽/아침/…), `{weather}`는 "맑음, 11°C" 형식.
- 함께 탄 가족이 없으면 "없음"으로 쓰고 규칙은 "지명 최소 2개 포함"으로 바꾼다. 모르는 지명·날씨는 "알 수 없음".
- 출력 검사: 첫 줄이 섹션 태그, 태그 순서 정확히 verse/chorus, 섹션마다 4줄, 그 밖의 `[..]` 줄·코드 펜스 없음, 한글·영문·숫자·흔한 기호 밖의 문자 없음(v0.4: gemma3가 태국어 낱말을 섞음), 추임새 줄 없음(v0.5: "랄랄라"가 가사에 들어가 가족 목소리가 가사 대신 그 소리를 부름). 태그는 소문자로 정규화하고 번호는 뗀다(`[Verse 1]` → `[verse]`, v0.4). 전체를 감싼 코드 펜스 한 겹은 벗긴다(v0.4). 12음절 규칙은 검사하지 않는다. 검사 실패나 LLM 오류면 1회 재요청, 그래도 안 되면 502. 재시도를 포함한 전체가 180초(`DEADLINE_SEC`)를 넘기면 진행 중인 호출을 취소하고 504.
- 제목: 서버가 LLM이 아니라 직접 만든다 — `"<destinationName> 가는 길"` (도착지 이름이 없으면 null).

### 7.3 노래 생성 (서버 MusicProvider)
```python
class MusicProvider(Protocol):
    name: str
    async def submit(self, lyrics: str, style: str, duration_sec: int) -> str: ...   # provider job id
    async def poll(self, provider_job_id: str) -> MusicResult: ...                   # status, audio_url

@dataclass(frozen=True)
class MusicResult:
    status: MusicStatus            # pending | succeeded | failed
    audio_url: str | None = None   # http(s):// 또는 file:// (fake)
    error: str | None = None
    chorus_start_sec: float | None = None   # 첫 후렴 구간. 공급자가 줄 때만 (§7.4)
    chorus_end_sec: float | None = None
```
| 어댑터 | 엔드포인트 | 비용(2026 기준 공개가) | 비고 |
|---|---|---|---|
| `minimax` | fal `fal-ai/minimax-music/v2.6` (`FAL_KEY`) | 곡당 $0.15 (fal 모델 페이지 확인, 2026-10-29) | 가사 + 스타일 입력. 길이 입력 없음, 섹션 타이밍 없음. 더 싼 v2는 $0.03 |
| `mureka` | `POST /v1/song/generate` (model `auto`, n=1, `MUREKA_API_KEY`) | 곡당 약 $0.225 (V9.5 generate-song) `[확인 필요]` 가격 페이지 미확인 | 길이 입력 없음. 문서상 `lyrics_sections` 섹션 타이밍 제공. 확장 시 보컬 클론 ID 지원. V9.5 고정은 `mureka-9.5`로 가능 |
| `acestep` | fal `fal-ai/ace-step` (`FAL_KEY`) | 초당 $0.0002 (75초 약 $0.015, fal 확인) | 길이 입력이 있는 유일한 공급자. 셀프호스팅 검토 가능 |
| `fake` | 없음 (ffmpeg로 요청 길이의 화음 진행 mp3를 만들어 `DATA_DIR/fake_music/`에 캐시) | 0 | 키 없이 전체 흐름 동작. 서버 기본값 |
| `acestep_local` | 로컬 ACE-Step 1.5 REST API (`/release_task` → `/query_result` → `/v1/audio`, `ACESTEP_LOCAL_URL`) | 0 (자체 GPU) | 길이 지정, bpm·key 메타데이터, 섹션 시각 없음(CALL은 40% 규칙, 가족 목소리 변환을 거치면 노래 없는 빈 구간 §7.4). RTX 5070 Ti에서 75초 곡 약 11초(thinking 끔)/24초(켬). 서버 잡 전체(생성 + 클립 3개 믹싱) 14.2초 (2026-11-03). 측정·조건은 `docs/api-bench-report.md` §5-1. 사용자 청취: "괜찮다" |

- 어떤 공급자를 기본으로 쓸지는 **T1 벤치마크 결과로 결정** (`settings.MUSIC_PROVIDER`).
  - v0.2: 벤치마크 미실행(키 없음). 공개 정보 기반 **잠정안: 기본 mureka, 예비 minimax**, acestep은 저가 후보 (`docs/api-bench-report.md` §5). 서버 기본값은 측정 전까지 `fake`.
- 곡 길이: 60~90초 (생성 시간·비용 절감, 도착 직후 듣기 적당). 앱은 75초로 요청. minimax·mureka는 길이 입력이 없어 90초를 넘는 곡이 올 수 있다 `[확인 필요]`.
- 스타일 프리셋: `동요`, `시티팝`, `어쿠스틱`, `신나는 로드트립` (사용자는 프리셋만 고름).
  - API 값(앱 `SongStyle.wire` = 서버 `Style`): `kids`(동요) | `city_pop`(시티팝, 앱 기본) | `acoustic`(어쿠스틱) | `road_trip`(신나는 로드트립).
  - minimax·mureka는 공용 영어 스타일 프롬프트, acestep은 쉼표 구분 태그를 따로 쓴다.
  - 가사 섹션 태그는 minimax·mureka에 보낼 때 `[verse]` → `[Verse]`로 바꾸고, acestep에는 그대로 보낸다. `[확인 필요]` 공급자가 태그 대소문자를 구분하는지.
- 상업적 이용 약관은 출시 단계에서 공급자별로 다시 확인 (`docs/`에 기록).
- 원본 다운로드는 `DATA_DIR/songs/raw/`에 받고 믹싱 후 지운다. 완성 곡은 `DATA_DIR/songs/<songId>.m4a`.
- `[확인 필요]` fal 실패 요청 결과 응답 형식, 진행 중 상태 코드(200/202 모두 허용), mureka n=1 허용 여부. 실제 공급자 호출은 아직 한 번도 하지 않았다 (각 어댑터 docstring에 근거 문서 기록).

### 7.4 가족 클립 믹싱 (Mixer)
MVP는 **목소리 복제 없이 실제 녹음 클립을 노래에 삽입**한다.

| 클립 슬롯 | 예시 | 삽입 위치 |
|---|---|---|
| `INTRO` | "출발~!" | 곡 시작 전 0~2초 |
| `CALL` | "아빠 최고!" | 첫 후렴 직후 (없으면 곡 길이 40% 지점). 노래하는 구간을 알면 그 지점에 가장 가까운 노래 없는 빈 구간 |
| `OUTRO` | "도착했다!" | 곡 종료 직후 |

처리 순서 (ffmpeg):
1. 클립: 노이즈 게이트 → 앞뒤 무음 제거 → 라우드니스 정규화 (-16 LUFS)
2. 클립 구간 동안 배경 음악 **ducking** (-8dB, 페이드 150ms)
3. 전체 마스터 라우드니스 정규화 (-14 LUFS), AAC 192kbps m4a 출력
- 후렴 시작 시점은 공급자가 섹션 타이밍을 주면 그대로 쓰고, 없으면 고정 비율(40%)로 대체한다. `[확인 필요]` 공급자별 타이밍 메타데이터 유무.

**v0.2 구현 세부** (`server/app/mixer/mix.py`, `mix_song`)
- 정규화는 ffmpeg `loudnorm` 필터로 하지 않고 **측정 + 고정 게인**이다: 적분 라우드니스(LUFS)·트루 피크를 측정한 뒤 `volume=(목표 - I)dB`를 건다 (loudnorm 선형 모드가 조건에 따라 동적 게인으로 바뀌어 -8dB ducking을 망가뜨리기 때문). 마스터 게인 후 피크가 -1dBFS를 넘을 때만 `alimiter`를 건다.
- 측정은 `ebur128=peak=true` 요약(I, true Peak)으로 한다 (같은 BS.1770 라우드니스, loudnorm 측정보다 약 5배 빠름). ebur128은 무음을 -70 LUFS로 내므로 -70 이하는 -inf(무음)로 본다. 그래서 정리 후 약 0.4초보다 짧은 클립은 -70으로 측정되어 빠진다 (예전 loudnorm 측정은 남겼을 수 있음).
- 마스터 인코딩: AAC 192kbps (`-c:a aac -aac_ms 0 -b:a 192k`). M/S 스테레오 탐색을 끈 이유는 양 채널이 거의 같은 곡(fake 곡 등)에서 인코딩이 약 3배 느려졌기 때문 (fake 곡 약 16초). 측정·인코딩 변경으로 클립 3개 잡이 26.6초 → 13~15초 (커밋 747f771, 데모 타이밍 §7.1). 비트레이트(약 192~202kbps)·-14 LUFS 테스트는 그대로 통과.
- INTRO: 곡 시작을 `min(INTRO 길이, 2초)`만큼 늦춘다. 2초보다 긴 INTRO(클립은 최대 5초)는 곡 앞부분과 겹치고 그 구간은 ducking된다.
- CALL: 첫 후렴 **끝**(`chorus_end_sec`) 시점에 넣는다. 값이 없거나 (0, 곡 길이) 밖이면 40% 지점. `chorus_start_sec`은 받지만 쓰지 않는다. 곡 끝으로 자르지 않는다.
- (2026-11-11) CALL 빈 구간: 가족 목소리 변환을 거친 곡은 voice-lab이 원래 보컬의 노래 구간(`vocalPhrases`, 곡 기준 초)을 돌려준다. 믹서는 위 목표 지점에 가장 가까운, CALL이 앞뒤 0.3초 여유와 함께 통째로 들어가는 노래 없는 빈 구간으로 옮긴다 (INTRO와도 겹치지 않게). 그런 빈 구간이 없거나 노래 구간을 모르면(변환 없음·실패) 예전처럼 목표 지점에 넣고 ducking. 이유: ACE-Step은 섹션 시각을 주지 않아 40% 지점(75초 곡의 30초)이 1절 한가운데라 아이 한마디가 노래 위에 겹쳤다 (2026-11-11 숏폼 녹화에서 확인). 고친 뒤 같은 곡이면 노래가 시작되기 직전(전주 끝)에 들어간다.
- OUTRO: 곡 파일이 끝나는 시점에 시작, 음악과 겹치지 않으므로 ducking 없음. 결과 길이 = 곡 시작 지연 + 곡 길이 + OUTRO 길이.
- ducking: `sidechaincompress`가 아니라 프레임별 `volume` 표현식(사다리꼴, 150ms 경사)으로 정확히 -8dB.
- 클립 정리 파라미터 (`[확인 필요]`, 합성 음성 신호로만 조정): 게이트 threshold 0.01(약 -40dBFS), ratio 4, attack 5ms, release 100ms. 무음 제거 -45dB, 앞 무음 0.05초 유지.
- 게이트·무음 제거 후 소리가 남지 않은 클립(비었거나 ≤ -70 LUFS)은 건너뛴다. 프리마스터 전체가 무음이면 잡 FAILED. 클립이 없으면 곡만 마스터링한다.
- 내부 포맷 44.1kHz 스테레오 float, 출력 m4a는 `-movflags +faststart`.

---

### 7.5 대화 → 가족 목소리 노래 (v0.4, 2026-11-03 방향 전환)
주행 중 가족 대화를 담아 ① 대화 속 이야기로 가사를 쓰고, ② 대화 속 실제 한마디를 노래에 넣고, ③ AI 가수의 노래를 가족 목소리로 바꿔 **가족 모두가 나눠 부르게** 한다. 사용자 결정(11/03): 가족 전원이 노래에 담기고, 목소리는 **대화에서만 자동으로** 모으며, 대화는 '대화 담기'를 켜면 **주행 내내** 녹음한다.

**처리 순서 (서버, 노래 잡 하나 안에서)**

| 단계 | 내용 | 구현 (모두 자체 GPU, 외부 서비스 없음) | 시험 결과 (11/03, `docs/conversation-song-trial.md`) |
|---|---|---|---|
| 1 대화 받기 | 앱이 대화 녹음 조각(5분, AAC 모노 16kHz 32kbps)을 올린다 | 서버 `DATA_DIR/conversations/<tripId>/` 임시 저장 | — |
| 2 받아 적기 | 무음 기준 발화 분할 → Whisper large-v3-turbo (한국어) | voice-lab 서비스 | 글자 오류 5.7% (숫자 표기 차이 제외) |
| 3 누가 말했나 | 발화마다 ECAPA 화자 임베딩 → 구성원의 등록 클립(§8)과 코사인 비교, 가장 높은 사람. 기준(0.45 `[확인 필요]`) 미만은 '모르는 사람'으로 버린다 | voice-lab | 12/12 |
| 4 가사 | 대화 기록("호칭: 말") + EnrichedTrip → LLM, §7.2 형식 검사·재요청 그대로 | Ollama `gemma3:12b` (`LLM_PROVIDER=ollama`) | 8초 |
| 5 한마디 고르기 | 구성원마다 1~4.5초 발화(짧고 큰 소리 우선) → INTRO·CALL·OUTRO 클립 | voice-lab | — |
| 6 노래 생성 | §7.3. 가족이 부를 곡은 후보를 `SONG_CANDIDATES`곡 만든다 (9단계에서 고름) | ACE-Step (`acestep_local`) | 곡당 11~15초 (3곡 32초) |
| 7 보컬 분리 | 보컬 / 반주 두 갈래 | Demucs `htdemucs` (MIT) | 약 10초 |
| 8 목소리 변환 | 구성원마다 참고 목소리(≤30초)로 노래 목소리 변환 (f0 조건, 60 스텝. 30 스텝은 반주가 단출한 곡에서 기계음이 들려 2026-11-13 에 올렸다: 28초 → 40초/명. 정밀도 fp16 → fp32 는 결과 차이 0.14 dB 에 시간만 두 배라 그대로 둔다). 음역 맞춤: 옥타브 이동 k = round(log2(구성원 말소리 중앙값 ÷ 가수 중앙값)) × 12 — 옥타브 단위라 반주와 키가 유지된다 | Seed-VC (GPL-3.0 → 우리 코드와 분리된 별도 프로세스) | 유사도 0.23 → **0.69** (같은 사람 말소리끼리 0.82), 75초 곡 약 45초/명 |
| 9 나눠 부르기 | 보컬을 무음 기준 구절(가사 줄)로 나눠 줄마다 가족을 돌아가며 배정(참고 목소리 10초 이상인 사람만), 구절 경계 교차 페이드. v0.5(2026-11-12): 1.2초보다 짧은 조각(허밍·애드리브)은 차례 없이 가까운 줄을 부르는 사람에게 붙이고, 8초 넘게 이어지면 가장 조용한 곳에서 나눈다. 작곡 요청에 "리드 보컬 하나, 허밍·애드리브·코러스 없이, 짧은 전주"를 붙인다. 변환 음량은 참고 목소리 음량을 따라가서(같은 곡에서 원래 가수 대비 엄마 -7 dB·첫째 +7 dB) 구절마다 원래 보컬 음량(RMS)에 맞춘다(±12 dB 한도, 같은 사람 구절 경계는 배율도 교차 페이드). 쉼 없이 붙은 경계(나눈 곳, 숨 쉬는 틈 0.05~0.15초)에선 0.15초씩 넓게 섞으면 두 사람이 같은 음절을 같이 불러서, 틈이 0.3초보다 좁으면 틈 안에서만(붙은 곳은 가장 조용한 프레임 가운데서 0.04초) 넘겨준다. v0.6(2026-11-13) **후보 곡 고르기:** ACE-Step 은 리드 보컬을 두 겹(좌우가 다른 소리)으로 만드는 구간이 있고, 그대로 변환하면 두 사람이 한 가사를 같이 부르는 것처럼 들린다. 요청 문구("한 명만, 겹치지 않게, 울림 없이")로는 줄지 않았다. 같은 가사로 잰 겹친 시간 비율(보컬 좌우 상관이 0.9 미만인 0.5초 창): 시티팝 19~80%(14곡), 로드트립 18~35%(3곡), 어쿠스틱 1~18%(8곡). 그래서 후보 곡을 모두 보컬 분리해 보고 점수 = 겹친 비율 + 0.5 × 빠진 가사 줄 비율이 가장 낮은 곡으로 부른다 `[확인 필요: 기준값]`. **가사 없는 구절 빼기:** 줄(1.2초 이상)마다 원래 보컬을 받아 적어(Whisper) 가사와 비교한다 (두 글자 묶음의 30% 이상이 가사에 있으면 가사를 부른 줄). 서로 다른 글자가 2개 이하인 줄(후후후·라라라)과, 가사와 맞는 첫 줄보다 앞·끝 줄보다 뒤에 있으면서 안 맞는 줄(전주·후주의 흥얼거림)은 가족 목소리로 부르지 않고 뺀다. 가사 줄 사이에 낀 줄은 받아 적기가 틀렸을 수 있어 남긴다. voice-lab 은 누가 어느 구간을 불렀는지(`assignment`)를 돌려주고 기록에 남긴다 (영상 자막·확인용). 후렴 합창(여러 명 겹치기)은 시간이 남으면 | voice-lab | 후보 3곡: 분리·받아 적기 약 25초 추가 |
| 10 합치기 | 변환 보컬 + 반주 → §7.4 믹서 (한마디 클립 삽입, -14 LUFS) | 기존 mixer | — |

- **시간:** 가족 N명 기준 약 30초 + 45초×N (4명 약 3.5분). 후보 3곡이면 약 35초가 더 든다 (2026-11-13 실측, 3명·대화 86초: 분석·가사 14~22초, 작곡 3곡 32초, 고르기·변환 110~118초, 합계 158~172초). 실제 주행은 도착 10분 전에 시작하므로 충분하다. 1분 데모 시나리오는 맞지 않아 다시 설계한다 (T19).
- **참고 목소리:** 이번 주행 대화의 해당 구성원 발화 중 등록 클립과 유사도가 높고 소리가 큰 구간 순으로 최대 30초. 동의(§8.3)한 구성원은 주행마다 더 좋은 구간으로 갱신해 보관한다. 10초 미만이면 그 사람은 이번 노래를 부르지 않는다 (한마디 클립에는 들어갈 수 있음).
- **등록 클립의 새 역할:** 가족 등록 때 녹음하는 5초 클립(§8)은 '누구 목소리인지 알아보는 기준(음성 ID)'이다. 등록 클립이 없는 구성원은 대화에서 찾을 수 없다.
- **대체 경로:** 대화 없음·분석 실패 → v0.2 흐름(§7.1~7.4). 부를 수 있는 사람이 없음 → AI 가수 그대로 + 한마디 클립. 변환 실패 → AI 가수.
- **삭제:** 원본 대화와 받아 적은 글은 잡이 끝나면(성공·실패 모두) 지운다. 남는 것은 노래(가사 포함), 구성원별 참고 목소리(동의 시), 잡 메타데이터.
- **GPU 서비스 구성:** ACE-Step API(8001), Ollama(11434), voice-lab(새로 만듦, 8002 예정: 받아 적기·화자 식별·한마디·보컬 분리·변환·나눠 부르기). 서버는 Provider 인터페이스 `ConversationAnalyzer`·`VoiceConverter`로만 부르고 fake 구현을 둔다(키·GPU 없이 테스트). GPU 메모리 16GB를 나눠 쓰므로 voice-lab은 단계별로 모델을 올리고 내린다 `[확인 필요]`.
- **Pleos 플랫폼 대안:** STT SDK의 `AmbientAiApi`(화자 등록·인식·음원 분리)를 쓸 수 있으면 2~3단계를 차량에서 처리해 원본 대화를 서버로 보내지 않아도 된다 `[확인 필요]` (공개 문서 없음, 서버 모드·앱 등록 필요, §13-10).

**v0.4 구현 세부 (T17, 서버 `app/jobs/worker.py`, `app/providers/voice/`)**
- 대화 잡(`conversation: true`)만 ANALYZING을 거친다. 탑승자 = `enrichedTrip.passengers`(순서 유지). 그중 **대화 녹음 동의가 있는 구성원**만 찾는다(등록 클립 = 그 구성원의 모든 클립). 동의가 없거나 클립이 없는 사람의 말은 '모르는 사람'처럼 버려진다.
- 원본 대화는 **분석이 끝나는 즉시**(성공·실패 모두) DB 행과 파일을 지운다 (잡이 끝날 때까지 기다리지 않음). 서버 종료로 분석이 취소되면 남겨 두고 재시작 후 다시 분석한다. 받아 적은 글은 저장하지 않는다(메모리에서 가사 프롬프트에만 씀).
- 가사: 요청에 `lyrics`가 있으면 그대로, 없으면 §7.2 프롬프트 + 대화. 외부 LLM(`claude`)이면 대화를 빼고 주행 정보만 넣는다(§8.3). 두 번 다 실패하면 잡 FAILED (`lyrics generation failed`), 180초 상한.
- 한마디: `clipPlan`이 채운 슬롯이 우선이고 **빈 슬롯만** 대화 한마디로 채운다(INTRO→CALL→OUTRO, 한 사람에 하나). 순서는 clipPlan에 클립이 없는 구성원부터, 그다음 탑승 순서 (가족 모두가 노래에 담기게). 대화 잡에서 앱은 이번 주행에 녹음한 클립만 clipPlan에 넣는다(T18).
- 참고 목소리: 변환 동의가 있는 구성원은 이번 대화의 참고 목소리가 보관본보다 **같거나 길면** `DATA_DIR/voices/<memberId>.wav`로 바꾼다. **이번 대화가 10초 미만이어도 보관본이 있으면 부른다** (보관하는 이유).
- CONVERTING 직전에 구성원 삭제·변환 동의 철회를 다시 확인한다. 변환 실패(voice-lab 오류·시간 초과)면 AI 가수 그대로. `singers`는 실제로 구절을 부른 사람(READY 때만 응답).
- 작업 폴더 `DATA_DIR/work/<jobId>`(한마디·변환 파일)는 잡이 끝나면(READY·FAILED·삭제) 지운다. 한마디도 믹싱 직전에 그 구성원이 지워졌거나 대화 동의를 철회했으면 뺀다.
- 그 주행의 대화 잡이 생긴 뒤 오는 조각은 409로 거부한다(쓰이지 않고 남는 대화를 만들지 않게). 앱은 노래 생성이 시작되면 그 주행의 대화 담기를 멈춘다(T18). 잡이 실패해 앱이 재시도하면 대화는 이미 지워졌으므로 주행 정보 가사 + 보관된 참고 목소리로 만든다.
- 시간 (2026-11-04 실측, 공개 음성 대화 114초·화자 3명, RTX 5070 Ti): ANALYZING 23초(분석 + Gemma 가사) → GENERATING 12초 → CONVERTING 130초(3명) → MIXING 2초, 합계 167.6초. 앱의 잡 제한 5분(§7.1) 안이지만 4명·긴 대화면 넘을 수 있다 → T18에서 대화 잡 제한을 늘린다 `[확인 필요]`.
- `[확인 필요]` 노래를 만들지 않은 주행의 조각은 앱이 DELETE로 지운다(T18). 서버 쪽 보관 기한(예: 24시간 뒤 자동 삭제)은 아직 없다.

---

## 8. 가족 음성 클립 (VoiceClip)

- 녹음은 **정차 중에만** 가능 (`CarUxRestrictions`에서 제한 시 녹음 버튼 비활성).
- 구성원별로 슬롯(INTRO/CALL/OUTRO)마다 최대 3개 클립, 클립당 최대 5초.
- 녹음 직후 미리듣기 → 저장 → 서버 업로드(`/clips`).
- 한 번 녹음해두면 매 주행에 재사용하고, 주행마다 랜덤 또는 순서대로 고른다.
- `[확인 필요]` 차량 마이크 녹음 권한/품질. 안 되면 **폰 웹페이지(QR 접속)에서 녹음 업로드** 경로로 대체.

**v0.2 구현 세부**
- 5초 제한: 앱은 코루틴 타이머(5초 후 정지)로 자르고 (`MediaRecorder.setMaxDuration`은 정지 시 예외 위험으로 안 씀), 서버는 ffprobe 길이 5.0초 + 허용오차 0.3초까지 받는다. 서버 업로드 크기 상한 2MiB(413). ffprobe로 읽을 수 없거나 길이가 없는 파일은 400.
- 슬롯당 3개: 앱에서도 막고(아직 업로드 안 된 클립 포함, 3개면 녹음 버튼 비활성) 서버는 4번째를 409로 거부.
- 녹음 흐름: 정지 → 초안(미리듣기/저장/버리기) → 저장 시 Room에 넣고 바로 업로드. 녹음 중 주행 제한이 시작되면 녹음을 멈추고 초안으로 남긴다 (정차 후 확인). 너무 짧아 데이터가 없으면 버리고 안내. 구성원이 지워지면 그 구성원의 녹음 중/초안은 버린다.
- 업로드 상태: 클립은 PENDING → UPLOADED / FAILED. UPLOADED가 아닌 클립에는 "다시 업로드" 버튼 (프로세스 종료로 남은 PENDING 포함).
- 구성원 등록은 서버 우선: `POST /v1/members`가 성공해야 로컬에 저장한다 (구성원 추가에는 네트워크 필요).
- 클립 선택(`clipPlan`): 이번 주행 탑승자들의 **UPLOADED** 클립을 슬롯별로 모아 하나씩 고른다. 모드는 설정 `clipPlanMode` (가족·녹음 화면의 칩): `RANDOM`(기본) 또는 `IN_ORDER`(가장 오래전에 쓴 클립 우선, 동률이면 녹음 순서. 시계와 무관한 사용 순번). 고를 때마다 사용 기록을 갱신한다.
- 녹음 파일: MediaRecorder MIC, AAC m4a (§4.1), 앱 `filesDir/voice_clips/`. 업로드는 multipart `audio/mp4`, 파일명 `<uuid>.m4a`. 미리듣기는 오디오 포커스를 요청하지 않는다 (5초 이하).
- 폰 웹 QR 업로드 대체 경로는 **미구현**. 브라우저 MediaRecorder WebM/Opus는 컨테이너 길이가 없는 경우가 많아 현재 서버 검사에서 거부될 수 있다 `[확인 필요]`.

### 8.2 음성 명령 (Gleo Interface + SpeechToText, v0.3 추가)
화면을 만지지 않고 노래를 틀고, 주행 중에도 동승자가 목소리를 녹음한다.

| 명령 | 예시 발화 | 동작 |
|---|---|---|
| 노래 재생 | "드라이브 송 틀어줘" | 보관함 최신 곡부터 재생 (홈 재생 버튼과 같음) |
| 일시정지 | "노래 멈춰" | 재생 중이면 일시정지 |
| 목소리 녹음 | "둘째 응원 한마디 녹음해줘" | 해당 구성원·슬롯으로 5초 녹음 → 자동 저장·업로드 |

- **입력 경로 두 가지** (같은 `VoiceCommandHandler`로 처리):
  1. **Gleo Interface** (차량 AI 비서): `assets/schema.json`에 function 2개(`PLAY_DRIVE_SONG`, `RECORD_FAMILY_VOICE`, component `service`) → Gleo가 브로드캐스트로 호출 → `GleoActionReceiver` → 응답은 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` 브로드캐스트(`packageName`, `response` JSON `{functionName, status, message, data}`). 파라미터는 extra 키(`nickname`, `slot`)와 JSON 문자열 extra `ACTION_PARAM` 둘 다 읽는다. `[확인 필요]` Pleos의 schema 검토·승인 없이 에뮬레이터에서 호출되는지, 응답 브로드캐스트 대상 패키지.
  2. **"말로 하기" 버튼** (홈, 주행 중에도 표시): Pleos SpeechToText SDK `ON_DEVICE` 모드(앱 등록 불필요), `Locale.KOREA`, 한 번 듣기(최종 결과 또는 8초). 인식문을 `VoiceCommandParser`(core:model)로 해석: "녹음" → 녹음, "틀어/재생/들려" → 재생, "멈춰/정지/그만" → 일시정지. 호칭은 등록된 구성원 호칭 중 문장에 들어 있는 것(긴 것 우선), 슬롯은 "출발" → INTRO, "도착" → OUTRO, 그 밖에 CALL. STT를 쓸 수 없으면 "음성 인식을 쓸 수 없어요".
- **주행 중 녹음 허용 (음성 명령만)**: 손으로 하는 녹음 화면(§8 가족·녹음)은 그대로 정차 전용. 음성 녹음은 화면 조작이 없으므로 주행 중에도 허용한다 — 신호음 → 5초 녹음 → 미리듣기 없이 저장(Room, `recordedAtMs`) → 업로드. 홈에는 정적 한 줄 상태만 ("둘째 · 응원 한마디 녹음 중"). 정차 후 가족·녹음 화면에서 듣고 지울 수 있다.
  - 호칭을 못 찾으면: 이번 탑승자가 1명이면 그 사람, 아니면 실패 안내("누구 목소리인지 함께 말해 주세요"). 동의(§8.1)는 구성원 등록 때 이미 받았으므로 등록된 구성원만 녹음한다.
  - 슬롯이 가득(3개)이면 그 슬롯의 가장 오래된 클립을 지우고(서버 포함) 새 녹음을 넣는다.
  - 한 번에 하나: 처리 중에 들어온 명령은 "잠시 후 다시 말해 주세요".
- **이번 주행에 녹음한 목소리 우선**: clipPlan(§8)을 고를 때 슬롯마다, 이번 주행 시작 이후 녹음한 클립(탑승자 것)이 있으면 그중 가장 최근 것을 쓴다. 없으면 기존 규칙(랜덤/순서대로). 주행 시작 시각과 녹음 시각은 같은 Clock(데모에서는 시나리오 시각)을 쓴다. 노래 생성(도착 10분 전)이 시작된 뒤의 녹음은 다음 노래에 들어간다.
- 보안: 녹음 브로드캐스트는 export된 수신기라 다른 앱도 보낼 수 있다 → 등록·동의한 구성원만, 5초, 신호음과 홈 상태 표시로 알린다. `[확인 필요]` Gleo 발신 권한으로 수신기를 제한할 수 있는지.

### 8.1 동의·개인정보 (심사 필수 대응)
- 구성원 등록 시 음성 수집 동의 화면 (아동은 보호자 동의 체크).
  - 구현: 하나의 "구성원 추가" 폼 — 호칭 프리셋 칩(엄마, 아빠, 첫째, 둘째, 셋째, 할머니, 할아버지, 이모, 삼촌; 이미 쓴 호칭은 비활성) + "만 14세 미만 아이예요" 스위치 + 음성 수집 동의 체크, 아이면 보호자 동의 체크. 서버도 동의 누락을 400으로 거부한다. 동의는 members 행의 boolean으로 저장(별도 동의 시각 없음, `created_at`만). 호칭은 1~20자.
- 저장 기간: 사용자가 삭제할 때까지. 구성원 삭제 시 서버 클립 즉시 삭제.
  - 삭제는 서버 우선: 서버 DELETE가 네트워크/5xx 오류면 로컬도 지우지 않고 오류를 보여준다(재시도). 404는 이미 지워진 것으로 본다. 구성원 삭제는 확인을 묻고, 탑승자 선택에서도 뺀다.
  - **이미 믹싱된 노래는 구성원을 지워도 남는다.** 노래(서버 곡 파일·잡·가사)는 `DELETE /v1/songs/{id}` 또는 `DELETE /v1/songs`로 지운다 (§9). privacy.html §4·§6과 같은 정책.
- 음성은 **노래 믹싱 용도로만** 사용하고, MVP에서는 학습·복제에 쓰지 않음을 명시. 음성 클립은 외부 서비스로 보내지 않는다 (믹싱은 자체 서버).
- 위치 데이터: 서버에는 다운샘플링된 좌표만 전송하고, 곡 생성 후 30일 뒤 원본 좌표 삭제.
  - 서버: 좌표를 저장하지 않는다. 남는 것은 Nominatim 결과 메모리 캐시뿐(소수 3자리 반올림 좌표 → 지역, 최대 1만 건, 재시작 시 소멸).
  - 앱: 원본 위치 점(`trip_points`)은 **주행 종료 시각(끝나지 않은 주행은 시작 시각) 기준 30일**이 지나면 삭제한다. 기록 시작 시와 매 주행 종료 시 실행하며 기록 중인 주행은 건드리지 않는다. 다운샘플링된 요약(`trips.summaryJson`)과 곡 커버용 `songs.waypoints`는 남는다 → privacy.html에 "다운샘플링된 경유 좌표는 보관" 문구 반영 필요.
- 서버 `static/legal/`에 개인정보 처리방침과 위치정보 이용약관 페이지 제공. 시행일·책임자·연락처·사업자명·외부 공급자명은 아직 자리표시자이고, 위치정보법상 사업 신고 등 법률 검토 전이다.
- 설정 > "모든 데이터 삭제" (`AllDataDeleter`): ① 구성원마다 `DELETE /v1/members/{id}` 후 로컬 클립 (서버 실패한 구성원은 남기고 나머지는 계속 지운 뒤 실패를 표시) ② 노래: `DELETE /v1/songs`로 서버의 노래·잡·가사(진행 중 잡 포함)를 지운 뒤 로컬 노래 행·파일 (서버 삭제가 실패해도 로컬 노래는 지우고 실패를 표시, 다시 시도하면 다시 요청) ③ 기록 중인 주행을 뺀 모든 주행 (프로세스 종료로 열린 채 남은 주행 포함) ④ 클립 행이 없는 녹음 파일. 설정·디바이스 토큰은 초기화하지 않는다.
  - 시작 전: 재생을 멈추고 재생 목록을 비운다. 그다음 `SongOrchestrator.clearForDataDeletion` 안에서 삭제를 실행해, 만들던 노래를 취소한다 (재시도가 서버에서 지운 잡을 다시 만들지 않게, §7.1).
  - 휴대폰 에뮬레이터에서 확인: 삭제 후 서버 DB의 members·clips·jobs·songs가 모두 0, 서버 clips/·songs/ 파일과 앱 `files/songs`가 사라짐 (커밋 e2b22c1).

### 8.3 대화 담기·목소리 변환 동의 (v0.4)
- 구성원 등록 동의를 셋으로 나눈다. 아이는 각각 보호자 동의도 받는다.
  1. **목소리 클립** (필수, 기존 §8.1): 등록 클립 = 음성 ID, 한마디 삽입.
  2. **대화 녹음** (선택): '대화 담기' 중 이 사람의 대화를 녹음·받아 적어 가사와 한마디에 쓴다.
  3. **노래 목소리 변환** (선택): 이 사람의 목소리로 노래를 부르게 하고, 그 기준이 되는 참고 목소리(≤30초)를 보관한다.
- **'대화 담기'는 이번 탑승자 전원이 2에 동의했을 때만 켤 수 있다.** 등록하지 않은 사람이 탔는지 앱은 알 수 없으므로 켤 때 "등록하지 않은 사람이 타면 꺼 주세요"라고 안내한다. 서버는 등록 클립과 맞지 않는 목소리를 버린다(가사·한마디·참고 목소리에 쓰지 않음).
- 녹음 중에는 모든 화면 상단에 정적 배너 "대화 담는 중 · 끄기"를 띄운다 (주행 중에도 한 번 터치로 끌 수 있음).
- (T18 구현) '대화 담기'는 **이번 주행만** 켜지는 스위치다(저장하지 않음, 노래 생성이 시작되거나 앱이 다시 켜지면 꺼짐). 녹음은 주행 기록 포그라운드 서비스 안에서 5분 조각으로 하고, 조각마다 시작할 때 탑승자 전원 동의를 다시 확인한다. 올린 조각은 기기에서 바로 지우고, 노래 생성 시작 때 못 올린 조각도 지운다. 주행 중 '끄기'는 그 주행의 대화를 기기·서버에서 지운다. 노래 생성이 실패하거나 노래를 만들지 않는 주행(다른 노래를 만드는 중)의 대화도 지운다.
- 원본 대화·받아 적은 글: 잡이 끝나면 서버에서 삭제, 앱의 녹음 조각은 업로드 성공 즉시 삭제. 외부 AI 서비스로 보내지 않는다 (받아 적기·가사·작곡·변환 모두 자체 GPU 서버).
- 참고 목소리(3): 구성원 삭제·동의 철회 시 즉시 삭제. 그 가족의 노래에만 쓰고 다른 사람 목소리를 만드는 데 쓰지 않는다. '모든 데이터 삭제'(§8.1)에도 포함한다.
- privacy.html·location-terms.html에 대화 녹음, 목소리 변환, 보관 기간을 반영했다 (T20, 2026-11-04).
- `[확인 필요]` 법률 검토: 차 안 대화 녹음(탑승자 전원 동의 구조), 목소리 변환(음성권), 아동 목소리.

---

## 9. 서버 API (v1)

인증: 앱 최초 실행 시 `POST /v1/devices`로 디바이스 토큰 발급 → 이후 `Authorization: Bearer <token>`.
- 서버는 토큰의 해시만 저장한다. 토큰이 없거나 틀리면 401 + `WWW-Authenticate: Bearer`.
- 앱 `AuthInterceptor`: 토큰이 없으면 등록 후 요청한다 (동시 요청은 mutex로 한 번만 등록). `/v1/devices`가 아닌 요청이 401이면 다시 등록하고 **한 번만** 재시도한다. 등록 실패는 `IOException`으로 바꿔 OkHttp 스레드에서 프로세스가 죽지 않게 한다.
- 모든 JSON 필드는 camelCase. id는 접두어 + 16 hex (`dev_`, `mem_`, `clip_`, `job_`, `song_`).
- 다른 디바이스의 리소스는 없는 것과 똑같이 404.

| Method | Path | 설명 | 성공 |
|---|---|---|---|
| POST | `/v1/devices` | 디바이스 등록, 토큰 발급 | 201 `{deviceId, token}` |
| POST | `/v1/members` | 가족 구성원 등록 (호칭, 동의 여부) | 201 |
| DELETE | `/v1/members/{id}` | 구성원 및 클립 삭제 | 204 |
| POST | `/v1/clips` | multipart 업로드 (memberId, slot, audio) | 201 |
| DELETE | `/v1/clips/{id}` | 클립 삭제 | 204 |
| POST | `/v1/trips/enrich` | TripSummary → EnrichedTrip (지명·날씨) | 200 |
| POST | `/v1/lyrics` | (fallback) EnrichedTrip → 가사 | 200 / 502 / 504 |
| POST | `/v1/song-jobs` | 노래 생성 잡 생성 | 201 (GET과 같은 본문, `QUEUED`) |
| GET | `/v1/song-jobs/{id}` | 상태 조회 | 200 |
| GET | `/v1/songs/{id}/audio` | 완성 곡 스트리밍/다운로드 (`audio/mp4` m4a, Range 206 지원) | 200 |
| DELETE | `/v1/songs/{id}` | (v0.2 추가) 노래 1곡 + 그 잡(가사) + m4a 파일 삭제 | 204 |
| DELETE | `/v1/songs` | (v0.2 추가) 이 디바이스의 모든 노래·잡 삭제 (진행 중 잡 포함, 워커는 노래를 만들지 않고 멈춤). 앱 설정 > 모든 데이터 삭제가 호출 (§8.1) | 204 |
| GET | `/legal/privacy`, `/legal/location` | 심사용 약관 페이지 (HTML, 인증 없음) | 200 |

**오류 코드**

| 코드 | 의미 |
|---|---|
| 400 | 동의 누락(음성 수집 동의, 아동은 보호자 동의), 읽을 수 없는 오디오, 5초 초과 클립 |
| 401 | 토큰 없음/잘못됨 |
| 404 | 없는 리소스 또는 다른 디바이스의 리소스 (`clipPlan`의 남의 클립 포함) |
| 409 | 구성원·슬롯별 클립 3개 초과 |
| 413 | 클립 파일 2MiB 초과 |
| 422 | 요청 형식 오류 (필드 누락, 잘못된 slot·style, 빈 호칭, durationSec 60~90 밖, clipPlan 같은 slot 중복, 시간대 없는 시각 등) |
| 502 | `/v1/lyrics` 재시도 후에도 가사 생성 실패 (`{"detail": "lyrics generation failed"}`) |
| 504 | `/v1/lyrics` 재시도 포함 전체 180초 초과 (`{"detail": "lyrics generation timed out"}`) |

**POST /v1/members**: 요청 `{nickname(1~20자), isChild, voiceConsent, guardianConsent}` → `{memberId, nickname, isChild, voiceConsent, guardianConsent, createdAt}`.

**POST /v1/clips**: multipart `memberId`, `slot`(INTRO|CALL|OUTRO), `audio` → `{clipId, memberId, slot, durationSec, createdAt}`.

**POST /v1/trips/enrich**: 요청 = TripSummary (§6.3; waypoints 최대 50, 위경도 범위, 거리·시간·속도 ≥ 0, tripId 1~64자). 응답 = EnrichedTrip:
```json
{
  "...TripSummary 전체 필드": "...",
  "originName": "서울 중구",
  "destinationName": "강릉시",
  "placeNames": ["서울 중구", "용인시", "평창군", "강릉시"],
  "weather": {"description": "맑음", "temperatureC": 15.0}
}
```
- `destinationName`은 요청 값이 있으면 유지. `placeNames`: 출발·경유·도착 경로 위 8개 지점을 역지오코딩해 (시·도, 시·군·구) 단위로 중복 제거 (일반구는 시로 합침, 예: 수원시 장안구 → 수원시), 경로 순서, 최대 5개(처음·끝 유지). 짧은 주행은 3개보다 적을 수 있다. 표시 이름: 광역시 구는 "서울 중구", 세종은 "세종", 도의 시·군은 "강릉시".
- `weather`는 객체 또는 null (§6.1). 지오·날씨 실패는 해당 값만 비우고 200.
- `GEO_PROVIDER=live` + Nominatim이면 초당 1건 제한 때문에 한 번에 약 8~12초 걸린다.
- 시간 상한: 역지오코딩은 30초(`GEOCODE_DEADLINE_SEC`) 안에 끝난 지점만 쓰고 나머지는 취소한다 (Nominatim 대기열에서도 빠짐). 날씨는 15초(`WEATHER_DEADLINE_SEC`)에서 자른다. 늦은 값은 실패와 같이 비우고 200 → 최악 약 45초. Nominatim 1건/초 제한은 서버 전체가 공유하는 줄이라 동시 요청이 많을 때 이 상한이 의미가 있다.

**POST /v1/lyrics**: 요청 `{enrichedTrip, familyNicknames(최대 10, 각 1~20자, 빈 목록 허용), style}` → `{lyrics, title|null}` (§7.2). 두 번 모두 실패하면 502, 재시도 포함 180초를 넘기면 504.

**POST /v1/song-jobs 요청**
```json
{
  "tripId": "trip_20261101_0930",
  "lyrics": "[verse]\n...\n[chorus]\n...",
  "style": "city_pop",
  "durationSec": 75,
  "clipPlan": [
    {"slot": "INTRO", "clipId": "clip_a1"},
    {"slot": "CALL", "clipId": "clip_b2"},
    {"slot": "OUTRO", "clipId": "clip_c3"}
  ],
  "title": "강릉 가는 길"
}
```
- 검증: `tripId` 1~64자, `lyrics` 1~3500자 (공급자 중 가장 짧은 MiniMax 가사 제한), `style`은 §7.3 값, `durationSec` 60~90 정수(필수), `clipPlan` 선택(기본 []) 최대 3개·슬롯당 1개 (클립의 저장 슬롯과 계획 슬롯이 달라도 허용), `title` 선택 1~100자 (없으면 "오늘의 드라이브 송").

**GET /v1/song-jobs/{id} 응답**
```json
{
  "jobId": "job_123",
  "status": "QUEUED | GENERATING | MIXING | READY | FAILED",
  "songId": "song_456",
  "audioUrl": "/v1/songs/song_456/audio",
  "title": "강릉 가는 길",
  "error": null
}
```
- `songId`, `audioUrl`은 READY 전에는 null.
- 앱은 5초 간격으로 폴링하고, 최대 5분 동안 응답이 없으면 실패 처리한다. (앱: 잡 제출부터 5분. 서버: 공급자 제출부터 `JOB_TIMEOUT_SEC` 300초.)
- 앱 HTTP 타임아웃은 read 5분 (§4.1). 서버 쪽 상한은 그보다 짧다: `/v1/lyrics`는 재시도·SDK 재시도를 포함해 180초 후 504 `{"detail": "lyrics generation timed out"}` (공급자 1회 최악 = Claude 120초 × SDK 재시도라 상한이 없으면 5분을 넘을 수 있었다), `/v1/trips/enrich`는 약 45초 후 빈 값으로 200. 앱은 클라이언트 타임아웃 대신 JSON 오류나 부분 결과를 받는다.

---

### 9.1 v0.4 추가 API (대화, T17 구현)
| Method | Path | 설명 | 성공 |
|---|---|---|---|
| POST | `/v1/trips/{tripId}/conversation` | 대화 녹음 조각 업로드 (multipart `seq`(0 이상), `passengers`(탑승자 구성원 id, 폼 필드 반복, 1명 이상), `audio`). 탑승자 전원의 대화 녹음 동의를 서버도 확인 → 없으면 400, 남의 구성원 404. 5분+5초 초과·읽을 수 없는 오디오 400, 4MiB 초과 413. 같은 `seq`를 다시 올리면 바꿔 넣는다. 그 주행의 대화 잡이 이미 있으면 409. 응답 `{tripId, seq, durationSec}` | 201 |
| DELETE | `/v1/trips/{tripId}/conversation` | 올린 조각 삭제 (대화 담기를 끄고 지우기, 노래를 만들지 않은 주행). 없어도 204 | 204 |
| POST | `/v1/song-jobs` | 요청에 `conversation`(기본 false), `enrichedTrip` 추가. `conversation: true`면 `enrichedTrip` 필수(`tripId` 같아야 함), `lyrics` 선택(주면 그대로 씀). 아니면 `lyrics` 필수(v0.2). 탑승자 = `enrichedTrip.passengers`(남의 구성원 404), 가사 호칭은 서버가 구성원 호칭으로 채운다(`familyNicknames`는 받지 않음). 잡에는 좌표 없이 가사용 주행 정보만 저장. 제목 = `title` → "<도착지> 가는 길" → 기본 | 201 |
| GET | `/v1/song-jobs/{id}` | 상태에 `ANALYZING`(받아 적기·화자·가사), `CONVERTING`(목소리 변환) 추가. 응답에 `lyrics`(대화 잡은 분석 뒤, 그 전엔 null), `singers`(READY 때 실제로 부른 구성원 id, 그 밖엔 []) | 200 |
| PATCH | `/v1/members/{id}` | `conversationConsent`, `voiceConversionConsent` 변경 (보내지 않은 필드는 그대로). 변환 동의 철회 시 참고 목소리 즉시 삭제. 응답은 POST와 같은 구성원 본문 | 200 |
- `POST /v1/members` 요청·응답에 `conversationConsent`, `voiceConversionConsent` 추가 (기본 false). 아이는 등록 자체에 보호자 동의가 필수라 선택 동의도 그 아래에서 받는다 (앱이 동의마다 보호자 확인을 받는다).
- 잡 상태: `QUEUED → ANALYZING → GENERATING → CONVERTING → MIXING → READY | FAILED` (대화가 없으면 v0.2와 같은 순서, 부를 사람이 없으면 CONVERTING을 건너뜀).
- `DELETE /v1/members/{id}`는 참고 목소리도, `DELETE /v1/songs`(모든 데이터 삭제)는 이 디바이스의 대화 조각도 지운다.
- 기존 DB: 서버 시작 시 새 열만 자동 추가 (`add_missing_columns`, 기존 구성원은 선택 동의 false).

---

## 10. 화면 설계

| 화면 | 주행 중 | 정차 중 | 내용 |
|---|---|---|---|
| 홈 | ✅ 단순화 | ✅ | 진행 상태 카드(기록 중/생성 중/준비됨), 최근 노래 1곡 재생 버튼 |
| 재생 | ✅ | ✅ | 제목, 정적 커버(지도 썸네일), 재생/일시정지/다음 — 큰 터치 영역 |
| 노래 보관함 | ❌ | ✅ | 날짜·제목 목록, 가사 보기 |
| 가족 구성원·녹음 | ❌ | ✅ | 구성원 추가, 슬롯별 녹음/미리듣기/삭제, 동의 |
| 이번 주행 탑승자 | ❌ (출발 전 선택) | ✅ | 구성원 체크 (기본값: 지난번 선택) |
| 설정 | ❌ | ✅ | 스타일 프리셋, 자동 재생, 데모 모드, 데이터 삭제 |

- 주행 중 제한 상태에서 정차 전용 화면에 진입하면 "정차 후 이용할 수 있어요" 안내만 표시한다.
- 폭 대응: 분할 화면(절반 폭)에서는 홈/재생 화면을 1열 레이아웃으로 전환한다.
- 버튼 크기·간격·대비는 Pleos Connect UI Design Guide를 따른다.

**v0.2 구현 세부**
- 내비게이션: 왼쪽 NavigationRail 6개 항목(홈, 재생, 보관함, 가족·녹음, 탑승자, 설정). 정차 전용 화면도 주행 중 목록에 남고, 열면 안내만 보인다. 주행 제한 시뮬레이션이 켜져 있으면 모든 화면에 "주행 제한 시뮬레이션 중 · 끄기" 배너가 뜬다 (설정이 정차 전용이라 끌 수 있게).
- 1열 전환 기준 폭 600dp `[확인 필요]` Pleos 분할 화면 실제 폭.
- 홈: 상태 카드 = 주행 기록 꺼짐 / 대기 중 / 주행 기록 중 + 시작·끄기 버튼(주행 기록 서비스는 여기서 수동 시작), 노래 단계별 정적 한 줄("노래가 곧 완성돼요" 포함). 최근 노래 카드는 가장 최근 곡 제목, 곡이 없으면 재생 버튼 비활성. 재생 중이면 이 버튼이 "일시정지"(PauseIcon)가 되고 누르면 일시정지한다 (재생 화면으로 이동하지 않음). 홈 ViewModel이 시작 시 플레이어에 연결해 재생 상태를 받는다. (v0.3) "말로 하기" 버튼(주행 중에도 표시, 마이크 권한 필요)과 음성 명령 상태 한 줄(§8.2).
- 재생: 플레이어에 올라간 곡(없으면 최신 곡). 진행 막대·탐색 없음(정적 화면). "다음" = 대기열의 다음 곡. 홈·보관함·재생에서 재생하면 보관함 전체(최신순)를 고른 곡부터 대기열에 넣는다. 커버는 지도 SDK 없이 `songs.waypoints`로 Canvas에 그린다(격자 + 경로 선 + 출발·도착 점). 1열 레이아웃에서 커버는 (가용 높이 − 220dp)로 제한하고 제목은 2줄까지라 96dp 컨트롤이 화면에 남는다.
- 보관함: 최신순 날짜·제목 목록(정렬 전환 없음), 가사는 펼쳐 보기, 재생 시 대기열 구성 후 재생 화면으로.
- 가족·녹음: §8·§8.1의 추가 폼, 슬롯별 녹음/미리듣기/저장/버리기/다시 업로드/삭제, 클립 선택 모드 칩(랜덤/순서대로).
- 탑승자: 행 전체가 토글, 바꿀 때마다 바로 저장(확인 버튼 없음). 구성원이 없으면 "가족 등록하러 가기".
- 설정: 노래 스타일 칩(기본 시티팝), 도착하면 자동 재생(기본 켬), 노래에 맞춰 실내 조명 바꾸기(기본 켬, §6.5), 데모 모드 + 시나리오별 재생/정지, 목적지 프리셋(§6.1, "내비 연동 전 임시"), 주행 제한 시뮬레이션, 모든 데이터 삭제(확인 대화상자, 서버의 구성원·목소리·노래 포함, §8.1).
- 휴대폰 에뮬레이터(Medium_Phone_API_36)에서 모든 화면 렌더링과 주행 제한 시뮬레이션 전환을 확인했다 (§11 T6). 접근성: 탑승자 체크박스가 uiautomator 덤프에서 checkable=false, checked=false로 나온다 (화면에는 체크됨) → TalkBack 상태 전달 수정 필요.

**v0.4 화면 변경 (T18 구현, 2026-11-04)**
- 가족 추가 폼: 필수 목소리 클립 동의(문구에 '음성 ID' 용도 추가) + 선택 카드 2개(대화 녹음, 노래 목소리 변환: 수집 항목·목적·보관·삭제 설명). 아이는 보호자 동의 하나가 선택 항목까지 덮는다("위 항목(선택한 항목 포함)에 아이 대신 동의"). 동의 줄은 글자를 눌러도 체크된다(줄 전체가 56dp 터치 영역).
- 구성원 카드: "대화 녹음 동의", "노래 목소리 변환 동의" 스위치 → 바로 `PATCH` (서버 실패면 그대로 두고 안내).
- 탑승자 화면: **'대화 담기 (이번 주행)'** 카드. 탑승자가 있고 모두 대화 녹음에 동의했을 때만 켤 수 있다(아니면 "탑승자 모두가 '대화 녹음'에 동의해야 켤 수 있어요"). 켤 때 마이크 권한을 묻고 "등록하지 않은 사람이 타면 꺼 주세요"를 보여 준다. 조건이 깨지면(동의 안 한 사람을 탑승자로 고름) 바로 꺼진다.
- 모든 화면 상단 배너 (켜져 있는 동안): 녹음 중 "● 대화 담는 중 · 끄기", 출발 전 "대화 담기 켜짐 · 출발하면 녹음해요 · 끄기". 주행 제한 중에도 보이고 한 번 터치로 끈다.
- 홈 진행 상태: 대화 노래 대기 중 서버 단계를 보여 준다 — ANALYZING "차 안 대화를 가사로 옮기고 있어요", CONVERTING "가족 목소리를 입히고 있어요".
- 보관함: "부른 가족: 엄마 · 첫째" (완성 시점 호칭). 가사에서 대화에서 온 줄 표시는 하지 않았다.
- 주행 기록 알림: 대화 담기가 켜져 있으면 "가족 대화를 담고 있어요…". Pleos 상태 표시줄에도 마이크 사용 표시(초록)가 뜬다.
- Pleos 화면(2560×1440, 160dpi)에서 글자·버튼이 작던 문제 → 아래 UI 디자인으로 해결.

**UI 디자인 (2026-11-11, 사용자 선택)**
- 시안 5종(캔버스 https://claude.ai/artifact/Vwg4bF78BGeCxHk6GW1Jdh: 네이비 클래식, 나이트 드라이브, 샌드 패밀리, 한눈에, 가족 앨범) 중 사용자가 **5번 '가족 앨범' 구성 + 2번 '나이트 드라이브' 색**을 골랐다 (시안 6).
- 테마(`app/ui/theme/Theme.kt`): 늘 다크. 바탕 #0A1628, 카드 #12233A, 강조 카드 #13294A, 선 #1E3654, 글자 흰색·보조 #A9B8CC, 강조 하늘색 #36C2E8 (현대차 홈페이지의 네이비·하늘색 분위기, 로고·전용 글꼴은 쓰지 않음). 글자는 앱 영역(약 1700dp) 기준으로 기본의 약 1.6배(본문 26sp, 제목 30~56sp), 모서리 16~36dp.
- 셸: 왼쪽 큰 내비게이션(칸 112dp, 고른 화면은 남색 칸에 하늘색), 화면 위 배너는 알약 모양("대화 담는 중 · 끄기" 빨간 점, 주행 제한 시뮬레이션).
- 홈: ① 진행 상태 알약(원형 진행 n/5: 주행·대화 담기 → 가사 → 작곡 → 가족 목소리 → 완성) + 주행 기록 시작·끄기 ② 오늘의 노래(오늘 만든 곡이 아니면 '최근 노래', 경로 그림, 날짜·부른 가족, 큰 재생 버튼) ③ 우리 가족 노래 앨범(지난 3곡 카드, 누르면 그 곡부터 재생) + 탑승자 선택(정차 중만)·말로 하기. 정적 화면(애니메이션 없음).
- 다른 화면: 테마로 색·글자가 바뀌고, 여백 48/32dp, 버튼 72dp, 탑승자 줄 88dp로 키웠다. Pleos 에뮬레이터에서 6개 화면 모두 잘림 없이 확인.

---

## 11. 구현 Task (Claude Code 순서)

각 Task는 독립 PR/커밋 단위로 진행한다. **상태**는 2026-10-29 기준 (자세한 내용은 §16). 기기 확인은 모두 **휴대폰 에뮬레이터**(emulator-5554, Medium_Phone_API_36) + fake 공급자 서버로 했다. Pleos Connect 에뮬레이터는 쓰지 않았다.

### T1. 노래 생성 API 벤치마크 (최우선, 1일)
- `tools/api-bench/`: 샘플 한국어 가사 3종(동요/시티팝/어쿠스틱) × 공급자 3종 호출 스크립트.
- 기록 항목: 생성 시간, 비용, 한국어 발음 정확도(주관 1~5), 음질, 섹션 타이밍 메타데이터 유무, 실패율.
- **완료 기준**: `docs/api-bench-report.md`에 표와 결론(기본 공급자 1개 + 예비 1개) 작성.
- **상태: 부분 완료.** 도구(서버 어댑터 재사용, `--repeat`/`--include-fake`, ffprobe 실제 길이 기록)와 보고서 틀·채점 기준은 완성. **API 키가 없어 실행하지 않았다** — 결과 표는 비어 있고 결론은 공개 정보 기반 잠정안(기본 mureka, 예비 minimax).

### T2. 서버 골격
- FastAPI, settings, SQLite 모델(devices, members, clips, jobs, songs), Docker.
- `/v1/devices`, `/v1/members`, `/v1/clips` 구현 + pytest.
- **완료 기준**: `docker compose up` 후 업로드/삭제 테스트 통과.
- **상태: 부분 완료.** API·pytest 통과(로컬 uvicorn/TestClient). **Docker 미설치로 `docker compose up`은 실행하지 않았다** (compose의 `env_file required:false`는 Compose v2.24 이상 필요).

### T3. MusicProvider 어댑터 + 잡 워커
- base 인터페이스, T1에서 고른 공급자 2종 어댑터, Fake 어댑터(로컬 샘플 mp3 반환).
- `/v1/song-jobs` 생성·폴링, 상태 전이 테스트.
- **완료 기준**: Fake 어댑터로 전 과정 READY 도달 테스트 통과.
- **상태: 완료** (Fake로 READY 도달 테스트 통과). T1 미실행이라 3종(minimax/mureka/acestep) 어댑터를 모두 만들었고, 실제 공급자 호출은 한 번도 하지 않았다 (문서 기반, `[확인 필요]`).

### T4. Mixer
- 클립 정리 → ducking → 정규화 파이프라인, 슬롯별 삽입.
- **완료 기준**: 샘플 곡 + 클립 3개로 결과 파일 생성, 결과 라우드니스 -14±1 LUFS 확인 테스트.
- **상태: 완료** (ffmpeg 8.0.1에서 테스트 통과). 실제 공급자 곡·실제 차량 녹음으로는 미검증, Docker 이미지의 ffmpeg 미확인.

### T5. Enrich + 가사 fallback
- 역지오코딩(지명 다운샘플링 → 대표 지명 3~5개), 날씨 조회, `/v1/lyrics`.
- **완료 기준**: 샘플 TripSummary로 지명·날씨가 붙은 EnrichedTrip과 가사 생성.
- **상태: 완료** (fake 지오/LLM 테스트 + Nominatim·Open-Meteo 라이브 스모크 1회). Kakao·Claude 실제 호출은 키가 없어 미실행.

### T6. Android 프로젝트 골격
- 멀티모듈 구성(§5), Hilt, Compose, Room, Retrofit, 네비게이션.
- `UxRestrictionsMonitor`: `CarUxRestrictionsManager` 래핑 → `Flow<Boolean /*isRestricted*/>`.
- **완료 기준**: Pleos Connect Emulator에서 앱 실행, 주행 제한 상태 토글 시 UI 변화 확인.
- **상태: 휴대폰 에뮬레이터에서 확인 (Pleos 에뮬레이터는 미실행).** 설치·실행, 권한 대화상자(마이크·위치·알림), 전 실행 중 크래시 없음. "주행 제한 시뮬레이션" 스위치를 켜면 배너 + 설정·보관함·가족·녹음·탑승자가 "정차 후 이용할 수 있어요"로 바뀌고 홈이 단순화되며("이번 주행 탑승자 선택" 버튼 숨김), 배너 "끄기"로 원래대로 돌아온다. **확인 안 한 것**: 휴대폰에는 Car API가 없어 실제 `CarUxRestrictions` 값에 따른 전환은 미확인, 데모 모드 주행은 제한 상태를 바꾸지 않음(§3.2), 릴리스 빌드(minify) 미검증.

### T7. 차량 데이터 래퍼 + 데모 모드
- `VehicleDataSource`, `NavigationDataSource`, `LocationDataSource`의 Pleos/표준/Fake 구현.
- Fake 시나리오 JSON(서울→강릉, 동네 마트) 재생기.
- **완료 기준**: 데모 모드로 3시간 주행을 60초 안에 재생하며 TripSummary 생성.
- **상태: 완료.** 서울→강릉 약 54.5초 재생 + TripSummary 생성 테스트 통과. 휴대폰 에뮬레이터에서 데모 모드(서울→강릉, 동네 마트)를 반복 재생했다. Pleos 구현은 SDK가 없어 생략, 표준 구현(CarPropertyManager·LocationManager·목적지 프리셋)은 기기에서 미실행.

### T8. TripRecorder 서비스
- Foreground Service, 수집 정책(§6.2), Room 저장, 주행 종료 판정.
- **완료 기준**: 데모 모드 주행 후 DB에 요약 저장. CPU 사용량 측정 결과 기록.
- **상태: 부분 완료.** 데모 주행 → DB 요약 저장은 Robolectric 테스트로 확인. 휴대폰 에뮬레이터에서 포그라운드 서비스(홈 "주행 기록 시작")로 데모 주행을 기록해 노래 생성까지 이어졌다. **CPU 사용량 미측정**. 측정 방법: 기기/에뮬레이터에서 데모(또는 실주행) 중 `adb shell top -m 10 -d 5 -n 24 | grep com.drivesong`, `adb shell dumpsys cpuinfo | grep com.drivesong` (데모는 10Hz 프레임 처리가 더해지므로 실주행 값이 대표값).

### T9. 가족 구성원·녹음
- 구성원 CRUD, 동의 화면, 슬롯별 녹음(정차 전용), 업로드.
- **완료 기준**: 에뮬레이터 녹음 → 서버 업로드 → 삭제까지 확인. 제한 상태에서 녹음 버튼 비활성.
- **상태: 완료 (휴대폰 에뮬레이터).** 아이 구성원 추가: "만 14세 미만"을 켜면 보호자 동의 체크가 나타나고 두 동의를 모두 체크해야 등록이 켜짐, 서버 `POST /v1/members 201`. 녹음 → 미리듣기(MediaPlayer 재생 확인) → 저장 → `POST /v1/clips 201`(서버 파일 생성) → 삭제 `DELETE /v1/clips/{id} 204`(서버 파일 삭제, UI 0/3). 제한 중에는 가족·녹음 화면 전체가 안내로 바뀌어 녹음 버튼에 닿을 수 없다. **확인 안 한 것**: 녹음 도중 제한이 시작되는 경우는 코드 리뷰·단위 테스트로만 확인. 에뮬레이터 마이크는 거의 무음(-70dB 안팎)으로 녹음되어 믹서가 클립을 건너뛴다 (호스트 오디오 입력을 켜야 목소리가 들어감). 새 마이크의 첫 녹음이 1.4초만 된 적이 있다(준비 지연). 실제 차량 마이크는 미확인 (§13-3).

### T10. LyricsProvider + SongOrchestrator
- Gleo LLM 구현(가능 시) + Remote 구현, 상태 머신(§7.1), ETA 트리거, 재시도.
- **완료 기준**: 데모 모드에서 도착 전에 READY 도달, 도착 판정 시 자동 재생 이벤트 발생.
- **상태: 완료 (휴대폰 에뮬레이터 + fake 서버).** 단위 테스트(ScenarioPlayer + Fake 소스 + TripRecorder + SongOrchestrator)에 더해 기기 E2E: 서울→강릉은 처음에 실패(서버 잡 26.6초, 도착 약 10초 뒤 READY, 자동 재생 없음) → 믹서 수정(§7.4) 후 기본 서버 설정에서 READY 54.8초 / 도착·자동 재생 58.6초, 깨끗한 설치 리허설에서 READY 40.7초 / 자동 재생 56.4초 (media_session PLAYING, 제목 "강릉 경포해변 가는 길"). 동네 마트: READY 18.6초, 자동 재생 22.3초 (주행 제한 시뮬레이션을 켠 상태에서도 동일). 보관함에 곡과 가사(탑승자 호칭 포함) 표시, 잡 clipPlan에 INTRO/CALL/OUTRO 3개. 믹스에 클립이 들어가는지는 서버 쪽 클립 파일을 들리는 합성 톤으로 바꿔 확인했다 (에뮬레이터 마이크가 무음이라). **확인 안 한 것**: 실제 공급자 타이밍(여유가 얇음, §7.1), Gleo 미구현.

### T11. PlaybackService + 재생/보관함 UI
- Media3 세션, 오디오 포커스(내비 음성 시 ducking, 포커스 상실 시 일시정지), 보관함.
- **완료 기준**: 재생 중 다른 앱 포커스 요청 시 정책대로 동작.
- **상태: 부분 완료 (휴대폰 에뮬레이터에서 ducking·일시 손실 확인).** 포커스 정책은 ExoPlayer 내장 처리(`handleAudioFocus=true`): 일시 손실(ducking 허용) 시 볼륨 0.2배, 일시 손실 시 일시정지 후 GAIN에 재개, 영구 손실 시 일시정지. 기기 확인: 알림 소리(GAIN_TRANSIENT_MAY_DUCK) → `LOSS_TRANSIENT_CAN_DUCK`, 볼륨 1.0→0.2 ducking 후 복귀, 재생 유지. 수신 전화(`adb emu gsm call`) → `LOSS_TRANSIENT`로 일시정지, 통화 취소 후 자동 재개. 재생·보관함 화면 렌더링 확인. **확인 안 한 것**: 영구 손실(휴대폰 에뮬레이터에 포커스를 가져갈 다른 미디어 앱이 없어 Robolectric `AudioFocusPolicyTest`로만), 실제 차량/Pleos 포커스 정책(내비 음성), 미디어 센터 탐색(§13-5).

### T13. Pleos Connect 에뮬레이터 + SDK (v0.3 추가)
- Pleos Connect Emulator 2.1.0 설치(Android 34 Automotive, `system-images;android-34;pleos_car_emulator;x86_64`, AVD `Pleos_Connect_v2.1.0`, RAM 4GB, cold boot), Pleos Maven 저장소·SDK 의존성 추가.
- **상태: 완료 (2026-11-03).** 에뮬레이터 설치·부팅(약 30초)·앱 설치·실행, 서울→강릉이 아닌 동네 마트 데모로 E2E(주행 → 가사 → 잡 → 도착 자동 재생) 확인. 새 SDK 도구(Android CLI)는 사용자 저장소를 읽지 않아 이미지 zip을 직접 받아(SHA-1 확인) SDK 폴더에 풀었다. 앱은 사용자 10(운전자)으로 돈다 → adb 권한 부여는 `pm grant --user 10`.
- 에뮬레이터에서 확인한 사실: Pleos 시스템 서비스(`ai.pleos.playground.service.vehicle` 2.3.22, `...service.navi` 3.0.4, `...service.location.fused`, `ai.pleos.playground.caas` 4.2.10, Gleo AI) 존재. `pleos.car.permission.CAR_LIGHTS`/`CONTROL_CAR_LIGHTS`는 설치 시 자동 허용(normal). STT는 `pleos.car.permission.STT_SERVICE` 선언이 없으면 initialize 가 SecurityException. 화면 2560×1440 160dpi, 왼쪽 차량 위젯과 분할 화면으로 앱이 오른쪽 약 2/3에 뜬다 → 글자·버튼이 작게 보임(UI 리디자인 때 반영). 시스템 미디어 위젯(왼쪽 아래)은 우리 노래 재생 중에도 "재생 중인 항목 없음" `[확인 필요]` §13-5.

### T14. 도착 조명 연출 (§6.5)
- **완료 기준**: 정차 중 재생 시 조명 변경·멈춤 시 복원·주행 제한 시 미적용 단위 테스트, 에뮬레이터에서 동작 확인.
- **상태: 완료 (Pleos 에뮬레이터).** 단위 테스트(`LightShowDirectorTest` 5건). 에뮬레이터: 도착 자동 재생 순간 `color #B84DFF on 1 areas, changed=true`, 일시정지 시 `restored 1 areas: [FFFFFF/ON]` (원래 흰색·켜짐으로 복원). 조명 변화는 에뮬레이터 화면에는 보이지 않아 로그로 확인 (지원 영역 1개). `[확인 필요]` 실차에서 색 int 해석.

### T15. 음성 명령 (§8.2)
- **완료 기준**: 명령 해석·녹음(슬롯 교체 포함)·이번 주행 클립 우선 단위 테스트, 에뮬레이터에서 Gleo 브로드캐스트(adb로 흉내)·"말로 하기" 버튼 동작 확인.
- **상태: 완료 (Pleos 에뮬레이터, 실제 음성 발화는 미확인).** 단위 테스트: `VoiceCommandParserTest` 8, `ClipPlannerTest` +2, `VoiceRepositoryTest` +4, `VoiceCommandHandlerTest` 12, `GleoActionsTest` 6. 에뮬레이터:
  - Gleo: `am broadcast --user 10 -a RECORD_FAMILY_VOICE -p com.drivesong --es nickname 둘째 --es slot CALL` → 5초 녹음·저장·업로드(`POST /v1/clips 201`), 다음 노래 clipPlan에 포함. 응답 브로드캐스트는 Gleo AI(GAA)가 받는다 (`processAppActionResponse`). adb로 보낸 호출이라 GAA가 대화 중이 아니어서 "Reject"로 기록됨 — 실제 음성 호출(스키마 등록·승인)은 미확인.
  - "말로 하기": Pleos 차량 내 STT(ON_DEVICE)가 시작돼 8초 듣고 종료, 화면 "듣고 있어요…" → "잘 못 알아들었어요" (에뮬레이터 마이크 무음). 실제 한국어 인식 결과는 호스트 마이크 입력으로 다시 확인 필요.
  - 데모 영상(62초 화면 녹화)으로 Gleo 녹음 → 데모 주행 → 도착 자동 재생 + 조명 흐름 확인.
- 실제 목소리 믹싱 확인 (2026-11-03): Wikimedia Commons Lingua Libre 한국어 녹음 4개(화자 4명, CC0 2·CC BY-SA 4.0 2)를 4인 가족 클립으로 서버 API에 올려 노래 2곡 생성 → INTRO(곡 앞), CALL(후렴 끝 37.5초 + 시작 지연, ±5dB 변화 약 2초), OUTRO(곡 끝 뒤) 모두 들어감, 곡 길이 76.3/76.6초.
- 노래 한 곡의 목소리 슬롯은 3개(INTRO·CALL·OUTRO, 슬롯당 1개)라 4인 가족 전원이 한 곡에 다 들어가지는 않는다 → **3개 유지로 결정** (사용자, 2026-11-03).

### T16. voice-lab GPU 서비스 (§7.5 2·3·5·7·8·9단계, v0.4)
- 자체 HTTP 서비스(FastAPI, 8002): 대화 분석(발화 분할·Whisper 받아 적기·ECAPA 화자 식별·한마디 고르기·참고 목소리 고르기), 노래 변환(Demucs 보컬 분리·Seed-VC 변환·옥타브 이동·구절 나눠 부르기·반주 합치기). Seed-VC는 별도 설치·별도 프로세스로 호출(GPL 분리).
- **완료 기준**: 공개 음성 대화로 화자 정확도·글자 오류율, 변환 유사도가 11/03 실험 수준(12/12, CER 5.7%, 유사도 0.69) 재현. GPU 없이 도는 단위 테스트(분할·배정·옥타브 계산).
- **상태: 완료 (2026-11-03).** `voice-lab/` (FastAPI, `voicelab/` 패키지, README). 단위 테스트 20건(GPU 없음). HTTP API로 `eval/trial.py` 실행: 화자 12/12, CER 12.2%(숫자 표기 제외 5.7%), 3명 나눠 부르기(구절 16개: 6·5·5), 옥타브 자동(-12·-12·0), 변환 유사도 엄마 0.73·아빠 0.64·첫째 0.81 (원곡 0.17~0.22), 분석 + 3명 변환 약 3분. 음높이는 pYIN(가수 219.9Hz ≈ RMVPE 221Hz). 서버와 같은 PC에서 파일 경로로 주고받는다 (다른 PC면 업로드 방식 필요 `[확인 필요]`). 분석이 끝나면 Whisper·ECAPA를 GPU에서 내린다.

### T17. 서버 대화 파이프라인 (§7.5, §9.1)
- 대화 업로드·삭제 API, 구성원 동의 필드, `ConversationAnalyzer`·`VoiceConverter` Provider(voice-lab | fake), `ollama` LLM Provider(대화 기반 가사), 잡 상태 ANALYZING·CONVERTING, 대체 경로, 원본 삭제·참고 목소리 보관.
- **완료 기준**: fake로 전체 흐름 READY 테스트, 실제 GPU 서비스로 E2E 1회, pytest 통과.
- **상태: 완료 (2026-11-04).** 서버 테스트 155건 통과 (새 `tests/test_conversation.py` 24건: 동의·PATCH·참고 목소리 삭제, 조각 업로드 검증·교체·삭제·409, 잡 검증, fake 전체 흐름 READY, 상태 ANALYZING·CONVERTING 관찰, 분석·변환·가사 실패 대체 경로, 외부 LLM 차단, 보관 참고 목소리 갱신 규칙, DB 열 자동 추가, Ollama·voice-lab 어댑터 요청 형식). 실제 GPU E2E (`MUSIC_PROVIDER=acestep_local LLM_PROVIDER=ollama VOICE_PROVIDER=voicelab`, data-pleos DB에 열 자동 추가 확인): 공개 음성 대화 114초·3명 → READY 167.6초, 3명 모두 부름, 한마디 3개, 곡 80.2초, 원본 대화·작업 폴더 삭제, 참고 목소리 3개 보관(20~27초). 처음 두 번은 gemma3 출력 형식(`[Verse 1]`, 코드 펜스, `[Bridge]`) 때문에 가사 실패 → §7.2 검사·프롬프트 보완. 시험 대화가 뉴스 낭독 문장이라 가사 내용은 어색하다 → 실제 가족 대화로 다시 확인 필요.

### T18. 앱 대화 담기 (§8.3, §10 v0.4)
- 녹음(TripRecorder 포그라운드 서비스 안, 5분 조각, AAC 모노 16kHz 32kbps), 조각 업로드·삭제, 동의 3종 UI, 탑승자 '대화 담기' 스위치, 상단 배너, 오케스트레이터 연동(대화 있으면 `conversation: true`), 보관함 '부른 가족'.
- **완료 기준**: 단위 테스트, Pleos 에뮬레이터 E2E (에뮬레이터 마이크는 호스트 오디오 입력).
- **상태: 완료 (2026-11-04).** 단위 테스트 392건 통과(디버그·릴리스 합산, 이전 343): 녹음기(5분 조각·업로드 순번·탑승자·동의 없으면 안 담음·마이크 권한·주행 중 끄면 삭제 후 다시 켜면 이어서·업로드 실패 재시도 후 기기에서 삭제·이전 프로세스 조각 정리), 오케스트레이터(대화 잡 요청 형식·서버 가사·부른 가족·이번 주행 클립만·서버 단계 표시·10분 제한·실패 시 대화 삭제·노래 안 만든 주행 삭제), DB v5→v6 마이그레이션(가족·클립·노래 유지), 동의 PATCH, 클립 선택 '이번 주행만', 네트워크 형식(탑승자 반복 필드, 가사 비우면 안 보냄), 모든 데이터 삭제가 대화도 끔·삭제.
  Pleos 에뮬레이터 E2E (서버 `acestep_local + ollama + voicelab`): 앱 재설치 후 기존 가족·클립 유지(마이그레이션) → 동의 스위치 2개(PATCH 200) → 탑승자 '대화 담기' 켬(배너) → 주행 기록 시작(포그라운드 서비스 타입 location|microphone, `types=0x88`) → 서울→강릉 데모: "● 대화 담는 중" 배너, `files/conversation/<trip>/0.m4a` 녹음 → ETA 트리거에 조각 업로드(201) → enrich → `conversation: true` 잡 → voice-lab 분석 → READY 23초 → 서버·앱의 대화 파일 모두 삭제, 대화 담기 자동 꺼짐, 홈 "노래가 준비됐어요". **에뮬레이터 마이크(호스트 입력)는 무음이라** 말이 없어 주행 정보 가사 + AI 가수로 끝났다(대체 경로 확인). 가족 목소리까지는 T19에서 데모 대화 파일로.
  구현: `core:data` ConversationRepository(켜짐·녹음 중 상태, 조각 파일, 업로드·삭제), `feature:trip` ConversationRecorder(+ ChunkRecorder: MediaRecorder AAC 모노 16kHz 32kbps, ConversationCapture 인터페이스), TripRecorderService(대화 담기가 켜지면 microphone 타입, Android 14 백그라운드 제한 때문에 화면에서 켤 때 바뀐다), SongOrchestrator(finish → 대화 잡, 실패·건너뛴 주행 discard), DB v6(MIGRATION_5_6).
  결정: '대화 담기'는 저장하지 않는 **이번 주행 스위치**(앱을 다시 켜거나 노래 생성이 시작되면 꺼짐). 주행 중 끄면 그 주행 대화를 기기·서버에서 지우고, 다시 켜면 그 뒤부터 담는다. 대화 노래 앱 제한 시간 10분(일반 5분).

### T19. 데모 모드 대화 + 데모 타이밍
- 데모 시나리오에 미리 녹음한 대화 파일(공개 음성)을 넣어 마이크 대신 재생·업로드. 변환 시간(약 3분)에 맞춰 데모 타이밍 재설계 (생성 시작을 앞당기거나 영상 편집).
- **완료 기준**: 서울→강릉 데모로 가족 목소리 노래까지 끊김 없이 재현.
- **상태: 완료 (2026-11-04).** 절차·타임라인은 `docs/demo-scenario.md` §7.
  - 데모 음성: Zeroth-Korean(CC BY 4.0) 화자 3명을 목소리 높이로 엄마(147)·아빠(118)·첫째(105)로 정하고, 뉴스 낭독 중 무난한 문장만 골라 94초 대화(9번 주고받기) + 등록 클립 4.9초씩 (`tools/demo-conversation/build_demo.py`, 출처 `assets/demo/NOTICE.txt`). voice-lab 화자 10/10.
  - 앱: 데모 모드면 대화 조각을 마이크 대신 데모 대화로 쓴다 (`ModeSwitchingChunkRecorder` → `DemoChunkRecorder`). 설정 > 데모 모드 > **데모 가족 불러오기**: 엄마·아빠·첫째를 모든 동의 + 공개 음성 등록 클립으로 등록하고 탑승자로 고른다(같은 호칭은 지우고 새로, 확인 대화상자). 데모 가족 클립은 녹음 시각 0(데모 시계와 실제 시각이 달라 '이번 주행 녹음'으로 잘못 치지 않게).
  - 타이밍: 대화 담기를 켜고 데모를 재생하면 도착 전 10분을 2배속(약 5분)으로 재생 (`ScenarioPlayer.CONVERSATION_FINALE_SPEED_UP`). 실제 주행은 도착 10분 전에 시작하므로 처리 시간(약 3분)이 남는다 — 데모가 그 관계를 그대로 보여 준다. 1회차(3배속, 약 200초)는 화면 녹화·에뮬레이터와 GPU를 함께 써서 트리거부터 READY까지 218초 → 도착 뒤 완성(알림) → 2배속으로 늘림.
  - Pleos 에뮬레이터 E2E (2회차, 서버 `acestep_local + ollama + voicelab`, 화면 녹화 중): 트리거 0:38 → 대화 분석 0:44 → 작곡 1:15 → 가족 목소리 1:28 → **READY 3:26 (트리거부터 168초, 도착 전)** → **도착 5:29 자동 재생**. 엄마·아빠·첫째 셋 다 부름, 한마디 INTRO 아빠·CALL 엄마·OUTRO 첫째(모두 대화 속), 가사에 대화 내용("어린이집 방학", "에우노토사우루스", "짜장, 짬뽕, 라면"). 서버 대화 조각·작업 폴더 삭제 확인. 편집 영상(대기 구간 10배속, 168초)과 완성곡을 사용자에게 보냄.
  - 단위 테스트 398건 통과(디버그·릴리스 합산): 데모 조각이 번들 대화와 같음, 데모 가족 등록(동의·클립·탑승자·같은 호칭 교체·녹음 시각 0), 대화 데모 재생 창 280~310초.
  - 데모 대화 내용 (사용자 결정 2026-11-04: **A안, 가족이 직접 녹음**): 녹음 가이드·대본 `docs/demo-family-recording.md`(서울→강릉 29줄, 약 2분 30초~3분, 사람마다 15~22초, 짧은 외침은 한마디 슬롯용). 받은 파일은 `tools/demo-conversation/make_family_assets.py` 로 git 밖 `android/demo-private/assets/demo-family/`(대화·음성 ID·`family.json`)에 두고 `--check` 로 voice-lab 화자 구분을 미리 본다(공개 음성으로 시험: 11/11 발화 정확). 앱 모듈이 그 폴더를 assets 에 넣고, 데모 가족 불러오기·데모 대화가 `demo-family/` 를 먼저 쓴다(없으면 공개 음성 `demo/`). 가족 목소리는 기본 비공개: GitHub·제출 APK 는 이 폴더 없이 만든다.
  - 남은 것: 가족 녹음 파일 받기 → 반영·에뮬레이터 재현. 화면 녹화에는 소리가 없다(영상 편집 때 완성곡을 따로 넣는다). Pleos 왼쪽 계기판은 데모 차속을 따르지 않는다(데모 모드는 앱 안 데이터).

### T20. 기획서·개인정보 문서·영상 콘티 갱신 (v0.4 방향)
- **완료 기준**: `docs/proposal/proposal.md`, privacy.html, 영상 시나리오가 §7.5·§8.3과 일치.
- **상태: 완료 (2026-11-04).** 기획서 v0.3(구현 현황 11/04: 대화 → 가족 목소리 노래가 앱·서버·에뮬레이터에서 동작, 실측 수치, 개인정보 설계를 구현 기준으로). 개인정보 처리방침 다시 씀: 항목에 대화 녹음·참고 목소리·동의 3종, 목적(목소리 클립 = 음성 ID, 대화 → 가사·한마디, 참고 목소리 → 노래 목소리 변환), '대화 담기' 운영 방식(이번 주행만·전원 동의·표시·끄면 삭제·미등록 목소리 미사용), 보유 기간(원본 대화 분석 직후 삭제, 받아 적은 글 저장 안 함, 참고 목소리 철회 시 즉시 삭제), 외부 전송은 좌표(지명·날씨)뿐. 예전 문구 '목소리 복제에 쓰지 않는다'는 목소리 변환과 맞지 않아 '그 가족의 노래에만, 학습에 쓰지 않는다'로 바꿨다. 좌표 보관도 구현에 맞춤(서버 저장 안 함, 기기 원본 30일) — 위치정보 이용약관 제4조 포함. 영상 콘티 `docs/video-storyboard.md`(본편 8장면 약 2분 20초, 숏폼 45초, AI 장면 프롬프트, 편집 체크리스트). 서버 테스트 155건 통과.
  - 운영 조건: 가사·작곡을 자체 서버(`ollama`, `acestep_local`)로 둔다는 전제다. 외부 작곡·LLM 공급자로 바꾸면 대화에서 온 가사가 나가므로 처리방침 6절을 바꿔야 한다.
  - (2026-11-11) 숏폼 v0 (`tools/promo-video/`, 새 UI 실제 앱 화면 + 데모 가족 곡 + 자막, 46초). 녹화하며 찾은 앱 문제 3건: ① 한마디(CALL)가 1절 노래 위에 겹침 → 노래 빈 구간으로 (§7.4) ② 네 섹션 가사 중 절반만 불림 → 두 섹션 8줄 (§7.2) ③ Pleos 시스템 미디어 위젯 미표시 → 앱 밖 원인, 문의 (§13-5). 고친 뒤 실제 GPU 데모(녹화 없이): 트리거 → READY 157초, 8줄 모두 부름, CALL 은 노래 시작 직전(곡 13.5~17.0초, 첫 소절 17.3초), 도착(P단) 순간 자동 재생. 서버 테스트 165건, voice-lab 20건 통과. 차량은 더 뉴 그랜저로 확정, AI 장면 프롬프트 `docs/video-ai-prompts.md`.

### T12. 데모 영상용 시나리오 다듬기
- "서울→강릉" 데모 모드 + 실제 가족 클립 + 완성 곡 재생 흐름을 1분 내외로 촬영할 수 있게 정리.
- **완료 기준**: 처음부터 끝까지 끊김 없이 재현 가능.
- **상태: 완료 (fake 공급자 기준).** 촬영 절차는 `docs/demo-scenario.md` (서버 데모 설정 `FAKE_MUSIC_DELAY_SEC=0 JOB_POLL_INTERVAL_SEC=1`, 앱 설치·`pm clear`, 구성원·클립·탑승자 준비, 촬영, 알려진 제약). 휴대폰 에뮬레이터에서 `pm clear`(권한 초기화)부터 구성원 추가 → 클립 3개 → 탑승자 → 데모 모드 → 주행 기록 시작(권한 대화상자) → 서울→강릉까지 끊김·크래시 없이 재현 (READY 40.7초, 자동 재생 56.4초). **남은 제약**: 실제 공급자로는 도착 전 READY가 안 되어 자동 재생 장면이 나오지 않는다 (알림 재생 장면으로 편집 필요, §7.1). 에뮬레이터 마이크 무음 → 목소리를 넣으려면 호스트 오디오 입력을 켠다. 주행 제한 화면은 시뮬레이션 스위치로 따로 켠다.

---

## 12. 일정 (마감 역산)

| 기간 | 목표 |
|---|---|
| 10/29 ~ 10/31 | T1 벤치마크, 사무국 문의 발송, SDK 권한 신청 |
| 11/01 ~ 11/05 | T2~T5 서버 MVP |
| 11/03 ~ 11/10 | T6~T11 앱 MVP (서버와 병행) |
| 11/11 ~ 11/13 | T12 데모 영상 촬영, 기획서 작성 |
| 11/14 | 최종 점검 |
| **11/15** | **제출 (기획서 + 데모 영상 + 구현물)** |
| 11/23 ~ 12/02 | (서류 통과 시) 안정화, Pleos 에뮬레이터 시연 준비 (실차 시연 없음, §1.2), PT 자료 |

- (2026-11-03 추가) 진행 체크리스트는 `docs/goals.md`. 추가된 목표:
  - **AI 연출 시연 영상**: 4인 가족이 현대차로 이동하며 나눈 차 안 대화가 가족 목소리 노래가 되어 도착 순간 흐르는 이야기를 휴머니즘적으로 (v0.4, 2026-11-04 갱신). AI 장면 + 에뮬레이터 실제 앱 화면, 소리는 가족이 직접 녹음한 대화와 앱이 만든 완성곡. 콘티 `docs/video-storyboard.md`. (v0.3까지는 한 명씩 목소리를 녹음하는 장면이었다.)
  - **실제 노래·가사**: 유료 API 없이 로컬 ACE-Step + 로컬 LLM/Gemini 무료 등급 (`docs/desktop-handoff.md`).
  - **UI 리디자인** (앱 완성 후): 현대차 홈페이지 색감·느낌 반영, Pleos UI 가이드 준수 (§10). 영상에 실제 앱 화면을 넣으면 촬영 전에 끝낸다.
  - **Pleos SDK 연동 (필수, 오리엔테이션 §1.2)**: NaviHelper·FusedLocation·Vehicle(보조 신호)·TextToSpeech (§6.4 바인딩 교체) + Pleos 에뮬레이터 E2E.
  - **2차 PT 준비**: 오프라인 현장 시연 환경(노트북 에뮬레이터에서 서버·음악 모델 접근), 로직 설명 준비.

**v0.4 일정 (방향 전환 후, 2026-11-03 작성)**

| 날짜 | 작업 |
|---|---|
| 11/04 | T16 voice-lab 서비스 (분석·변환, 11/03 완료), T17 서버 대화 파이프라인 + Ollama 가사 Provider (완료) |
| 11/04 | T18 앱 대화 담기·동의 UI (완료) |
| 11/04 | T19 데모 대화 + 데모 타이밍 (완료) |
| 11/05 ~ 11/06 | T20 기획서·개인정보 문서, 데모 대화 내용 개선 검토 |
| 11/07 ~ 11/08 | 여유: 실제 가족 대화 시험, 버그 수정 |
| 11/09 ~ 11/10 | UI 리디자인 (현대차 색감, Pleos 화면 크기) |
| 11/10 ~ 11/11 | 기획서 확정, DOCX 옮기기, 참가신청서·서명 |
| 11/11 ~ 11/13 | 시연 영상 (에뮬레이터 녹화 + AI 연출), 숏폼 |
| 11/14 | GitHub 저장소·release APK·README, 최종 점검 |
| **11/15** | **제출** |
| 11/16 ~ 12/02 | 나머지 Pleos SDK(NaviHelper·Gear·FusedLocation·TTS), 개인 맞춤 목소리(미세 조정), 후렴 합창, PT 시연 환경(GPU 서버 접근), CPU·메모리 측정, PT 준비 |

---

## 13. 미해결 질문 (사무국 / 문서 확인)

1. Gleo AI LLM SDK를 공모전 기간에 쓸 수 있는가? 서버 모드 `registerApp` 승인이 필요한가? (2026-11-03: 공모전 FAQ의 제공 SDK 목록에 Gleo LLM이 없다, §1.2)
2. NaviHelper SDK가 ETA·남은 거리·목적지 이름을 제공하는가?
3. 차량 마이크 녹음(RECORD_AUDIO)을 서드파티 앱에서 쓸 수 있는가? 좌석별 마이크·발화 위치 정보도 제공하는가? (2026-11-03: Pleos 에뮬레이터에서 RECORD_AUDIO 런타임 권한으로 MediaRecorder 녹음·STT 동작. 좌석 정보는 STT `Speaker` 데이터가 있으나 미확인)
4. 백그라운드 Foreground Service(위치 타입)를 주행 중에 계속 실행해도 되는가?
5. 오디오 앱은 MediaLibraryService 기반 시스템 미디어 UI를 써야 하는가, 자체 UI도 허용되는가? (2026-11-03: Pleos 에뮬레이터 시스템 미디어 위젯이 우리 재생을 표시하지 않음 → 원인 조사 필요). **2026-11-11 조사**: 우리 쪽은 AAOS 표준을 다 갖췄다 — 재생 중 Media3 세션이 `active=true`, `PLAYING`, 제목 메타데이터 있음, `CarMediaService`도 사용자 10의 현재 재생 소스를 `com.drivesong/...PlaybackService`로 잡는다. 그런데 위젯을 그리는 Pleos 홈 화면(`ai.umos.homescreen`의 MediaViewContainer, 왼쪽 패널 `ai.umos.drivingview`는 `ai.pleos.multimedia.permission.BIND_MEDIA_BRIDGE_SERVICE` 사용)은 우리 세션에 컨트롤러로 붙지 않는다 (세션의 컨트롤러 2개 = 앱 화면·CarMediaService). Pleos 전용 미디어 브리지(`ai.pleos.multimedia.mediabridge`, signature|privileged 권한)를 거치는 소스만 보이는 것으로 추정 → 앱 코드로는 고칠 수 없다. **Pleos 문의 항목**: 서드파티 미디어 앱이 시스템 미디어 위젯에 나오려면 무엇이 필요한가.
6. 외부 서버 통신·외부 생성형 AI API 사용에 제한이 있는가? → (2026-11-03, 오리엔테이션) 퍼블릭 네트워크·외부 API 사용 가능, Pleos SDK와 병행.
7. 2차 시연은 실차, 에뮬레이터, 영상 중 어떤 환경에서 진행되는가? → (2026-11-03, 오리엔테이션) 실차 시연 없음, Pleos Connect 에뮬레이터.
8. (v0.2 추가) Pleos Connect의 API 레벨(minSdk 29 가정), `CAR_SPEED`·`CAR_POWERTRAIN` 권한 보호 수준, GPS_PROVIDER 제공 여부는?
9. (v0.2 추가) 앱이 백그라운드일 때(도착 순간 화면에 다른 앱) Media3 포그라운드 서비스 시작·재생이 허용되는가? Pleos 오디오 포커스 정책(내비 음성 시 시스템 ducking 여부)은?
10. (v0.4) STT SDK의 `AmbientAiApi`(화자 등록·인식·음원 분리)를 서드파티 앱이 쓸 수 있는가? 공개 문서·신청 조건은? (SDK 코드에는 있으나 공개 문서 페이지는 없음, 2026-11-03)
11. (v0.4) 주행 내내 마이크를 쓰는 앱이 Gleo 웨이크업·통화·내비 음성 인식과 함께 동작할 수 있는가? 마이크를 동시에 쓸 수 있는가?
12. (v0.4) 차 안 대화 녹음·목소리 변환 기능에 대한 앱마켓 심사 정책 제약이 있는가?

---

## 14. 확장 로드맵 (기획서 기재용)

- **개인 맞춤 목소리**: 대화가 쌓이면 구성원별로 목소리 변환 모델을 미세 조정해 더 닮게 (v0.4 MVP는 미세 조정 없이 유사도 0.69, §7.5)
- **가족 합창 후렴**: 후렴은 온 가족 목소리를 겹쳐 합창으로
- **장소 기억 앨범**: 같은 목적지를 다시 갈 때 지난 노래를 이어서 2절 생성
- **동승자 참여**: 주행 중 아이가 "노래에 고양이 넣어줘"라고 말하면 가사에 반영 (Gleo STT)
- **연간 리캡**: 1년 동안의 드라이브 송 메들리
- **여행 앱·내비 연동**: 휴게소·관광지 정보를 가사 소재로 활용

---

## 15. 참고 문서

- Pleos Connect SDK 개요: https://pleos.ai/playground/resources/api-reference/connect-sdk
- 주행 안전 디자인 가이드: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline
- 앱 카테고리: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/app-category
- 성능·리소스 가이드: https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/performance-resource-mgmt
- 프로젝트 생성·SDK 권한: https://document.pleos.ai/en/docs/pleos-only/create-my-project
- Gleo LLM SDK: https://document.pleos.ai/en/api-reference/connect-sdk-pleos/LLM/intro
- Vehicle SDK 튜토리얼: https://pleos.ai/playground/resources/docs/pleos-tutorial/vehicle-sdk
- 앱 심사 제출 가이드: https://document.pleos.ai/en/docs/pleos-only/distribute/app-submission-guide
- AAOS Driver Distraction Guidelines: https://source.android.com/docs/automotive/driver_distraction/guidelines

---

## 16. 구현 현황 (2026-10-29)

### 16.1 요약
| Task | 상태 | 남은 확인 |
|---|---|---|
| T1 벤치마크 | 부분 (도구·보고서 틀) | API 키로 실행, 공급자 확정 → §7.3·§7.4 갱신 |
| T2 서버 골격 | 부분 | `docker compose up` 실행 |
| T3 음악 공급자·워커 | 완료 | 실제 공급자 호출 |
| T4 믹서 | 완료 | 실제 곡·실제 녹음으로 청취 확인 |
| T5 enrich·가사 | 완료 | Kakao·Claude 실호출, 가사 품질 |
| T6 앱 골격 | 휴대폰 에뮬레이터 확인 | Pleos 에뮬레이터에서 실제 CarUxRestrictions 전환 |
| T7 차량 데이터·데모 | 완료 | 기기에서 실제 소스(CarPropertyManager·GPS) |
| T8 TripRecorder | 부분 | CPU 측정 |
| T9 가족·녹음 | 완료 (휴대폰 에뮬레이터) | 실제 목소리 녹음(에뮬레이터 마이크 무음), 차량 마이크 |
| T10 가사·오케스트레이터 | 완료 (휴대폰 에뮬레이터, fake) | 실제 공급자 타이밍 |
| T11 재생·보관함 | 부분 (ducking·일시 손실 확인) | 영구 포커스 손실, Pleos 포커스 정책, 미디어 센터 연동 |
| T12 데모 시나리오 | 완료 (fake, `docs/demo-scenario.md`) | 실제 공급자로 촬영 시 편집 방법 |

- 테스트 결과(최종 수정 후): 서버 `pytest` 122 통과(live 1건 제외), `tools/api-bench` 4 통과(이번에 재실행 안 함), Android `./gradlew assembleDebug test` 성공 — 단위 테스트 debug 변형 133건 + core:model 13건 (release 변형 포함 전체 279건), 실패 0.
- 2026-11-03 데스크탑(Windows 11, RTX 5070 Ti) 이전 후 재실행: 서버 122 통과, api-bench 4 통과, Android `assembleDebug test` 279건 실패 0 (Python 3.12.10, ffmpeg 9.0.2, Temurin JDK 17.0.20).
- 2026-11-03 T13~T15 추가 후: Android `assembleDebug test` **343건 실패 0** (release 변형 포함, core:model 23). 서버 코드는 바꾸지 않음.
- 기기 실행: 휴대폰 에뮬레이터(Medium_Phone_API_36)에서 E2E를 했다 (§11 T6·T9·T10·T11·T12). Pleos Connect 에뮬레이터는 한 번도 실행하지 않았다.

### 16.2 실행 방법
- 서버: `cd server && pip install -e ".[dev]" && uvicorn app.main:app --reload --port 8000` (Python 3.12+, ffmpeg/ffprobe 필요). 기본값(`MUSIC_PROVIDER`/`LLM_PROVIDER`/`GEO_PROVIDER` = `fake`)으로 키 없이 전체 흐름이 동작한다. 자세한 것은 `server/README.md`.
- 앱: `cd android && ./gradlew assembleDebug` (JDK 17 필수, §4.1. 예: `JAVA_HOME` = Temurin 17). 에뮬레이터에서 기본 서버 주소 `http://10.0.2.2:8000/`. 실기기는 `-PdriveSongApiBaseUrl=http://<PC IP>:8000/`.
- 데모: 홈 "주행 기록 시작"(정확한 위치 권한 허용) → 설정 "데모 모드" 켜기 → "서울→강릉" 재생 → 약 55초 뒤 도착 시 자동 재생. 촬영용 전체 절차(서버 데모 설정, 깨끗한 설치, 준비, 확인 방법)는 `docs/demo-scenario.md`.
- 벤치마크: `python tools/api-bench/bench.py` (`FAL_KEY`/`MUREKA_API_KEY` 필요, 키 없이 경로만 볼 때는 `--include-fake`).
- 명령 목록은 `CLAUDE.md` "빌드·테스트 명령".

### 16.3 미검증 항목·남은 일 (§13과 함께 볼 것)
- **외부 키 필요**: 음악 공급자 3종, Claude, Kakao 실호출 모두 미실행. 공급자 응답 형식·섹션 타이밍·실제 곡 길이·한국어 품질은 문서 기반 가정 (§7.3 `[확인 필요]`).
- **Docker**: 이미지 빌드·실행 안 함 (Debian ffmpeg의 libmp3lame·버전 미확인).
- **Pleos/AAOS 실기 동작**: Pleos Connect 에뮬레이터는 쓰지 않았다 (휴대폰 에뮬레이터만). CarUxRestrictions, CarPropertyManager 속도·기어, GPS_PROVIDER, 위치 포그라운드 서비스 지속 실행(§13-4), 차량 마이크 녹음(§13-3), 미디어 센터 탐색(§13-5), 백그라운드 자동 재생(§13-9), Pleos 오디오 포커스 정책, 콜드 스타트 영향, 분할 화면 폭.
- **오디오 포커스 영구 손실**: 기기에서 실행하지 않음 (Robolectric `AudioFocusPolicyTest`만). ducking·일시 손실은 휴대폰 에뮬레이터에서 확인 (T11).
- **CPU·메모리 측정** (§2, T8 완료 기준) 미실시.
- **에뮬레이터 마이크**: 휴대폰 에뮬레이터 기본 입력은 거의 무음이라 믹서가 클립을 건너뛴다 (흐름은 정상, 노래에 목소리 없음). 호스트 오디오 입력을 켜야 한다. 믹스 포함 여부는 서버 쪽 합성 톤 클립으로만 확인 (T9·T10).
- **짧은 클립**: ebur128 측정에서 정리 후 약 0.4초보다 짧은 클립은 -70 LUFS(무음)로 나와 빠진다 (§7.4).
- **앱 ↔ 서버 연동 공백**:
  - 401 재등록 후 예전 디바이스의 서버 clip/member id가 로컬에 남는다 → 그 클립을 쓰는 잡은 404로 실패할 수 있다. 다시 업로드할지 사용자에게 물을지 제품 결정 필요.
  - `SongOrchestrator`의 잡 폴링은 HTTP 404(없어진 잡)도 일시 네트워크 오류로 보고 5분 제한까지 계속 폴링한다. 데이터 삭제는 이제 이 경로에 오지 않지만(§7.1), 404면 시도를 바로 끝내는 편이 낫다.
  - 데이터 삭제 중 짧은 경쟁: 삭제 도중 들어온 `TripEvent.Started`가 RECORDING_TRIP을 만들고 ETA 트리거가 서버 삭제 완료 전에 잡을 제출할 수 있다 (실제로는 거의 없음, 미처리).
  - 해결됨: 앱 "모든 데이터 삭제"가 `DELETE /v1/songs`를 부른다 (커밋 e2b22c1), 삭제 전에 만들던 노래를 취소한다 (커밋 2963744). `/v1/lyrics`는 서버에서 180초 상한 후 504, `/v1/trips/enrich`는 약 45초 상한 (커밋 99bbd60, §9). 앱 `NetworkModule.kt`의 OkHttp KDoc은 아직 "Claude, 서버 타임아웃 120초 × 재시도"라고만 쓰여 있다 (서버 상한 180초 언급 추가 필요).
- **데모 타이밍**: fake 공급자로도 여유가 얇다. 기본 서버 설정에서 READY는 도착 약 4초 전 (앱 5초 폴링 + 서버 잡 13~15초) → 호스트 부하가 크면 놓칠 수 있다. 데모 서버는 `FAKE_MUSIC_DELAY_SEC=0 JOB_POLL_INTERVAL_SEC=1`로 띄운다. 실제 음악·LLM·live 지오 공급자로는 약 20초 안에 끝나지 않아 도착 전 READY가 안 된다 (§7.1, `docs/demo-scenario.md`).
- **주행 제한 + 데모**: 데모 모드의 차속·기어는 `UxRestrictionsMonitor`에 반영되지 않는다. 데모 중 제한 화면은 시뮬레이션 스위치로만 보인다 (§3.2).
- **접근성**: 탑승자 체크박스가 uiautomator 덤프에서 checkable=false, checked=false (화면에는 체크됨) (§10).
- **프로세스 수명**: 주행 서비스 종료 후 프로세스가 죽으면 생성 중인 곡을 잃는다 (서버 잡 재개 미구현).
- **보안**: `PlaybackService`는 export되어 모든 컨트롤러 연결을 받는다 → AAOS 동작 확인 후 신뢰 컨트롤러로 제한 검토.
- **개인정보 문서**: privacy.html 자리표시자(시행일·책임자·연락처·공급자명), 다운샘플링 좌표 보관 문구, 좌표 30일 기준(문서: 곡 생성 후 / 구현: 주행 종료 후) 정리, 위치정보법 검토.
- **운영**: Nominatim 초당 1건 제한은 프로세스 단위 (다중 워커 시 초과), 운영 시 `NOMINATIM_USER_AGENT`에 연락처. DB 마이그레이션 도구 없음 (서버 `create_all`, 앱 destructive migration).
- **폰 웹 QR 녹음 업로드**(§8) 미구현.
