#!/data/data/com.termux/files/usr/bin/python3
"""
cdp.py — sniffer-browser の実WebViewを CDP で外部ドライブするエージェント用ドライバ。

実ブラウザを叩くので TLS/JS challenge/Canvas fingerprint が全部本物になり、
curl/wget が 403 で弾かれる bot 検知を構造的に回避できる。

前提:
  - sniffer-browser が起動していて setWebContentsDebuggingEnabled(true) 済み
  - adb forward tcp:9222 localabstract:webview_devtools_remote_<pid> が張られている
    （bridge.sh が自動でやる）

使い方（CLI、LLMから叩く想定）:
  python3 cdp.py goto https://example.com
  python3 cdp.py text                 # 可視テキスト全文（本文抽出）
  python3 cdp.py html                 # 現在のDOM(outerHTML)
  python3 cdp.py eval "document.title"
  python3 cdp.py click "button.login"
  python3 cdp.py fill "input#q" "検索語"
  python3 cdp.py shot out.png         # スクリーンショット
  python3 cdp.py wait "div.result"    # セレクタ出現待ち
  python3 cdp.py url                  # 現在URL
"""
import json
import sys
import time
import urllib.request

import websocket  # websocket-client

CDP_HTTP = "http://127.0.0.1:9222"


def _probe_headless(ws_url):
    """page targetに短時間接続して window.__gobieHeadless マーカーを確認する。
    読み取りevalのみ（副作用なし）。応答しないタブ（凍結中など）はFalse扱い。"""
    try:
        ws = websocket.create_connection(
            ws_url, timeout=3, max_size=None, suppress_origin=True)
        try:
            ws.send(json.dumps({
                "id": 1, "method": "Runtime.evaluate",
                "params": {"expression": "window.__gobieHeadless===true",
                           "returnByValue": True}}))
            deadline = time.time() + 3
            while time.time() < deadline:
                msg = json.loads(ws.recv())
                if msg.get("id") == 1:
                    return bool(msg.get("result", {})
                                .get("result", {}).get("value"))
        finally:
            ws.close()
    except Exception:
        pass
    return False


def find_headless_target(http=CDP_HTTP):
    """HeadlessBrowserServiceのWebViewのtargetを返す。見つからなければNone。
    MainActivityのユーザータブを誤って掴まないよう、マーカーで厳密に識別する。"""
    try:
        targets = json.load(urllib.request.urlopen(http + "/json", timeout=5))
    except Exception:
        return None
    pages = [t for t in targets
             if t.get("type") == "page" and t.get("webSocketDebuggerUrl")]
    for t in pages:
        if _probe_headless(t["webSocketDebuggerUrl"]):
            return t
    # マーカー未注入の旧APK/起動直後への保険: about:blank はヘッドレス初期状態
    for t in pages:
        if t.get("url") in ("", "about:blank"):
            return t
    return None


class CDP:
    def __init__(self, http=CDP_HTTP, timeout=30):
        self.http = http
        self.timeout = timeout
        self._id = 0
        self.ws = self._connect_page()

    def _connect_page(self):
        t = find_headless_target(self.http)
        if t is None:
            raise RuntimeError(
                "headless target が見つからない（ユーザータブは操作しない）。"
                "HeadlessBrowserService が起動してるか確認")
        ws_url = t["webSocketDebuggerUrl"]
        # Chrome 111+ は Origin ヘッダ付きのCDP WS接続を拒否する。
        # suppress_origin で Origin を送らない（no-origin は許可される）。
        ws = websocket.create_connection(
            ws_url, timeout=self.timeout, max_size=None, suppress_origin=True)
        return ws

    def send(self, method, **params):
        self._id += 1
        mid = self._id
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params}))
        # 自分のidの応答が返るまでイベントを読み飛ばす
        deadline = time.time() + self.timeout
        while time.time() < deadline:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})
        raise TimeoutError(f"{method} timeout")

    # --- 高レベル動詞 ---
    def goto(self, url, wait_load=True):
        self.send("Page.enable")
        self.send("Page.navigate", url=url)
        if wait_load:
            self._wait_load()
        return self.url()

    def _wait_load(self, timeout=20):
        """document.readyState == complete を軽くポーリング"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            rs = self.eval("document.readyState")
            if rs == "complete":
                time.sleep(0.4)  # 動的描画の小休止
                return
            time.sleep(0.25)

    def eval(self, expr):
        r = self.send("Runtime.evaluate", expression=expr,
                      returnByValue=True, awaitPromise=True)
        res = r.get("result", {})
        return res.get("value")

    def url(self):
        return self.eval("location.href")

    def title(self):
        return self.eval("document.title")

    def text(self):
        # body の可視テキスト（script/style除外はinnerTextが面倒見る）
        return self.eval("document.body ? document.body.innerText : ''")

    def html(self):
        return self.eval("document.documentElement.outerHTML")

    def wait(self, selector, timeout=15):
        deadline = time.time() + timeout
        sel = json.dumps(selector)
        while time.time() < deadline:
            if self.eval(f"!!document.querySelector({sel})"):
                return True
            time.sleep(0.3)
        return False

    def click(self, selector):
        sel = json.dumps(selector)
        ok = self.eval(
            f"(function(){{var e=document.querySelector({sel});"
            f"if(!e)return false;e.click();return true;}})()")
        return ok

    def fill(self, selector, value):
        sel = json.dumps(selector)
        val = json.dumps(value)
        ok = self.eval(
            f"(function(){{var e=document.querySelector({sel});if(!e)return false;"
            f"e.focus();e.value={val};"
            f"e.dispatchEvent(new Event('input',{{bubbles:true}}));"
            f"e.dispatchEvent(new Event('change',{{bubbles:true}}));return true;}})()")
        return ok

    def screenshot(self, path):
        # HeadlessBrowserService の画面外オーバーレイは VISIBLE 維持＝レンダラが動くので
        # 前面化なしでそのまま撮れる（Activity背面版と違いストールしない）。
        import base64
        self.send("Page.enable")
        r = self.send("Page.captureScreenshot", format="png")
        with open(path, "wb") as f:
            f.write(base64.b64decode(r["data"]))
        return path

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def main(argv):
    if not argv:
        print(__doc__)
        return 1
    cmd, args = argv[0], argv[1:]
    c = CDP()
    try:
        if cmd == "goto":
            print(c.goto(args[0]))
        elif cmd == "text":
            print(c.text())
        elif cmd == "html":
            print(c.html())
        elif cmd == "url":
            print(c.url())
        elif cmd == "title":
            print(c.title())
        elif cmd == "eval":
            print(json.dumps(c.eval(args[0]), ensure_ascii=False))
        elif cmd == "click":
            print("ok" if c.click(args[0]) else "not-found")
        elif cmd == "fill":
            print("ok" if c.fill(args[0], args[1]) else "not-found")
        elif cmd == "wait":
            print("ok" if c.wait(args[0]) else "timeout")
        elif cmd == "shot":
            print(c.screenshot(args[0] if args else "shot.png"))
        else:
            print(f"unknown command: {cmd}")
            return 1
    finally:
        c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
