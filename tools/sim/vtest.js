// ver 1.1.0 | 2026-11-04 20:50
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ acceptDownloads: true, viewport: { width: 420, height: 900 } });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => { errs.push(e.message); console.log('PAGEERROR', e.message); });
  const frames = fs.readFileSync('/tmp/tsfx/frames.txt', 'utf8').trim().split('\n');
  // what a phone camera app does: open the link in frame 0 (camera itself is unavailable headless)
  await p.goto('http://127.0.0.1:8765/verify/index.html' + frames[0].slice(frames[0].indexOf('#')));
  await p.waitForTimeout(500);
  console.log('after open: url hash =', JSON.stringify(await p.evaluate(() => location.hash)), '| progress:', await p.$eval('#progText', e => e.textContent));
  const n = fs.readdirSync('/tmp/vt').filter(f => /^f\d+\.png$/.test(f)).length;
  let last;
  for (let i = n - 1; i >= 1; i--) {
    const data = fs.readFileSync(`/tmp/vt/f${i}.png`).toString('base64');
    last = await p.evaluate(async (d) => {
      const img = new Image(); img.src = 'data:image/png;base64,' + d; await img.decode();
      const c = document.createElement('canvas'); c.width = img.width; c.height = img.height; const x = c.getContext('2d'); x.drawImage(img, 0, 0);
      const im = x.getImageData(0, 0, c.width, c.height); const code = jsQR(im.data, c.width, c.height);
      if (!code) return { decoded: false };
      return Object.assign({ decoded: true, link: code.data.startsWith('https://tripsign.beyondspark.kr/#TSQ1:') }, window.__tsTakeFrame(code.data));
    }, data);
    if (!last.decoded) console.log('decode failed f' + i);
  }
  console.log('frames', n, 'last', JSON.stringify(last));
  await p.evaluate(() => window.__tsFinish()); console.log('recvErr:', await p.$eval('#recvErr', e => e.textContent));
  await p.waitForSelector('#result:not(.hidden)');
  console.log((await p.$eval('#facts', e => e.innerText)).replace(/\n+/g, ' | '));
  console.log('sum:', await p.$eval('#tbl tr.sum', e => e.innerText.replace(/\s+/g, ' ')));
  const [dl] = await Promise.all([p.waitForEvent('download'), p.click('#xMonth')]); await dl.saveAs('/tmp/vt/month.xlsx');
  const [dl2] = await Promise.all([p.waitForEvent('download'), p.click('#xYear')]); await dl2.saveAs('/tmp/vt/year.xlsx');
  // tamper + delete on the stored pack
  const r = await p.evaluate(async () => {
    const lines = current.text.split('\n');
    const i = lines.findIndex(l => l.startsWith('E\t') && l.split('\t')[1].split('|')[3] === 'TRIP');
    const parts = lines[i].split('\t'); const f = parts[1].split('|'); f[8] = (parseFloat(f[8]) + 1).toFixed(3); parts[1] = f.join('|'); lines[i] = parts.join('\t');
    const v = await verifyPack(lines.join('\n'));
    const l2 = current.text.split('\n'); l2.splice(20, 1); const v2 = await verifyPack(l2.join('\n'));
    const v0 = await verifyPack(current.text);
    return { orig: [v0.signOk, v0.linkBad], tamper: [v.signOk, v.linkBad], drop: [v2.signOk, v2.linkBad] };
  });
  console.log('verify', JSON.stringify(r));
  console.log('errors', JSON.stringify(errs));
  await b.close();
})();
