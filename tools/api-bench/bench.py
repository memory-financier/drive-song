"""노래 생성 API 벤치마크 (설계 §7.3, §11-T1).

샘플 한국어 가사 3종(samples/*.txt) x 공급자(minimax, mureka, acestep)를 서버 어댑터
(server/app/providers/music)로 그대로 호출하고, 실행마다 생성 시간·비용·성공 여부·섹션 타이밍 메타데이터
유무를 기록한다. 결과 곡은 audio/ 에 저장해 들어 보고 주관 점수(한국어 발음, 음질)를 채운다.

키가 없는 공급자는 건너뛰고 skipped 로 보고한다. 사용법은 README.md.
"""

import argparse
import asyncio
import csv
import json
import re
import shutil
import sys
import time
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import url2pathname

import httpx

BENCH_DIR = Path(__file__).resolve().parent
SERVER_DIR = BENCH_DIR.parents[1] / "server"
sys.path.insert(0, str(SERVER_DIR))

from app.api.clips import probe_audio_duration  # noqa: E402
from app.providers.music.base import MusicProvider, MusicStatus, Style  # noqa: E402
from app.providers.music.factory import make_music_provider  # noqa: E402
from app.settings import Settings  # noqa: E402

PROVIDERS = ("minimax", "mureka", "acestep")
STYLE_KO = {Style.KIDS: "동요", Style.CITY_POP: "시티팝", Style.ACOUSTIC: "어쿠스틱"}

# 공개 가격표 (USD). 확인일 2026-10-29.
# - minimax: https://fal.ai/models/fal-ai/minimax-music/v2.6 "$0.15 per audio" (페이지에서 확인)
# - acestep: https://fal.ai/models/fal-ai/ace-step "$0.0002 per second of generated audio" (페이지에서 확인)
# - mureka:  설계 §7.3 "곡당 약 $0.225 (V9.5 generate-song)".
#   [확인 필요] 가격 페이지(https://platform.mureka.ai/pricing)가 JS 렌더링이라 자동으로 확인하지 못했다.
#   어댑터는 model="auto", n=1 로 요청한다 (곡 수만큼 과금).
# 실패한 요청의 과금 여부는 공개 문서에서 확인하지 못해 비용을 비워 둔다.
PRICES: dict[str, dict[str, float]] = {
    "minimax": {"per_song": 0.15},
    "mureka": {"per_song": 0.225},
    "acestep": {"per_second": 0.0002},
    "fake": {"per_song": 0.0},
}

CSV_FIELDS = [
    "provider", "style", "attempt", "success", "generation_sec", "audio_duration_sec",
    "cost_usd", "has_section_timing", "chorus_start_sec", "chorus_end_sec", "audio_file",
    "error", "korean_pronunciation_1to5", "audio_quality_1to5", "notes",
]


def load_samples() -> dict[Style, str]:
    return {style: (BENCH_DIR / "samples" / f"{style.value}.txt").read_text(encoding="utf-8").strip()
            for style in STYLE_KO}


def cost_usd(provider: str, duration_sec: float | None, requested_sec: int) -> float:
    price = PRICES[provider]
    if "per_song" in price:
        return price["per_song"]
    # 초당 과금: 실제 길이를 못 읽으면 요청 길이로 계산
    return round(price["per_second"] * (duration_sec or requested_sec), 4)


async def download(client: httpx.AsyncClient, url: str, dest_stem: Path) -> Path:
    parsed = urlparse(url)
    suffix = Path(parsed.path).suffix.lower()
    dest = dest_stem.with_name(dest_stem.name + (suffix if re.fullmatch(r"\.[a-z0-9]{1,5}", suffix) else ".bin"))
    dest.parent.mkdir(parents=True, exist_ok=True)
    if parsed.scheme == "file":  # fake 공급자
        shutil.copyfile(url2pathname(parsed.path), dest)
        return dest
    async with client.stream("GET", url) as res:
        res.raise_for_status()
        with dest.open("wb") as f:
            async for chunk in res.aiter_bytes():
                f.write(chunk)
    return dest


async def run_one(
    provider: MusicProvider, style: Style, lyrics: str, attempt: int, *,
    client: httpx.AsyncClient, out_dir: Path, duration_sec: int, poll_interval: float, timeout: float,
) -> dict:
    row = {f: None for f in CSV_FIELDS}
    row.update(provider=provider.name, style=style.value, attempt=attempt, success=False)
    t0 = time.monotonic()
    try:
        job_id = await provider.submit(lyrics, style.value, duration_sec)
        last_error = None
        while True:
            try:
                result = await provider.poll(job_id)
            except httpx.HTTPError as e:  # 서버 워커처럼 일시 오류는 제한 시간까지 다시 시도
                result, last_error = None, f"{type(e).__name__}: {e}"
            if result is not None and result.status != MusicStatus.PENDING:
                break
            if time.monotonic() - t0 > timeout:
                raise TimeoutError(f"no result after {timeout:g}s" + (f" (last error: {last_error})" if last_error else ""))
            await asyncio.sleep(poll_interval)
        row["generation_sec"] = round(time.monotonic() - t0, 1)
        if result.status == MusicStatus.FAILED:
            row["error"] = result.error
            return row
        row["has_section_timing"] = result.chorus_start_sec is not None
        row["chorus_start_sec"] = result.chorus_start_sec
        row["chorus_end_sec"] = result.chorus_end_sec
        path = await download(client, result.audio_url, out_dir / "audio" / f"{provider.name}_{style.value}_{attempt}")
        row["audio_file"] = path.relative_to(out_dir).as_posix()
        row["audio_duration_sec"] = probe_audio_duration(path)
        row["cost_usd"] = cost_usd(provider.name, row["audio_duration_sec"], duration_sec)
        row["success"] = True
    except Exception as e:  # 공급자 오류도 결과로 남긴다
        row["error"] = f"{type(e).__name__}: {e}"
    return row


async def run_bench(
    settings: Settings, providers: list[str], samples: dict[Style, str], out_dir: Path, *,
    client: httpx.AsyncClient, repeat: int = 1, duration_sec: int = 75,
    poll_interval: float = 5.0, timeout: float = 600.0,
) -> dict:
    """공급자끼리는 동시에, 한 공급자 안에서는 순서대로 실행한다 (생성 시간이 서로 섞이지 않게)."""
    skipped, ready = [], []
    for name in providers:
        try:
            ready.append(make_music_provider(name, settings, client))
        except ValueError as e:  # 키 없음
            skipped.append({"provider": name, "reason": str(e)})

    async def run_provider(provider: MusicProvider) -> list[dict]:
        rows = []
        for attempt in range(1, repeat + 1):
            for style, lyrics in samples.items():
                print(f"[{provider.name}] {style.value} #{attempt} ...", flush=True)
                row = await run_one(provider, style, lyrics, attempt, client=client, out_dir=out_dir,
                                    duration_sec=duration_sec, poll_interval=poll_interval, timeout=timeout)
                print(f"[{provider.name}] {style.value} #{attempt} -> "
                      f"{'ok' if row['success'] else 'FAIL'} {row['generation_sec']}s {row['error'] or ''}", flush=True)
                rows.append(row)
        return rows

    started_at = datetime.now().astimezone().isoformat(timespec="seconds")
    per_provider = await asyncio.gather(*(run_provider(p) for p in ready))
    return {
        "startedAt": started_at,
        "durationSec": duration_sec,
        "repeat": repeat,
        "prices": PRICES,
        "skipped": skipped,
        "runs": [row for rows in per_provider for row in rows],
    }


def _fmt(v) -> str:
    if v is None:
        return ""
    if isinstance(v, bool):
        return "O" if v else "X"
    return str(v)


def summarize(runs: list[dict]) -> list[dict]:
    summary = []
    for name in dict.fromkeys(r["provider"] for r in runs):
        rows = [r for r in runs if r["provider"] == name]
        ok = [r for r in rows if r["success"]]
        summary.append({
            "provider": name,
            "attempts": len(rows),
            "succeeded": len(ok),
            "failure_rate": round(1 - len(ok) / len(rows), 2),
            "avg_generation_sec": round(sum(r["generation_sec"] for r in ok) / len(ok), 1) if ok else None,
            "avg_cost_usd": round(sum(r["cost_usd"] for r in ok) / len(ok), 4) if ok else None,
            "section_timing": f"{sum(1 for r in ok if r['has_section_timing'])}/{len(ok)}",
        })
    return summary


def render_markdown(result: dict) -> str:
    lines = [
        f"# API 벤치마크 결과 ({result['startedAt']})",
        "",
        f"- 요청 곡 길이: {result['durationSec']}초, 반복: {result['repeat']}회",
        "- 생성 시간: 제출부터 완료 응답까지 (폴링 간격만큼 오차). 비용: 성공한 곡만, 공개 가격표 기준 계산값",
        "- 주관 점수(한국어 발음, 음질)는 audio/ 의 곡을 들은 뒤 results.csv 또는 아래 표에 채운다.",
        "",
    ]
    if result["skipped"]:
        lines += ["## 건너뛴 공급자", ""]
        lines += [f"- {s['provider']}: {s['reason']}" for s in result["skipped"]]
        lines.append("")
    if result["runs"]:
        lines += [
            "## 공급자 요약", "",
            "| 공급자 | 시도 | 성공 | 실패율 | 평균 생성 시간(s) | 평균 비용($) | 섹션 타이밍 제공 | 한국어 발음(1-5) | 음질(1-5) |",
            "|---|---|---|---|---|---|---|---|---|",
        ]
        for s in summarize(result["runs"]):
            lines.append(f"| {s['provider']} | {s['attempts']} | {s['succeeded']} | {s['failure_rate']} | "
                         f"{_fmt(s['avg_generation_sec'])} | {_fmt(s['avg_cost_usd'])} | {s['section_timing']} |  |  |")
        lines += [
            "", "## 실행별 결과", "",
            "| 공급자 | 스타일 | # | 결과 | 생성 시간(s) | 곡 길이(s) | 비용($) | 섹션 타이밍 | 후렴 시작(s) | 한국어 발음(1-5) | 음질(1-5) | 파일 | 오류 |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
        ]
        for r in result["runs"]:
            duration = round(r["audio_duration_sec"], 1) if r["audio_duration_sec"] is not None else None
            error = (r["error"] or "").replace("|", "\\|").replace("\n", " ")[:200]
            lines.append(
                f"| {r['provider']} | {STYLE_KO[Style(r['style'])]} | {r['attempt']} | {'성공' if r['success'] else '실패'} | "
                f"{_fmt(r['generation_sec'])} | {_fmt(duration)} | {_fmt(r['cost_usd'])} | {_fmt(r['has_section_timing'])} | "
                f"{_fmt(r['chorus_start_sec'])} |  |  | {_fmt(r['audio_file'])} | {error} |"
            )
    else:
        lines.append("실행된 공급자가 없다 (API 키 필요).")
    return "\n".join(lines) + "\n"


def write_reports(result: dict, out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    # utf-8-sig: 엑셀에서 한글이 깨지지 않게
    with (out_dir / "results.csv").open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(result["runs"])
    (out_dir / "results.md").write_text(render_markdown(result), encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="노래 생성 API 벤치마크 (설계 §11-T1)")
    parser.add_argument("--providers", default=",".join(PROVIDERS), help="쉼표 구분 (기본: minimax,mureka,acestep)")
    parser.add_argument("--include-fake", action="store_true", help="키 없이 전체 경로를 확인하는 fake 공급자 추가")
    parser.add_argument("--repeat", type=int, default=1, help="샘플당 반복 횟수 (실패율 측정용)")
    parser.add_argument("--duration", type=int, default=75, help="요청 곡 길이(초). acestep 만 반영 (§7.3: 60~90)")
    parser.add_argument("--poll-interval", type=float, default=5.0)
    parser.add_argument("--timeout", type=float, default=600.0, help="곡 하나당 제한 시간(초)")
    parser.add_argument("--out", type=Path, help="결과 디렉터리 (기본: results/<시각>)")
    args = parser.parse_args(argv)

    providers = [p.strip() for p in args.providers.split(",") if p.strip()]
    if args.include_fake and "fake" not in providers:
        providers.append("fake")
    out_dir = args.out or BENCH_DIR / "results" / datetime.now().strftime("%Y%m%d-%H%M%S")
    # 키는 환경 변수 또는 server/.env (FAL_KEY, MUREKA_API_KEY). fake 곡은 결과 디렉터리 안에 만든다.
    settings = Settings(_env_file=SERVER_DIR / ".env", data_dir=out_dir / "fake_data")

    async def go() -> dict:
        async with httpx.AsyncClient(timeout=60, follow_redirects=True) as client:
            return await run_bench(settings, providers, load_samples(), out_dir, client=client,
                                   repeat=args.repeat, duration_sec=args.duration,
                                   poll_interval=args.poll_interval, timeout=args.timeout)

    result = asyncio.run(go())
    write_reports(result, out_dir)
    for s in result["skipped"]:
        print(f"skipped {s['provider']}: {s['reason']}")
    print(f"results: {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
