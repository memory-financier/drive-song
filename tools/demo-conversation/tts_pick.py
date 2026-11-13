"""후보 중 받아 적기 글자 오류가 가장 적은 것으로 바꾸고, 대화를 다시 잇는다 (voice-lab venv)."""
import json, shutil, sys
from pathlib import Path
import numpy as np, soundfile as sf
from voicelab import audio as au
from voicelab.analyze import Analyzer
from voicelab.settings import Settings
d = Path(sys.argv[1]); an = Analyzer(Settings())
def cer(ref, hyp):
    r = [c for c in ref if c.isalnum()]; h = [c for c in hyp if c.isalnum()]
    dp = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, dp[0] = dp[0], i
        for j in range(1, len(h) + 1):
            prev, dp[j] = dp[j], min(dp[j] + 1, dp[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
    return dp[-1] / max(1, len(r))
meta = json.loads((d / "conversation.json").read_text(encoding="utf-8"))
for x in meta:
    cands = sorted((d / "lines").glob(f"{x['i']:02d}_{x['who']}_c*.wav"))
    if not cands:
        continue
    scored = [(cer(x["text"], an.transcribe(au.load(p))), p) for p in [d / "lines" / x["file"], *cands]]
    best = min(scored, key=lambda s: s[0])
    print(x["i"], x["text"], [round(s, 2) for s, _ in scored], "->", best[1].name)
    if best[1].name != x["file"]:
        shutil.copy(best[1], d / "lines" / x["file"])
# 다시 잇기
pieces, t, timeline, sr0 = [], 0.0, [], None
for x in meta:
    a, sr = sf.read(d / "lines" / x["file"], dtype="float32"); sr0 = sr
    if pieces:
        pieces.append(np.zeros(int(0.6 * sr), np.float32)); t += 0.6
    x["sec"] = round(len(a) / sr, 2)
    timeline.append(dict(x, start=round(t, 2), end=round(t + len(a) / sr, 2)))
    pieces.append(a); t += len(a) / sr
sf.write(d / "conversation.wav", np.concatenate(pieces), sr0)
(d / "conversation.json").write_text(json.dumps(timeline, ensure_ascii=False, indent=1), encoding="utf-8")
print("conversation", round(t, 1))
