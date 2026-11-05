"""Entry point for hosts that start from the repo root (Render): `uvicorn main:app --host 0.0.0.0 --port $PORT`.

The backend lives in backend/app; running from backend/ works too: `uvicorn app.main:app`.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "backend"))

from app.main import app  # noqa: E402,F401  (re-exported for uvicorn)
