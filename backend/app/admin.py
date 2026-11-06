"""Admin analytics: who signed in, every Groq call they caused, and the logs they saved.

Open /admin and enter ADMIN_TOKEN. Without ADMIN_TOKEN set, every /admin route answers 404.
The overview, users and calls show phone numbers, counts, models, tokens, timings and outcomes only.
/admin/api/entries returns saved logs: the confirmed card (fields or meal items, summary, red flags, questions),
never the raw transcript, typed text or photo, and never drafts or discarded entries.
"""

import secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import HTMLResponse

from app.config import Settings
from app.db import Store, loads

IST = ZoneInfo("Asia/Kolkata")
PAGE = Path(__file__).with_name("admin.html")


def _utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds")


def mount_admin(app: FastAPI, store: Store, settings: Settings, brain: Any) -> None:
    def require_admin(x_admin_token: str = Header(default="")) -> None:
        if not settings.admin_token:
            raise HTTPException(404)
        if not secrets.compare_digest(x_admin_token.encode(), settings.admin_token.encode()):
            raise HTTPException(401, "Wrong admin token.")

    @app.get("/admin", response_class=HTMLResponse, include_in_schema=False)
    def admin_page() -> str:
        if not settings.admin_token:
            raise HTTPException(404)
        return PAGE.read_text(encoding="utf-8")

    @app.get("/admin/api/overview", dependencies=[Depends(require_admin)])
    def overview() -> dict[str, Any]:
        now = datetime.now(IST)
        today = _utc(now.replace(hour=0, minute=0, second=0, microsecond=0))
        since_24h = _utc(now - timedelta(hours=24))

        def one(sql: str, *args: Any) -> Any:
            row = store.one(sql, *args)
            return row[0] if row else None

        calls = store.all(
            "SELECT model, job, outcome, COUNT(*) n, COALESCE(SUM(tokens_in),0) tin, COALESCE(SUM(tokens_out),0) tout, "
            "AVG(CASE WHEN outcome='ok' THEN ms END) avg_ms, COALESCE(SUM(audio_s),0) audio "
            "FROM llm_calls WHERE created_at >= ? GROUP BY model, job, outcome", today,
        )
        by_model: dict[str, dict[str, Any]] = {}
        for r in calls:
            m = by_model.setdefault(r["model"], {"model": r["model"], "jobs": set(), "ok": 0, "rate_limited": 0,
                                                 "errors": 0, "tokens_in": 0, "tokens_out": 0, "audio_s": 0.0,
                                                 "avg_ms": None})
            m["jobs"].add(r["job"])
            key = "ok" if r["outcome"] == "ok" else "rate_limited" if r["outcome"] == "rate_limited" else "errors"
            m[key] += r["n"]
            m["tokens_in"] += r["tin"]
            m["tokens_out"] += r["tout"]
            m["audio_s"] += r["audio"]
            if r["outcome"] == "ok" and r["avg_ms"] is not None:
                m["avg_ms"] = round(r["avg_ms"])
        models = sorted(
            ({**m, "jobs": sorted(m["jobs"])} for m in by_model.values()),
            key=lambda m: -(m["ok"] + m["rate_limited"] + m["errors"]),
        )

        hours = {}
        for r in store.all(
            "SELECT substr(created_at, 1, 13) h, SUM(outcome='ok') ok, SUM(outcome!='ok') bad FROM llm_calls "
            "WHERE created_at >= ? GROUP BY h", since_24h,
        ):
            hours[r["h"]] = (r["ok"], r["bad"])
        hourly = []
        for back in range(23, -1, -1):
            slot = (now - timedelta(hours=back)).astimezone(timezone.utc)
            ok, bad = hours.get(slot.strftime("%Y-%m-%dT%H"), (0, 0))
            hourly.append({"hour": _utc(slot.replace(minute=0, second=0, microsecond=0)), "ok": ok, "not_ok": bad})

        return {
            "as_of": _utc(now),
            "engine": brain.name,
            "users_total": one("SELECT COUNT(*) FROM users"),
            "users_new_today": one("SELECT COUNT(*) FROM users WHERE created_at >= ?", today),
            "logins_today": one("SELECT COUNT(*) FROM logins WHERE created_at >= ?", today),
            "active_today": one(
                "SELECT COUNT(DISTINCT user_id) FROM (SELECT user_id FROM logins WHERE created_at >= ? "
                "UNION SELECT user_id FROM llm_calls WHERE created_at >= ?)", today, today,
            ),
            "entries_saved_today": {
                r["kind"]: r["n"]
                for r in store.all(
                    "SELECT kind, COUNT(*) n FROM entries WHERE status='confirmed' AND confirmed_at >= ? GROUP BY kind",
                    today,
                )
            },
            "calls_today": sum(m["ok"] + m["rate_limited"] + m["errors"] for m in models),
            "calls_limited_today": sum(m["rate_limited"] for m in models),
            "calls_failed_today": sum(m["errors"] for m in models),
            "tokens_today": sum(m["tokens_in"] + m["tokens_out"] for m in models),
            "models_today": models,
            "hourly": hourly,
            "pools": brain.status() if hasattr(brain, "status") else {},
        }

    @app.get("/admin/api/users", dependencies=[Depends(require_admin)])
    def users() -> list[dict[str, Any]]:
        rows = store.all(
            """
            SELECT u.phone, u.created_at,
              (SELECT MAX(created_at) FROM logins l WHERE l.user_id = u.id) last_login,
              (SELECT COUNT(*) FROM logins l WHERE l.user_id = u.id) logins,
              (SELECT COUNT(*) FROM entries e WHERE e.user_id = u.id AND e.status = 'confirmed') saved,
              (SELECT COUNT(*) FROM llm_calls c WHERE c.user_id = u.id) calls,
              (SELECT COUNT(*) FROM llm_calls c WHERE c.user_id = u.id AND c.outcome != 'ok') calls_not_ok,
              (SELECT COALESCE(SUM(COALESCE(tokens_in,0) + COALESCE(tokens_out,0)),0) FROM llm_calls c
                 WHERE c.user_id = u.id) tokens,
              (SELECT MAX(created_at) FROM llm_calls c WHERE c.user_id = u.id) last_call
            FROM users u
            """
        )
        out = [dict(r) for r in rows]
        out.sort(key=lambda r: max(r["last_call"] or "", r["last_login"] or "", r["created_at"]), reverse=True)
        return out

    @app.get("/admin/api/calls", dependencies=[Depends(require_admin)])
    def calls(phone: str = Query(default=""), limit: int = Query(default=200, ge=1, le=1000)) -> list[dict[str, Any]]:
        sql = (
            "SELECT c.created_at, u.phone, c.kind, c.job, c.model, c.outcome, c.ms, c.tokens_in, c.tokens_out, "
            "c.audio_s, c.detail, c.entry_id FROM llm_calls c LEFT JOIN users u ON u.id = c.user_id"
        )
        args: list[Any] = []
        if phone:
            sql += " WHERE u.phone = ?"
            args.append(phone)
        sql += " ORDER BY c.id DESC LIMIT ?"
        args.append(limit)
        return [dict(r) for r in store.all(sql, *args)]

    @app.get("/admin/api/entries", dependencies=[Depends(require_admin)])
    def entries(
        phone: str = Query(default="", description="Only this member, as stored, e.g. +919876543210"),
        date_from: str = Query(default="", alias="from", pattern=r"^(\d{4}-\d{2}-\d{2})?$"),
        date_to: str = Query(default="", alias="to", pattern=r"^(\d{4}-\d{2}-\d{2})?$"),
        limit: int = Query(default=500, ge=1, le=5000),
    ) -> list[dict[str, Any]]:
        """Saved logs, newest first. `from`/`to` are inclusive log dates (the member's local day)."""
        sql = ("SELECT e.id, u.phone, e.kind, e.log_date, e.time_zone, e.created_at, e.confirmed_at, e.body "
               "FROM entries e JOIN users u ON u.id = e.user_id WHERE e.status = 'confirmed'")
        args: list[Any] = []
        for clause, value in (("u.phone = ?", phone), ("e.log_date >= ?", date_from), ("e.log_date <= ?", date_to)):
            if value:
                sql += f" AND {clause}"
                args.append(value)
        sql += " ORDER BY e.confirmed_at DESC LIMIT ?"
        args.append(limit)
        out = []
        for row in store.all(sql, *args):
            body = loads(row["body"])
            entry = {k: row[k] for k in ("id", "phone", "kind", "log_date", "time_zone", "created_at", "confirmed_at")}
            entry["summary"] = body.get("summary", "")
            entry["language"] = body.get("language")
            if row["kind"] == "photo":
                entry["slot"] = body.get("slot")
                entry["items"] = body.get("items", [])
                entry["flags"] = body.get("flags", [])
            else:
                entry["fields"] = [{k: v for k, v in f.items() if k != "label"} for f in body.get("fields", [])]
                entry["red_flags"] = body.get("red_flags", [])
                entry["questions"] = body.get("questions", [])
            out.append(entry)
        return out
