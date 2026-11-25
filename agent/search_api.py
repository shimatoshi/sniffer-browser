#!/data/data/com.termux/files/usr/bin/python3
"""sniffer-browser(実WebView)を使う検索・資料取得の HTTP API。LLM の資料集め用。
  GET /search?q=語&n=8[&engine=google|yahoo|bing|ddg]
      → {"results":[{"title","url"}],"engine"}
  GET /fetch?url=URL&max=20000[&html=1][&mode=auto|direct|browser][&ocr=8][&pages=30]
      → {"url","title","text"[,"html"],"type":"html|pdf|image|text","via","sec"}
  GET /health
  python3 search_api.py [待ち受けアドレス] [ポート]   （既定 127.0.0.1 8095）
  環境変数 SEARCH_POOL=並列に使うブラウザの数（既定4）

何でも取れることを優先する:
- 依頼は同時に受け付ける。ブラウザ(画面外の WebView)を複数枚持ち、空いているものを使う。
- /fetch はまず直接取り(速い・ブラウザを使わない)、429/5xx は待ってやり直す。
  弾かれた・中身が無い(JavaScript で描くページ、bot 判定の画面)ときは実ブラウザで開く。
  それも駄目なら Internet Archive の写しを取る。
- PDF はダウンロードして pdftotext で読み、字が化けるページ・画像だけのページは
  画像にして tesseract で OCR する。画像の URL も OCR する。
- 検索は Google が止められたら(sorry ページ)しばらく Yahoo! → Bing → DuckDuckGo に回す。
速さのため CDP の接続は持ち続け、待ちは「読み込み完了」ではなく「中身が出そろった時点」で切る。"""
import base64
import json
import os
import queue
import re
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
# 端末起動時に立ち上げると PATH に /system/bin が無く、bridge.sh の am・pidof が見つからない
if "/system/bin" not in os.environ.get("PATH", "").split(":"):
    os.environ["PATH"] = os.environ.get("PATH", "") + ":/system/bin"
import cdp  # noqa: E402
import gobie  # noqa: E402

POOL = max(1, int(os.environ.get("SEARCH_POOL", "4")))
UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
RETRY_CODES = {429, 500, 502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 529}
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def log(*a):
    sys.stderr.write(time.strftime("%F %T ") + " ".join(map(str, a)) + "\n")
    sys.stderr.flush()


class NavError(Exception):
    """ページを開けなかった（接続は生きているので、つなぎ直さない）"""


# ---------------- ブラウザ(WebView)の貸し出し ----------------

INIT = threading.Lock()
SLOTS = queue.Queue()   # 空いているブラウザ
_known = set()          # 貸し出し対象に入れた番号
GEN = [0]               # アプリを再起動したら増やす。古い世代のブラウザは返却時に捨てる


class Slot:
    def __init__(s, k):
        s.k, s.c, s.gen = k, None, GEN[0]

    def conn(s):
        if s.c is None:
            s.c = cdp.CDP(slot=s.k)
            s.c.send("Page.enable")
        return s.c

    def drop(s):
        if s.c is not None:
            s.c.close()
            s.c = None


def ensure_pool():
    """ブラウザを POOL 枚そろえ、新しく見つかったものを貸し出しに加える。
    旧アプリ(1枚しか持てない)でもそのまま1枚で動く。"""
    with INIT:
        for attempt in range(2):
            try:
                gobie.ensure_bridge()
                break
            except SystemExit as e:  # gobie は CLI 用に sys.exit で失敗を返す
                if attempt:
                    raise RuntimeError(str(e)) from None
                # devtools ソケットが応答しないまま固まることがある。アプリを落として起こし直す
                log("ブラウザにつながらない → アプリを再起動")
                subprocess.run(gobie.SHELL + ["am force-stop " + gobie.PKG], capture_output=True)
                _known.clear()
                GEN[0] += 1
                while not SLOTS.empty():
                    SLOTS.get_nowait().drop()
        ts = cdp.headless_targets()
        if len(ts) < POOL:
            subprocess.run(gobie.SHELL + [
                f"am start-foreground-service -n {gobie.PKG}/net.localhost.sniffer.HeadlessBrowserService"
                f" --ei pool {POOL}"], capture_output=True)
            for _ in range(20):
                time.sleep(0.3)
                ts = cdp.headless_targets()
                if len(ts) >= POOL:
                    break
        for k in sorted(ts):
            if k not in _known:
                _known.add(k)
                SLOTS.put(Slot(k))
        return len(ts)


def borrow(timeout=120):
    if not _known:
        ensure_pool()
    try:
        return SLOTS.get(timeout=timeout)
    except queue.Empty:
        raise RuntimeError("ブラウザが空かない") from None


def run(fn, *a):
    """空いているブラウザで fn(c, ...) を実行する。接続が切れていたら（自動停止・
    再起動など）1回だけつなぎ直してやり直す"""
    s = borrow()
    try:
        for attempt in range(2):
            try:
                c = s.conn()
                r = fn(c, *a)
                # 開いたままだと裏で動く JavaScript が他のブラウザとレンダラを取り合うので白紙に戻す。
                # 遷移はアプリ側の無操作自動停止(10分)の「使用」にも数えられる
                c.send("Page.navigate", url="about:blank")
                return r
            except NavError:
                raise
            except Exception:
                s.drop()
                if attempt:
                    raise
                try:
                    ensure_pool()
                except Exception:
                    pass
    finally:
        if s.gen == GEN[0]:
            SLOTS.put(s)
        else:
            s.drop()


# 遷移前の文書に印を付け、印の無い文書になったら新しいページに切り替わったと分かる
MARK = "window.__gobieOld=1"
PAGE_JS = (
    "JSON.stringify([window.__gobieOld===1,document.readyState,"
    "document.body?document.body.innerText.length:0])")


def navigate(c, url, ready, timeout):
    """url へ遷移し、ready(文字数) が真になるか読み込み完了まで待つ。
    文字数が2回続けて同じ＝描画が落ち着いた時点で返す。
    新しいページに切り替わらなかった（ダウンロード扱いの URL など）ときは False。"""
    c.eval(MARK)
    r = c.send("Page.navigate", url=url)
    if r.get("errorText"):
        # エラー画面のままだと headless の印が付かず次回つながらないので白紙に戻す
        c.send("Page.navigate", url="about:blank")
        raise NavError("navigate: " + r["errorText"])
    deadline = time.time() + timeout
    last, new = -1, False
    while time.time() < deadline:
        old, rs, n = json.loads(c.eval(PAGE_JS) or "[true,\"\",0]")
        new = new or not old
        if not old and rs != "loading":
            if rs == "complete" or (ready(n) and n == last):
                return True
            last = n
        time.sleep(0.15)
    return new


# ---------------- 検索 ----------------

ENGINES = {
    "google": gobie.SEARCH_URL,
    "yahoo": "https://search.yahoo.co.jp/search?p={}",
    "bing": "https://www.bing.com/search?q={}&setlang=ja&cc=JP",
    "ddg": "https://html.duckduckgo.com/html/?q={}&kl=jp-jp",
}
ORDER = ["google", "yahoo", "bing", "ddg"]
OWN = re.compile(r"(^|\.)(google\.[a-z.]+|goo\.gl|gstatic\.com|googleusercontent\.com|"
                 r"yahoo\.co\.jp|yahoo\.com|yimg\.jp|lycorp\.co\.jp|bing\.com|"
                 r"microsoft\.com|msn\.com|duckduckgo\.com|aka\.ms|goo\.gl)$")
BLOCKED = {}   # 検索エンジン → 使わない期限（止められたとき）
# 検索結果のリンク（文書順、文字の付いたもの）。Bing/DuckDuckGo の転送用 URL は呼び出し側で戻す
SERP_JS = r"""JSON.stringify([location.href, document.body?document.body.innerText.slice(0,3000):'',
  Array.from(document.querySelectorAll('a[href]')).map(function(a){
    return {t:(a.innerText||'').trim().replace(/\s+/g,' ').slice(0,120),u:a.href};
  }).filter(function(x){return x.t.length>=2 && /^https?:/.test(x.u);})])"""
SORRY = re.compile(r"/sorry/|unusual traffic|通常と異なるトラフィック|"
                   r"ロボットではない|not a robot|captcha|アクセスが集中", re.I)


def unwrap_link(u):
    p = urllib.parse.urlsplit(u)
    q = urllib.parse.parse_qs(p.query)
    if p.netloc.endswith("bing.com") and p.path.startswith("/ck/"):
        v = q.get("u", [""])[0][2:]   # 先頭2文字は印、残りが元の URL の base64
        try:
            return base64.urlsafe_b64decode(v + "=" * (-len(v) % 4)).decode()
        except Exception:
            return ""
    if p.netloc.endswith("duckduckgo.com") and "uddg" in q:
        return q["uddg"][0]
    if p.netloc.endswith("google.com") and p.path == "/url" and "q" in q:
        return q["q"][0]
    if "yahoo.co.jp" in p.netloc and "/RU=" in u:
        m = re.search(r"/RU=([^/]+)/", u)
        return urllib.parse.unquote(m.group(1)) if m else ""
    return u


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


NOREDIR = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect)


def resolve_goto(u):
    """Google の /goto?url=暗号 を転送先の URL に戻す（302 の Location か、
    間に挟まる meta refresh を1回だけたどる）。戻せなければ空。"""
    for _ in range(2):
        try:
            with NOREDIR.open(urllib.request.Request(u, headers={"User-Agent": UA}),
                              timeout=8) as r:
                m = re.search(rb'url=([^"]+)" http-equiv="refresh"', r.read(4000))
                if not m:
                    return ""
                import html
                u = urllib.parse.urljoin(u, html.unescape(m.group(1).decode()))
                time.sleep(1)   # 続けて引くと挟まる待ち画面。少し待てば転送先が返る
        except urllib.error.HTTPError as e:
            loc = e.headers.get("Location", "")
            if e.code in (301, 302, 303, 307, 308) and loc:
                u = urllib.parse.urljoin(u, loc)
                if "google." not in urllib.parse.urlsplit(u).netloc:
                    return u
            else:
                return ""
        except Exception:
            return ""
    return ""


RESOLVE = ThreadPoolExecutor(6)


def search_one(c, engine, q, n):
    navigate(c, ENGINES[engine].format(urllib.parse.quote(q)),
             lambda chars: chars > 500, 10)
    for i in range(3):   # 起動直後などで描画が間に合わないことがある
        href, body, ls = json.loads(c.eval(SERP_JS))
        if SORRY.search(href) or (SORRY.search(body) and len(ls) < 5):
            raise PermissionError(engine + " に止められた")
        # Google は結果のリンクを /goto?url=暗号 にすることがある。上位だけ並列に転送先へ戻す
        gotos = [l for l in ls if "/goto?" in l["u"]][:n * 3]
        for l, u in zip(gotos, RESOLVE.map(resolve_goto, [l["u"] for l in gotos])):
            l["u"] = u
        res, seen = [], set()
        for l in ls:
            u = unwrap_link(l["u"]).split("#")[0]
            if not u.startswith("http") or u in seen or OWN.search(urllib.parse.urlsplit(u).netloc):
                continue
            seen.add(u)
            # Yahoo!/Bing は題の後ろにサイト名や URL が付くので切る
            t = re.split(r"\s+https?://|\s+[\w.-]+\.[a-z]{2,}(?:\s|›)", l["t"])[0].strip() or l["t"]
            res.append({"title": t, "url": u})
        if res:
            return res[:n]
        time.sleep(0.7)
    return []


def search(q, n, engine=None):
    """件数が足りなければ(止められた・リンクが取れない)次の検索エンジンで補う"""
    tried, out, seen = [], [], set()
    for e in ([engine] if engine else ORDER):
        if not engine and BLOCKED.get(e, 0) > time.time():
            continue
        try:
            res = run(search_one, e, q, n)
        except PermissionError as x:
            BLOCKED[e] = time.time() + 600
            log("search", x, "→ 10分ほかの検索に回す")
            continue
        except NavError as x:
            log("search", e, x)
            continue
        if res:
            tried.append(e)
        for r in res:
            if r["url"] not in seen and len(out) < n:
                seen.add(r["url"])
                out.append(r)
        if len(out) >= min(n, 4):
            break
    return {"results": out, "engine": ",".join(tried)}


# ---------------- 直接取得 ----------------

HOSTS = {}
HOSTS_LOCK = threading.Lock()


def host_sem(u):
    """同じサイトへ同時に何本も投げると 429 になりやすいので、1サイト2本まで"""
    h = urllib.parse.urlsplit(u).netloc
    with HOSTS_LOCK:
        return HOSTS.setdefault(h, threading.Semaphore(2))


def http_get(u, limit=40_000_000, tries=4, timeout=20, headers=None):
    """GET。429/5xx・接続エラーは待ってやり直す(Retry-After に従う、最大20秒)。
    返り値 (最終URL, Content-Type, 本文bytes)。駄目なら例外(HTTPError は code 付き)。"""
    h = {"User-Agent": UA, "Accept-Language": "ja,en;q=0.7",
         "Accept": "text/html,application/xhtml+xml,application/pdf,*/*;q=0.8"}
    h.update(headers or {})
    wait = 1.0
    for i in range(tries):
        try:
            with host_sem(u):
                with OPENER.open(urllib.request.Request(u, headers=h), timeout=timeout) as r:
                    return r.geturl(), r.headers.get("Content-Type", ""), r.read(limit)
        except urllib.error.HTTPError as e:
            if e.code not in RETRY_CODES or i == tries - 1:
                raise
            ra = e.headers.get("Retry-After", "")
            w = min(float(ra), 20) if ra.isdigit() else wait
            log("http", e.code, u[:80], "→ %.0f秒待って再試行" % w)
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
            if i == tries - 1:
                raise
            w = wait
            log("http", repr(e)[:80], u[:80], "→ 再試行")
        time.sleep(w)
        wait *= 3


def decode_html(raw, ctype):
    m = re.search(r"charset=([\w-]+)", ctype)
    cs = m.group(1) if m else None
    if not cs:
        m = re.search(rb"charset=[\"']?([\w-]+)", raw[:4000])
        cs = m.group(1).decode() if m else "utf-8"
    try:
        return raw.decode(cs, "replace")
    except LookupError:
        return raw.decode("utf-8", "replace")


CHALLENGE = re.compile(
    r"Just a moment|Checking your browser|Attention Required|cf-browser-verification|"
    r"challenge-platform|Access Denied|Request unsuccessful|Pardon Our Interruption|"
    r"enable JavaScript|JavaScriptを有効|captcha", re.I)


def html_text(h, u):
    try:
        import trafilatura
        return trafilatura.extract(h, url=u, output_format="markdown", include_tables=True,
                                   include_links=False, favor_recall=True) or ""
    except Exception:
        t = re.sub(r"(?is)<(script|style|noscript)[^>]*>.*?</\1>", " ", h)
        return re.sub(r"[ \t]+", " ", re.sub(r"<[^>]+>", " ", t)).strip()


def title_of(h):
    import html
    m = re.search(r"<title[^>]*>(.*?)</title>", h, re.S | re.I)
    return html.unescape(m.group(1)).strip() if m else ""


def kind(ctype, raw, u):
    ct = ctype.split(";")[0].strip().lower()
    if raw[:5] == b"%PDF-" or ct == "application/pdf":
        return "pdf"
    if ct.startswith("image/"):
        return "image"
    if "html" in ct or "xml" in ct or raw.lstrip()[:15].lower().startswith((b"<!doctype", b"<html")):
        return "html"
    if ct.startswith("text/") or "json" in ct:
        return "text"
    return "binary"


# ---------------- PDF・画像の文字 ----------------

READABLE = re.compile(r"[\u3000-\u30ff\u4e00-\u9fff\uff00-\uffef\x20-\x7e]")


def ocr_fix(t):
    """tesseract が日本語の文字の間に入れる空白を詰める。"""
    t = re.sub(r"(?<=[　-ヿ一-鿿＀-￯0-9,，]) (?=[　-ヿ一-鿿＀-￯])", "", t)
    return re.sub(r"(?<=[　-ヿ一-鿿＀-￯]) (?=[0-9])|(?<=[0-9],) (?=[0-9])", "", t)


def columns(t):
    """pdftotext -layout の1ページが2段組か（行の中ほどの大きな空白の位置がそろっている行が多い）"""
    ls = [l for l in t.split("\n") if l.strip()]
    pos = [m.start() for l in ls for m in [re.search(r"\S {3,}(?=\S)", l)]
           if m and len(l[:m.start()].strip()) >= 8 and len(l[m.end():].strip()) >= 8
           and len(re.findall(r" {3,}", l.strip())) == 1]
    if len(ls) < 10 or len(pos) < len(ls) * 0.4:
        return False
    pos.sort()
    mid = pos[len(pos) // 2]
    return sum(abs(p - mid) <= 8 for p in pos) >= len(pos) * 0.7


def unwrap(t):
    """段の幅で折り返された行をつなぐ。段落の境目は空行"""
    t = re.sub(r"(?<=[A-Za-z,;])\n(?=[A-Za-z(])", " ", t)
    return re.sub(r"(?<=\S)\n(?=\S)", "", t)


def tesseract(png):
    # jpn の学習データは英数字も読む。jpn+eng にすると倍近く遅くなる
    r = subprocess.run(["tesseract", png, "-", "-l", "jpn", "--psm", "6"],
                       capture_output=True, text=True, timeout=120,
                       env=dict(os.environ, OMP_THREAD_LIMIT="1"))
    return ocr_fix(r.stdout)


OCR_POOL = ThreadPoolExecutor(max(2, (os.cpu_count() or 4) // 2))


def pdf_text(b, pages=30, ocr=8):
    """pdftotext で読む。字が化けるページ(フォントに文字の対応表が無い官公庁の PDF に多い)と
    文字の無いページ(スキャン画像)は、画像にして OCR する。返り値 (本文, OCRしたページ数, 総ページ数)"""
    with tempfile.TemporaryDirectory() as d:
        f = os.path.join(d, "a.pdf")
        open(f, "wb").write(b)
        info = subprocess.run(["pdfinfo", f], capture_output=True, text=True, timeout=30).stdout
        m = re.search(r"^Pages:\s+(\d+)", info, re.M)
        total = int(m.group(1)) if m else 0
        last = str(pages)
        ps = subprocess.run(["pdftotext", "-layout", "-l", last, f, "-"],
                            capture_output=True, text=True, timeout=90).stdout.split("\f")
        if ps and not ps[-1].strip():
            ps = ps[:-1]
        if any(columns(t) for t in ps):   # 2段組は -layout だと左右の段が1行に混ざるので段順で読み直す
            flow = subprocess.run(["pdftotext", "-l", last, f, "-"],
                                  capture_output=True, text=True, timeout=90).stdout.split("\f")
            if len(flow) >= len(ps):
                ps = [unwrap(b) if columns(a) else a for a, b in zip(ps, flow)]

        def bad(t):
            c = re.sub(r"\s", "", t)
            if len(c) < 30:
                return True   # 画像だけのページ（白紙なら OCR しても空が返るだけ）
            return len(READABLE.findall(c)) < len(c) * 0.8
        badp = [n for n, t in enumerate(ps) if bad(t)][:ocr]

        def one(n):
            img = os.path.join(d, "p%d" % n)
            # PNG に圧縮すると1ページ十数秒かかるので、無圧縮の PGM で渡す
            subprocess.run(["pdftoppm", "-f", str(n + 1), "-l", str(n + 1), "-r", "250", "-gray",
                            "-singlefile", f, img], timeout=90)
            return tesseract(img + ".pgm")
        for n, t in zip(badp, OCR_POOL.map(one, badp)):
            if len(re.sub(r"\s", "", t)) > len(re.sub(r"\s", "", ps[n])) * 0.3:
                ps[n] = t
        title = re.search(r"^Title:\s+(.+)$", info, re.M)
        return "\n".join(ps), len(badp), total, (title.group(1).strip() if title else "")


def image_text(b):
    with tempfile.TemporaryDirectory() as d:
        f = os.path.join(d, "a.img")
        open(f, "wb").write(b)
        return tesseract(f)


def binary_result(k, u, b, pages, ocr):
    if k == "pdf":
        t, nocr, total, title = pdf_text(b, pages, ocr)
        return {"url": u, "title": title, "text": t, "type": "pdf",
                "pages": total, "ocr_pages": nocr}
    return {"url": u, "title": "", "text": image_text(b), "type": "image"}


# ---------------- ブラウザで取る ----------------

ERRPAGE = re.compile(r"^\s*(429|50[234]|52[0-9])\b|Too Many Requests|Bad Gateway|"
                     r"Service Unavailable|Gateway Time-?out", re.I)


class NotPage(NavError):
    """ブラウザが画面として開かなかった（PDF などダウンロード扱い）"""


def browser_page(c, url, mx, html):
    if not navigate(c, url, lambda chars: chars >= 200, 15):
        raise NotPage("画面が切り替わらない（ダウンロード扱い？）")
    js = ("JSON.stringify({url:location.href,title:document.title,"
          "ctype:document.contentType,"
          "text:document.body?document.body.innerText:''"
          + (",html:document.documentElement.outerHTML" if html else "")
          + "})")
    r = json.loads(c.eval(js))
    # bot 判定の画面(Cloudflare など)は実ブラウザなら数秒で抜けるので少し待つ
    for _ in range(20):
        if not CHALLENGE.search(r["title"] + r["text"][:500]):
            break
        time.sleep(0.5)
        r = json.loads(c.eval(js))
    return r


def browser_bytes(c, url):
    """ブラウザの Cookie・身元のまま中身を bytes で取る（直接だと弾かれる PDF・画像用）。
    同じサイトのページを開いてから、その中で fetch する。"""
    p = urllib.parse.urlsplit(url)
    navigate(c, "%s://%s/" % (p.scheme, p.netloc), lambda chars: chars >= 50, 12)
    js = ("fetch(%s,{credentials:'include'}).then(r=>r.arrayBuffer().then(b=>{"
          "let s='',u=new Uint8Array(b);for(let i=0;i<u.length;i+=32768)"
          "s+=String.fromCharCode.apply(null,u.subarray(i,i+32768));"
          "return JSON.stringify([r.status,r.headers.get('content-type')||'',btoa(s)])}))"
          % json.dumps(url))
    st, ct, b64 = json.loads(c.eval(js))
    if st >= 400:
        raise urllib.error.HTTPError(url, st, "browser fetch", None, None)
    return ct, base64.b64decode(b64)


# ---------------- /fetch ----------------

CACHE = {}
CACHE_SEC = 900


def fetch(url, mx=20000, html=False, mode="auto", ocr=8, pages=30):
    key = (url, html, ocr, pages)
    hit = CACHE.get(key)
    if hit and hit[0] > time.time() - CACHE_SEC and mode == "auto":
        return dict(hit[1], cached=True)
    r = _fetch(url, html, mode, ocr, pages)
    r["text"] = r.get("text", "")[:mx]
    if html and "html" in r:
        r["html"] = r["html"][:3_000_000]
    elif "html" in r:
        del r["html"]
    if r["text"].strip():
        CACHE[key] = (time.time(), dict(r))
        if len(CACHE) > 500:
            for k in sorted(CACHE, key=lambda k: CACHE[k][0])[:100]:
                CACHE.pop(k, None)
    return r


def _fetch(url, html, mode, ocr, pages):
    errs = []
    if mode in ("auto", "direct"):
        try:
            fu, ct, raw = http_get(url)
            k = kind(ct, raw, fu)
            if k in ("pdf", "image"):
                return dict(binary_result(k, fu, raw, pages, ocr), via="direct")
            if k == "text":
                return {"url": fu, "title": "", "text": decode_html(raw, ct), "type": "text",
                        "via": "direct"}
            if k == "html":
                h = decode_html(raw, ct)
                t = html_text(h, fu)
                if len(t) >= 500 and not CHALLENGE.search(title_of(h) + t[:300]) or mode == "direct":
                    return {"url": fu, "title": title_of(h), "text": t, "html": h,
                            "type": "html", "via": "direct"}
                errs.append("direct: 本文が短い(%d字)" % len(t))
            else:
                errs.append("direct: " + ct)
        except Exception as e:
            errs.append("direct: %r" % e)
            if mode == "direct":
                raise
    # 直接では駄目だったので実ブラウザで
    looks_bin = re.search(r"\.(pdf|png|jpe?g|gif|webp)(\?|$)", url, re.I) or \
        any("application/pdf" in x or "image/" in x for x in errs)
    if not looks_bin:
        wait = 2
        for i in range(3):
            try:
                r = run(browser_page, url, 0, True)
            except NotPage as e:
                errs.append("browser: %s" % e)
                looks_bin = True
                break
            except Exception as e:
                errs.append("browser: %r" % e)
                break
            if ERRPAGE.search(r["title"] + "\n" + r["text"][:200]) and len(r["text"]) < 2000:
                errs.append("browser: " + r["title"][:60])
                time.sleep(wait)
                wait *= 3
                continue
            if CHALLENGE.search(r["title"] + r["text"][:300]) and len(r["text"]) < 1500:
                errs.append("browser: bot判定 " + r["title"][:60])
                break
            if len(r["text"].strip()) >= 50:
                h = r.pop("html", "")
                t = html_text(h, r["url"]) if h else ""
                # 本文抽出が画面の文字よりずっと短いのは一覧ページなどで取りこぼしたときなので、画面の文字を使う
                ok = len(t) >= max(300, len(r["text"]) * 0.3)
                return {"url": r["url"], "title": r["title"], "type": "html", "via": "browser",
                        "text": t if ok else r["text"], "html": h}
            errs.append("browser: 中身なし")
            break
    if looks_bin:
        try:
            ct, raw = run(browser_bytes, url)
            k = kind(ct, raw, url)
            if k in ("pdf", "image"):
                return dict(binary_result(k, url, raw, pages, ocr), via="browser")
        except Exception as e:
            errs.append("browser-bytes: %r" % e)
    # 最後の手段: Internet Archive の写し（id_ は書き換え前の元の HTML/PDF）
    try:
        fu, ct, raw = http_get("https://web.archive.org/web/2id_/" + url, tries=2, timeout=30)
        k = kind(ct, raw, url)
        if k in ("pdf", "image"):
            return dict(binary_result(k, url, raw, pages, ocr), via="archive")
        h = decode_html(raw, ct)
        t = html_text(h, url)
        if len(t) >= 200:
            return {"url": url, "title": title_of(h), "text": t, "html": h, "type": "html",
                    "via": "archive", "archive": fu}
        errs.append("archive: 本文が短い")
    except Exception as e:
        errs.append("archive: %r" % e)
    raise RuntimeError(" / ".join(errs)[:600])


# ---------------- HTTP ----------------

class H(BaseHTTPRequestHandler):
    def log_message(s, f, *a):
        log(f % a)

    def send(s, code, o):
        b = json.dumps(o, ensure_ascii=False).encode()
        s.send_response(code)
        s.send_header("Content-Type", "application/json; charset=utf-8")
        s.send_header("Content-Length", str(len(b)))
        s.end_headers()
        s.wfile.write(b)

    def do_GET(s):
        u = urllib.parse.urlsplit(s.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        t0 = time.time()
        try:
            if u.path == "/health":
                return s.send(200, {"ok": True, "browsers": len(_known),
                                    "idle": SLOTS.qsize(),
                                    "blocked": [e for e, t in BLOCKED.items() if t > time.time()]})
            if u.path == "/search" and q.get("q"):
                e = q.get("engine")
                r = search(q["q"], int(q.get("n", 8)), e if e in ENGINES else None)
            elif u.path == "/fetch" and q.get("url", "").startswith(("http://", "https://")):
                r = fetch(q["url"], int(q.get("max", 20000)), q.get("html") == "1",
                          q.get("mode", "auto"), int(q.get("ocr", 8)), int(q.get("pages", 30)))
            else:
                return s.send(400, {"error": "使い方: /search?q=語 または /fetch?url=URL"})
            r["sec"] = round(time.time() - t0, 1)
            s.send(200, r)
        except Exception as e:
            s.send(502, {"error": repr(e)[:600]})


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64


if __name__ == "__main__":
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8095
    log("search_api listen", host, port, "pool", POOL)
    Server((host, port), H).serve_forever()
