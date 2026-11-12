import secrets
from dataclasses import asdict
from pathlib import Path
from typing import Annotated

from anyio import from_thread
from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.responses import FileResponse
from pydantic import Field, StringConstraints, field_validator, model_validator
from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.api.auth import current_device
from app.api.clips import Slot
from app.api.conversations import delete_chunks
from app.api.lyrics import trip_lyrics_input
from app.api.members import get_owned_member
from app.api.schemas import CamelModel
from app.api.trips import EnrichedTrip
from app.db import get_session
from app.jobs.models import Job, Song
from app.jobs.worker import QUEUED, READY, JobWorker
from app.models import Clip, Device, Member
from app.providers.music.base import Style

router = APIRouter(prefix="/v1", tags=["songs"], dependencies=[Depends(current_device)])

DEFAULT_TITLE = "오늘의 드라이브 송"


class ClipPlanItem(CamelModel):
    slot: Slot
    clip_id: str


class SongJobIn(CamelModel):
    trip_id: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=64)]
    # 최대 길이는 공급자 중 가장 짧은 MiniMax 가사 제한(3500자)에 맞춘다. 대화 잡은 비우면 서버가 쓴다
    lyrics: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=3500)] | None = None
    style: Style
    duration_sec: int = Field(ge=60, le=90)  # 설계 §7.3: 60~90초
    clip_plan: list[ClipPlanItem] = Field(default_factory=list, max_length=len(Slot))
    title: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)] | None = None
    # v0.4 (§9.1): 이 주행에 올린 대화로 가사·한마디·가족 목소리를 만든다
    conversation: bool = False
    enriched_trip: EnrichedTrip | None = None  # 대화 잡이면 필수 (가사의 주행 정보, 탑승자)

    @field_validator("clip_plan")
    @classmethod
    def one_clip_per_slot(cls, plan: list[ClipPlanItem]) -> list[ClipPlanItem]:
        if len({item.slot for item in plan}) != len(plan):
            raise ValueError("at most one clip per slot")
        return plan

    @model_validator(mode="after")
    def lyrics_source(self) -> "SongJobIn":
        if self.conversation:
            if self.enriched_trip is None:
                raise ValueError("enrichedTrip is required when conversation is true")
            if self.enriched_trip.trip_id != self.trip_id:
                raise ValueError("enrichedTrip.tripId must match tripId")
        elif self.lyrics is None:
            raise ValueError("lyrics is required unless conversation is true")
        return self


class SongJobOut(CamelModel):
    job_id: str
    status: str
    song_id: str | None
    audio_url: str | None
    title: str
    error: str | None
    lyrics: str | None  # 대화 잡은 분석 뒤에 채워진다
    singers: list[str]  # 노래를 부른 구성원 id (READY 일 때만, 없으면 AI 가수)


def _job_out(job: Job) -> SongJobOut:
    ready = job.status == READY
    return SongJobOut(
        job_id=job.id,
        status=job.status,
        song_id=job.song_id if ready else None,
        audio_url=f"/v1/songs/{job.song_id}/audio" if ready else None,
        title=job.title,
        error=job.error,
        lyrics=job.lyrics or None,
        singers=job.singers if ready else [],
    )


@router.post("/song-jobs", status_code=status.HTTP_201_CREATED, response_model=SongJobOut)
def create_song_job(
    body: SongJobIn,
    request: Request,
    device: Device = Depends(current_device),
    session: Session = Depends(get_session),
) -> SongJobOut:
    clip_ids = [item.clip_id for item in body.clip_plan]
    owned = set(
        session.scalars(
            select(Clip.id).join(Member).where(Clip.id.in_(clip_ids), Member.device_id == device.id)
        )
    )
    missing = [cid for cid in clip_ids if cid not in owned]
    if missing:
        # 다른 디바이스의 클립은 없는 클립과 똑같이 404
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail=f"clip not found: {missing[0]}")

    title, passengers, lyrics_input = body.title, [], None
    if body.conversation:
        trip = body.enriched_trip
        passengers = list(dict.fromkeys(trip.passengers))  # 탑승 순서 유지, 중복 제거
        nicknames = [get_owned_member(session, device, mid).nickname for mid in passengers]  # 남의 구성원은 404
        # 좌표는 저장하지 않는다 (§8.1): 가사에 쓰는 이름·시간·거리만
        lyrics_input = asdict(trip_lyrics_input(trip, nicknames, body.style))
        title = title or (f"{trip.destination_name} 가는 길" if trip.destination_name else None)

    job = Job(
        id=f"job_{secrets.token_hex(8)}",
        device_id=device.id,
        trip_id=body.trip_id,
        lyrics=body.lyrics or "",
        style=body.style,
        duration_sec=body.duration_sec,
        clip_plan=[item.model_dump(by_alias=True, mode="json") for item in body.clip_plan],
        status=QUEUED,
        title=title or DEFAULT_TITLE,
        provider=request.app.state.settings.music_provider,
        conversation=body.conversation,
        lyrics_input=lyrics_input,
        passengers=passengers,
    )
    session.add(job)
    session.commit()
    worker: JobWorker = request.app.state.worker
    from_thread.run_sync(worker.enqueue, job.id)
    return _job_out(job)


@router.get("/song-jobs/{job_id}", response_model=SongJobOut)
def get_song_job(
    job_id: str,
    device: Device = Depends(current_device),
    session: Session = Depends(get_session),
) -> SongJobOut:
    job = session.get(Job, job_id)
    if job is None or job.device_id != device.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="job not found")
    return _job_out(job)


@router.get("/songs/{song_id}/audio", response_class=FileResponse)
def get_song_audio(
    song_id: str,
    request: Request,
    device: Device = Depends(current_device),
    # 파일 전송 내내 커넥션 풀을 잡고 있지 않도록 응답 전송 전에 세션을 닫는다
    session: Session = Depends(get_session, scope="function"),
) -> FileResponse:
    song = session.get(Song, song_id)
    path: Path | None = request.app.state.settings.data_dir / song.audio_path if song else None
    if song is None or song.device_id != device.id or not path.exists():
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="song not found")
    # Starlette FileResponse 가 Range 요청(206)을 처리한다
    return FileResponse(path, media_type="audio/mp4")


def _delete_songs_and_jobs(request: Request, session: Session, songs: list[Song], job_ids: set[str]) -> None:
    """songs 행·jobs 행(가사 포함)을 지우고 커밋한 뒤 믹싱된 파일을 지운다 (설계 §8.1)."""
    paths = [song.audio_path for song in songs]
    for song in songs:
        session.delete(song)
    session.flush()  # songs.job_id 가 jobs 를 참조하므로 노래를 먼저 지운다
    session.execute(delete(Job).where(Job.id.in_(list(job_ids))))
    session.commit()
    data_dir = request.app.state.settings.data_dir
    for path in paths:
        (data_dir / path).unlink(missing_ok=True)


@router.delete("/songs/{song_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_song(
    song_id: str,
    request: Request,
    device: Device = Depends(current_device),
    session: Session = Depends(get_session),
) -> Response:
    song = session.get(Song, song_id)
    if song is None or song.device_id != device.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="song not found")
    _delete_songs_and_jobs(request, session, [song], {song.job_id})
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.delete("/songs", status_code=status.HTTP_204_NO_CONTENT)
def delete_all_songs(
    request: Request,
    device: Device = Depends(current_device),
    session: Session = Depends(get_session),
) -> Response:
    """설정 > 데이터 삭제: 이 디바이스의 노래와 (진행 중인 것을 포함한) 모든 잡, 올린 대화 조각을 지운다.
    진행 중인 잡은 워커가 행이 사라진 것을 보고 멈춘다."""
    songs = list(session.scalars(select(Song).where(Song.device_id == device.id)))
    job_ids = set(session.scalars(select(Job.id).where(Job.device_id == device.id)))
    _delete_songs_and_jobs(request, session, songs, job_ids)
    delete_chunks(session, request.app.state.settings.data_dir, device.id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
