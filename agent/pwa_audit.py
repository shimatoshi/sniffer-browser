#!/usr/bin/env python3
"""インストール済みPWA全件のオフライン駆動可能性監査。
各URLをPwaActivityで開き、CDPでSW登録/CacheStorage/persistedを確認後、
Network.emulateNetworkConditionsでオフライン模擬→リロード→復元可否を判定する。
usage: python3 pwa_audit.py [port=9223]
前提: adb forward tcp:<port> localabstract:webview_devtools_remote_<pid> 済み
"""
import json, subprocess, sys, time, urllib.request
import websocket

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9223
HTTP = f"http://127.0.0.1:{PORT}"
ADB = ["adb", "-P", "5038", "-s", "emulator-5554"]

URLS = [
    ("乗換案内",     "https://transit-pwa.vercel.app/"),
    ("ダメ計",       "https://pokechampions-calc.vercel.app/"),
    ("Taskbox",      "https://shimatoshi.github.io/taskbox/"),
    ("ResearchZIM",  "https://research-zim.vercel.app/"),
    ("ShimaTube",    "https://shimatube.vercel.app/"),
    ("ニュース天気", "https://news-pwa-rho.vercel.app/index.html"),
    ("としゆきNote", "https://toshiyuki-note.vercel.app/"),
    ("DMOnline2",    "https://dmonline2.vercel.app/"),
    ("URLBoard",     "https://url-board.vercel.app/"),
    ("バトルDB",     "https://champs.pokedb.tokyo/"),
    ("Tapology",     "https://www.tapology.com/"),
]


def adb(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True, timeout=30).stdout


def targets():
    try:
        return json.load(urllib.request.urlopen(HTTP + "/json", timeout=5))
    except Exception:
        return []


def find_target(url):
    host = url.split("/")[2]
    for t in targets():
        if t.get("type") == "page" and ("//" + host) in t.get("url", ""):
            return t
    return None


class Sess:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, timeout=30, max_size=None, suppress_origin=True)
        self._id = 0

    def send(self, method, **params):
        self._id += 1
        self.ws.send(json.dumps({"id": self._id, "method": method, "params": params}))
        deadline = time.time() + 30
        while time.time() < deadline:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self._id:
                if "error" in msg:
                    raise RuntimeError(msg["error"])
                return msg.get("result", {})
        raise TimeoutError(method)

    def ev(self, expr):
        r = self.send("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=True)
        return r.get("result", {}).get("value")

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def audit(name, url):
    res = {"name": name, "url": url}
    adb("shell", "am", "start", "-n", "net.localhost.sniffer/.PwaActivity",
        "-a", "android.intent.action.VIEW", "-d", url)
    # ターゲット出現待ち
    t = None
    for _ in range(20):
        time.sleep(1)
        t = find_target(url)
        if t:
            break
    if not t:
        res["error"] = "target not found"
        return res
    s = Sess(t["webSocketDebuggerUrl"])
    try:
        # ページロード+SWインストール待ち(最大15秒、登録が見えたら3秒だけ追加待ちでactive化)
        sw = None
        for i in range(15):
            sw = s.ev("""navigator.serviceWorker ? navigator.serviceWorker.getRegistrations().then(rs=>rs.map(r=>({scope:r.scope, state:r.active?r.active.state:(r.installing?'installing':(r.waiting?'waiting':'none'))}))) : 'no-api'""")
            if sw and sw != "no-api" and any(x.get("state") == "activated" for x in sw):
                break
            time.sleep(1)
        res["sw"] = sw
        res["controlled"] = s.ev("String(!!(navigator.serviceWorker && navigator.serviceWorker.controller))")
        res["caches"] = s.ev("typeof caches !== 'undefined' ? caches.keys() : 'no-api'")
        res["persisted"] = s.ev("navigator.storage && navigator.storage.persisted ? navigator.storage.persisted().then(String) : 'no-api'")
        est = s.ev("navigator.storage && navigator.storage.estimate ? navigator.storage.estimate().then(e=>JSON.stringify({u:e.usage,q:e.quota})) : 'no-api'")
        res["estimate"] = est
        # オフライン模擬→リロード
        s.send("Network.enable")
        s.send("Network.emulateNetworkConditions", offline=True, latency=0,
               downloadThroughput=-1, uploadThroughput=-1)
        s.send("Page.enable")
        s.send("Page.reload", ignoreCache=False)
        time.sleep(7)
        res["off_title"] = s.ev("document.title")
        res["off_body"] = s.ev("document.body ? document.body.innerText.length : 0")
        res["off_err"] = s.ev("(document.title||'').includes('Webページ') || ((document.body&&document.body.innerText)||'').includes('ERR_')")
        res["off_controlled"] = s.ev("String(!!(navigator.serviceWorker && navigator.serviceWorker.controller))")
        s.send("Network.emulateNetworkConditions", offline=False, latency=0,
               downloadThroughput=-1, uploadThroughput=-1)
    except Exception as e:
        res["error"] = repr(e)
    finally:
        s.close()
    return res


def main():
    for name, url in URLS:
        r = audit(name, url)
        print(json.dumps(r, ensure_ascii=False))
        sys.stdout.flush()


if __name__ == "__main__":
    main()
