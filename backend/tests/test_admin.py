"""Admin analytics: token gate, logins and Groq calls recorded per number, no content exposed."""

import dataclasses
import uuid
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from fastapi.testclient import TestClient

from app.brain import FakeBrain
from app.config import load_settings
from app.main import create_app

TZ = "Asia/Kolkata"
ADMIN = {"X-Admin-Token": "s3cret"}


def make(tmp_path, token="s3cret"):
    settings = dataclasses.replace(load_settings(), db_path=tmp_path / "t.db", groq_api_key=None, admin_token=token)
    return TestClient(create_app(settings, FakeBrain()))


def today() -> str:
    return datetime.now(ZoneInfo(TZ)).date().isoformat()


def sign_in(client, phone):
    return {"Authorization": "Bearer " + client.post("/auth/login", json={"phone": phone}).json()["token"]}


def test_admin_is_off_without_a_token(tmp_path):
    client = make(tmp_path, token=None)
    assert client.get("/admin").status_code == 404
    assert client.get("/admin/api/overview", headers=ADMIN).status_code == 404


def test_admin_needs_the_right_token(tmp_path):
    client = make(tmp_path)
    assert client.get("/admin").status_code == 200
    assert client.get("/admin/api/users", headers={"X-Admin-Token": "nope"}).status_code == 401


def test_logins_and_calls_are_counted_per_number(tmp_path):
    client = make(tmp_path)
    headers = sign_in(client, "9000000101")
    sign_in(client, "9000000101")
    sign_in(client, "9000000102")
    secret_words = "Walked 30 minutes, sugar 118, PRIVATE-WORDS"
    draft = client.post("/entries/text", headers=headers, json={
        "entry_id": str(uuid.uuid4()), "log_date": today(), "time_zone": TZ, "text": secret_words}).json()
    client.post(f"/entries/{draft['id']}/confirm", headers=headers, json={"fields": draft["fields"]})
    client.post("/entries/voice", headers=headers, data={
        "entry_id": str(uuid.uuid4()), "log_date": today(), "time_zone": TZ},
        files={"audio": ("v.m4a", b"x", "audio/mp4")})

    users = {u["phone"]: u for u in client.get("/admin/api/users", headers=ADMIN).json()}
    assert users["+919000000101"]["logins"] == 2
    assert users["+919000000101"]["calls"] == 3  # text log + voice: speech-to-text + log
    assert users["+919000000101"]["saved"] == 1
    assert users["+919000000102"]["calls"] == 0

    calls = client.get("/admin/api/calls", headers=ADMIN, params={"phone": "+919000000101"}).json()
    assert sorted(c["job"] for c in calls) == ["log", "log", "stt"]
    assert {c["kind"] for c in calls} == {"text", "voice"}

    overview = client.get("/admin/api/overview", headers=ADMIN).json()
    assert overview["users_total"] == 2 and overview["logins_today"] == 3
    assert overview["calls_today"] == 3 and overview["entries_saved_today"] == {"text": 1}
    assert len(overview["hourly"]) == 24

    everything = "".join(client.get(p, headers=ADMIN).text for p in ("/admin/api/overview", "/admin/api/users", "/admin/api/calls"))
    assert "PRIVATE-WORDS" not in everything  # analytics never carry what members said


@pytest.mark.parametrize("path", ["/admin/api/overview", "/admin/api/users", "/admin/api/calls", "/admin/api/entries"])
def test_member_tokens_do_not_open_admin(tmp_path, path):
    client = make(tmp_path)
    member = sign_in(client, "9000000103")
    assert client.get(path, headers=member).status_code == 401


def test_saved_logs_per_member_without_drafts_or_raw_words(tmp_path):
    client = make(tmp_path)
    a, b = sign_in(client, "9000000201"), sign_in(client, "9000000202")
    def log(headers, text, save=True):
        draft = client.post("/entries/text", headers=headers, json={
            "entry_id": str(uuid.uuid4()), "log_date": today(), "time_zone": TZ, "text": text}).json()
        if save:
            client.post(f"/entries/{draft['id']}/confirm", headers=headers, json={"fields": draft["fields"]})
    log(a, "RAW-WORDS dinner")
    log(a, "left as a draft", save=False)
    log(b, "another member")

    mine = client.get("/admin/api/entries", headers=ADMIN, params={"phone": "+919000000201"})
    assert mine.status_code == 200
    rows = mine.json()
    assert len(rows) == 1 and rows[0]["phone"] == "+919000000201" and rows[0]["log_date"] == today()
    assert {f["key"] for f in rows[0]["fields"]} == {"meal", "walk", "glucose", "feeling"}
    assert "RAW-WORDS" not in mine.text and "transcript" not in rows[0]
    assert len(client.get("/admin/api/entries", headers=ADMIN).json()) == 2
    assert client.get("/admin/api/entries", headers=ADMIN, params={"from": "2000-01-01", "to": "2000-01-02"}).json() == []
    assert client.get("/admin/api/entries", headers=ADMIN, params={"from": "yesterday"}).status_code == 400
