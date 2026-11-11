// ver 1.1.0 | 2026-11-05 09:23
// Every scenario at 360 / 390 / 430 px: no line may overflow, and no short label may wrap on its own.
const { chromium } = require('playwright');
const fs = require('fs');
const scen = JSON.parse(fs.readFileSync('/tmp/tsfx/scenarios.json', 'utf8'));
(async () => {
  const b = await chromium.launch(); let bad = 0, checked = 0;
  for (const w of [320, 344, 360, 390, 412, 430, 480]) for (const sc of scen) {
    const ctx = await b.newContext({ viewport: { width: w, height: 800 } }); const p = await ctx.newPage();
    await p.goto('http://127.0.0.1:8765/verify/index.html');
    const r = await p.evaluate(async ({ packs, today }) => {
      for (const t of packs) await openPack(t, false, true); await renderCoverage(today);
      const out = [];
      const vis = el => el.offsetParent !== null;
      document.querySelectorAll('.lines div, .mon b, .mon span, .tile span, .tile b, h1, h2, .sub2, .facts dt, button').forEach(el => {
        if (!vis(el)) return;
        if (el.scrollWidth > el.clientWidth + 1) out.push('OVERFLOW ' + el.textContent.trim().slice(0, 40));
        // count visual lines of the text itself (not the box height, which min-height can raise)
        const rg = document.createRange(); rg.selectNodeContents(el);
        const fsz = parseFloat(getComputedStyle(el).fontSize);
        const tops = [...rg.getClientRects()].filter(r => r.width > 0).map(r => r.top).sort((x, y) => x - y);
        let lines = tops.length ? 1 : 0; for (let i = 1; i < tops.length; i++) if (tops[i] - tops[i - 1] > fsz * 0.6) lines++;   // mixed font sizes on one line differ by a few px only
        if (lines > 1 && !el.querySelector('br')) out.push('WRAP ' + el.textContent.trim().slice(0, 40));
      });
      if (document.documentElement.scrollWidth > innerWidth + 1) {
        const wide = [...document.querySelectorAll('body *')].filter(e => vis(e) && e.getBoundingClientRect().right > innerWidth + 1 && !e.closest('.tbl')).map(e => e.tagName + '.' + e.className + ':' + e.textContent.trim().slice(0, 30));
        out.push('PAGE WIDER THAN SCREEN ' + wide.slice(-3).join(' ; '));
      }
      return out;
    }, sc);
    checked++;
    if (r.length) { bad++; console.log(w, sc.name, [...new Set(r)].join(' | ')); }
    await ctx.close();
  }
  console.log(`${checked - bad}/${checked} layouts clean`);
  await b.close();
})();
