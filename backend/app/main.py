"""Daily Log prototype API.

Sign in with a phone number (no OTP: a demo, test data only). Say, type or photograph a log; the model proposes
a card; the member edits and saves. Nothing is kept until Save except the draft, and discarded drafts are deleted.

Run: uv run uvicorn app.main:app --host 0.0.0.0 --port 8000
"""

import hashlib
import logging
import re
import secrets
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import date, datetime, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import Depends, FastAPI, File, Form, Header, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from app import logic
from app.admin import mount_admin
from app.brain import TRACE, Brain, BrainError, make_brain
from app.config import Settings, load_settings
from app.db import Store, dumps, loads

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("api")


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str = "") -> None:
        super().__init__(code)
        self.status, self.code, self.message = status, code, message


MESSAGES = {
    "busy": "The AI is busy right now. Try again in a minute.",
    "model_failed": "Taking longer than usual. Try again.",
    "stt_failed": "Couldn't hear that. Try again or type it.",
    "not_food": "I couldn't see a meal here.",
    "daily_cap_reached": "You've added the most photos for today. You can still say what you ate.",
}


def create_app(settings: Settings | None = None, brain: Brain | None = None) -> FastAPI:
    settings = settings or load_settings()
    brain = brain or make_brain(settings)
    store = Store(settings.db_path)
    app = FastAPI(title="Daily Log prototype", version="0.1.0")
    log.info("brain=%s text=%s vision=%s stt=%s", brain.name, settings.text_models, settings.vision_models,
             settings.stt_models)

    @app.exception_handler(ApiError)
    async def api_error(_: Request, error: ApiError) -> JSONResponse:
        return JSONResponse({"code": error.code, "message": error.message or MESSAGES.get(error.code, "")},
                            status_code=error.status)

    @app.exception_handler(RequestValidationError)
    async def bad_request(_: Request, error: RequestValidationError) -> JSONResponse:
        # Never echo the request back: it may hold what the member said.
        fields = sorted({".".join(str(p) for p in e.get("loc", [])[1:]) for e in error.errors()})
        return JSONResponse({"code": "invalid_input", "message": f"Check: {', '.join(fields)}"}, status_code=400)

    # ------------------------------------------------------------ auth

    def token_hash(token: str) -> str:
        return hashlib.sha256(token.encode()).hexdigest()

    def current_user(authorization: str = Header(default="")) -> dict[str, str]:
        token = authorization.removeprefix("Bearer ").strip()
        row = store.one(
            "SELECT u.id, u.phone FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?",
            token_hash(token),
        ) if token else None
        if row is None:
            raise ApiError(401, "unauthorized", "Please sign in again.")
        return {"id": row["id"], "phone": row["phone"]}

    class Login(BaseModel):
        phone: str

    @app.get("/health")
    def health() -> dict[str, Any]:
        # Which model answered how often, and which are resting after a rate limit (counts only).
        models = brain.status() if hasattr(brain, "status") else {}
        return {"ok": True, "brain": brain.name, "models": models}

    @app.post("/auth/login")
    def login(body: Login) -> dict[str, Any]:
        phone = normalize_phone(body.phone)
        if phone is None:
            raise ApiError(400, "invalid_phone", "Enter a 10-digit mobile number.")
        row = store.one("SELECT id FROM users WHERE phone = ?", phone)
        is_new = row is None
        user_id = str(uuid.uuid4()) if is_new else row["id"]
        if is_new:
            store.run("INSERT INTO users (id, phone, created_at) VALUES (?, ?, ?)", user_id, phone, now_iso())
        token = secrets.token_urlsafe(32)
        store.run("INSERT INTO logins (user_id, is_new, created_at) VALUES (?, ?, ?)", user_id, int(is_new), now_iso())
        store.run("INSERT INTO sessions (token_hash, user_id, created_at) VALUES (?, ?, ?)",
                  token_hash(token), user_id, now_iso())
        log.info("login user=%s new=%s", user_id[:8], is_new)
        return {"token": token, "phone": phone, "is_new": is_new}

    @app.post("/auth/logout")
    def logout(authorization: str = Header(default="")) -> dict[str, bool]:
        store.run("DELETE FROM sessions WHERE token_hash = ?", token_hash(authorization.removeprefix("Bearer ").strip()))
        return {"ok": True}

    @app.get("/me")
    def me(user: dict = Depends(current_user)) -> dict[str, str]:
        return {"phone": user["phone"]}

    @app.get("/config")
    def config() -> dict[str, Any]:
        return {
            "max_voice_seconds": 180,
            "max_text_chars": settings.max_text_chars,
            "photo_long_edge_px": 1024,
            "photo_daily_cap": settings.photo_daily_cap,
            "slots": logic.SLOTS,
            "engine": brain.name,
        }

    # ------------------------------------------------------------ helpers

    @contextmanager
    def traced(user: dict, entry_id: str, kind: str) -> Iterator[None]:
        """Store every Groq call made inside, with who caused it (for /admin). Counts and timings only."""
        trace: list[dict[str, Any]] = []
        token = TRACE.set(trace)
        try:
            yield
        finally:
            TRACE.reset(token)
            for row in trace:
                store.run(
                    "INSERT INTO llm_calls (user_id, entry_id, kind, job, model, outcome, ms, tokens_in, tokens_out, "
                    "audio_s, detail, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    user["id"], entry_id, kind, row.get("job"), row.get("model"), row.get("outcome"), row.get("ms"),
                    row.get("tokens_in"), row.get("tokens_out"), row.get("audio_s"), row.get("detail"), now_iso(),
                )

    def zone_of(name: str) -> ZoneInfo:
        try:
            return ZoneInfo(name)
        except (ZoneInfoNotFoundError, ValueError) as error:
            raise ApiError(400, "invalid_input", "Unknown time zone.") from error

    def day_of(text: str, zone: ZoneInfo) -> date:
        try:
            log_date = date.fromisoformat(text)
        except ValueError as error:
            raise ApiError(400, "invalid_input", "log_date must be YYYY-MM-DD.") from error
        if not logic.backfill_ok(log_date, datetime.now(zone).date()):
            raise ApiError(400, "date_out_of_range", "You can log today and the 6 days before.")
        return log_date

    def existing(user: dict, entry_id: str) -> dict | None:
        row = store.one("SELECT * FROM entries WHERE id = ?", entry_id)
        if row is None:
            return None
        if row["user_id"] != user["id"]:
            raise ApiError(409, "conflict", "That entry id is taken.")
        return view(row)

    def view(row: Any) -> dict[str, Any]:
        body = loads(row["body"])
        local_today = datetime.now(zone_of(row["time_zone"])).date()
        return {
            "id": row["id"],
            "kind": row["kind"],
            "status": row["status"],
            "log_date": row["log_date"],
            "logged_later": date.fromisoformat(row["log_date"]) < local_today,
            "confirmed_at": row["confirmed_at"],
            **body,
        }

    def labelled(fields: list[dict]) -> list[dict]:
        return [{**f, "label": logic.label_for(f)} for f in fields]

    def save_draft(user: dict, entry_id: str, kind: str, log_date: date, tz: str, body: dict) -> dict:
        store.run(
            "INSERT INTO entries (id, user_id, kind, log_date, time_zone, status, body, created_at) "
            "VALUES (?, ?, ?, ?, ?, 'draft', ?, ?)",
            entry_id, user["id"], kind, log_date.isoformat(), tz, dumps(body), now_iso(),
        )
        return view(store.one("SELECT * FROM entries WHERE id = ?", entry_id))

    def log_draft(user: dict, entry_id: str, kind: str, text: str, log_date: date, tz: str,
                  transcript: str | None) -> dict:
        zone = zone_of(tz)
        user_message = logic.log_user_message(text, log_date, datetime.now(zone), tz)
        try:
            with traced(user, entry_id, kind):
                raw = brain.extract_log(logic.log_system_prompt(), user_message)
        except BrainError as error:
            raise ApiError(503 if error.code == "busy" else 502, error.code) from error
        draft = logic.check_log(raw, log_date)
        log.info("draft %s kind=%s fields=%d flags=%d questions=%d dropped=%s", entry_id[:8], kind,
                 len(draft.fields), len(draft.red_flags), len(draft.questions), draft.dropped)
        return save_draft(user, entry_id, kind, log_date, tz, {
            "summary": draft.summary,
            "language": draft.language,
            "fields": labelled(draft.fields),
            "red_flags": draft.red_flags,
            "questions": draft.questions,
            "transcript": transcript,
            "engine": brain.name,
            "model": raw.get("_model"),
        })

    # ------------------------------------------------------------ entries

    class TextEntry(BaseModel):
        entry_id: str
        log_date: str
        time_zone: str
        text: str

    @app.post("/entries/text")
    def submit_text(body: TextEntry, user: dict = Depends(current_user)) -> dict:
        if (found := existing(user, body.entry_id)) is not None:
            return found
        text = body.text.strip()
        if not text:
            raise ApiError(400, "invalid_input", "Type what you did today.")
        if len(text) > settings.max_text_chars:
            raise ApiError(413, "too_long", f"Keep it under {settings.max_text_chars} characters.")
        log_date = day_of(body.log_date, zone_of(body.time_zone))
        return log_draft(user, body.entry_id, "text", text, log_date, body.time_zone, None)

    @app.post("/entries/voice")
    def submit_voice(
        entry_id: str = Form(...),
        log_date: str = Form(...),
        time_zone: str = Form(...),
        audio: UploadFile = File(...),
        user: dict = Depends(current_user),
    ) -> dict:
        if (found := existing(user, entry_id)) is not None:
            return found
        day = day_of(log_date, zone_of(time_zone))
        data = audio.file.read(settings.max_audio_bytes + 1)
        if not data:
            raise ApiError(400, "stt_failed")
        if len(data) > settings.max_audio_bytes:
            raise ApiError(413, "too_long", "That recording is too long.")
        try:
            with traced(user, entry_id, "voice"):
                transcript, _ = brain.transcribe(data, audio.filename or "voice.m4a", audio.content_type or "audio/mp4")
        except BrainError as error:
            raise ApiError(503 if error.code == "busy" else 502, error.code) from error
        if not transcript:
            raise ApiError(422, "stt_failed")
        return log_draft(user, entry_id, "voice", transcript, day, time_zone, transcript)

    @app.post("/entries/photo")
    def submit_photo(
        entry_id: str = Form(...),
        log_date: str = Form(...),
        time_zone: str = Form(...),
        slot: str = Form(""),
        image: UploadFile = File(...),
        user: dict = Depends(current_user),
    ) -> dict:
        if (found := existing(user, entry_id)) is not None:
            return found
        zone = zone_of(time_zone)
        day = day_of(log_date, zone)
        used = store.one(
            "SELECT COUNT(*) AS n FROM entries WHERE user_id = ? AND log_date = ? AND kind = 'photo' "
            "AND status IN ('draft', 'confirmed')", user["id"], day.isoformat(),
        )["n"]
        if used >= settings.photo_daily_cap:
            raise ApiError(429, "daily_cap_reached")
        data = image.file.read(settings.max_image_bytes + 1)
        mime = image_type(data)
        if mime is None:
            raise ApiError(400, "invalid_image", "Send a JPEG, PNG or WebP photo.")
        if len(data) > settings.max_image_bytes:
            raise ApiError(413, "invalid_image", "That photo is too large.")
        meal_slot = slot if slot in logic.SLOTS else logic.slot_for_time(datetime.now(zone))
        note = f"The member says this is their {meal_slot}."
        try:
            with traced(user, entry_id, "photo"):
                raw = brain.extract_meal(data, mime, note)
        except BrainError as error:
            raise ApiError(503 if error.code == "busy" else 502, error.code) from error
        meal = logic.check_meal(raw)
        log.info("draft %s kind=photo food=%s items=%d dropped=%s", entry_id[:8], meal.is_food, len(meal.items),
                 meal.dropped)
        if not meal.is_food:
            raise ApiError(422, "not_food")
        return save_draft(user, entry_id, "photo", day, time_zone, {
            "slot": meal_slot,
            "slot_label": logic.SLOT_LABELS[meal_slot],
            "items": meal.items,
            "flags": meal.flags,
            "engine": brain.name,
            "model": raw.get("_model"),
        })

    @app.get("/entries/{entry_id}")
    def get_entry(entry_id: str, user: dict = Depends(current_user)) -> dict:
        found = existing(user, entry_id)
        if found is None:
            raise ApiError(404, "not_found")
        return found

    class Confirm(BaseModel):
        fields: list[dict[str, Any]] | None = None
        items: list[dict[str, Any]] | None = None
        slot: str | None = None

    @app.post("/entries/{entry_id}/confirm")
    def confirm(entry_id: str, body: Confirm, user: dict = Depends(current_user)) -> dict:
        row = store.one("SELECT * FROM entries WHERE id = ? AND user_id = ?", entry_id, user["id"])
        if row is None or row["status"] == "discarded":
            raise ApiError(404, "not_found")
        if row["status"] == "confirmed":
            return {"entry": view(row)}  # a second Save returns the first result
        draft = loads(row["body"])
        log_date = date.fromisoformat(row["log_date"])
        if row["kind"] == "photo":
            items = [i for i in (logic.check_item(i) for i in body.items or []) if i]
            if not items:
                raise ApiError(400, "invalid_input", "Keep at least one item.")
            slot = body.slot if body.slot in logic.SLOTS else draft["slot"]
            saved = {**draft, "slot": slot, "slot_label": logic.SLOT_LABELS[slot], "items": items,
                     "summary": logic.meal_summary(slot, items)}
        else:
            fields = []
            for candidate in body.fields or []:
                clean, _ = logic.check_field(candidate, log_date)  # the member's edits are checked too
                if clean:
                    fields.append(clean)
            if not fields:
                raise ApiError(400, "invalid_input", "Nothing to save.")
            summary = draft.get("summary") or ""
            if not logic.summary_is_grounded(summary, fields):
                summary = logic.built_summary(fields)
            saved = {**draft, "fields": labelled(fields), "summary": summary}
        store.run("UPDATE entries SET status = 'confirmed', body = ?, confirmed_at = ? WHERE id = ?",
                  dumps(saved), now_iso(), entry_id)
        log.info("confirmed %s", entry_id[:8])
        return {"entry": view(store.one("SELECT * FROM entries WHERE id = ?", entry_id))}

    @app.delete("/entries/{entry_id}")
    def discard(entry_id: str, user: dict = Depends(current_user)) -> dict:
        # Only drafts can be discarded; saved entries are kept.
        store.run("DELETE FROM entries WHERE id = ? AND user_id = ? AND status = 'draft'", entry_id, user["id"])
        return {"ok": True}

    @app.get("/days/{log_date}")
    def day(log_date: str, time_zone: str = "Asia/Kolkata", user: dict = Depends(current_user)) -> dict:
        zone = zone_of(time_zone)
        today = datetime.now(zone).date()
        try:
            wanted = date.fromisoformat(log_date)
        except ValueError as error:
            raise ApiError(400, "invalid_input") from error
        first = today - timedelta(days=6)
        counts = {
            r["log_date"]: r["n"]
            for r in store.all(
                "SELECT log_date, COUNT(*) AS n FROM entries WHERE user_id = ? AND status = 'confirmed' "
                "AND log_date BETWEEN ? AND ? GROUP BY log_date", user["id"], first.isoformat(), today.isoformat(),
            )
        }
        strip = []
        for back in range(6, -1, -1):
            d = today - timedelta(days=back)
            n = counts.get(d.isoformat(), 0)
            strip.append({"date": d.isoformat(), "status": "full" if n >= 3 else "half" if n else "none",
                          "is_today": d == today})
        rows = store.all(
            "SELECT * FROM entries WHERE user_id = ? AND log_date = ? AND status = 'confirmed' ORDER BY confirmed_at",
            user["id"], wanted.isoformat(),
        )
        return {"log_date": wanted.isoformat(), "entries": [view(r) for r in rows], "strip": strip}

    mount_admin(app, store, settings, brain)
    return app


PHONE_10 = re.compile(r"^[6-9]\d{9}$")


def normalize_phone(raw: str) -> str | None:
    digits = re.sub(r"\D", "", raw or "")
    if len(digits) == 12 and digits.startswith("91"):
        digits = digits[2:]
    if len(digits) == 11 and digits.startswith("0"):
        digits = digits[1:]
    return f"+91{digits}" if PHONE_10.match(digits) else None


def image_type(data: bytes) -> str | None:
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


app = create_app()
