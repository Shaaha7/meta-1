// Ustad backend - keeps the AI and voice API keys off the phones
//
// The app sends its requests here with an app token instead of calling Google / ElevenLabs with
// real keys. This server adds the keys and forwards. If a phone or APK leaks, revoke its token
// here; the paid keys never left the server. Plain Node.js (18+), no dependencies.
//
//   POST /v1/gemini/:model      → Gemini generateContent   (body passed through)
//   POST /v1/openrouter         → OpenRouter chat completions (optional backup)
//   POST /v1/tts/:voiceId       → ElevenLabs text-to-speech (returns MP3)
//   GET  /health
//
// Environment:
//   USTAD_APP_TOKENS   comma-separated tokens the app may use (one per team / device group)
//   GEMINI_API_KEY, ELEVENLABS_API_KEY, OPENROUTER_API_KEY (optional)
//   RATE_PER_MIN       requests per token per minute (default 30)
//   PORT               default 8787

'use strict';
const http = require('http');

const env = process.env;
const TOKENS = new Set((env.USTAD_APP_TOKENS || '').split(',').map(s => s.trim()).filter(Boolean));
const RATE = Number(env.RATE_PER_MIN || 30);
const MAX_BODY = 25 * 1024 * 1024; // photos, short audio clips
const GEMINI_BASE = env.GEMINI_BASE || 'https://generativelanguage.googleapis.com';
const ELEVEN_BASE = env.ELEVEN_BASE || 'https://api.elevenlabs.io';
const OPENROUTER_BASE = env.OPENROUTER_BASE || 'https://openrouter.ai';

const hits = new Map(); // token -> [timestamps]
function allowed(token) {
  const now = Date.now();
  const recent = (hits.get(token) || []).filter(t => now - t < 60_000);
  if (recent.length >= RATE) { hits.set(token, recent); return false; }
  recent.push(now); hits.set(token, recent); return true;
}

function send(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(obj));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', c => { size += c.length; if (size > MAX_BODY) { reject(new Error('too large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

async function forward(res, url, headers, body) {
  const upstream = await fetch(url, { method: 'POST', headers, body });
  const buf = Buffer.from(await upstream.arrayBuffer());
  res.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/octet-stream' });
  res.end(buf);
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { ok: true });
    if (req.method !== 'POST') return send(res, 405, { error: { message: 'POST only' } });

    const token = req.headers['x-ustad-token'];
    if (!token || !TOKENS.has(token)) return send(res, 401, { error: { message: 'Unknown app token' } });
    if (!allowed(token)) return send(res, 429, { error: { message: 'Too many requests, slow down' } });

    const body = await readBody(req);
    let m;
    if ((m = url.pathname.match(/^\/v1\/gemini\/([\w.\-]+)$/))) {
      if (!env.GEMINI_API_KEY) return send(res, 503, { error: { message: 'Server has no GEMINI_API_KEY' } });
      return await forward(res, `${GEMINI_BASE}/v1beta/models/${m[1]}:generateContent`,
        { 'Content-Type': 'application/json', 'x-goog-api-key': env.GEMINI_API_KEY }, body);
    }
    if (url.pathname === '/v1/openrouter') {
      if (!env.OPENROUTER_API_KEY) return send(res, 503, { error: { message: 'Server has no OPENROUTER_API_KEY' } });
      return await forward(res, `${OPENROUTER_BASE}/api/v1/chat/completions`,
        { 'Content-Type': 'application/json', Authorization: `Bearer ${env.OPENROUTER_API_KEY}`, 'X-Title': 'Ustad' }, body);
    }
    if ((m = url.pathname.match(/^\/v1\/tts\/([\w\-]+)$/))) {
      if (!env.ELEVENLABS_API_KEY) return send(res, 503, { error: { message: 'Server has no ELEVENLABS_API_KEY' } });
      const format = url.searchParams.get('output_format') || 'mp3_44100_128';
      return await forward(res, `${ELEVEN_BASE}/v1/text-to-speech/${m[1]}?output_format=${encodeURIComponent(format)}`,
        { 'Content-Type': 'application/json', Accept: 'audio/mpeg', 'xi-api-key': env.ELEVENLABS_API_KEY }, body);
    }
    return send(res, 404, { error: { message: 'Unknown route' } });
  } catch (e) {
    return send(res, e.message === 'too large' ? 413 : 502, { error: { message: `Backend: ${e.message}` } });
  }
});

if (require.main === module) {
  if (!TOKENS.size) console.warn('Warning: USTAD_APP_TOKENS is empty, every request will be refused.');
  const port = Number(env.PORT || 8787);
  server.listen(port, () => console.log(`Ustad backend on :${port}`));
}
module.exports = { server };
