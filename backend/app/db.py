"""SQLite storage. One file, created on start. Prototype only: test data, no real members."""

import json
import sqlite3
import threading
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    phone TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS entries (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,              -- text | voice | photo
    log_date TEXT NOT NULL,          -- member's local date, YYYY-MM-DD
    time_zone TEXT NOT NULL,
    status TEXT NOT NULL,            -- draft | confirmed | discarded
    body TEXT NOT NULL,              -- the draft (then the saved card) as JSON
    created_at TEXT NOT NULL,
    confirmed_at TEXT
);
CREATE INDEX IF NOT EXISTS entries_user_day ON entries(user_id, log_date, status);
CREATE TABLE IF NOT EXISTS logins (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    is_new INTEGER NOT NULL,
    created_at TEXT NOT NULL
);
-- One row per Groq call, for the admin analytics. Ids, models, counts and timings only: never content.
CREATE TABLE IF NOT EXISTS llm_calls (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT REFERENCES users(id) ON DELETE CASCADE,
    entry_id TEXT,
    kind TEXT,                       -- text | voice | photo
    job TEXT NOT NULL,               -- stt | log | meal
    model TEXT NOT NULL,
    outcome TEXT NOT NULL,           -- ok | rate_limited | error | network_error
    ms INTEGER,
    tokens_in INTEGER,
    tokens_out INTEGER,
    audio_s REAL,
    detail TEXT,                     -- limit type or HTTP code, never member data
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS llm_calls_time ON llm_calls(created_at);
CREATE INDEX IF NOT EXISTS llm_calls_user ON llm_calls(user_id, created_at);
"""


class Store:
    def __init__(self, path: Path) -> None:
        self.lock = threading.Lock()
        self.conn = sqlite3.connect(str(path), check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA foreign_keys = ON")
        self.conn.executescript(SCHEMA)

    def one(self, sql: str, *args: Any) -> sqlite3.Row | None:
        with self.lock:
            return self.conn.execute(sql, args).fetchone()

    def all(self, sql: str, *args: Any) -> list[sqlite3.Row]:
        with self.lock:
            return self.conn.execute(sql, args).fetchall()

    def run(self, sql: str, *args: Any) -> None:
        with self.lock:
            self.conn.execute(sql, args)
            self.conn.commit()


def dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False)


def loads(value: str) -> Any:
    return json.loads(value)
