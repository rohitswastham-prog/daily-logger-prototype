"""Lets `uvicorn app.main:app` work from the repo root (Render's usual start command).

The real package is backend/app; this one only points Python at it.
"""

from pathlib import Path

__path__.insert(0, str(Path(__file__).resolve().parent.parent / "backend" / "app"))
