"""Settings from the environment (`backend/.env`)."""

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

BACKEND_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BACKEND_DIR / ".env")

MB = 1024 * 1024


@dataclass(frozen=True)
class Settings:
    groq_api_key: str | None
    groq_vision_api_key: str | None
    text_models: list[str]
    vision_models: list[str]
    stt_models: list[str]
    db_path: Path
    photo_daily_cap: int
    max_text_chars: int
    max_audio_bytes: int
    max_image_bytes: int
    admin_token: str | None = None

    @property
    def fake(self) -> bool:
        """No key: nothing is sent anywhere, every entry gets a sample card."""
        return not self.groq_api_key

    @property
    def vision_key(self) -> str | None:
        return self.groq_vision_api_key or self.groq_api_key


def _int(name: str, default: int) -> int:
    value = os.getenv(name, "").strip()
    return int(value) if value else default


def _models(list_name: str, single_name: str, default: list[str]) -> list[str]:
    """`TEXT_MODELS=a,b,c` sets the whole order; `TEXT_MODEL=a` only changes the first choice."""
    listed = [m.strip() for m in os.getenv(list_name, "").split(",") if m.strip()]
    if listed:
        return listed
    first = os.getenv(single_name, "").strip()
    return [first, *(m for m in default if m != first)] if first else default


def load_settings() -> Settings:
    return Settings(
        groq_api_key=os.getenv("GROQ_API_KEY", "").strip() or None,
        groq_vision_api_key=os.getenv("GROQ_VISION_API_KEY", "").strip() or None,
        text_models=_models("TEXT_MODELS", "TEXT_MODEL", ["openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b"]),
        vision_models=_models("VISION_MODELS", "VISION_MODEL", ["qwen/qwen3.8-27b"]),
        stt_models=_models("STT_MODELS", "STT_MODEL", ["whisper-large-v3", "whisper-large-v3-turbo"]),
        db_path=Path(os.getenv("DB_PATH", "").strip() or BACKEND_DIR / "prototype.db"),
        photo_daily_cap=_int("PHOTO_DAILY_CAP", 4),
        max_text_chars=_int("MAX_TEXT_CHARS", 2000),
        max_audio_bytes=_int("MAX_AUDIO_MB", 20) * MB,
        max_image_bytes=_int("MAX_IMAGE_MB", 4) * MB,
        admin_token=os.getenv("ADMIN_TOKEN", "").strip() or None,
    )
