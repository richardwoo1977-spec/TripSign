// ver 1.0.0 | 2026-11-04 20:50
const { chromium } = require('playwright');
const fs = require('fs');
const scen = Object.fromEntries(JSON.parse(fs.readFileSync('/tmp/tsfx/scenarios.json', 'utf8')).map(s => [s.name, s]));
const trip = fs.readFileSync('/tmp/tsfx/s5_trip.txt', 'utf8').trim();
(async () => {
  const b = await chromium.launch();
  for (const name of ['S5_reclassified_after_export', 'S8_same_pack_twice', 'S6_reinstalled_new_key', 'S4_forgot_two_months']) {
    const ctx = await b.newContext({ acceptDownloads: true }); const p = await ctx.newPage();
    await p.goto('http://127.0.0.1:8765/verify/index.html');
    const r = await p.evaluate(async ({ packs, trip }) => {
      for (const t of packs) await openPack(t, false, true);
      const m = await mergedRows(yearPacks(), yearMonthsCovered());
      const ids = m.rows.map(x => x.id);
      const t5 = m.rows.find(x => x.id === trip);
      return { rows: ids.length, unique: new Set(ids).size, months: yearMonthsCovered().length, t5: t5 ? [t5.purpose, t5.changes] : null };
    }, { packs: scen[name].packs, trip });
    const [dl] = await Promise.all([p.waitForEvent('download'), p.click('#xYear')]); await dl.saveAs(`/tmp/vt/${name}.xlsx`);
    console.log(name, JSON.stringify(r));
    await ctx.close();
  }
  await b.close();
})();
