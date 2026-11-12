# Drive Song voice-lab

대화 분석과 가족 목소리 노래 변환을 맡는 GPU 서비스 (설계 `../docs/design.md` §7.5, T16).
Drive Song 서버와 **같은 PC**에서 돌고 **파일 경로**를 주고받는다.

| 기능 | 모델 (라이선스) |
|---|---|
| 발화 나누기 | 프레임 음량 기준 (numpy) |
| 받아 적기 | Whisper large-v3-turbo (MIT) |
| 누가 말했나 | SpeechBrain ECAPA (Apache 2.0), 등록 클립과 코사인 |
| 음높이 | librosa pYIN (ISC) |
| 보컬 분리 | Demucs htdemucs (MIT) |
| 목소리 변환 | Seed-VC (GPL-3.0) — **이 저장소에 포함하지 않고 별도 설치본의 CLI만 부른다** |

## 설치 (Windows, Git Bash)

```bash
cd voice-lab
uv sync --extra dev                     # PyTorch 2.7.1+cu128 (RTX 50 지원)
```

- ffmpeg / ffprobe 가 PATH 에 있어야 한다 (오디오 읽기, Demucs).
- Seed-VC 별도 설치 (GPL, 우리 코드와 분리):
  ```bash
  git clone --depth 1 https://github.com/Plachtaa/seed-vc.git ~/ai/seed-vc
  cd ~/ai/seed-vc && uv venv --python 3.10 .venv
  uv pip install --python .venv/Scripts/python.exe "torch==2.7.1+cu128" "torchaudio==2.7.1+cu128" --index-url https://download.pytorch.org/whl/cu128
  uv pip install --python .venv/Scripts/python.exe accelerate "librosa==0.10.2" "huggingface-hub>=0.28.1" "munch==4.0.0" "einops==0.8.0" "descript-audio-codec==1.0.0" "pydub==0.25.1" "transformers==4.46.3" soundfile "numpy==1.26.4" "hydra-core==1.3.2" pyyaml python-dotenv tqdm "scipy==1.13.1"
  ```
- Windows 주의: 애플리케이션 제어 정책이 scipy 1.18.1 DLL 을 막아 1.17.0 으로 고정했다. 처음 실행 때 모델(Whisper, ECAPA, Demucs, Seed-VC)을 내려받는다.

## 실행

```bash
PYTHONUTF8=1 .venv/Scripts/python.exe -m uvicorn voicelab.app:app --host 127.0.0.1 --port 8002
```

| 환경 변수 | 기본 | 설명 |
|---|---|---|
| `VOICELAB_DEVICE` | `cuda` | |
| `VOICELAB_ASR_MODEL` | `openai/whisper-large-v3-turbo` | |
| `VOICELAB_MODELS_DIR` | `.models` | ECAPA 모델 복사 위치 |
| `VOICELAB_MATCH_THRESHOLD` | `0.45` | 등록 클립과의 유사도가 이보다 낮으면 '모르는 사람' `[확인 필요]` |
| `VOICELAB_SEEDVC_DIR` / `VOICELAB_SEEDVC_PYTHON` | `~/ai/seed-vc` / 그 `.venv` 의 python | Seed-VC 설치본 |
| `VOICELAB_SEEDVC_STEPS` | `30` | 확산 단계 |

## API

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/health` | | `{status}` |
| POST | `/v1/analyze` | `{audioPaths[], members[{memberId, enrollPaths[]}], outDir}` | `{durationSec, segments[{start,end,memberId,score,text}], members[{memberId, voicedSec, momentPath, momentText, refPath, refSec, refF0Hz}]}` — 한마디는 `outDir/moments/`, 참고 목소리(10초 이상일 때만)는 `outDir/refs/`. 끝나면 모델을 GPU 에서 내린다 |
| POST | `/v1/sing` | `{songPath, singers[{memberId, refPath, refF0Hz}], outPath, workDir}` | `{outPath, durationSec, singerF0Hz, singers[{memberId, shiftSemitones, phrases, seconds}]}` — 44.1kHz 스테레오 wav (-1 dBFS 이하). 부를 사람이 없으면 AI 가수 그대로 |

- 없는 파일은 400, 형식 오류는 422. GPU 작업은 한 번에 하나.

## 시험

```bash
.venv/Scripts/python.exe -m pytest -q          # GPU 없이 도는 로직·API 시험
# 완료 기준 확인 (서비스를 띄운 뒤): Zeroth-Korean 시험 세트 + ACE-Step 노래 1곡
.venv/Scripts/python.exe eval/trial.py <zeroth_test.parquet> <song.mp3> <work_dir>
```

2026-11-03 결과: 화자 12/12, 글자 오류 5.7%(숫자 표기 제외), 변환 유사도 엄마 0.73·아빠 0.64·첫째 0.81 (원곡 가수 0.17~0.22), 분석 + 3명 변환 약 3분.
