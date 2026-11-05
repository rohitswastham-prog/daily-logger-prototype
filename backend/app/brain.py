"""Calls to Groq: Whisper for speech, a text model for the log, a vision model for meal photos.

Each job has a list of models. Groq publishes limits per model, so when one model reaches its limit the next one
in the list answers, and the limited model rests until Groq says it's free again. All models are on the same
account; nothing here spreads use across accounts (Groq's Acceptable Use Policy forbids that).

`FakeBrain` is used when no key is set: nothing leaves the machine and every call returns a sample.
Logs here hold model names, status codes, timings and token counts only, never what the member said or showed.
"""

import base64
import json
import logging
import re
import threading
import time
from contextvars import ContextVar
from collections.abc import Callable
from typing import Any, Protocol, TypeVar

import httpx

from app import logic
from app.config import Settings

log = logging.getLogger("brain")

GROQ = "https://api.groq.com/openai/v1"
STT_PROMPT = "Daily health log in English, Hindi or Marathi, often mixed: meals, walk, sugar reading, weight, feelings."
MAX_RATE_WAIT_S = 8.0  # if every model is resting, wait this long at most before saying "busy"
DEFAULT_REST_S = 60.0
T = TypeVar("T")

# Calls made while handling one request, for the admin analytics (ids, models, counts and timings only).
TRACE: ContextVar[list[dict[str, Any]] | None] = ContextVar("brain_trace", default=None)


def _record(**row: Any) -> None:
    trace = TRACE.get()
    if trace is not None:
        trace.append(row)


class BrainError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code  # "busy" | "model_failed" | "stt_failed"


class RateLimited(Exception):
    def __init__(self, rest_s: float, detail: str) -> None:
        super().__init__(detail)
        self.rest_s, self.detail = rest_s, detail


_DURATION = re.compile(r"(?:(\d+(?:\.\d+)?)h)?(?:(\d+(?:\.\d+)?)m(?!s))?(?:(\d+(?:\.\d+)?)s)?(?:(\d+(?:\.\d+)?)ms)?$")


def _seconds(text: str | None) -> float | None:
    """Groq sends "2.5", "547ms", "5m45.6s" or "1h2m3s"."""
    if not text:
        return None
    text = text.strip()
    try:
        return float(text)
    except ValueError:
        pass
    match = _DURATION.match(text)
    if not match or not any(match.groups()):
        return None
    h, m, s, ms = (float(g) if g else 0.0 for g in match.groups())
    return h * 3600 + m * 60 + s + ms / 1000


def rest_time(response: httpx.Response) -> float:
    """How long a limited model should rest: Groq's retry-after, else its reset headers, else a minute."""
    for header in ("retry-after", "x-ratelimit-reset-tokens", "x-ratelimit-reset-requests"):
        value = _seconds(response.headers.get(header))
        if value is not None and value > 0:
            return value
    return DEFAULT_REST_S


class ModelPool:
    """An ordered list of models for one job, remembering which ones are resting after a rate limit."""

    def __init__(self, job: str, models: list[str]) -> None:
        self.job, self.models = job, models
        self._resting: dict[str, float] = {}
        self._lock = threading.Lock()
        self.served: dict[str, int] = {m: 0 for m in models}
        self.limited: dict[str, int] = {m: 0 for m in models}

    def ready(self) -> list[str]:
        now = time.monotonic()
        with self._lock:
            return [m for m in self.models if self._resting.get(m, 0.0) <= now]

    def rest(self, model: str, seconds: float) -> None:
        with self._lock:
            self._resting[model] = time.monotonic() + max(seconds, 0.5)
            self.limited[model] += 1

    def shortest_rest(self) -> float:
        now = time.monotonic()
        with self._lock:
            return min((until - now for until in self._resting.values()), default=0.0)

    def run(self, call: Callable[[str], T]) -> tuple[T, str]:
        """Try each ready model in order; a rate-limited one rests and the next one answers."""
        failed = False
        for attempt in range(2):
            for model in self.ready():
                try:
                    result = call(model)
                except RateLimited as limited:
                    self.rest(model, limited.rest_s)
                    log.warning("%s: %s limited, resting %.0f s (%s)", self.job, model, limited.rest_s, limited.detail)
                    continue
                except BrainError as error:
                    if error.code != "model_failed":
                        raise
                    failed = True  # this model couldn't answer; the next one may
                    log.warning("%s: %s failed, trying the next model", self.job, model)
                    continue
                with self._lock:
                    self.served[model] += 1
                if model != self.models[0]:
                    log.info("%s: answered by fallback %s", self.job, model)
                return result, model
            wait = self.shortest_rest()
            if attempt == 0 and 0 < wait <= MAX_RATE_WAIT_S:
                log.info("%s: every model resting, waiting %.1f s", self.job, wait)
                time.sleep(wait)
                continue
            break
        if failed and not self.shortest_rest() > 0:
            raise BrainError("model_failed")
        log.warning("%s: every model is at its limit", self.job)
        raise BrainError("busy")

    def status(self) -> dict[str, Any]:
        now = time.monotonic()
        with self._lock:
            return {
                m: {
                    "served": self.served[m],
                    "limited": self.limited[m],
                    "resting_s": max(0, round(self._resting.get(m, 0.0) - now)),
                }
                for m in self.models
            }


class Brain(Protocol):
    name: str

    def transcribe(self, audio: bytes, filename: str, content_type: str) -> tuple[str, str | None]: ...
    def extract_log(self, system: str, user: str) -> dict[str, Any]: ...
    def extract_meal(self, image: bytes, mime: str, note: str) -> dict[str, Any]: ...


class GroqBrain:
    name = "groq"

    def __init__(self, settings: Settings) -> None:
        self.s = settings
        self.http = httpx.Client(timeout=httpx.Timeout(60.0, connect=10.0))
        self.text = ModelPool("log", settings.text_models)
        self.vision = ModelPool("meal", settings.vision_models)
        self.stt = ModelPool("stt", settings.stt_models)

    def status(self) -> dict[str, Any]:
        return {"text": self.text.status(), "vision": self.vision.status(), "stt": self.stt.status()}

    def _post(self, key: str, path: str, job: str, model: str, **kwargs: Any) -> dict[str, Any]:
        what = f"{job} {model}"
        started = time.monotonic()
        try:
            response = self.http.post(f"{GROQ}{path}", headers={"Authorization": f"Bearer {key}"}, **kwargs)
        except httpx.HTTPError as error:
            log.warning("%s: network error %s", what, type(error).__name__)
            _record(job=job, model=model, outcome="network_error", ms=int((time.monotonic() - started) * 1000))
            raise BrainError("model_failed") from error
        ms = int((time.monotonic() - started) * 1000)
        if response.status_code == 429:
            # Groq's message names the limit (requests or tokens, per minute or day); it holds no member data.
            detail = ""
            try:
                message = str(response.json().get("error", {}).get("message", ""))
                # Keep only the limit type ("... on tokens per minute (TPM): ..."), not the org id before it.
                found = re.search(r" on ([a-z ]+\([A-Z]+\))", message)
                detail = found.group(1) if found else "rate limit"
            except ValueError:
                pass
            _record(job=job, model=model, outcome="rate_limited", ms=ms, detail=detail)
            raise RateLimited(rest_time(response), detail)
        if response.status_code >= 400:
            code = ""
            try:
                code = response.json().get("error", {}).get("code", "")
            except ValueError:
                pass
            log.warning("%s: HTTP %s %s after %d ms", what, response.status_code, code, ms)
            _record(job=job, model=model, outcome="error", ms=ms, detail=f"HTTP {response.status_code} {code}".strip())
            raise BrainError("model_failed")
        log.info("%s: ok in %d ms", what, ms)
        data = response.json()
        usage = data.get("usage") or {}
        _record(
            job=job,
            model=model,
            outcome="ok",
            ms=ms,
            tokens_in=usage.get("prompt_tokens"),
            tokens_out=usage.get("completion_tokens"),
            audio_s=data.get("duration"),
        )
        return data

    def transcribe(self, audio: bytes, filename: str, content_type: str) -> tuple[str, str | None]:
        def call(model: str) -> dict[str, Any]:
            return self._post(
                self.s.groq_api_key,
                "/audio/transcriptions",
                "stt",
                model,
                data={"model": model, "response_format": "verbose_json", "temperature": "0", "prompt": STT_PROMPT},
                files={"file": (filename, audio, content_type)},
            )

        try:
            data, _ = self.stt.run(call)
        except BrainError as error:
            raise BrainError("busy" if error.code == "busy" else "stt_failed") from error
        return (data.get("text") or "").strip(), data.get("language")

    def _tool_call(self, key: str, model: str, messages: list[dict], tool: dict, what: str, named: bool) -> dict:
        # With a single tool, "required" forces the same call; some models only accept that form.
        choice: Any = {"type": "function", "function": {"name": tool["name"]}} if named else "required"
        body: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "tools": [{"type": "function", "function": tool}],
            "tool_choice": choice,
            "temperature": 0,
            "max_completion_tokens": 2048,
        }
        if model.startswith("openai/gpt-oss"):
            body["reasoning_effort"] = "low"
        try:
            data = self._post(key, "/chat/completions", what, model, json=body)
        except BrainError as error:
            if error.code != "model_failed":
                raise
            # One retry: a malformed tool call now and then, or a model that wants "required".
            body["tool_choice"] = "required"
            data = self._post(key, "/chat/completions", what, model, json=body)
        usage = data.get("usage") or {}
        log.info("%s %s: tokens in=%s out=%s", what, model, usage.get("prompt_tokens"), usage.get("completion_tokens"))
        message = (data.get("choices") or [{}])[0].get("message") or {}
        for tool_call in message.get("tool_calls") or []:
            function = tool_call.get("function") or {}
            if function.get("name") == tool["name"]:
                try:
                    return json.loads(function.get("arguments") or "{}")
                except json.JSONDecodeError as error:
                    raise BrainError("model_failed") from error
        match = re.search(r"\{.*\}", message.get("content") or "", re.S)
        if match:
            try:
                return json.loads(match.group(0))
            except json.JSONDecodeError:
                pass
        raise BrainError("model_failed")

    def extract_log(self, system: str, user: str) -> dict[str, Any]:
        messages = [{"role": "system", "content": system}, {"role": "user", "content": user}]
        tool = logic.record_log_tool()
        # The qwen vision model rejects a named tool_choice; the gpt-oss models accept it.
        raw, model = self.text.run(
            lambda m: self._tool_call(self.s.groq_api_key, m, messages, tool, "log", named=not m.startswith("qwen/"))
        )
        return {**raw, "_model": model}

    def extract_meal(self, image: bytes, mime: str, note: str) -> dict[str, Any]:
        data_url = f"data:{mime};base64,{base64.b64encode(image).decode()}"
        messages = [
            {"role": "system", "content": logic.meal_system_prompt()},
            {"role": "user", "content": [{"type": "image_url", "image_url": {"url": data_url}}, {"type": "text", "text": note}]},
        ]
        tool = logic.record_meal_tool()
        raw, model = self.vision.run(lambda m: self._tool_call(self.s.vision_key, m, messages, tool, "meal", named=False))
        return {**raw, "_model": model}


class FakeBrain:
    """No key set. Returns the same sample every time so the app can be shown without any network calls."""

    name = "fake"
    SAMPLE = "Had dinner at 8:15, two chapati, dal and sabzi. Walked thirty minutes after. Morning sugar was 118. Felt a bit tired. Can I eat mango?"

    def transcribe(self, audio: bytes, filename: str, content_type: str) -> tuple[str, str | None]:
        _record(job="stt", model="fake", outcome="ok", ms=0)
        return self.SAMPLE, "en"

    def extract_log(self, system: str, user: str) -> dict[str, Any]:
        _record(job="log", model="fake", outcome="ok", ms=0)
        log_date = re.search(r"Log date: (\d{4}-\d{2}-\d{2})", user)
        day = log_date.group(1) if log_date else "2026-01-01"
        return {
            "summary": "Dinner at 8:15 PM, a 30-minute walk after, and morning sugar 118. You felt a bit tired.",
            "language": "en",
            "fields": [
                {"key": "meal", "slot": "dinner", "value": "2 chapati, dal, sabzi", "event_time": f"{day}T20:15+05:30"},
                {"key": "walk", "value": 30, "unit": "min", "context": "after dinner"},
                {"key": "glucose", "value": 118, "unit": "mg/dL", "context": "fasting"},
                {"key": "feeling", "value": "A bit tired"},
            ],
            "red_flags": [],
            "questions": ["Can I eat mango?"],
        }

    def extract_meal(self, image: bytes, mime: str, note: str) -> dict[str, Any]:
        _record(job="meal", model="fake", outcome="ok", ms=0)
        return {
            "is_food": True,
            "items": [
                {"name": "Chapati", "category": "grain", "count": 2},
                {"name": "Dal", "category": "dal_protein"},
                {"name": "Bhindi sabzi", "category": "sabzi"},
                {"name": "Rice", "category": "rice"},
                {"name": "Salad", "category": "salad"},
            ],
            "flags": [],
        }


def make_brain(settings: Settings) -> Brain:
    return FakeBrain() if settings.fake else GroqBrain(settings)
