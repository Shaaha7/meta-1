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

Deploy anywhere that runs Node: Render, Railway, Fly.io, a VPS, or a UAE-region cloud VM. Put it behind HTTPS.

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
