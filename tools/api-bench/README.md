# api-bench — 노래 생성 API 벤치마크 (설계 §7.3, §11-T1)

샘플 한국어 가사 3종 × 공급자 3종(minimax, mureka, acestep)을 **서버 어댑터**
(`server/app/providers/music`)로 그대로 호출해 비교한다. API 호출 코드는 서버와 공유하므로
여기서 확인한 동작이 곧 서버 동작이다.

| 파일 | 내용 |
|---|---|
| `bench.py` | 실행 스크립트 |
| `samples/kids.txt` | 동요 (서울→강릉, 아침) |
| `samples/city_pop.txt` | 시티팝 (강변북로→춘천, 저녁) |
| `samples/acoustic.txt` | 어쿠스틱 (대전→전주, 비 오는 오후) |
| `test_bench.py` | 오프라인 테스트 (MockTransport, fake 공급자) |

샘플 가사는 §7.2 규칙(verse/chorus/verse/chorus 4줄씩, 한 줄 12음절 이내, 지명 2개 이상, 가족 호칭 1번 이상)을
따르는 창작 가사다. 실존 노래를 인용하지 않았다.

## 준비

- Python 3.12+, `ffmpeg`/`ffprobe` (PATH)
- 서버 의존성: `pip install -e "server[dev]"` (저장소 루트에서)
- API 키 (환경 변수 또는 `server/.env`. 환경 변수가 우선)

| 환경 변수 | 공급자 | 발급 |
|---|---|---|
| `FAL_KEY` | minimax (`fal-ai/minimax-music/v2.6`), acestep (`fal-ai/ace-step`) | https://fal.ai/dashboard/keys |
| `MUREKA_API_KEY` | mureka (`/v1/song/generate`, model `auto`, n=1) | https://platform.mureka.ai |

키가 없는 공급자는 호출하지 않고 "건너뛴 공급자"로 보고한다.

## 실행 (저장소 루트에서, Git Bash 기준)

```bash
export FAL_KEY=...            # 있는 것만
export MUREKA_API_KEY=...
python tools/api-bench/bench.py                 # 3 공급자 × 3 샘플, 1회
python tools/api-bench/bench.py --repeat 3      # 실패율을 보려면 반복
python tools/api-bench/bench.py --providers mureka --repeat 2
python tools/api-bench/bench.py --include-fake  # 키 없이 전체 경로 확인 (fake 공급자 추가)
```

PowerShell에서는 `$env:FAL_KEY="..."` 로 설정한다. Windows 콘솔에서 한글이 깨지면 `PYTHONIOENCODING=utf-8`.

| 옵션 | 기본 | 설명 |
|---|---|---|
| `--providers` | `minimax,mureka,acestep` | 쉼표 구분 |
| `--include-fake` | 끔 | fake 공급자 추가 (키 불필요, 화음 진행 mp3) |
| `--repeat` | 1 | 샘플당 반복 횟수 |
| `--duration` | 75 | 요청 곡 길이(초). 길이 입력이 있는 acestep에만 반영 |
| `--poll-interval` | 5 | 상태 조회 간격(초) = 생성 시간 측정 오차 |
| `--timeout` | 600 | 곡 하나당 제한 시간(초). 서버(5분)보다 길게 두어 느린 공급자도 시간을 잰다 |
| `--out` | `tools/api-bench/results/<시각>/` | 결과 디렉터리 (git에 올리지 않음) |

공급자끼리는 동시에, 한 공급자 안에서는 한 곡씩 순서대로 실행한다.
예상 비용 (공개 가격 기준, 1회 반복): minimax 3×$0.15 + mureka 3×$0.225 + acestep 3×약 $0.015 ≈ **$1.17**.

## 결과

| 파일 | 내용 |
|---|---|
| `results.json` | 전체 결과 (가격표, 건너뛴 공급자, 실행별 행) |
| `results.csv` | 실행별 행. 주관 점수 열은 비어 있음 (UTF-8 BOM, 엑셀에서 바로 열림) |
| `results.md` | 공급자 요약 표 + 실행별 표 (`docs/api-bench-report.md`에 옮겨 붙인다) |
| `audio/<공급자>_<스타일>_<회차>.<확장자>` | 생성된 곡 (듣기용) |

실행별 기록 항목:

- `generation_sec`: 제출 요청 시작부터 완료 응답까지 (곡 다운로드 제외, 폴링 간격만큼 오차)
- `audio_duration_sec`: 받은 곡의 실제 길이 (ffprobe)
- `cost_usd`: 성공한 곡만, `bench.py`의 `PRICES` 공개 가격표로 계산 (acestep은 초당 가격 × 실제 길이). 청구액이 아니다
- `has_section_timing`, `chorus_start_sec`, `chorus_end_sec`: 공급자가 후렴 구간을 돌려줬는지 (§7.4 CALL 클립 위치)
- `success`, `error`: 실패·타임아웃 이유. 상태 조회 중 네트워크 오류는 제한 시간까지 다시 시도한다
- `korean_pronunciation_1to5`, `audio_quality_1to5`, `notes`: 들은 뒤 직접 채운다 (채점 기준은 `docs/api-bench-report.md`)

## 테스트

```bash
python -m pytest tools/api-bench -q
```
