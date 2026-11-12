from conftest import add_member, register, upload


# --- devices / auth ---------------------------------------------------------

def test_register_device_issues_token_and_stores_only_hash(client):
    res = client.post("/v1/devices")
    assert res.status_code == 201
    body = res.json()
    assert body["deviceId"].startswith("dev_")
    assert len(body["token"]) >= 32

    from app.models import Device

    with client.app.state.session_factory() as s:
        device = s.get(Device, body["deviceId"])
        assert device.token_hash != body["token"]
        assert len(device.token_hash) == 64


def test_tokens_are_unique(client):
    a = client.post("/v1/devices").json()
    b = client.post("/v1/devices").json()
    assert a["token"] != b["token"]
    assert a["deviceId"] != b["deviceId"]


def test_missing_token_is_401(client):
    res = client.post("/v1/members", json={"nickname": "x", "isChild": False,
                                           "voiceConsent": True, "guardianConsent": False})
    assert res.status_code == 401
    assert res.headers["WWW-Authenticate"] == "Bearer"


def test_invalid_token_is_401(client):
    for header in ("Bearer nope", "Basic abc", "Bearer", "token"):
        res = client.delete("/v1/clips/clip_x", headers={"Authorization": header})
        assert res.status_code == 401, header


def test_auth_required_on_all_v1_routes_except_devices(client):
    assert client.post("/v1/members", json={}).status_code == 401
    assert client.delete("/v1/members/mem_x").status_code == 401
    assert client.post("/v1/clips").status_code == 401
    assert client.delete("/v1/clips/clip_x").status_code == 401


# --- members ----------------------------------------------------------------

def test_create_member_camel_case(client):
    h = register(client)
    res = client.post("/v1/members", json={"nickname": "  아빠 ", "isChild": False,
                                           "voiceConsent": True, "guardianConsent": False}, headers=h)
    assert res.status_code == 201
    body = res.json()
    assert body["memberId"].startswith("mem_")
    assert body["nickname"] == "아빠"
    assert body["isChild"] is False
    assert body["voiceConsent"] is True
    assert "createdAt" in body


def test_member_requires_voice_consent(client):
    h = register(client)
    res = client.post("/v1/members", json={"nickname": "엄마", "isChild": False,
                                           "voiceConsent": False, "guardianConsent": False}, headers=h)
    assert res.status_code == 400


def test_child_requires_guardian_consent(client):
    h = register(client)
    body = {"nickname": "하은", "isChild": True, "voiceConsent": True, "guardianConsent": False}
    assert client.post("/v1/members", json=body, headers=h).status_code == 400
    body["guardianConsent"] = True
    assert client.post("/v1/members", json=body, headers=h).status_code == 201


def test_member_missing_consent_fields_is_422(client):
    h = register(client)
    assert client.post("/v1/members", json={"nickname": "아빠", "isChild": False},
                       headers=h).status_code == 422
    assert client.post("/v1/members", json={"nickname": "   ", "isChild": False,
                                            "voiceConsent": True, "guardianConsent": False},
                       headers=h).status_code == 422


# --- clips ------------------------------------------------------------------

def test_upload_and_delete_clip(client, data_dir, audio_files):
    h = register(client)
    mid = add_member(client, h)
    res = upload(client, h, mid, "INTRO", audio_files["short"])
    assert res.status_code == 201, res.text
    body = res.json()
    assert body["clipId"].startswith("clip_")
    assert body["memberId"] == mid
    assert body["slot"] == "INTRO"
    assert 1.9 < body["durationSec"] < 2.2
    files = list((data_dir / "clips").iterdir())
    assert len(files) == 1

    assert client.delete(f"/v1/clips/{body['clipId']}", headers=h).status_code == 204
    assert list((data_dir / "clips").iterdir()) == []
    assert client.delete(f"/v1/clips/{body['clipId']}", headers=h).status_code == 404


def test_invalid_slot_is_422(client, audio_files):
    h = register(client)
    mid = add_member(client, h)
    assert upload(client, h, mid, "CHORUS", audio_files["short"]).status_code == 422


def test_unknown_member_is_404(client, audio_files):
    h = register(client)
    assert upload(client, h, "mem_nope", "INTRO", audio_files["short"]).status_code == 404


def test_max_three_clips_per_slot(client, audio_files):
    h = register(client)
    mid = add_member(client, h)
    for _ in range(3):
        assert upload(client, h, mid, "CALL", audio_files["short"]).status_code == 201
    assert upload(client, h, mid, "CALL", audio_files["short"]).status_code == 409
    # 다른 슬롯은 별도로 센다
    assert upload(client, h, mid, "OUTRO", audio_files["short"]).status_code == 201


def test_duration_limit(client, data_dir, audio_files):
    h = register(client)
    mid = add_member(client, h)
    assert upload(client, h, mid, "INTRO", audio_files["edge"]).status_code == 201
    res = upload(client, h, mid, "INTRO", audio_files["long"])
    assert res.status_code == 400
    # 거절된 파일은 디스크에 남지 않는다
    assert len(list((data_dir / "clips").iterdir())) == 1


def test_non_audio_is_rejected(client, data_dir, tmp_path):
    h = register(client)
    mid = add_member(client, h)
    fake = tmp_path / "fake.m4a"
    fake.write_bytes(b"not audio at all")
    assert upload(client, h, mid, "INTRO", fake).status_code == 400
    assert list((data_dir / "clips").iterdir()) == []


def test_too_large_upload_is_413(client, data_dir, tmp_path):
    from app.api.clips import MAX_CLIP_BYTES

    h = register(client)
    mid = add_member(client, h)
    big = tmp_path / "big.wav"
    big.write_bytes(b"\0" * (MAX_CLIP_BYTES + 1))
    assert upload(client, h, mid, "INTRO", big).status_code == 413
    assert list((data_dir / "clips").iterdir()) == []


def test_delete_member_removes_clips_from_db_and_disk(client, data_dir, audio_files):
    from app.models import Clip, Member

    h = register(client)
    mid = add_member(client, h)
    other = add_member(client, h, nickname="엄마")
    for slot in ("INTRO", "CALL", "OUTRO"):
        assert upload(client, h, mid, slot, audio_files["short"]).status_code == 201
    kept = upload(client, h, other, "INTRO", audio_files["short"]).json()["clipId"]
    assert len(list((data_dir / "clips").iterdir())) == 4

    assert client.delete(f"/v1/members/{mid}", headers=h).status_code == 204

    remaining = list((data_dir / "clips").iterdir())
    assert [p.stem for p in remaining] == [kept]
    with client.app.state.session_factory() as s:
        assert s.get(Member, mid) is None
        assert [c.id for c in s.query(Clip).all()] == [kept]
    assert client.delete(f"/v1/members/{mid}", headers=h).status_code == 404


# --- cross-device isolation -------------------------------------------------

def test_cross_device_isolation(client, data_dir, audio_files):
    a = register(client)
    b = register(client)
    mid = add_member(client, a)
    clip_id = upload(client, a, mid, "INTRO", audio_files["short"]).json()["clipId"]

    # B 는 A 의 구성원에 업로드/삭제할 수 없고, A 의 클립도 지울 수 없다 (404)
    assert upload(client, b, mid, "INTRO", audio_files["short"]).status_code == 404
    assert client.delete(f"/v1/clips/{clip_id}", headers=b).status_code == 404
    assert client.delete(f"/v1/members/{mid}", headers=b).status_code == 404

    # A 의 데이터는 그대로
    assert len(list((data_dir / "clips").iterdir())) == 1
    assert client.delete(f"/v1/clips/{clip_id}", headers=a).status_code == 204


# --- legal ------------------------------------------------------------------

def test_legal_pages(client):
    for path, title in (("/legal/privacy", "개인정보 처리방침"), ("/legal/location", "위치정보 이용약관")):
        res = client.get(path)
        assert res.status_code == 200
        assert res.headers["content-type"].startswith("text/html")
        assert title in res.text
