// Tests the proxy against a fake Google / ElevenLabs: auth, key injection, rate limit, routes.
// Run: node test.js
'use strict';
const http = require('http');
const assert = require('assert');

const seen = [];
const upstream = http.createServer((req, res) => {
  let body = ''; req.on('data', c => body += c); req.on('end', () => {
    seen.push({ url: req.url, headers: req.headers, body });
    if (req.url.startsWith('/v1/text-to-speech/')) { res.writeHead(200, { 'Content-Type': 'audio/mpeg' }); return res.end(Buffer.from([0xff, 0xfb, 1, 2])); }
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ candidates: [{ content: { parts: [{ text: '{"ok":true}' }] } }] }));
  });
});

upstream.listen(0, async () => {
  const base = `http://127.0.0.1:${upstream.address().port}`;
  Object.assign(process.env, { USTAD_APP_TOKENS: 'team-a,team-b', GEMINI_API_KEY: 'G-SECRET', ELEVENLABS_API_KEY: 'E-SECRET',
    GEMINI_BASE: base, ELEVEN_BASE: base, RATE_PER_MIN: '3' });
  const { server } = require('./server');
  server.listen(0, async () => {
    const proxy = `http://127.0.0.1:${server.address().port}`;
    const post = (path, token, body = '{}') => fetch(proxy + path, { method: 'POST', headers: token ? { 'X-Ustad-Token': token, 'Content-Type': 'application/json' } : {}, body });
    try {
      assert.strictEqual((await fetch(proxy + '/health')).status, 200);
      assert.strictEqual((await post('/v1/gemini/gemini-flash-latest', null)).status, 401, 'no token');
      assert.strictEqual((await post('/v1/gemini/gemini-flash-latest', 'stolen')).status, 401, 'unknown token');

      const r = await post('/v1/gemini/gemini-flash-latest', 'team-a', '{"contents":[]}');
      assert.strictEqual(r.status, 200);
      assert.deepStrictEqual(await r.json(), { candidates: [{ content: { parts: [{ text: '{"ok":true}' }] } }] });
      const g = seen.at(-1);
      assert.strictEqual(g.url, '/v1beta/models/gemini-flash-latest:generateContent');
      assert.strictEqual(g.headers['x-goog-api-key'], 'G-SECRET', 'server adds the key');
      assert.strictEqual(g.headers['x-ustad-token'], undefined, 'app token not forwarded');
      assert.strictEqual(g.body, '{"contents":[]}');

      const t = await post('/v1/tts/voice123', 'team-a', '{"text":"hi"}');
      assert.strictEqual(t.status, 200);
      assert.strictEqual(t.headers.get('content-type'), 'audio/mpeg');
      assert.strictEqual(seen.at(-1).headers['xi-api-key'], 'E-SECRET');
      assert.ok(seen.at(-1).url.startsWith('/v1/text-to-speech/voice123?output_format='));

      assert.strictEqual((await post('/v1/gemini/x', 'team-a')).status, 200); // 3rd in this minute
      assert.strictEqual((await post('/v1/gemini/x', 'team-a')).status, 429, 'rate limited');
      assert.strictEqual((await post('/v1/gemini/x', 'team-b')).status, 200, 'other token unaffected');
      assert.strictEqual((await post('/v1/nope', 'team-b')).status, 404);
      assert.strictEqual((await post('/v1/gemini/bad%2Fmodel', 'team-b')).status, 404, 'model name is validated');
      console.log('backend: all tests passed');
    } catch (e) { console.error('FAILED:', e.message); process.exitCode = 1; }
    server.close(); upstream.close();
  });
});
