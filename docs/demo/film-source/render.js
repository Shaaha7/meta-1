const { chromium } = require('playwright');
const [a, b] = process.argv.slice(2).map(Number), FPS = 30;
(async () => {
  const br = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const p = await br.newPage({ viewport: { width: 1920, height: 1080 } });
  p.on('pageerror', e => console.log('ERR', e.message));
  await p.goto('file:///tmp/claude-0/film/film.html'); await p.evaluate(() => document.fonts.ready);
  await p.evaluate(l => window.LINES = l, require('/tmp/claude-0/film/lines.json'));
  const fs = require('fs');
  for (let f = a; f < b; f++) {
    const out = `/tmp/claude-0/film/frames/f${String(f).padStart(5, '0')}.jpg`;
    if (fs.existsSync(out)) continue;
    await p.evaluate(t => setT(t), f / FPS);
    await p.screenshot({ path: out + '.tmp.jpg', type: 'jpeg', quality: 92 }); fs.renameSync(out + '.tmp.jpg', out);
    if (f % 100 === 0) console.log(a, 'at', f, new Date().toISOString());
  }
  await br.close(); console.log('done', a, b);
})();
