"""노래 생성 잡 워커 (설계 §4.2: asyncio 백그라운드 태스크 + SQLite 잡 테이블).

상태: QUEUED -> [ANALYZING] -> GENERATING -> [CONVERTING] -> MIXING -> READY | FAILED
- ANALYZING (대화 잡만, §7.5 2~5단계): 대화 분석 -> 가사 -> 한마디·참고 목소리. 분석이 끝나면(실패해도) 원본 대화를
  지운다. 대화가 없거나 분석이 실패하면 주행 정보만으로 가사를 쓴다 (v0.2 흐름). 가사가 두 번 다 실패하면 FAILED.
- GENERATING: 공급자에 제출하고 완료/실패/제한 시간까지 폴링
- CONVERTING (부를 사람이 있을 때, §7.5 7~9단계): AI 가수 노래를 가족 목소리로 나눠 부르기. 실패하면 AI 가수 그대로
- MIXING: 결과 오디오(또는 변환본)를 mix_song() 으로 최종 m4a 로 (clipPlan 클립 + 대화 한마디), songs 행 추가 -> READY

서버 재시작 시: 끝나지 않은 잡은 시작할 때 모두 다시 이어서 처리한다.
공급자 잡 id 가 저장돼 있으면 다시 제출하지 않고 그 id 로 폴링을 이어간다 (중복 과금 방지).
제한 시간은 처음 제출한 시각 기준이다. 오래 멈춰 있던 잡은 첫 폴링에서 공급자가 이미 완성했으면
그 결과를 쓰고(이미 과금됨), 아직 pending 이면 바로 FAILED(timeout) 가 된다.
제출 응답을 저장하기 전에 죽은 경우에만 다시 제출된다. ANALYZING 중에 죽으면 대화가 남아 있으므로 다시 분석한다.
잡이 끝나면(READY·FAILED·삭제) 작업 폴더 work/<jobId> (한마디·변환 파일)를 지운다.
"""

import asyncio
import logging
import re
import secrets
import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import url2pathname

import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from app.api.conversations import delete_chunks
from app.api.lyrics import DEADLINE_SEC as LYRICS_DEADLINE_SEC
from app.db import utcnow
from app.jobs.models import Job, Song
from app.mixer.mix import PlannedClip, mix_song
from app.models import Clip, ConversationChunk, Member
from app.providers.geo.weather import Weather
from app.providers.llm.base import LlmProvider, LyricsInput, write_valid_lyrics
from app.providers.music.base import MusicProvider, MusicResult, MusicStatus, Style
from app.providers.music.factory import make_music_provider
from app.providers.voice.base import Analysis, Singer
from app.providers.voice.factory import make_voice_providers
from app.settings import Settings

log = logging.getLogger(__name__)

QUEUED, ANALYZING, GENERATING, CONVERTING, MIXING, READY, FAILED = (
    "QUEUED", "ANALYZING", "GENERATING", "CONVERTING", "MIXING", "READY", "FAILED")
UNFINISHED = (QUEUED, ANALYZING, GENERATING, CONVERTING, MIXING)
SLOTS = ("INTRO", "CALL", "OUTRO")


class JobFailed(Exception):
    pass


class JobDeleted(Exception):
    """처리 중에 잡 행이 삭제됨 (DELETE /v1/songs). 조용히 멈춘다."""


def _aware(dt: datetime) -> datetime:
    # SQLite 는 타임존 없이 돌려준다 (저장 값은 UTC)
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def plan_moments(taken_slots: set[str], moments: list[tuple[str, Path]]) -> list[dict]:
    """clipPlan 이 비운 슬롯을 대화 한마디로 채운다 (구성원마다 하나, 주어진 순서대로)."""
    free = [slot for slot in SLOTS if slot not in taken_slots]
    return [{"slot": slot, "memberId": mid, "path": str(path)} for slot, (mid, path) in zip(free, moments)]


def conversation_lines(analysis: Analysis, nicknames: dict[str, str]) -> list[str]:
    """가사에 넣을 대화 "호칭: 말". 모르는 사람의 말과 빈 글은 버린다 (§8.3)."""
    return [f"{nicknames[s.member_id]}: {s.text.strip()}"
            for s in analysis.segments if s.member_id in nicknames and s.text.strip()]


class JobWorker:
    def __init__(self, settings: Settings, session_factory: sessionmaker[Session]):
        self.settings = settings
        self.session_factory = session_factory
        # 공급자 이름 -> 어댑터 (처음 쓸 때 생성). 테스트는 여기에 직접 넣어 바꾼다.
        self.providers: dict[str, MusicProvider] = {}
        self.client = httpx.AsyncClient(timeout=60, follow_redirects=True)
        # 대화 잡용 (§7.5). llm 은 create_app 이 app.state.llm 과 같은 것을 넣는다. 테스트는 직접 바꾼다.
        self.llm: LlmProvider | None = None
        self.analyzer, self.converter = make_voice_providers(settings, self.client)
        self._tasks: set[asyncio.Task] = set()

    def provider(self, name: str) -> MusicProvider:
        if name not in self.providers:
            self.providers[name] = make_music_provider(name, self.settings, self.client)
        return self.providers[name]

    async def start(self) -> None:
        self.provider(self.settings.music_provider)  # 키 누락·잘못된 이름이면 서버 시작 실패
        with self.session_factory() as s:
            ids = s.scalars(
                select(Job.id).where(Job.status.in_(UNFINISHED)).order_by(Job.created_at)
            ).all()
        for job_id in ids:
            self.enqueue(job_id)

    async def stop(self) -> None:
        # 취소된 잡은 상태가 그대로 남아 다음 시작 때 이어서 처리된다
        for task in list(self._tasks):
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        await self.client.aclose()

    def enqueue(self, job_id: str) -> None:
        """이벤트 루프 스레드에서 호출해야 한다 (동기 핸들러는 anyio.from_thread 사용)."""
        task = asyncio.create_task(self._run(job_id))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    def _work_dir(self, job_id: str) -> Path:
        return self.settings.data_dir / "work" / job_id

    async def _run(self, job_id: str) -> None:
        try:
            await self._process(job_id)
        except JobDeleted:
            log.info("song job %s was deleted while processing", job_id)
        except JobFailed as e:
            self._mark_failed(job_id, str(e))
        except Exception as e:
            log.exception("song job %s failed", job_id)
            self._mark_failed(job_id, f"{type(e).__name__}: {e}")
        # 취소(서버 종료)면 여기 오지 않는다: 작업 폴더를 남겨 다음 시작 때 이어서 쓴다
        shutil.rmtree(self._work_dir(job_id), ignore_errors=True)

    def _mark_failed(self, job_id: str, error: str) -> None:
        # 여기서 예외가 나면 태스크가 조용히 죽고 잡이 진행 중 상태로 남으므로 로그만 남긴다
        try:
            self._update(job_id, status=FAILED, error=error)
        except JobDeleted:
            pass
        except Exception:
            log.exception("could not mark song job %s FAILED", job_id)

    def _update(self, job_id: str, **fields) -> Job:
        with self.session_factory() as s:
            job = s.get(Job, job_id)
            if job is None:
                raise JobDeleted(job_id)
            for key, value in fields.items():
                setattr(job, key, value)
            s.commit()
            return job

    async def _process(self, job_id: str) -> None:
        with self.session_factory() as s:
            job = s.get(Job, job_id)
            if job is None or job.status not in UNFINISHED:
                return
        if job.status == QUEUED:
            job = self._update(job_id, status=ANALYZING if job.conversation else GENERATING)
        if job.status == ANALYZING:
            job = self._update(job_id, status=GENERATING, **await self._analyze(job))

        provider = self.provider(job.provider)
        if job.provider_job_id is None:
            provider_job_id = await provider.submit(job.lyrics, job.style, job.duration_sec)
            job = self._update(job_id, provider_job_id=provider_job_id, submitted_at=utcnow())
        deadline = _aware(job.submitted_at) + timedelta(seconds=self.settings.job_timeout_sec)
        result = await self._wait(provider, job.provider_job_id, deadline)

        singers = self._singers(job.singers)
        self._update(job_id, status=CONVERTING if singers else MIXING)
        data_dir = self.settings.data_dir
        raw = await self._download(result.audio_url, data_dir / "songs" / "raw" / job_id)
        try:
            song, sang, phrases = await self._convert(job_id, raw, singers) if singers else (raw, [], [])
            self._update(job_id, status=MIXING, singers=sang)
            song_id = f"song_{secrets.token_hex(8)}"
            rel_path = f"songs/{song_id}.m4a"
            await asyncio.to_thread(
                mix_song,
                song,
                self._planned_clips(job.clip_plan, job.moments),
                result.chorus_start_sec,
                result.chorus_end_sec,
                data_dir / rel_path,
                vocal_phrases=phrases,
            )
        finally:
            raw.unlink(missing_ok=True)

        try:
            with self.session_factory() as s:
                job = s.get(Job, job_id)
                if job is None:
                    raise JobDeleted(job_id)
                s.add(Song(id=song_id, device_id=job.device_id, job_id=job_id, title=job.title, audio_path=rel_path))
                job.status = READY
                job.song_id = song_id
                s.commit()
        except BaseException:
            # 믹싱 중 잡이 삭제됐거나 저장에 실패하면 고아 파일을 남기지 않는다
            (data_dir / rel_path).unlink(missing_ok=True)
            raise

    # --- 대화 (§7.5) ----------------------------------------------------------

    async def _analyze(self, job: Job) -> dict:
        """대화 분석 -> 가사 -> 한마디·참고 목소리. 잡 행에 저장할 값을 돌려준다."""
        data_dir = self.settings.data_dir
        analysis, nicknames = None, {}
        with self.session_factory() as s:
            in_plan = {c.member_id for c in (s.get(Clip, item["clipId"]) for item in job.clip_plan) if c}
        try:
            with self.session_factory() as s:
                chunks = s.scalars(select(ConversationChunk).where(
                    ConversationChunk.device_id == job.device_id, ConversationChunk.trip_id == job.trip_id,
                ).order_by(ConversationChunk.seq)).all()
                audio = [data_dir / c.path for c in chunks if (data_dir / c.path).exists()]
                # 대화 녹음에 동의한 탑승자만 찾는다. 등록 클립이 음성 ID 다 (§7.5)
                enroll = {}
                for mid in job.passengers:
                    member = s.get(Member, mid)
                    if member is None or not member.conversation_consent:
                        continue
                    nicknames[mid] = member.nickname
                    clips = s.scalars(select(Clip).where(Clip.member_id == mid).order_by(Clip.created_at)).all()
                    paths = [data_dir / c.path for c in clips if (data_dir / c.path).exists()]
                    if paths:
                        enroll[mid] = paths
            if audio and enroll:
                analysis = await self.analyzer.analyze(audio, enroll, self._work_dir(job.id) / "analysis")
        except Exception as e:
            log.warning("job %s: conversation analysis failed, continuing without it: %r", job.id, e)
        # 원본 대화는 분석이 끝나면 바로 지운다 (성공·실패 모두). 취소(서버 종료)면 남겨 두고 다시 분석한다
        with self.session_factory() as s:
            delete_chunks(s, data_dir, job.device_id, job.trip_id)

        lines = []
        if analysis:
            if getattr(self.llm, "external", True):  # 외부 LLM 에는 대화를 보내지 않는다 (§8.3)
                log.info("job %s: llm '%s' is external, writing lyrics without the conversation", job.id, self.llm.name)
            else:
                lines = conversation_lines(analysis, nicknames)
        lyrics = job.lyrics or await self._write_lyrics(job, lines)

        voices = {v.member_id: v for v in analysis.members} if analysis else {}
        # 가족 모두가 노래에 담기게: clipPlan 에 클립이 없는 구성원의 한마디부터 (그다음 탑승 순서)
        order = sorted(job.passengers, key=lambda mid: mid in in_plan)
        moments = plan_moments(
            {item["slot"] for item in job.clip_plan},
            [(mid, voices[mid].moment_path) for mid in order if mid in voices and voices[mid].moment_path],
        )
        return {"lyrics": lyrics, "moments": moments, "singers": self._keep_refs(job.passengers, voices)}

    async def _write_lyrics(self, job: Job, conversation: list[str]) -> str:
        values = dict(job.lyrics_input)
        weather = values.pop("weather")
        inp = LyricsInput(**{
            **values,
            "style": Style(values["style"]),
            "weather": Weather(**weather) if weather else None,
            "conversation": conversation,
        })
        try:
            async with asyncio.timeout(LYRICS_DEADLINE_SEC):
                lyrics = await write_valid_lyrics(self.llm, inp)
        except TimeoutError:
            raise JobFailed("lyrics generation timed out") from None
        if lyrics is None:
            raise JobFailed("lyrics generation failed")
        return lyrics

    def _keep_refs(self, passengers: list[str], voices: dict) -> list[str]:
        """변환에 동의한 구성원의 참고 목소리를 보관(이번 것이 같거나 길면 바꿈)하고, 이번 노래를 부를 구성원 id 를 돌려준다.
        이번 대화가 짧아도 보관된 참고 목소리가 있으면 부른다."""
        data_dir = self.settings.data_dir
        singers = []
        with self.session_factory() as s:
            for mid in passengers:
                member = s.get(Member, mid)
                if member is None or not member.voice_conversion_consent:
                    continue
                voice = voices.get(mid)
                if voice and voice.ref_path and voice.ref_sec >= (member.voice_ref_sec or 0):
                    rel = f"voices/{mid}.wav"
                    (data_dir / "voices").mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(voice.ref_path, data_dir / rel)
                    member.voice_ref_path, member.voice_ref_sec, member.voice_ref_f0_hz = (
                        rel, voice.ref_sec, voice.ref_f0_hz)
                if member.voice_ref_path and (data_dir / member.voice_ref_path).exists():
                    singers.append(mid)
            s.commit()
        return singers

    def _singers(self, member_ids: list[str]) -> list[Singer]:
        """변환 직전에 다시 확인한다: 그사이 구성원 삭제·동의 철회가 있으면 뺀다."""
        singers = []
        with self.session_factory() as s:
            for mid in member_ids:
                member = s.get(Member, mid)
                if member is None or not member.voice_conversion_consent or not member.voice_ref_path:
                    continue
                path = self.settings.data_dir / member.voice_ref_path
                if path.exists():
                    singers.append(Singer(mid, path, member.voice_ref_f0_hz))
        return singers

    async def _convert(
        self, job_id: str, song: Path, singers: list[Singer],
    ) -> tuple[Path, list[str], list[tuple[float, float]]]:
        """(부른 곡, 실제로 부른 구성원, 노래하는 구간). 변환이 실패하면 AI 가수 곡 그대로."""
        work = self._work_dir(job_id)
        try:
            sung = await self.converter.sing(song, singers, work / "sung.wav", work / "sing")
        except Exception as e:
            log.warning("job %s: voice conversion failed, using the AI singer: %r", job_id, e)
            return song, [], []
        return sung.out_path, sung.singers, sung.vocal_phrases

    # --- 음악 공급자·믹싱 --------------------------------------------------------

    async def _wait(self, provider: MusicProvider, provider_job_id: str, deadline: datetime) -> MusicResult:
        last_error = None
        while True:
            try:
                result = await provider.poll(provider_job_id)
            except httpx.HTTPError as e:
                # 네트워크 오류·5xx 는 일시적일 수 있어 제한 시간까지 다시 시도한다
                log.warning("poll %s failed: %r", provider_job_id, e)
                result, last_error = None, f"{type(e).__name__}: {e}"
            if result is not None and result.status == MusicStatus.SUCCEEDED:
                if not result.audio_url:
                    raise JobFailed(f"{provider.name}: succeeded without audio url")
                return result
            if result is not None and result.status == MusicStatus.FAILED:
                raise JobFailed(result.error or f"{provider.name}: music generation failed")
            if utcnow() >= deadline:
                message = f"music generation timed out after {self.settings.job_timeout_sec:g}s"
                raise JobFailed(f"{message} (last error: {last_error})" if last_error else message)
            await asyncio.sleep(self.settings.job_poll_interval_sec)

    async def _download(self, url: str, dest_stem: Path) -> Path:
        parsed = urlparse(url)
        suffix = Path(parsed.path).suffix.lower()
        dest = dest_stem.with_name(dest_stem.name + (suffix if re.fullmatch(r"\.[a-z0-9]{1,5}", suffix) else ""))
        dest.parent.mkdir(parents=True, exist_ok=True)
        if parsed.scheme == "file":  # fake 공급자
            await asyncio.to_thread(shutil.copyfile, url2pathname(parsed.path), dest)
            return dest
        async with self.client.stream("GET", url) as res:
            res.raise_for_status()
            with dest.open("wb") as f:
                async for chunk in res.aiter_bytes():
                    f.write(chunk)
        return dest

    def _planned_clips(self, clip_plan: list[dict], moments: list[dict]) -> list[PlannedClip]:
        """잡 생성 후 삭제된 클립(구성원 삭제 등)은 건너뛴다. 대화 한마디도 그 구성원이 지워졌거나 동의를 철회했으면 뺀다."""
        clips = []
        with self.session_factory() as s:
            for item in clip_plan:
                clip = s.get(Clip, item["clipId"])
                path = self.settings.data_dir / clip.path if clip else None
                if path is not None and path.exists():
                    clips.append(PlannedClip(slot=item["slot"], path=path))
            for item in moments:
                member = s.get(Member, item["memberId"])
                path = Path(item["path"])
                if member is not None and member.conversation_consent and path.exists():
                    clips.append(PlannedClip(slot=item["slot"], path=path))
        return clips
