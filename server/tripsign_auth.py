# ver 1.0.1 | 2026-11-07 16:46
"""TripSign 본인확인 서버 (tripsign.beyondspark.kr/auth).

차 화면의 QR → 폰 브라우저 → 이메일 6자리 인증(+전화번호 입력) → 폰에 뜬 '차 확인번호'를 차에 입력
→ 서버가 서명한 확인서(assertion)를 차가 받아 원장에 넣는다. 나중에 폰 검증 페이지가 인터넷 없이도 이 서명을 확인한다.

증빙의 뜻: "그 시각, 그 차 화면 앞에서, 이 이메일·전화번호의 주인이 본인확인을 했다". 운전석에 앉았는지는 증명하지 않는다.

- 파이썬 표준 라이브러리만 쓴다(눈물병 서비스·가상환경과 엮이지 않게). 서명은 서버의 openssl 로 한다(ECDSA P-256, SHA-256).
- 127.0.0.1 에서만 듣는다. 아파치가 /auth/api/ 만 이쪽으로 넘긴다.
- 기록: 같은 폴더의 var/auth.db(SQLite). 메일 주소·전화번호는 확인서 발급 기록에만 남고(보관 5년), 확인서에는 가린 값과 해시만 들어간다.
- 비밀값(네이버 클라우드 키)은 설정 파일에만 있고 기록에 남기지 않는다.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

log = logging.getLogger("tripsign.auth")

SESSION_TTL_S = 300          # 차가 세션을 만든 뒤 전체 유효 시간 (1.0.1: 10분 → 5분. 실측 56초, 처음 쓰는 사람·실수 여유)
QR_STEP_S = 10               # 차 화면의 QR 이 바뀌는 간격
QR_SKEW_STEPS = 3            # 찍은 QR 이 이만큼(약 30초) 지난 것까지 받는다 → 사진을 돌려 찍기 어렵게
CODE_TTL_S = 300             # 메일 번호 유효 시간 (1.0.1: 세션과 같게)
CODE_MAX_TRIES = 5
CODE_MAX_SENDS = 3           # 세션 하나당 메일 보내기 횟수
CAR_CODE_TTL_S = 180         # 폰에 뜬 차 확인번호를 차에 넣어야 하는 시간
CAR_CODE_MAX_TRIES = 5
RETENTION_S = 5 * 365 * 86400
PURPOSES = ("enroll", "confirm")
ID_SALT = "tripsign-id|"

EMAIL_RE = re.compile(r"^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$")
PHONE_RE = re.compile(r"^01[016789][0-9]{7,8}$")
SID_RE = re.compile(r"^[a-z2-7]{26}$")
CAR_RE = re.compile(r"^[0-9a-f]{16,64}$")
TARGET_RE = re.compile(r"^[A-Za-z0-9:_\-]{1,48}$")


# ---------------------------------------------------------------- config

def load_env(path: str) -> dict:
    env = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    except FileNotFoundError:
        pass
    return env


class Config:
    def __init__(self, env: dict):
        self.host = env.get("HOST", "127.0.0.1")
        self.port = int(env.get("PORT", "8100"))
        self.db = env.get("DB_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "var", "auth.db"))
        self.key_pem = env.get("SIGN_KEY", os.path.join(os.path.dirname(os.path.abspath(__file__)), "var", "sign_key.pem"))
        self.openssl = env.get("OPENSSL", "openssl")
        self.access_key = env.get("NCLOUD_ACCESS_KEY", "")
        self.secret_key = env.get("NCLOUD_SECRET_KEY", "")
        self.service_id = env.get("NCLOUD_MAIL_SERVICE_ID", "")
        self.sender = env.get("TRIPSIGN_MAIL_FROM", "") or env.get("MAIL_FROM", "")
        self.sender_name = env.get("TRIPSIGN_MAIL_NAME", "트립사인")
        # 시험 모드: 메일 키가 없을 때. 번호를 응답에 실어 준다(운영 서버에서는 절대 켜지 않는다 — TEST_ECHO=1 일 때만)
        self.test_echo = env.get("TEST_ECHO", "") == "1"

    @property
    def mail_ready(self) -> bool:
        return bool(self.access_key and self.secret_key and self.service_id and self.sender)


# ---------------------------------------------------------------- helpers

def now_s() -> float:
    return time.time()


def b32id() -> str:
    return base64.b32encode(secrets.token_bytes(16)).decode("ascii").lower().rstrip("=")


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def id_hash(kind: str, value: str) -> str:
    return sha(ID_SALT + kind + "|" + value)


def norm_email(raw: str):
    e = (raw or "").strip().lower()
    return e if EMAIL_RE.match(e) and len(e) <= 120 else None


def norm_phone(raw: str):
    p = re.sub(r"[^0-9]", "", raw or "")
    return p if PHONE_RE.match(p) else None


def mask_email(e: str) -> str:
    local, _, dom = e.partition("@")
    keep = local[:2] if len(local) > 2 else local[:1]
    return keep + "*" * max(1, len(local) - len(keep)) + "@" + dom


def mask_phone(p: str) -> str:
    return p[:3] + "-****-" + p[-4:]


def qr_mac(key: bytes, sid: str, step: int) -> str:
    return hmac.new(key, f"{sid}.{step}".encode("ascii"), hashlib.sha256).hexdigest()[:16]


def claim_mac(key: bytes, sid: str, car_code: str) -> str:
    return hmac.new(key, f"claim.{sid}.{car_code}".encode("ascii"), hashlib.sha256).hexdigest()[:32]


def six_digits() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


def sign_text(cfg: Config, text: str) -> str:
    """ECDSA P-256 / SHA-256, DER 서명을 Base64 로. 차(Java SHA256withECDSA)와 폰(WebCrypto, DER→raw 변환)이 확인한다."""
    out = subprocess.run([cfg.openssl, "dgst", "-sha256", "-sign", cfg.key_pem], input=text.encode("utf-8"),
                         capture_output=True, timeout=10, check=True)
    return base64.b64encode(out.stdout).decode("ascii")


def public_key_b64(cfg: Config) -> str:
    out = subprocess.run([cfg.openssl, "ec", "-in", cfg.key_pem, "-pubout", "-outform", "DER"], capture_output=True, timeout=10, check=True)
    return base64.b64encode(out.stdout).decode("ascii")


# ---------------------------------------------------------------- mail (네이버 클라우드 SENS 메일 v2)

SENS_URL = "https://sens.apigw.ntruss.com"


def sens_signature(secret_key: str, method: str, uri: str, ts: str, access_key: str) -> str:
    msg = f"{method} {uri}\n{ts}\n{access_key}".encode("utf-8")
    return base64.b64encode(hmac.new(secret_key.encode("utf-8"), msg, hashlib.sha256).digest()).decode("ascii")


def mail_html(code: str) -> str:
    return (
        '<div style="font-family:\'Apple SD Gothic Neo\',\'Noto Sans KR\',sans-serif;max-width:520px;margin:0 auto;padding:24px;color:#16243A">'
        '<div style="font-size:16px;font-weight:700">트립사인 본인확인</div>'
        '<p style="font-size:14px;line-height:1.7;color:#374151">아래 6자리 번호를 폰 화면에 입력해 주세요. 10분 동안 쓸 수 있어요.</p>'
        f'<div style="font-size:40px;font-weight:800;letter-spacing:10px;padding:18px 0;text-align:center;background:#EEF4FF;border-radius:12px">{code}</div>'
        '<p style="font-size:13px;line-height:1.7;color:#4B5563">직접 요청하지 않으셨다면 이 메일은 무시하셔도 됩니다. 이 번호를 다른 사람에게 알려 주지 마세요.</p>'
        '<p style="font-size:12px;color:#6B7280">보내기 전용 메일입니다 · 주식회사 비욘드스파크</p></div>'
    )


def send_code_mail(cfg: Config, to: str, code: str) -> None:
    if not cfg.mail_ready:
        log.warning("mail not configured: code mail skipped (to=%s)", mask_email(to))
        return
    uri = f"/mail/v2/services/{cfg.service_id}/requests"
    ts = str(int(now_s() * 1000))
    payload = {
        "senderAddress": cfg.sender,
        "senderName": cfg.sender_name,
        "title": f"[트립사인] 인증번호 {code}",
        "body": mail_html(code),
        "individual": True,
        "advertising": False,
        "recipients": [{"address": to, "type": "TO"}],
    }
    req = urllib.request.Request(SENS_URL + uri, data=json.dumps(payload).encode("utf-8"), method="POST", headers={
        "x-ncp-apigw-timestamp": ts,
        "x-ncp-iam-access-key": cfg.access_key,
        "x-ncp-apigw-signature-v2": sens_signature(cfg.secret_key, "POST", uri, ts, cfg.access_key),
        "x-ncp-lang": "en-US",
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            if r.status != 202:
                raise RuntimeError(f"SENS HTTP {r.status}")
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"SENS HTTP {e.code}") from None


# ---------------------------------------------------------------- store

SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions(
  sid TEXT PRIMARY KEY, key BLOB NOT NULL, car TEXT NOT NULL, purpose TEXT NOT NULL, target TEXT NOT NULL,
  created REAL NOT NULL, state TEXT NOT NULL, phone_token TEXT, scanned REAL,
  email TEXT, phone TEXT, code_hash TEXT, code_sent REAL, code_tries INTEGER NOT NULL DEFAULT 0, sends INTEGER NOT NULL DEFAULT 0,
  verified REAL, car_code_hash TEXT, car_tries INTEGER NOT NULL DEFAULT 0, claimed REAL, assertion TEXT, ip TEXT);
CREATE TABLE IF NOT EXISTS issued(
  sid TEXT PRIMARY KEY, car TEXT NOT NULL, purpose TEXT NOT NULL, target TEXT NOT NULL,
  email TEXT NOT NULL, phone TEXT NOT NULL, verified REAL NOT NULL, claimed REAL NOT NULL, assertion TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS hits(k TEXT NOT NULL, t REAL NOT NULL);
CREATE INDEX IF NOT EXISTS hits_k ON hits(k, t);
"""


class Store:
    def __init__(self, path: str):
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        self.path = path
        self.lock = threading.Lock()
        with self.db() as db:
            db.executescript(SCHEMA)

    def db(self) -> sqlite3.Connection:
        c = sqlite3.connect(self.path, timeout=10)
        c.row_factory = sqlite3.Row
        return c

    def limited(self, key: str, limit: int, window_s: float) -> bool:
        """True = 한도를 넘음(거절). 넘지 않았으면 이번 요청을 세어 넣는다."""
        t = now_s()
        with self.lock, self.db() as db:
            db.execute("DELETE FROM hits WHERE t < ?", (t - 86400,))
            n = db.execute("SELECT COUNT(*) FROM hits WHERE k=? AND t>=?", (key, t - window_s)).fetchone()[0]
            if n >= limit:
                return True
            db.execute("INSERT INTO hits(k,t) VALUES(?,?)", (key, t))
            return False

    def cleanup(self) -> None:
        t = now_s()
        with self.lock, self.db() as db:
            db.execute("DELETE FROM sessions WHERE created < ?", (t - 86400,))
            db.execute("DELETE FROM issued WHERE claimed < ?", (t - RETENTION_S,))


class ApiError(Exception):
    def __init__(self, status: int, code: str, **extra):
        super().__init__(code)
        self.status, self.code, self.extra = status, code, extra


# ---------------------------------------------------------------- service

class AuthService:
    def __init__(self, cfg: Config, store: Store):
        self.cfg, self.store = cfg, store

    def _session(self, db, sid: str):
        if not SID_RE.match(sid or ""):
            raise ApiError(400, "ERR_SESSION")
        row = db.execute("SELECT * FROM sessions WHERE sid=?", (sid,)).fetchone()
        if row is None or now_s() - row["created"] > SESSION_TTL_S:
            raise ApiError(410, "ERR_EXPIRED")
        return row

    # 차 → 세션 만들기
    def create(self, body: dict, ip: str) -> dict:
        car = str(body.get("car", "")).lower()
        purpose = str(body.get("purpose", ""))
        target = str(body.get("target", ""))
        if not CAR_RE.match(car) or purpose not in PURPOSES or not TARGET_RE.match(target):
            raise ApiError(400, "ERR_INPUT")
        if self.store.limited("car:" + car, 30, 3600) or self.store.limited("ip:" + ip, 60, 3600):
            raise ApiError(429, "ERR_RATE")
        sid, key = b32id(), secrets.token_bytes(32)
        with self.store.lock, self.store.db() as db:
            db.execute("INSERT INTO sessions(sid,key,car,purpose,target,created,state,ip) VALUES(?,?,?,?,?,?,?,?)",
                       (sid, key, car, purpose, target, now_s(), "new", ip))
        return {"sid": sid, "key": base64.b64encode(key).decode("ascii"), "ttl": SESSION_TTL_S, "step": QR_STEP_S}

    # 폰 → QR 찍음
    def scan(self, body: dict, ip: str) -> dict:
        sid, step, mac = str(body.get("sid", "")), body.get("t"), str(body.get("m", ""))
        if self.store.limited("ip:" + ip, 120, 3600):
            raise ApiError(429, "ERR_RATE")
        with self.store.lock, self.store.db() as db:
            s = self._session(db, sid)
            try:
                step = int(step)
            except (TypeError, ValueError):
                raise ApiError(400, "ERR_QR")
            if not hmac.compare_digest(qr_mac(s["key"], sid, step), mac):
                raise ApiError(400, "ERR_QR")
            cur = int(now_s() // QR_STEP_S)
            if step > cur + 1 or cur - step > QR_SKEW_STEPS:
                raise ApiError(400, "ERR_QR_OLD")
            if s["state"] != "new":
                raise ApiError(409, "ERR_TAKEN")
            pt = secrets.token_urlsafe(24)
            db.execute("UPDATE sessions SET state='scanned', phone_token=?, scanned=? WHERE sid=?", (pt, now_s(), sid))
        return {"pt": pt, "purpose": s["purpose"]}

    def _phone_session(self, db, body: dict):
        s = self._session(db, str(body.get("sid", "")))
        if not s["phone_token"] or not hmac.compare_digest(s["phone_token"], str(body.get("pt", ""))):
            raise ApiError(403, "ERR_PHONE")
        return s

    # 폰 → 이메일·전화번호 → 번호 메일
    def email(self, body: dict, ip: str) -> dict:
        e, p = norm_email(str(body.get("email", ""))), norm_phone(str(body.get("phone", "")))
        if not e:
            raise ApiError(400, "ERR_EMAIL")
        if not p:
            raise ApiError(400, "ERR_PHONE_NO")
        if body.get("agree") is not True:
            raise ApiError(400, "ERR_AGREE")
        if self.store.limited("mail:" + sha(e), 6, 3600) or self.store.limited("ip:" + ip, 120, 3600):
            raise ApiError(429, "ERR_RATE")
        code = six_digits()
        with self.store.lock, self.store.db() as db:
            s = self._phone_session(db, body)
            if s["state"] not in ("scanned", "mailed"):
                raise ApiError(409, "ERR_STATE")
            if s["sends"] >= CODE_MAX_SENDS:
                raise ApiError(429, "ERR_SENDS")
            db.execute("UPDATE sessions SET state='mailed', email=?, phone=?, code_hash=?, code_sent=?, code_tries=0, sends=sends+1 WHERE sid=?",
                       (e, p, sha(s["sid"] + "|" + code), now_s(), s["sid"]))
        try:
            send_code_mail(self.cfg, e, code)
        except Exception as ex:  # 키·주소는 남기지 않는다
            log.warning("mail failed: %s to=%s", type(ex).__name__ + " " + str(ex)[:80], mask_email(e))
            raise ApiError(502, "ERR_MAIL")
        out = {"sent": mask_email(e)}
        if self.cfg.test_echo and not self.cfg.mail_ready:
            out["testCode"] = code
        return out

    # 폰 → 메일 번호 확인 → 차 확인번호
    def verify(self, body: dict, ip: str) -> dict:
        code = re.sub(r"\D", "", str(body.get("code", "")))
        with self.store.lock, self.store.db() as db:
            s = self._phone_session(db, body)
            if s["state"] != "mailed" or not s["code_hash"]:
                raise ApiError(409, "ERR_STATE")
            if now_s() - (s["code_sent"] or 0) > CODE_TTL_S:
                raise ApiError(410, "ERR_CODE_OLD")
            if s["code_tries"] >= CODE_MAX_TRIES:
                raise ApiError(429, "ERR_TRIES")
            if not hmac.compare_digest(sha(s["sid"] + "|" + code), s["code_hash"]):
                db.execute("UPDATE sessions SET code_tries=code_tries+1 WHERE sid=?", (s["sid"],))
                raise ApiError(400, "ERR_CODE", left=CODE_MAX_TRIES - s["code_tries"] - 1)
            car_code = six_digits()
            db.execute("UPDATE sessions SET state='verified', verified=?, car_code_hash=?, car_tries=0 WHERE sid=?",
                       (now_s(), sha(s["sid"] + "|car|" + car_code), s["sid"]))
        return {"carCode": car_code, "ttl": CAR_CODE_TTL_S}

    # 차 → 차 확인번호 → 서명된 확인서
    def claim(self, body: dict, ip: str) -> dict:
        car_code = re.sub(r"\D", "", str(body.get("carCode", "")))
        with self.store.lock, self.store.db() as db:
            s = self._session(db, str(body.get("sid", "")))
            if not hmac.compare_digest(claim_mac(s["key"], s["sid"], car_code), str(body.get("mac", ""))):
                raise ApiError(403, "ERR_CAR")
            if s["state"] == "claimed" and s["assertion"]:
                a = json.loads(s["assertion"])
                return a
            if s["state"] != "verified":
                raise ApiError(409, "ERR_STATE")
            if now_s() - (s["verified"] or 0) > CAR_CODE_TTL_S:
                raise ApiError(410, "ERR_CAR_OLD")
            if s["car_tries"] >= CAR_CODE_MAX_TRIES:
                raise ApiError(429, "ERR_TRIES")
            if not hmac.compare_digest(sha(s["sid"] + "|car|" + car_code), s["car_code_hash"] or ""):
                db.execute("UPDATE sessions SET car_tries=car_tries+1 WHERE sid=?", (s["sid"],))
                raise ApiError(400, "ERR_CAR_CODE", left=CAR_CODE_MAX_TRIES - s["car_tries"] - 1)
            claimed = now_s()
            text = "~".join([
                "tsa1", s["sid"], s["car"], s["purpose"], s["target"],
                id_hash("email", s["email"]), id_hash("phone", s["phone"]),
                mask_email(s["email"]), mask_phone(s["phone"]),
                str(int(s["verified"] * 1000)), str(int(claimed * 1000)),
            ])
            a = {"a": text, "s": sign_text(self.cfg, text)}
            db.execute("UPDATE sessions SET state='claimed', claimed=?, assertion=? WHERE sid=?", (claimed, json.dumps(a), s["sid"]))
            db.execute("INSERT OR REPLACE INTO issued(sid,car,purpose,target,email,phone,verified,claimed,assertion) VALUES(?,?,?,?,?,?,?,?,?)",
                       (s["sid"], s["car"], s["purpose"], s["target"], s["email"], s["phone"], s["verified"], claimed, text))
        return a

    # 폰 → 진행 상태(차에서 확인이 끝났는지)
    def status(self, body: dict, ip: str) -> dict:
        with self.store.lock, self.store.db() as db:
            s = self._phone_session(db, body)
        return {"state": s["state"]}


ROUTES = {
    "/auth/api/session": "create",
    "/auth/api/scan": "scan",
    "/auth/api/email": "email",
    "/auth/api/verify": "verify",
    "/auth/api/claim": "claim",
    "/auth/api/status": "status",
}


def make_handler(svc: AuthService):
    class Handler(BaseHTTPRequestHandler):
        server_version = "tripsign-auth/1.0"
        sys_version = ""

        def log_message(self, fmt, *args):  # 요청 내용(메일 주소 등)은 남기지 않고 경로·상태만
            log.info("%s %s", self.command, self.path.split("?")[0])

        def _ip(self) -> str:
            fwd = self.headers.get("X-Forwarded-For", "")
            return (fwd.split(",")[-1].strip() if fwd else self.client_address[0])[:64]

        def _send(self, status: int, obj: dict):
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path.split("?")[0] == "/auth/api/health":
                return self._send(200, {"ok": True, "mail": svc.cfg.mail_ready})
            return self._send(404, {"error": "ERR_NOT_FOUND"})

        def do_POST(self):
            name = ROUTES.get(self.path.split("?")[0])
            if not name:
                return self._send(404, {"error": "ERR_NOT_FOUND"})
            try:
                n = int(self.headers.get("Content-Length", "0"))
                if n <= 0 or n > 4096:
                    raise ApiError(400, "ERR_INPUT")
                body = json.loads(self.rfile.read(n).decode("utf-8"))
                if not isinstance(body, dict):
                    raise ApiError(400, "ERR_INPUT")
                out = getattr(svc, name)(body, self._ip())
                return self._send(200, out)
            except ApiError as e:
                return self._send(e.status, {"error": e.code, **e.extra})
            except (ValueError, json.JSONDecodeError):
                return self._send(400, {"error": "ERR_INPUT"})
            except Exception as e:
                log.exception("server error in %s: %s", name, type(e).__name__)
                return self._send(500, {"error": "ERR_SERVER"})

    return Handler


def main(argv=None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    argv = argv if argv is not None else sys.argv[1:]
    env = dict(os.environ)
    env_file = os.environ.get("ENV_FILE", "/etc/tripsign-auth/tripsign-auth.env")
    env.update(load_env(env_file))
    cfg = Config(env)
    if argv and argv[0] == "pubkey":
        print(public_key_b64(cfg))
        return 0
    if not os.path.exists(cfg.key_pem):
        log.error("sign key missing: %s", cfg.key_pem)
        return 2
    store = Store(cfg.db)
    store.cleanup()
    svc = AuthService(cfg, store)
    srv = ThreadingHTTPServer((cfg.host, cfg.port), make_handler(svc))
    log.info("tripsign-auth on %s:%d mail=%s", cfg.host, cfg.port, "ready" if cfg.mail_ready else "off")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
