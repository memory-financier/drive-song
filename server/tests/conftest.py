import subprocess
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.settings import Settings


def _make_tone(path: Path, seconds: float) -> Path:
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y",
            "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
            "-c:a", "aac", "-b:a", "64k", str(path),
        ],
        check=True,
    )
    return path


@pytest.fixture(scope="session")
def audio_files(tmp_path_factory) -> dict[str, Path]:
    d = tmp_path_factory.mktemp("audio")
    return {
        "short": _make_tone(d / "short.m4a", 2),
        "edge": _make_tone(d / "edge.m4a", 5),
        "long": _make_tone(d / "long.m4a", 7),
    }


@pytest.fixture
def data_dir(tmp_path) -> Path:
    return tmp_path / "data"


@pytest.fixture
def client(data_dir):
    # 테스트마다 별도의 data_dir 와 SQLite 파일
    app = create_app(Settings(_env_file=None, data_dir=data_dir))
    with TestClient(app) as c:
        yield c


def register(client) -> dict[str, str]:
    res = client.post("/v1/devices")
    assert res.status_code == 201
    return {"Authorization": f"Bearer {res.json()['token']}"}


def add_member(client, headers, **overrides) -> str:
    body = {"nickname": "아빠", "isChild": False, "voiceConsent": True, "guardianConsent": False}
    body.update(overrides)
    res = client.post("/v1/members", json=body, headers=headers)
    assert res.status_code == 201, res.text
    return res.json()["memberId"]


def upload(client, headers, member_id, slot, file: Path):
    with file.open("rb") as f:
        return client.post(
            "/v1/clips",
            data={"memberId": member_id, "slot": slot},
            files={"audio": (file.name, f, "audio/mp4")},
            headers=headers,
        )
