# ver 1.0.0 | 2026-11-04 20:50
# Builds verify/index.html (one self-contained file for https://tripsign.beyondspark.kr):
# inlines jsQR (Apache-2.0) and the sample pack ("예시 데이터로 보기").
# usage: python3 tools/build_verify.py <jsQR.js> <sample_payload.txt>
import sys, gzip, base64
from pathlib import Path
root = Path(__file__).resolve().parent.parent
src = (root / "verify" / "index.src.html").read_text(encoding="utf8")
jsqr = Path(sys.argv[1]).read_text(encoding="utf8")
sample = Path(sys.argv[2]).read_bytes()
demo = base64.b64encode(gzip.compress(sample, mtime=0)).decode()
assert "/*JSQR*/" in src and '"/*DEMO*/"' in src
out = src.replace("/*JSQR*/", "/* jsQR 1.4.0 | Apache-2.0 | https://github.com/cozmo/jsQR */\n" + jsqr).replace('"/*DEMO*/"', '"' + demo + '"')
(root / "verify" / "index.html").write_text(out, encoding="utf8")
print("verify/index.html", len(out.encode()), "bytes")
