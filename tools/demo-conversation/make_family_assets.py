"""가족이 직접 녹음한 데모 (T19 A안) → 앱 데모 자료. 결과는 git 에 넣지 않는 android/demo-private/assets/demo-family/.

usage (저장소 루트, ffmpeg·ffprobe 가 PATH 에):
  python tools/demo-conversation/make_family_assets.py --conversation 대화.m4a \
      --member 엄마=ID_엄마.m4a --member 아빠=ID_아빠.m4a --member 첫째=ID_첫째.m4a:child \
      [--member 둘째=ID_둘째.m4a:child] [--check http://127.0.0.1:8002]

- 대화: AAC 모노 16kHz 32kbps 로 바꾼다. 5분(서버 조각 상한)을 넘으면 거부한다.
- 음성 ID: 앞 무음을 떼고 4.9초로 자른다 (서버 클립 상한 5초). `:child` 는 아이(보호자 동의로 등록).
- --check: voice-lab 으로 미리 확인한다 — 발화마다 누구로 알아봤는지, 사람마다 참고 목소리가 10초 이상인지(노래를 부를 수 있는지).
가이드·대본: docs/demo-family-recording.md
"""
import argparse
import json
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "android" / "demo-private" / "assets" / "demo-family"
MAX_SEC = 300.0
AAC = ["-ac", "1", "-ar", "16000", "-c:a", "aac", "-b:a", "32k"]
TRIM_START = "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.1"


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args], check=True)


def duration(path: Path) -> float:
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
                         capture_output=True, text=True, check=True).stdout
    return float(out)


def parse_member(spec: str) -> tuple[str, Path, bool]:
    """'호칭=파일[:child]'. Windows 경로의 'C:' 와 헷갈리지 않게 끝의 ':child' 만 본다."""
    nickname, rest = spec.split("=", 1)
    child = rest.endswith(":child")
    return nickname.strip(), Path(rest[: -len(":child")] if child else rest), child


def check(url: str, out: Path, members: list[dict]) -> None:
    body = {
        "audioPaths": [str((out / "conversation.m4a").resolve())],
        "members": [{"memberId": m["nickname"], "enrollPaths": [str((out / m["enroll"]).resolve())]} for m in members],
        "outDir": str((out.parent.parent.parent / "check").resolve()),  # android/demo-private/check
    }
    req = urllib.request.Request(f"{url}/v1/analyze", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        result = json.load(r)
    print("\n[발화별 화자]")
    for s in result["segments"]:
        print(f"  {s['start']:6.1f}-{s['end']:6.1f}s  {s['memberId'] or '모름':4s} ({s['score']:.2f})  {s['text'][:40]}")
    print("\n[사람별]")
    for m in result["members"]:
        ok = "노래 가능" if m["refPath"] else "참고 목소리 10초 미만 → 이번 노래는 못 부름"
        print(f"  {m['memberId']}: 말한 시간 {m['voicedSec']:.0f}초, 참고 목소리 {m['refSec']:.0f}초 ({ok}), 한마디 '{m['momentText']}'")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--conversation", required=True, type=Path)
    ap.add_argument("--member", action="append", required=True, help="호칭=파일[:child]")
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--check", help="voice-lab 주소 (예: http://127.0.0.1:8002)")
    args = ap.parse_args()

    sec = duration(args.conversation)
    if sec > MAX_SEC:
        sys.exit(f"대화가 {sec:.0f}초예요. 5분 이하로 잘라 주세요.")
    args.out.mkdir(parents=True, exist_ok=True)
    ffmpeg("-i", str(args.conversation), *AAC, str(args.out / "conversation.m4a"))
    print(f"대화 {sec:.0f}초 → {args.out / 'conversation.m4a'}")

    members = []
    for i, spec in enumerate(args.member, 1):
        nickname, path, child = parse_member(spec)
        enroll = f"enroll_{i}.m4a"
        ffmpeg("-i", str(path), "-af", TRIM_START, "-t", "4.9", *AAC, str(args.out / enroll))
        members.append({"nickname": nickname, "isChild": child, "enroll": enroll})
        print(f"음성 ID {nickname}{' (아이)' if child else ''}: {path.name} → {enroll} ({duration(args.out / enroll):.1f}초)")
    (args.out / "family.json").write_text(json.dumps({"members": members}, ensure_ascii=False, indent=2), encoding="utf-8")

    if args.check:
        check(args.check, args.out, members)


if __name__ == "__main__":
    main()
