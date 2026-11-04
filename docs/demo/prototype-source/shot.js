const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.goto('file:///tmp/claude-0/proto/proto.html'); await p.evaluate(() => document.fonts.ready);
  await p.evaluate(tl => init(tl), require('/tmp/claude-0/proto/timeline.json'));
  const mode = process.argv[2];
  if (mode === 'all') {
    const fs = require('fs'); fs.mkdirSync('/tmp/claude-0/proto/frames', { recursive: true });
    const total = require('/tmp/claude-0/proto/timeline.json').total, N = Math.ceil(total * 30);
    const A = +(process.argv[3] || 0), B = +(process.argv[4] || N);
    for (let f = A; f < B; f++) { await p.evaluate(t => setT(t), f / 30); await p.screenshot({ path: `/tmp/claude-0/proto/frames/f${String(f).padStart(5, '0')}.jpg`, type: 'jpeg', quality: 92 }); }
    console.log('frames', N);
  } else for (const t of process.argv.slice(2).map(Number)) { await p.evaluate(t => setT(t), t); await p.screenshot({ path: `/tmp/claude-0/proto/test_${t}.jpg`, type: 'jpeg', quality: 85 }); }
  console.log(errs.join('\n')); await b.close();
})();
