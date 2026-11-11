// ver 1.3.0 | 2026-11-07 13:25  verify page: '내가 아님' · 경고 전달(음성·알림·화면) · 미인증 운행 · 관리자 수정 · 건너뜀 · 폰 본인확인 (server signature)
const { chromium } = require('playwright');
const crypto = require('crypto');
const car = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
const srv = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
const spki = k => k.export({ type: 'spki', format: 'der' });
const sha = t => crypto.createHash('sha256').update(t, 'utf8').digest('hex');
const SEP = '\u001F';
const carId = sha(spki(car.publicKey).toString('latin1')) ; // placeholder, replaced below
const carHex = crypto.createHash('sha256').update(spki(car.publicKey)).digest('hex').slice(0, 32);
const num = v => v == null ? '-' : v.toFixed(3);
const t0 = Date.UTC(2026, 9, 5, 0, 0);
let prev = '0'.repeat(64), seq = 0; const lines = [];
function add(type, tripId, o = {}) {
  seq++;
  const c = ['v1', seq, t0 + seq * 1000, type, tripId, o.s || 0, o.e || 0, num(o.a), num(o.b), num(o.km || 0), num(o.km || 0), o.km ? 'ODOMETER' : 'NONE', num(0), o.p || '-', (o.note || '').replace(/\|/g, '/'), prev].join('|');
  prev = sha(c); lines.push(['E', seq === 1 ? c : c.slice(0, c.length - 64), ''].join('\t'));
}
const trip = (id, s, a, b, note) => add('TRIP', id, { s, e: s + 600000, a, b, km: b - a, note });
add('WARN', 'W1', { s: t0 + 3500e3, e: t0 + 3500e3, note: 'notice' });   // 1.2.0: warning delivered before T1
add('WARN', 'W2', { s: t0 + 3510e3, e: t0 + 3510e3, note: 'voice' });
trip('T1', t0 + 3600e3, 100, 120, 'unverified');            // 미인증 운행
add('PURPOSE', 'T1', { p: 'BUSINESS' });
trip('T2', t0 + 7200e3, 120, 130);                            // skipped then admin
add('PURPOSE', 'T2', { note: 'skip' });
add('PURPOSE', 'T2', { p: 'COMMUTE', note: 'admin' + SEP + '총무팀' + SEP + '김하늘' });
add('DENY', 'T1', { s: t0 + 10000e3, e: t0 + 10000e3, note: 'notme' });   // 1.3.0: next start, before T3 (홍길동)
trip('T3', t0 + 10800e3, 130, 140);                           // confirmed by phone
const proof = ['tsa1', 'abcdefghijklmnopqrstuvwxyz', carHex, 'confirm', 'T3', 'e'.repeat(64), 'f'.repeat(64), 'ho**@example.com', '010-****-5678', String(t0), String(t0 + 60e3)].join('~');
const psig = crypto.sign('sha256', Buffer.from(proof), srv.privateKey).toString('base64');
add('AUTH', 'T3', { note: proof + SEP + psig });
add('ASSIGN', 'T3', { note: '영업팀' + SEP + '홍길동' });
add('PURPOSE', 'T3', { p: 'BUSINESS' });
add('WARN', 'W3', { s: t0 + 14000e3, e: t0 + 14000e3, note: 'voice' });  // used up by T4: T5 must not show it
trip('T4', t0 + 14400e3, 140, 145);                           // bad proof (other car)
const bad = proof.replace(carHex, '0'.repeat(32)).replace('T3', 'T4');
add('AUTH', 'T4', { note: bad + SEP + crypto.sign('sha256', Buffer.from(bad), srv.privateKey).toString('base64') });
add('ASSIGN', 'T4', { note: '영업팀' + SEP + '홍길동' });
// 1.1.0 (안 B): 미인증 → phone check → administrator's purpose = counts; admin before the check = does not
const mk = (id, ms) => { const x = proof.replace('~T3~', '~' + id + '~').replace(String(t0) + '~', String(ms) + '~'); return x + SEP + crypto.sign('sha256', Buffer.from(x), srv.privateKey).toString('base64'); };
trip('T5', t0 + 18000e3, 145, 153, 'unverified');
add('AUTH', 'T5', { note: mk('T5', t0) });
add('ASSIGN', 'T5', { note: '영업팀' + SEP + '홍길동' });
add('PURPOSE', 'T5', { p: 'BUSINESS', note: 'admin' + SEP + '총무팀' + SEP + '김하늘' });
add('WARN', 'W4', { s: t0 + 21000e3, e: t0 + 21000e3, note: 'screen' });
add('WARN', 'W5', { s: t0 + 21700e3, e: t0 + 21700e3, note: 'voice' });   // after T6 started: not a warning for T6
trip('T6', t0 + 21600e3, 153, 156, 'unverified');
add('PURPOSE', 'T6', { p: 'BUSINESS', note: 'admin' + SEP + '총무팀' + SEP + '김하늘' });
add('AUTH', 'T6', { note: mk('T6', t0) });
add('ASSIGN', 'T6', { note: '영업팀' + SEP + '홍길동' });
add('DENY', 'T6', { s: t0 + 23000e3, e: t0 + 23000e3, note: 'notme' });   // no drive after it yet
// sign the head
const last = lines[lines.length - 1].split('\t');
last[2] = crypto.sign('sha256', Buffer.from(prev), car.privateKey).toString('base64'); lines[lines.length - 1] = last.join('\t');
const head = ['TS1', 'from=2026-10', 'to=2026-10', 'fy=1', 'tzo=540', 'pub=' + spki(car.publicKey).toString('base64')].join('\t');
const text = head + '\n' + lines.join('\n');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 420, height: 900 } });
  const errs = []; p.on('pageerror', e => errs.push(String(e)));
  await p.goto('http://127.0.0.1:8765/verify/index.html');
  const out = await p.evaluate(async ({ text, sk }) => {
    authKeyP = crypto.subtle.importKey('spki', Uint8Array.from(atob(sk), c => c.charCodeAt(0)), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    const v = await verifyPack(text);
    const { rows } = buildRows(v.entries);
    return { signOk: v.signOk, link: v.linkBad, rows: rows.map(r => ({ id: r.id, unv: r.unverified, skip: r.skipped, admin: r.admin, authOk: r.authOk, authBad: r.authBad, settled: r.settled, counts: r.counts, rem: remark(r) })), t: totals(rows) };
  }, { text, sk: spki(srv.publicKey).toString('base64') });
  console.log(JSON.stringify(out, null, 1));
  const ok = out.signOk && out.link == null && out.rows[0].unv && out.rows[1].admin === '김하늘' && out.rows[1].skip && out.rows[2].authOk && !out.rows[2].authBad && out.rows[3].authBad && !out.rows[3].authOk
    && out.rows[4].settled && out.rows[4].counts && out.rows[4].authOk && out.rows[4].rem.includes('사후 확인 후 관리자 구분')
    && !out.rows[5].settled && !out.rows[5].counts && out.rows[5].authOk
    && out.rows[0].rem.includes('경고: 음성·알림') && out.rows[4].rem.includes('경고 전달 기록 없음') && out.rows[5].rem.includes('경고: 화면') && !out.rows[5].rem.includes('음성')
    && !out.rows[2].rem.includes('경고') && out.rows[0].rem.includes("'내가 아님' 1회(이어서 운전: 홍길동)") && out.rows[5].rem.includes("'내가 아님' 1회(이어서 운전: 아직 없음)") && !out.rows[4].rem.includes('내가 아님') && out.t.unv === 23 && out.t.unvN === 2 && out.t.biz === 18 && out.t.com === 10 && out.t.work === 28 && out.t.km === 56;
  console.log(ok ? 'ALL OK' : 'FAIL', 'errors', errs);
  await b.close();
})();
