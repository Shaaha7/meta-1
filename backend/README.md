# Ustad backend

Keeps the Gemini and ElevenLabs API keys **off the phones**. The app sends its AI and voice requests here with an **app token**. This server adds the real keys and forwards them. If an APK leaks, revoke its token here; your paid keys never left the server.

Plain Node.js 18+, no dependencies.

## Run

```bash
export USTAD_APP_TOKENS="team-dubai-7f3k,team-sharjah-91qa"   # one per team or device group
export GEMINI_API_KEY="..."
export ELEVENLABS_API_KEY="..."
export RATE_PER_MIN=30                                         # per token
npm start                                                      # listens on :8787
```

Test it locally (it uses a fake Google/ElevenLabs, no keys needed):

```bash
npm test
```

## Deploy without commands (Render, free plan)

1. Push this repo to GitHub.
2. On **render.com** → **New → Blueprint** → choose the repo. Render reads `render.yaml`.
3. Fill in the three secrets it asks for: `USTAD_APP_TOKENS` (make up a long random token), `GEMINI_API_KEY` and `ELEVENLABS_API_KEY`.
4. You get a URL like `https://ustad-backend.onrender.com`. Put it in the app's `local.properties` as `USTAD_BACKEND_URL`, with `USTAD_APP_TOKEN` set to your token.

The free plan sleeps when idle, so the first request after a while takes about 30 s. For a demo, open `/health` in a browser a minute before you start.

Other hosts: `backend/Dockerfile` runs anywhere Docker runs (Railway, Fly.io, a VPS, or a UAE-region cloud VM). Put it behind HTTPS.

## App

In `UstadApp/local.properties`:

```properties
USTAD_BACKEND_URL=https://your-backend.example.com
USTAD_APP_TOKEN=team-dubai-7f3k
```

You can then remove `GEMINI_API_KEY` and `ELEVENLABS_API_KEY` from the app's `local.properties`.

## Routes

| Route | Forwards to |
|---|---|
| `POST /v1/gemini/:model` | Gemini `generateContent` (inspection, lessons, listening, answers) |
| `POST /v1/tts/:voiceId` | ElevenLabs text-to-speech (Ustad's natural voice) |
| `POST /v1/openrouter` | OpenRouter chat completions (optional backup) |
| `GET /health` | `{ "ok": true }` |
