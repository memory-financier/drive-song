"""T19 데모 대화 만들기: Zeroth-Korean(CC BY 4.0) 화자 3명의 무난한 문장을 번갈아 이어 붙인다.

usage (voice-lab venv: pyarrow·soundfile, ffmpeg 필요):
  ../../voice-lab/.venv/Scripts/python.exe build_demo.py <zeroth test_kresnik.parquet> <out_dir>
결과: conversation.m4a → android/feature/trip/src/main/assets/demo/, enroll_*.m4a → android/feature/voice/src/main/assets/demo/
"""
import io, subprocess, sys
from pathlib import Path
import numpy as np, pyarrow.parquet as pq, soundfile as sf
sys.stdout.reconfigure(encoding="utf-8")
SR = 16_000
parquet, out = sys.argv[1], Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
rows = {r["id"]: r for r in pq.read_table(parquet, columns=["id", "speaker_id", "text", "audio"]).to_pylist()}
def audio(uid):
    a, sr = sf.read(io.BytesIO(rows[uid]["audio"]["bytes"]), dtype="float32")
    assert sr == SR, sr
    return a
TURNS = ["147_003_0539", "118_003_0285", "105_003_1049", "147_003_2038", "118_003_2761",
         "105_003_0580", "147_003_2095", "118_003_1951", "105_003_2603"]
ENROLL = {"mom": "147_003_2796", "dad": "118_003_1615", "kid": "105_003_2266"}
WHO = {"147": "엄마", "118": "아빠", "105": "첫째"}
gap = np.zeros(int(0.6 * SR), np.float32)
conv = np.concatenate([np.concatenate([audio(u), gap]) for u in TURNS])
sf.write(out / "conversation.wav", conv, SR)
def m4a(src, dst, extra=()):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(src), *extra, "-ac", "1", "-ar", "16000",
                    "-c:a", "aac", "-b:a", "32k", str(dst)], check=True)
m4a(out / "conversation.wav", out / "conversation.m4a")
(out / "conversation.wav").unlink()
for key, uid in ENROLL.items():
    sf.write(out / f"{key}.wav", audio(uid), SR)
    m4a(out / f"{key}.wav", out / f"enroll_{key}.m4a", ("-t", "4.9"))
    (out / f"{key}.wav").unlink()
print(f"conversation {len(conv)/SR:.1f}s")
for u in TURNS:
    print(f"{WHO[u[:3]]}: {rows[u]['text']}")
print("enroll:", {k: rows[v]["text"] for k, v in ENROLL.items()})
