// ver 1.1.0 | 2026-11-05 09:23
// Simulates what a person may have received on their phone, then checks what the page tells them.
const { chromium } = require('playwright');
const fs = require('fs');
const scen = JSON.parse(fs.readFileSync('/tmp/tsfx/scenarios.json', 'utf8'));
const expect = {
  S1_monthly_on_1st: { ok: '1-9', now: [10, 'none'], missing: [] },
  S2_fy_0510_then_0610: { ok: '1-5', now: [6, 'now'], missing: [] },
  S3_month_only_midmonth: { part: [5], none: [1, 2, 3, 4], now: [6, 'now'] },
  S4_forgot_two_months: { ok: '1,4', none: [2, 3], now: [5, 'none'] },
  S5_reclassified_after_export: { ok: '1-5', now: [6, 'now'], missing: [] },
  S6_reinstalled_new_key: { ok: '1-5', part: [6], now: [8, 'none'], keys: 2 },
  S7_empty_phone: { empty: true },
  S8_same_pack_twice: { ok: '1-5', now: [6, 'now'], missing: [] },
  S9_fy_april: { ok: '4', now: [5, 'now'], missing: [] },
  S10_export_during_last_trip: { ok: '1-4', part: [5], now: [6, 'none'] },
  S11_fy_on_last_day_evening: { ok: '1-5', part: [6], now: [7, 'none'] },
  S12_installed_midyear: { pre: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10], now: [11, 'now'], missing: [] },
  S13_installed_midmonth_past: { pre: [1, 2, 3, 4], ok: '5-6', now: [7, 'none'], missing: [] },
};
const rng = s => s.split(',').flatMap(t => { const [a, b] = t.split('-').map(Number); return b ? Array.from({ length: b - a + 1 }, (_, i) => a + i) : [a]; });
(async () => {
  const b = await chromium.launch();
  let fails = 0;
  const rows = [];
  for (const sc of scen) {
    const ctx = await b.newContext(); const p = await ctx.newPage();
    const errs = []; p.on('pageerror', e => errs.push(e.message));
    await p.goto('http://127.0.0.1:8765/verify/index.html');
    const r = await p.evaluate(async ({ packs, today }) => {
      for (const t of packs) await openPack(t, false, true);
      const c = await coverage(today, (loadStore().slice(-1)[0] || {}).plate, false);
      if (!c) return { empty: true };
      await renderCoverage(today);
      return { months: c.months.map(x => [+x.m.split('-')[1], x.st, x.label]), keys: c.keys.length, keyChange: c.keyChange, verdict: document.querySelector('#covVerdict').innerText.split('\n')[0], stored: loadStore().length };
    }, { packs: sc.packs, today: sc.today });
    const ex = expect[sc.name]; const bad = [];
    if (ex.empty) { if (!r.empty) bad.push('expected empty'); }
    else {
      const st = Object.fromEntries(r.months.map(([m, s]) => [m, s]));
      if (ex.ok) for (const m of rng(ex.ok)) if (st[m] !== 'ok') bad.push(`${m}월=${st[m]} (ok 기대)`);
      for (const k of ['none', 'part', 'gap', 'pre']) if (ex[k]) for (const m of ex[k]) if (st[m] !== k) bad.push(`${m}월=${st[m]} (${k} 기대)`);
      if (ex.now) { const [m, s] = ex.now; if (st[m] !== s) bad.push(`이번 달 ${m}월=${st[m]} (${s} 기대)`); }
      if (ex.keys && r.keys !== ex.keys) bad.push(`keys=${r.keys}`);
      if (ex.missing && ex.missing.length === 0 && !r.verdict.startsWith('빠진 기간 없음')) bad.push('verdict: ' + r.verdict);
    }
    if (errs.length) bad.push('page errors: ' + errs.join('; '));
    if (bad.length) fails++;
    rows.push(`${bad.length ? 'FAIL' : 'ok  '} ${sc.name} | ${r.empty ? '(빈 폰)' : r.months.map(([m, s, l]) => `${m}:${s === 'ok' ? '완료' : l}`).join(' ')} | 키 ${r.keys || 0} | ${r.verdict || ''}${bad.length ? '\n      ✗ ' + bad.join(' / ') : ''}`);
    await ctx.close();
  }
  console.log(rows.join('\n'));
  console.log(`\n${scen.length - fails}/${scen.length} scenarios as expected`);
  await b.close();
})();
