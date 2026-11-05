# Daily Log: prototype app

A standalone demo of the Daily Log. It's separate from the Swastham app and the AWS backend, and shares no code
with them.

| Piece | What it is |
| --- | --- |
| `backend/` | FastAPI + SQLite. Phone sign-in, Groq LLM (`openai/gpt-oss-120b`), Groq Whisper (`whisper-large-v3`), Groq vision (`qwen/qwen3.8-27b`) |
| `android/` | Kotlin + Jetpack Compose app, package `com.dailylog.prototype` |

> **Test data only.** Groq processes data in the US, and sign-in has no OTP: anyone who types a number gets
> that account. Don't let real patients use this build.

## 1. Add the Groq keys

```
cd backend
copy .env.example .env      (PowerShell: Copy-Item .env.example .env)
```

Open `backend/.env` and paste:
- `GROQ_API_KEY=` the key for the LLM and speech-to-text.
- `GROQ_VISION_API_KEY=` the key for photos. Leave it empty to use the first key for photos too.

Without a key, the backend runs in **fake mode**. Every entry gets a sample card, the card says "Sample card",
and nothing leaves your PC.

## 2. Run the backend

```
cd backend
uv sync
uv run uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Check it at http://localhost:8000/health. `"brain": "groq"` means the keys loaded; `"fake"` means fake mode.
Run the tests with `uv run pytest` (15 tests; they never call Groq).

## 3. Install the app

**Emulator:** the default build already points at your PC (`http://10.0.2.2:8000/`).

```
cd android
gradlew installDebug
```

**A real phone on the same Wi-Fi:**
1. Find your PC's IP with `ipconfig` (e.g. 192.168.1.20).
2. Build with that address:
   ```
   gradlew assembleRelease -PbackendUrl=http://192.168.1.20:8000/
   ```
3. Send `android/app/build/outputs/apk/release/app-release.apk` to the phone and install it.
4. Allow port 8000 through Windows Firewall if the phone can't connect.

To show it outside your Wi-Fi, put the backend behind an HTTPS tunnel (e.g. `cloudflared tunnel --url
http://localhost:8000`) and build with `-PbackendUrl=<tunnel URL>/`.

## Host it on Render (no PC needed)

`render.yaml` is a Render Blueprint for the backend: Python 3.11, Singapore region, `/health` check, SQLite on a
1 GB disk (Starter plan). Free-plan notes are at the bottom of the file.

1. Put this folder in a Git repo. The root `.gitignore` keeps `backend/.env`, the database and APKs out.
2. In Render: **New → Blueprint**, pick the repo, and enter `GROQ_API_KEY` when asked.
3. Build the app against the Render URL:
   `gradlew assembleRelease -PbackendUrl=https://<your-service>.onrender.com/`

## What the app does

1. **Sign in:** enter a 10-digit mobile number. A new number registers; a known one signs in.
2. **Speak:** tap the mic and talk in English, Hindi or Marathi (mixing is fine). Whisper transcribes it, and the
   LLM turns it into a card: meals, walk, sugar reading, weight, how you felt.
3. **Type:** use "Or type instead…" for the same card.
4. **Photo:** take one with the camera or pick one from the gallery, choose the meal, and the vision model lists
   what's on the plate.
5. **Confirm:** edit or remove rows, then Save. Nothing is saved until Save; Back discards the draft.
6. **History:** the 7-day strip shows which days have entries. Tap a past day to log for it.

## Groq limits and model fallback

Groq limits each model separately, per account. When a model reaches its limit, the next one in the list answers,
and the limited model rests until Groq says it's free again (`.env`: `TEXT_MODELS`, `VISION_MODELS`,
`STT_MODELS`). If every model is resting, the app shows "The AI is busy right now. Try again in a minute."

| Job | Order | Burst test, one minute (5 Nov) |
| --- | --- | --- |
| Typed / voice card | `gpt-oss-120b` → `gpt-oss-20b` → `qwen3.8-27b` | ~12–18 cards a minute (about 5 without fallback) |
| Speech-to-text | `whisper-large-v3` → `whisper-large-v3-turbo` | 25 of 25 clips (v3 caps at 20 a minute) |
| Photo | `qwen3.8-27b` only | ~3 photos a minute |

`GET /health` shows how many calls each model answered and which are resting. All models run on one account.
Spreading use across several Groq accounts breaks Groq's Acceptable Use Policy, so the backend doesn't do it.

## Guardrails built in

| Guardrail | Where |
| --- | --- |
| The model must answer through one fixed tool (`record_log` / `record_meal`) | `backend/app/brain.py` |
| Every field is checked: known item, number in range, unit forced, valid slot and time | `backend/app/logic.py` `check_field` |
| Red flags kept only if on the list; questions shown, never answered | `logic.check_log` |
| The summary is rebuilt from the fields if it mentions a number that isn't in them | `logic.summary_is_grounded` |
| The member's text can't close the prompt frame (`<<<` / `>>>` stripped) | `logic.log_user_message` |
| Edits are checked again on Save | `main.py` `confirm` |
| One member can't see or save another's entries | `main.py`, tested |
| Errors never echo what was sent; logs hold ids, counts and timings only | `main.py`, `brain.py` |
| Audio is deleted from the phone after upload; photos are resized and re-encoded (no location data) | `android/.../media/` |
| The same entry id never calls the model twice | `main.py` `existing` |
| Photo cap per day (4 by default) | `.env` `PHOTO_DAILY_CAP` |

The red-flag list and plausible ranges in `logic.py` are illustrative, not clinical.
