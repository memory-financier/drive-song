"""TTS 데모 가족: 공개 음성(Zeroth-Korean, CC BY 4.0) 화자를 목소리 샘플로, CosyVoice3 로 가족 대화·음성 ID 를 만든다.
아이 목소리는 실제 아이 녹음 없이, 밝은 여성 목소리 샘플의 음높이·음색을 올려(WORLD) 샘플로 쓴다.
CosyVoice 설치 폴더에서 그 venv 로 실행 (PYTHONUTF8=1, PYTHONPATH 에 CosyVoice 와 third_party/Matcha-TTS):
  python <이 파일> <zeroth parquet> <out dir> [mode=instruct|zeroshot]
다음: tts_regen.py (뭉개진 줄 다시 합성) -> tts_pick.py (가장 또렷한 후보 고르기) -> ffmpeg 로 assets/demo/ 형식으로.
절차와 확인 결과: docs/demo-scenario.md 7.1"""
import io
import json
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import pyworld as pw
import soundfile as sf
import torch
import torchaudio

sys.path.append("third_party/Matcha-TTS")
from cosyvoice.cli.cosyvoice import AutoModel  # noqa: E402

parquet, out = sys.argv[1], Path(sys.argv[2])
mode = sys.argv[3] if len(sys.argv) > 3 else "instruct"
out.mkdir(parents=True, exist_ok=True)
(out / "lines").mkdir(exist_ok=True)
SYS = "You are a helpful assistant."
PROMPTS = {"아빠": "112_003_2156", "엄마": "132_003_2988", "첫째": "121_003_2650"}
STYLE = {"아빠": "请用温和、开朗的爸爸语气说这句话。", "엄마": "请用温柔、开心的妈妈语气说这句话。",
         "첫째": "请用天真活泼、兴奋的小孩语气说这句话。"}
SCRIPT = [  # docs/demo-family-recording.md 대본의 3명판 (숫자는 한글로)
    ("아빠", "자, 다들 안전벨트 맸지? 강릉으로 출발!"),
    ("엄마", "출발! 오늘 날씨 진짜 좋다. 하늘이 완전 파래."),
    ("첫째", "아빠, 강릉까지 얼마나 걸려?"),
    ("아빠", "음, 안 막히면 세 시간쯤? 중간에 휴게소도 들를 거야."),
    ("첫째", "휴게소! 나 호두과자 먹을래!"),
    ("엄마", "호두과자 좋지. 엄마는 따뜻한 커피 한 잔."),
    ("아빠", "하하, 벌써 먹을 생각뿐이네. 노래 하나 틀어 줄까?"),
    ("첫째", "응! 크게 틀어 줘!"),
    ("엄마", "창밖 좀 봐. 산이 온통 빨갛고 노래. 단풍이 너무 예쁘다."),
    ("첫째", "저 빨간 산은 꼭 딸기 아이스크림 같아!"),
    ("아빠", "대관령만 넘으면 바다가 보일 거야. 누가 먼저 보나 내기할까?"),
    ("첫째", "내가 먼저 볼 거야!"),
    ("엄마", "엄마도 질 수 없지. 눈 크게 뜨고 있어 봐."),
    ("첫째", "엄마, 우리 바다 가서 뭐 할 거야?"),
    ("엄마", "모래사장 걷고, 저녁엔 다 같이 조개구이 먹자."),
    ("첫째", "나는 모래성 크게 쌓을 거야. 아빠도 같이 하자."),
    ("아빠", "좋아, 아빠가 멋진 성문 만들어 줄게."),
    ("첫째", "아빠, 운전 오래 했는데 안 힘들어?"),
    ("아빠", "네가 옆에서 노래 불러 주니까 하나도 안 힘들어."),
    ("첫째", "아빠 최고!"),
    ("엄마", "우리 가족 최고. 오늘 진짜 행복하다."),
    ("첫째", "어, 저기! 바다다! 바다 보인다!"),
    ("아빠", "다 왔다. 강릉 도착!"),
]
ID_LINE = "안녕하세요. 저는 우리 집 {}예요. 오늘의 드라이브 송, 지금 시작합니다."
GAP = 0.6

rows = {r["id"]: r for r in pq.read_table(parquet, columns=["id", "text", "audio"]).to_pylist()
        if r["id"] in PROMPTS.values()}


def childlike(x, sr, semis=5.0, formant=1.15):
    """WORLD 로 음높이를 올리고 성도(포먼트)를 짧게: 어른 여성 목소리 -> 아이 같은 목소리."""
    f0, sp, ap = pw.wav2world(x.astype(np.float64), sr)
    bins = sp.shape[1]
    src = np.clip(np.arange(bins) / formant, 0, bins - 1)
    lo = np.floor(src).astype(int)
    hi = np.minimum(lo + 1, bins - 1)
    w = src - lo
    warp = lambda m: np.ascontiguousarray(m[:, lo] * (1 - w) + m[:, hi] * w)  # noqa: E731
    y = pw.synthesize(f0 * 2 ** (semis / 12), warp(sp), warp(ap), sr)
    return (y / max(1e-6, np.abs(y).max()) * 0.9).astype(np.float32)


pdir = out / "prompts"
pdir.mkdir(exist_ok=True)
prompt = {}
for who, uid in PROMPTS.items():
    a, sr = sf.read(io.BytesIO(rows[uid]["audio"]["bytes"]), dtype="float32")
    if who == "첫째":
        a = childlike(a, sr)
    p = pdir / f"{who}.wav"
    sf.write(p, a, sr)
    prompt[who] = (str(p), rows[uid]["text"])
    print(who, uid, rows[uid]["text"])

m = AutoModel(model_dir="pretrained_models/Fun-CosyVoice3-0.5B")
SR = m.sample_rate


def tts(who, text):
    wav, ptext = prompt[who]
    if mode == "instruct":
        gen = m.inference_instruct2(text, f"{SYS} {STYLE[who]}<|endofprompt|>", wav, stream=False)
    else:
        gen = m.inference_zero_shot(text, f"{SYS}<|endofprompt|>{ptext}", wav, stream=False)
    return torch.cat([r["tts_speech"] for r in gen], dim=1)


meta = []
t0 = time.time()
for i, (who, text) in enumerate(SCRIPT):
    s = tts(who, text)
    p = out / "lines" / f"{i + 1:02d}_{who}.wav"
    torchaudio.save(str(p), s, SR)
    meta.append(dict(i=i + 1, who=who, text=text, sec=round(s.shape[1] / SR, 2), file=p.name))
    print(i + 1, who, meta[-1]["sec"], text, flush=True)
for who in PROMPTS:
    s = tts(who, ID_LINE.format(who))
    torchaudio.save(str(out / f"ID_{who}.wav"), s, SR)
print("tts", round(time.time() - t0, 1), "s")

# 대화 이어 붙이기 (발화 사이 0.6초), 16kHz 모노
pieces, t, timeline = [], 0.0, []
for mt in meta:
    a, sr = sf.read(out / "lines" / mt["file"], dtype="float32")
    if pieces:
        pieces.append(np.zeros(int(GAP * sr), np.float32))
        t += GAP
    timeline.append(dict(mt, start=round(t, 2), end=round(t + len(a) / sr, 2)))
    pieces.append(a)
    t += len(a) / sr
sf.write(out / "conversation.wav", np.concatenate(pieces), SR)
(out / "conversation.json").write_text(json.dumps(timeline, ensure_ascii=False, indent=1), encoding="utf-8")
per = {w: round(sum(x["sec"] for x in meta if x["who"] == w), 1) for w in PROMPTS}
print("conversation", round(t, 1), "s; per member", per)
