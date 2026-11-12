"""T16 완료 기준 확인: 11/03 시험(docs/conversation-song-trial.md)을 voice-lab HTTP API 로 다시 돌린다.

Zeroth-Korean(CC BY 4.0) 시험 세트로 화자 3명이 번갈아 말하는 대화를 만들고
/v1/analyze → 화자 정확도·글자 오류율, /v1/sing → 가족 3명이 나눠 부른 노래 + 사람별 변환 유사도.

usage: python eval/trial.py <zeroth_test.parquet> <song.mp3> <work_dir> [--url http://127.0.0.1:8002]
"""
import io
import json
import re
import sys
import urllib.request
from collections import defaultdict
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from voicelab import audio as au  # noqa: E402
from voicelab.analyze import Analyzer  # noqa: E402
from voicelab.settings import Settings  # noqa: E402

SPEAKERS = {"118": "mem_mom", "104": "mem_dad", "121": "mem_kid"}
TURNS, GAP, SR = 4, 0.6, 16_000


def post(url, body, timeout=1800):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def cer(ref, hyp):
    keep = lambda s: [c for c in s if c.isalnum()]
    r, h = keep(ref), keep(hyp)
    d = list(range(len(h) + 1))
    for i, rc in enumerate(r, 1):
        prev, d[0] = d[0], i
        for j, hc in enumerate(h, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (rc != hc))
    return d[len(h)] / max(1, len(r))


def build(parquet, work: Path):
    rows = pq.read_table(parquet, columns=["id", "speaker_id", "text", "audio"]).to_pylist()
    by = defaultdict(list)
    for r in sorted(rows, key=lambda r: r["id"]):
        if str(r["speaker_id"]) in SPEAKERS:
            a, _ = sf.read(io.BytesIO(r["audio"]["bytes"]), dtype="float32")
            by[str(r["speaker_id"])].append((r["text"], a))
    enroll, pieces, truth, t = {}, [], [], 0.0
    gap = np.zeros(int(GAP * SR), np.float32)
    for sp, mid in SPEAKERS.items():
        enroll[mid] = str(au.save(work / "enroll" / f"{mid}.wav", by[sp][0][1], SR))
    for i in range(1, TURNS + 1):
        for sp, mid in SPEAKERS.items():
            text, a = by[sp][i]
            pieces += [a, gap]
            truth.append((t, t + len(a) / SR, mid, text))
            t += (len(a) + len(gap)) / SR
    conv = au.save(work / "conversation.wav", np.concatenate(pieces), SR)
    return str(conv), enroll, truth


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parquet, song, work = sys.argv[1], sys.argv[2], Path(sys.argv[3])
    url = sys.argv[sys.argv.index("--url") + 1] if "--url" in sys.argv else "http://127.0.0.1:8002"
    conv, enroll, truth = build(parquet, work)

    res = post(f"{url}/v1/analyze", {"audioPaths": [conv], "outDir": str(work / "analysis"),
                                     "members": [{"memberId": m, "enrollPaths": [p]} for m, p in enroll.items()]})
    segs = res["segments"]
    correct, cers, cers_no_num = 0, [], []
    for s, e, mid, text in truth:
        parts = [x for x in segs if min(x["end"], e) - max(x["start"], s) > 0.2]
        who = max(parts, key=lambda x: min(x["end"], e) - max(x["start"], s))["memberId"] if parts else None
        correct += who == mid
        hyp = " ".join(x["text"] for x in parts)
        c = cer(text, hyp)
        cers.append(c)
        if not re.search(r"\d", hyp):
            cers_no_num.append(c)
    print(f"[분석] 대화 {res['durationSec']}초, 구간 {len(segs)}개")
    print(f"  화자 정확도 {correct}/{len(truth)}, 글자 오류 평균 {np.mean(cers):.1%}, 숫자 표기 없는 문장 {np.mean(cers_no_num):.1%}")
    for m in res["members"]:
        print(f"  {m['memberId']}: 말한 시간 {m['voicedSec']}초, 참고 목소리 {m['refSec']}초, 말소리 {m['refF0Hz']}Hz, "
              f"한마디 \"{(m['momentText'] or '')[:30]}\"")

    singers = [{"memberId": m["memberId"], "refPath": m["refPath"], "refF0Hz": m["refF0Hz"]}
               for m in res["members"] if m["refPath"]]
    out = work / "family_song.wav"
    sung = post(f"{url}/v1/sing", {"songPath": song, "singers": singers, "outPath": str(out),
                                   "workDir": str(work / "sing")})
    print(f"[노래] {sung['durationSec']}초, 원곡 가수 {sung['singerF0Hz']}Hz -> {out}")
    for s in sung["singers"]:
        print(f"  {s['memberId']}: 옥타브 이동 {s['shiftSemitones']:+d}반음, 구절 {s['phrases']}개 ({s['seconds']}초)")

    # 사람별 변환 유사도: 변환된 보컬 전체(목소리 있는 부분만) vs 그 사람 등록 클립
    an = Analyzer(Settings())
    vocals = work / "sing" / "sep" / "htdemucs" / Path(song).stem / "vocals.wav"
    voiced = lambda a: np.concatenate([au.cut(a, SR, s, e) for s, e in au.voiced_segments(a, SR, rel_db=30)])
    orig = an.embed(voiced(au.load(vocals)))
    for mid, p in enroll.items():
        ref = an.embed(au.load(p))
        conv_path = max((work / "sing" / "vc" / mid).glob("*.wav"), key=lambda x: x.stat().st_mtime)
        sim = float(np.dot(ref, an.embed(voiced(au.load(conv_path)))))
        print(f"  {mid}: 유사도 원곡 {float(np.dot(ref, orig)):.2f} -> 변환 {sim:.2f}")


main()
