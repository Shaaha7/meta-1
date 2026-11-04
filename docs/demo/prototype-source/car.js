// Drives the app's own car.html (unchanged) frame by frame: virtual clock + manual animation frames.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 329, height: 250 }, deviceScaleFactor: 2.75 });
  await p.addInitScript(() => { window.__t = 0; performance.now = () => window.__t * 1000; window.requestAnimationFrame = cb => { window.__raf = cb; return 1; }; });
  await p.goto('file:///home/user/meta-1/UstadApp/app/src/main/assets/ustad/car.html');
  await p.evaluate(() => highlight(['c-battery12']));
  const N = +process.argv[2] || 360, EXP = +process.argv[3] || 150;
  for (let f = 0; f < N; f++) {
    await p.evaluate(([f, EXP]) => { window.__t = f / 30; if (f === EXP) explode(true); window.__raf && window.__raf(window.__t * 1000); }, [f, EXP]);
    await p.screenshot({ path: `/tmp/claude-0/proto/car/c${String(f).padStart(4, '0')}.png` });
  }
  await b.close(); console.log('car frames', N);
})();
