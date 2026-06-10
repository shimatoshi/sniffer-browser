#!/usr/bin/env python3
"""news-pwaターゲットに直接繋いでSW状態確認→オフライン模擬→リロード検証"""
import json, sys, time, urllib.request
import websocket

HTTP = "http://127.0.0.1:9222"

def pick(url_part):
    targets = json.load(urllib.request.urlopen(HTTP + "/json", timeout=5))
    for t in targets:
        if t.get("type") == "page" and url_part in t.get("url", ""):
            return t
    return None

t = pick("news-pwa.vercel.app")
if not t:
    sys.exit("news-pwa target not found")
print("target:", t["title"], t["url"])

ws = websocket.create_connection(t["webSocketDebuggerUrl"], timeout=30, max_size=None, suppress_origin=True)
_id = 0

def send(method, **params):
    global _id
    _id += 1
    ws.send(json.dumps({"id": _id, "method": method, "params": params}))
    deadline = time.time() + 30
    while time.time() < deadline:
        msg = json.loads(ws.recv())
        if msg.get("id") == _id:
            if "error" in msg:
                raise RuntimeError(msg["error"])
            return msg.get("result", {})
    raise TimeoutError(method)

def ev(expr):
    r = send("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=True)
    return r.get("result", {}).get("value")

# 1. SW状態
print("SW:", ev("""navigator.serviceWorker.getRegistrations().then(rs=>JSON.stringify(rs.map(r=>({scope:r.scope, active:r.active?r.active.state:null, controlled:!!navigator.serviceWorker.controller}))))"""))
print("storage:", ev("navigator.storage ? navigator.storage.estimate().then(e=>JSON.stringify({usage:e.usage, quota:e.quota})) : 'no-api'"))
print("persisted:", ev("navigator.storage && navigator.storage.persisted ? navigator.storage.persisted().then(String) : 'no-api'"))

# 2. オフライン模擬
send("Network.enable")
send("Network.emulateNetworkConditions", offline=True, latency=0, downloadThroughput=-1, uploadThroughput=-1)
print("offline emulation ON")

# 3. リロード → SWキャッシュから復元できるか
send("Page.enable")
send("Page.reload", ignoreCache=False)
time.sleep(6)
title = ev("document.title")
body_len = ev("document.body ? document.body.innerText.length : 0")
err_page = ev("document.title.includes('Webページ') || (document.body && document.body.innerText.includes('ERR_'))")
print(f"after offline reload: title={title!r} bodyLen={body_len} errPage={err_page}")
print("controlled now:", ev("String(!!navigator.serviceWorker.controller)"))

# 4. 復帰
send("Network.emulateNetworkConditions", offline=False, latency=0, downloadThroughput=-1, uploadThroughput=-1)
print("offline emulation OFF")
ws.close()
