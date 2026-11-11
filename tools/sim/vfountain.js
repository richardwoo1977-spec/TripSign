// ver 1.0.0 | 2026-11-04 20:50
// 폰 페이지 TSQ2(분수 부호) 받기 시험: 차(Kotlin)와 같은 계수 · 무작위 프레임 복원 · 실제 QR 이미지로 끝까지
const { chromium } = require('playwright');
const fs = require('fs');
const D = '/tmp/tsfx3';
(async () => {
  const b = await chromium.launch();
  const p = await (await b.newContext({ viewport: { width: 390, height: 844 } })).newPage();
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.goto('http://127.0.0.1:8765/verify/index.html');
  let fail = 0; const ok = (c, m) => { console.log((c ? 'ok   ' : 'FAIL ') + m); if (!c) fail++; };
  // 1. 계수 비트가 Kotlin 과 같은가
  const k45 = Math.ceil(20000 / 450);
  for (const line of fs.readFileSync(D + '/fountain_coef.txt', 'utf8').trim().split('\n')) {
    const [j, words] = line.split(' ');
    const js = await p.evaluate(([j, k]) => window.__tsFountainCoef(0xc0de, j, k).join(','), [Number(j), k45]);
    ok(js === words, `coef j=${j}`);
  }
  // 2. 무작위 조합 프레임만으로 복원 (임의 바이트)
  const r2 = await p.evaluate(frames => { let st; for (const f of frames) { st = window.__tsTakeFrame(f); if (st.done) break; }
    const out = joined(); return { st, b64: out ? btoa(String.fromCharCode(...out)) : null }; }, fs.readFileSync(D + '/fountain_frames.txt', 'utf8').trim().split('\n'));
  ok(r2.b64 === fs.readFileSync(D + '/fountain_payload.b64', 'utf8').trim(), `random payload rebuilt from ${r2.st.n}/${r2.st.total}`);
  // 3. 사업연도 예시 묶음: 40%만 읽힌 상태로 중간부터 → 압축 풀기까지
  await p.reload();
  const yf = fs.readFileSync(D + '/fountain_year_frames.txt', 'utf8').trim().split('\n');
  const r3 = await p.evaluate(async frames => { const t0 = performance.now(); let used = 0, st;
    for (const f of frames) { used++; st = window.__tsTakeFrame(f); if (st.done) break; }
    const t1 = performance.now(); const text = await gunzip(joined()); return { used, st, ms: Math.round(t1 - t0), text }; }, yf);
  ok(r3.text === fs.readFileSync('/tmp/tsfx3/demo_year_payload.txt', 'utf8'), `year pack rebuilt: used ${r3.used} frames for k=${r3.st.total}, decode ${r3.ms} ms total`);
  // 4. 실제 QR 이미지 (서명된 묶음) → jsQR → 받기 → 서명 확인 결과 화면
  await p.reload();
  const n = fs.readdirSync(D).filter(f => /^q\d+\.png$/.test(f)).length; let last;
  for (let i = 0; i < n; i++) {
    const data = fs.readFileSync(`${D}/q${i}.png`).toString('base64');
    last = await p.evaluate(async d => { const img = new Image(); img.src = 'data:image/png;base64,' + d; await img.decode();
      const c = document.createElement('canvas'); c.width = img.width; c.height = img.height; const x = c.getContext('2d'); x.drawImage(img, 0, 0);
      const im = x.getImageData(0, 0, c.width, c.height); const code = jsQR(im.data, c.width, c.height); if (!code) return { decoded: false };
      return Object.assign({ decoded: true, link: code.data.startsWith('https://tripsign.beyondspark.kr/#TSQ2:') }, window.__tsTakeFrame(code.data)); }, data);
    if (!last.decoded) ok(false, 'jsQR q' + i);
    if (last.done) break;
  }
  ok(last.done && last.link, `QR images: done=${last.done} ${last.n}/${last.total}`);
  await p.evaluate(() => window.__tsFinish()); await p.waitForSelector('#result:not(.hidden)', { timeout: 10000 });
  const facts = (await p.$eval('#facts', e => e.innerText)).replace(/\n+/g, ' | ');
  ok(/서명 \| 유효/.test(facts), 'signature valid on result screen: ' + facts.slice(0, 80));
  ok(await p.$eval('#progText', e => e.textContent) === '받기 완료', 'progress text');
  // 5. 링크로 처음 열렸을 때 (주소창 # 에 TSQ2 프레임)
  const first = fs.readFileSync(D + '/frames2.txt', 'utf8').split('\n')[0];
  await p.goto('about:blank');   // a hash-only goto would not reload the page
  await p.goto('http://127.0.0.1:8765/verify/index.html' + first.slice(first.indexOf('#'))); await p.waitForTimeout(400);
  ok(await p.evaluate(() => location.hash) === '' && /^1 \/ \d+장 받음$/.test(await p.$eval('#progText', e => e.textContent)), 'opened from a TSQ2 link: ' + await p.$eval('#progText', e => e.textContent));
  // 6. 망가진/남의/중복 프레임
  await p.reload();
  const r6 = await p.evaluate(f => { const a = window.__tsTakeFrame(f[1]).ok, dup = window.__tsTakeFrame(f[1]).ok, bad = window.__tsTakeFrame(f[2].slice(0, -2) + 'AA').ok; return { a, dup, bad }; }, fs.readFileSync(D + '/frames2.txt', 'utf8').split('\n'));
  ok(r6.a === true && r6.dup === false && r6.bad === false, 'duplicate and broken frames ignored ' + JSON.stringify(r6));
  ok(errs.length === 0, 'no page errors ' + JSON.stringify(errs));
  console.log(fail ? `${fail} FAILED` : 'ALL OK');
  await b.close(); process.exit(fail ? 1 : 0);
})();
