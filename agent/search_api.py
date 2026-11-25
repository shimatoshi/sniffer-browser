#!/data/data/com.termux/files/usr/bin/python3
"""sniffer-browser(実WebView)で検索・ページ取得する HTTP API。LLM の資料集め用。
  GET /search?q=語&n=8  → {"results":[{"title","url"}]}
  GET /fetch?url=URL&max=20000[&html=1] → {"url","title","text"[,"html"]}
  GET /health
  python3 search_api.py [待ち受けアドレス] [ポート]   （既定 127.0.0.1 8095）

ブラウザは1つなので依頼は1件ずつ順に処理する。速さのため gobie コマンドを毎回
起動せず、CDP の接続を持ち続けて同じプロセス内で操作する（1操作ごとの起動・接続
確認が無くなる）。待ちは「読み込み完了」ではなく「中身が出そろった時点」で切る
（広告やトラッカーの読み込み完了まで待たない）。"""
import json
import os
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
import gobie  # noqa: E402
from cdp import CDP  # noqa: E402

LOCK = threading.Lock()
_c = [None]


class NavError(Exception):
    """ページを開けなかった（接続は生きているので、つなぎ直さない）"""

# 遷移前の文書に印を付け、印の無い文書になったら新しいページに切り替わったと分かる
MARK = "window.__gobieOld=1"
PAGE_JS = (
    "JSON.stringify([window.__gobieOld===1,document.readyState,"
    "document.body?document.body.innerText.length:0])")


def conn():
    if _c[0] is None:
        try:
            gobie.ensure_bridge()
        except SystemExit as e:  # gobie は CLI 用に sys.exit で失敗を返す
            raise RuntimeError(str(e)) from None
        _c[0] = CDP()
        _c[0].send("Page.enable")
    return _c[0]


def run(fn, *a):
    """接続が切れていたら（ブラウザの自動停止・再起動など）1回だけつなぎ直してやり直す"""
    for attempt in range(2):
        try:
            c = conn()
            r = fn(c, *a)
            # アプリ側の無操作自動停止(10分)用。遷移で消えるので毎回付け直す
            c.eval("window.__gobieLastUse=Date.now()")
            return r
        except NavError:
            raise
        except Exception:
            if _c[0] is not None:
                _c[0].close()
                _c[0] = None
            if attempt:
                raise


def navigate(c, url, ready, timeout):
    """url へ遷移し、ready(readyState, 文字数) が真になるか読み込み完了まで待つ。
    文字数が2回続けて同じ＝描画が落ち着いた時点で返す。"""
    c.eval(MARK)
    r = c.send("Page.navigate", url=url)
    if r.get("errorText"):
        # エラー画面のままだと headless の印が付かず次回つながらないので白紙に戻す
        c.send("Page.navigate", url="about:blank")
        raise NavError("navigate: " + r["errorText"])
    deadline = time.time() + timeout
    last = -1
    while time.time() < deadline:
        old, rs, n = json.loads(c.eval(PAGE_JS) or "[true,\"\",0]")
        if not old and rs != "loading":
            if rs == "complete" or (ready(n) and n == last):
                return
            last = n
        time.sleep(0.15)


def search(c, q, n):
    def links():
        raw = c.eval(gobie.LINKS_JS)
        return json.loads(raw)["links"] if raw else []
    navigate(c, gobie.SEARCH_URL.format(urllib.parse.quote(q)),
             lambda chars: chars > 500, 10)
    ls = links()
    if not ls:  # 起動直後などで描画が間に合わないことがある
        time.sleep(1.5)
        ls = links()
    res, seen = [], set()
    for l in ls:
        u = l["u"].split("#")[0]
        if u in seen or "google." in urllib.parse.urlsplit(u).netloc:
            continue
        seen.add(u)
        res.append({"title": l["t"], "url": u})
        if len(res) >= n:
            break
    return res


def fetch(c, url, mx, html=False):
    navigate(c, url, lambda chars: chars >= 200, 15)
    js = ("JSON.stringify({url:location.href,title:document.title,"
          "text:document.body?document.body.innerText:''"
          + (",html:document.documentElement.outerHTML" if html else "")
          + "})")
    r = json.loads(c.eval(js))
    r["text"] = r["text"][:mx]
    if html:
        r["html"] = r["html"][:3_000_000]  # 本文の抽出は呼び出し側(trafilatura)で行う
    return r


class H(BaseHTTPRequestHandler):
    def log_message(s, f, *a):
        sys.stderr.write("%s %s\n" % (time.strftime("%F %T"), f % a))

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
                return s.send(200, {"ok": True})
            if u.path == "/search" and q.get("q"):
                with LOCK:
                    r = {"results": run(search, q["q"], int(q.get("n", 8)))}
            elif u.path == "/fetch" and q.get("url", "").startswith(("http://", "https://")):
                with LOCK:
                    r = run(fetch, q["url"], int(q.get("max", 20000)), q.get("html") == "1")
            else:
                return s.send(400, {"error": "使い方: /search?q=語 または /fetch?url=URL"})
            r["sec"] = round(time.time() - t0, 1)
            s.send(200, r)
        except Exception as e:
            s.send(502, {"error": repr(e)[:300]})


if __name__ == "__main__":
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8095
    print("search_api listen", host, port, file=sys.stderr, flush=True)
    ThreadingHTTPServer((host, port), H).serve_forever()
