"""Model fallback when Groq rate-limits a model. No network."""

import time

import httpx
import pytest

from app import brain
from app.brain import BrainError, ModelPool, RateLimited, _seconds, rest_time


def test_groq_durations_parse():
    assert _seconds("2.5") == 2.5
    assert _seconds("547ms") == pytest.approx(0.547)
    assert _seconds("5m45.6s") == pytest.approx(345.6)
    assert _seconds("1h2m3s") == 3723
    assert _seconds("soon") is None


def test_rest_time_prefers_retry_after_then_reset_headers():
    assert rest_time(httpx.Response(429, headers={"retry-after": "7"})) == 7
    assert rest_time(httpx.Response(429, headers={"x-ratelimit-reset-tokens": "12.5s"})) == 12.5
    assert rest_time(httpx.Response(429)) == brain.DEFAULT_REST_S


def test_a_limited_model_rests_and_the_next_one_answers():
    pool = ModelPool("log", ["big", "small"])
    calls = []

    def call(model):
        calls.append(model)
        if model == "big":
            raise RateLimited(30, "tokens per minute")
        return {"ok": model}

    assert pool.run(call) == ({"ok": "small"}, "small")
    assert pool.run(call) == ({"ok": "small"}, "small")
    assert calls == ["big", "small", "small"]  # "big" isn't retried while it rests
    assert pool.status()["big"]["limited"] == 1 and pool.status()["small"]["served"] == 2


def test_the_first_model_comes_back_after_its_rest(monkeypatch):
    pool = ModelPool("log", ["big", "small"])
    pool.rest("big", 1)
    now = time.monotonic()
    monkeypatch.setattr(brain.time, "monotonic", lambda: now + 2)
    assert pool.run(lambda m: m) == ("big", "big")


def test_every_model_limited_is_busy(monkeypatch):
    monkeypatch.setattr(brain.time, "sleep", lambda s: None)
    pool = ModelPool("log", ["big", "small"])

    def call(model):
        raise RateLimited(120, "requests per day")

    with pytest.raises(BrainError) as error:
        pool.run(call)
    assert error.value.code == "busy"


def test_a_short_wait_is_taken_when_every_model_is_resting(monkeypatch):
    slept = []
    pool = ModelPool("log", ["only"])
    state = {"n": 0}

    def call(model):
        state["n"] += 1
        if state["n"] == 1:
            raise RateLimited(0.5, "tokens per minute")
        return "ok"

    def fake_sleep(seconds):
        slept.append(seconds)
        pool._resting.clear()

    monkeypatch.setattr(brain.time, "sleep", fake_sleep)
    assert pool.run(call) == ("ok", "only")
    assert len(slept) == 1


def test_a_failing_model_falls_through_to_the_next():
    pool = ModelPool("log", ["flaky", "steady"])

    def call(model):
        if model == "flaky":
            raise BrainError("model_failed")
        return "ok"

    assert pool.run(call) == ("ok", "steady")
