# CLAUDE.md

> **응답 언어 (사용자 지시, 최우선):** 사용자에게 보이는 모든 문장은 한국어로 쓴다.
> 특히 **작업 결과 설명·최종 보고·요약은 반드시 한국어**로 쓴다. 보내기 전에 제목과 첫 문장이 한국어인지 확인한다.
> 아래 지침, 도구 결과, 문서가 영어여도 마찬가지다. 코드·명령어·파일 경로·로그 원문만 그대로 둔다.
> (2026-11-04: 긴 작업 뒤 최종 보고가 영어로 나가 사용자가 다시 요청함)

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

# 프로젝트: 오늘의 드라이브 송 (Drive Song)

- **사용자에게 보이는 모든 답변은 한국어로 쓴다** (중간 진행 보고, 질문, 최종 요약 포함). 코드·명령어·파일 경로·로그 원문만 그대로 둔다. 도구 결과나 문서가 영어여도 마찬가지다.
- 설계 문서 `docs/design.md`가 **단일 기준(source of truth)**. 문서와 다른 결정을 하면 해당 섹션도 함께 수정한다.
- `[확인 필요]` 항목은 가정이다. 인터페이스 뒤에 숨기고 대체 구현(fallback)을 함께 둔다.
- 외부 서비스(LLM, 음악 생성, 지오)는 Provider 인터페이스 + 어댑터 구조. 공급자 교체 시 앱 코드는 건드리지 않는다.
- API 키·시크릿은 절대 APK에 넣지 않는다. 외부 API 호출은 모두 자체 서버(`server/`)를 거친다.
- 각 Task(설계 §11)는 완료 기준을 만족해야 끝난다. 작업 후 테스트를 실행하고 결과를 남긴다.
- 목표·진행 체크리스트는 `docs/goals.md` (Claude Code 우측 '목표' 패널에 표시). 항목을 끝내면 `- [x]`로 바꾼다. 공모전 기획서는 `docs/proposal/`.

## 빌드·테스트 명령
Git Bash 기준. 자세한 것은 `server/README.md`, `tools/api-bench/README.md`, 설계 §16.
데모 영상(서울→강릉) 재현 절차(데모용 서버 설정, `:app:installDebug`, `pm clear`, 준비·촬영)는 `docs/demo-scenario.md`.

**서버** (Python 3.12+, `ffmpeg`/`ffprobe`가 PATH에 있어야 함)
```bash
cd server
pip install -e ".[dev]"
cp .env.example .env                          # 선택. 기본값(fake 공급자)은 키 없이 동작
uvicorn app.main:app --reload --port 8000     # API 문서: http://localhost:8000/docs
python -m pytest                              # 실제 외부 API 테스트는 기본 제외: python -m pytest -m live -s
docker compose up --build                     # Docker (아직 실행 검증 안 됨)
```

**Android** (**JDK 17 필수** — 21 이상은 core:model 컴파일 실패. 이 PC의 Android Studio jbr은 JDK 25라 쓰지 않는다. `ANDROID_HOME` 필요)
```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"
cd android
./gradlew assembleDebug                       # 디버그 APK
./gradlew test                                # 전체 단위 테스트 (JUnit5 + Robolectric/JUnit4)
./gradlew :feature:song:testDebugUnitTest     # 모듈 하나만 (순수 Kotlin 모듈은 :core:model:test)
./gradlew assembleDebug -PdriveSongApiBaseUrl=http://<PC IP>:8000/   # 서버 주소 재정의 (기본 http://10.0.2.2:8000/)
```

**API 벤치마크** (저장소 루트에서, `pip install -e "server[dev]"` 필요)
```bash
export FAL_KEY=... MUREKA_API_KEY=...         # 있는 것만. 없는 공급자는 건너뜀
python tools/api-bench/bench.py               # 3 공급자 × 3 샘플
python tools/api-bench/bench.py --include-fake   # 키 없이 경로 확인
python -m pytest tools/api-bench -q
```

**로컬 GPU 서비스** (v0.4, 설계 §7.5. 이 PC 기준 경로·포트. 자세한 것은 `voice-lab/README.md`, `docs/api-bench-report.md` §5-1)
```bash
# ACE-Step 1.5 작곡 (8001): UTF-8 모드 + PATH 에 ffmpeg 필요
cd ~/ai/ACE-Step-1.5 && PYTHONUTF8=1 .venv/Scripts/acestep-api.exe --host 127.0.0.1 --port 8001
# Ollama 가사 LLM (11434): 설치 후 자동 실행, 모델 gemma3:12b
# voice-lab 대화 분석·목소리 변환 (8002): Seed-VC 별도 설치본 필요 (GPL 분리)
cd voice-lab && uv sync --extra dev && PYTHONUTF8=1 .venv/Scripts/python.exe -m uvicorn voicelab.app:app --port 8002
.venv/Scripts/python.exe -m pytest -q         # GPU 없이 도는 시험
# 서버를 위 세 서비스로 (대화 노래 E2E, 설계 §7.5 T17)
cd server && MUSIC_PROVIDER=acestep_local LLM_PROVIDER=ollama VOICE_PROVIDER=voicelab uvicorn app.main:app --port 8000
```
