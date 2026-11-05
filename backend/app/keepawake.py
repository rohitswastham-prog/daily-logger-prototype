"""Keeps a Render free-plan service awake.

Render puts a free web service to sleep after 15 minutes without an incoming request, and the next request then
waits ~50 s while it starts. While the service runs, this thread calls its own public /health every 10 minutes,
which counts as incoming traffic. /health makes no Groq calls.

On only when Render's RENDER_EXTERNAL_URL is set (never locally or in tests). KEEP_AWAKE=0 turns it off.
A service on 24/7 uses ~744 of the free plan's 750 instance hours a month, so it's meant for one free service.
"""

import logging
import os
import threading

import httpx

log = logging.getLogger("keepawake")
INTERVAL_S = 10 * 60


def target_url() -> str | None:
    if os.getenv("KEEP_AWAKE", "1").strip() == "0":
        return None
    base = os.getenv("RENDER_EXTERNAL_URL", "").strip()
    return f"{base.rstrip('/')}/health" if base else None


def start(stop: threading.Event) -> threading.Thread | None:
    url = target_url()
    if url is None:
        return None

    def loop() -> None:
        log.info("pinging %s every %d min", url, INTERVAL_S // 60)
        while not stop.wait(INTERVAL_S):
            try:
                status = httpx.get(url, timeout=30).status_code
                if status != 200:
                    log.warning("ping got HTTP %s", status)
            except httpx.HTTPError as error:
                log.warning("ping failed: %s", type(error).__name__)

    thread = threading.Thread(target=loop, name="keepawake", daemon=True)
    thread.start()
    return thread
