// ver 1.0.0 | 2026-11-05 10:09
// Real camera path: Chromium's fake camera plays a y4m of one QR; the page scans it with jsQR.
// Prints what the person would see after 3 s (progress text, error box, notice box).
const { chromium } = require('playwright');
const cases = process.argv.slice(2);
(async () => {
  for (const c of cases) {
    const b = await chromium.launch({ args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', `--use-file-for-fake-video-capture=/tmp/qrt/${c}.y4m`] });
    const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, permissions: ['camera'] });
    const p = await ctx.newPage();
    await p.goto('http://127.0.0.1:8765/verify/index.html');
    await p.evaluate(() => { window.__seen = []; const orig = window.takeFrame; window.takeFrame = t => { window.__seen.push(t); return orig(t); }; });
    await p.click('#startBtn');
    await p.waitForTimeout(3000);
    const r = await p.evaluate(() => {
      const t = id => { const e = document.getElementById(id); return e && !e.classList.contains('hidden') && e.offsetParent !== null ? e.textContent.trim() : '(숨김)'; };
      return { prog: t('progText'), err: t('recvErr'), notice: t('recvNote'), video: document.getElementById('video').style.display, decoded: window.__seen.length, sample: window.__seen[0] };
    });
    console.log(c.padEnd(12), JSON.stringify(r));
    await p.screenshot({ path: `/tmp/qrt/${c}_shot.png` });
    await b.close();
  }
})();
