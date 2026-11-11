// ver 1.0.0 | 2026-11-04 20:50
// 폰 페이지 줄바꿈 검사: 화면 폭별로, 브라우저가 저절로 꺾은 글(한 텍스트가 두 줄 이상)을 모두 찾음
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  for (const w of [320, 344, 360, 390, 412, 430, 480, 760]) {
    const ctx = await b.newContext({ viewport: { width: w, height: 900 } });
    const p = await ctx.newPage();
    await p.goto('http://127.0.0.1:8765/verify/index.html');
    await p.click('#demoBtn'); await p.waitForSelector('#result:not(.hidden)'); await p.waitForTimeout(500);
    const r = await p.evaluate(() => { const out = []; const tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      while (tw.nextNode()) { const n = tw.currentNode, el = n.parentElement; if (!n.textContent.trim() || !el.offsetParent) continue;
        if (el.closest('table,script,style')) continue;
        const rg = document.createRange(); rg.selectNodeContents(n); const tops = new Set([...rg.getClientRects()].filter(x => x.width > 0).map(x => Math.round(x.top)));
        const bb = rg.getBoundingClientRect(); const over = bb.right > innerWidth + 0.5;
        if (tops.size > 1 || over) out.push((over ? '[넘침] ' : '') + n.textContent.trim().slice(0, 40) + ' ×' + tops.size + ' @' + el.closest('[id]')?.id); }
      return out; });
    console.log(`${w}px: ${r.length ? '\n  ' + r.join('\n  ') : 'none'}`);
    if (w === 344) { await p.evaluate(() => document.querySelector('#facts').scrollIntoView()); await p.screenshot({ path: '/tmp/vt/r344b.png' }); }
    await ctx.close();
  }
  await b.close();
})();
