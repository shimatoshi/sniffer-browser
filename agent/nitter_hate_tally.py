import json, time, urllib.request, urllib.parse, websocket, os

INST = "https://nitter.net"

FIGHTERS = {
    "Jon Jones": ("jon jones", "ジョン・ジョーンズ", "존 존스"),
    "Colby Covington": ("colby covington", "コビントン", None),
    "Conor McGregor": ("mcgregor", "マクレガー", None),
    "Ciryl Gane": ("ciryl gane", "ガーヌ", "시릴 가네"),
    "Sean Strickland": ("sean strickland", "ストリックランド", None),
    "Paddy Pimblett": ("paddy pimblett", "パディ・ピンブレット", None),
    "Jack Della Maddalena": ("della maddalena", "デラマダレナ", None),
    "Aljamain Sterling": ("aljamain sterling", "スターリング", None),
    "Li Jingliang": ("li jingliang", "リー・ジンリャン", None),
    "Song Yadong": ("song yadong", "ソン・ヤドン", None),
    "Khamzat Chimaev": ("chimaev", "チマエフ", "치마예프"),
    "Nassourdine Imavov": ("imavov", "イマボフ", None),
    "Yoo Joosang": ("joosang yoo", "ユ・ジュサン", "유주상"),
}
EN = "(hate OR fraud OR cheater OR dirty OR coward OR disgrace)"
JA = "(嫌い OR 卑怯 OR クズ OR 反則 OR ダサい)"
KO = "(싫어 OR 비겁 OR 쓰레기 OR 사기꾼)"

ts = json.loads(urllib.request.urlopen('http://127.0.0.1:9222/json', timeout=5).read())
t = next((x for x in ts if 'nitter' in x.get('url', '')), [x for x in ts if x['type'] == 'page'][0])
ws = websocket.create_connection(t['webSocketDebuggerUrl'], timeout=45, suppress_origin=True)
mid = 0

def send(m, p=None):
    global mid
    mid += 1
    ws.send(json.dumps({"id": mid, "method": m, "params": p or {}}))
    while True:
        r = json.loads(ws.recv())
        if r.get("id") == mid:
            return r

def query(q):
    url = f"{INST}/search?f=tweets&q={urllib.parse.quote(q)}"
    send("Page.navigate", {"url": url})
    time.sleep(9)
    r = send("Runtime.evaluate", {"expression": r"""
(function(){
  var items=document.querySelectorAll('.timeline-item');
  var out=[];
  for(var i=0;i<items.length;i++){
    if(items[i].classList.contains('show-more'))continue;
    var c=items[i].querySelector('.tweet-content');
    if(c)out.push(c.innerText.slice(0,100));
  }
  return {n:out.length, s:out.slice(0,2), title:document.title};
})()""", "returnByValue": True})
    return r["result"]["result"].get("value") or {"n": -1, "s": []}

results = {}
for name, (en, ja, ko) in FIGHTERS.items():
    row, samples = {}, []
    for lang, q in [("EN", f"{en} {EN}"), ("JA", f"{ja} {JA}")] + ([("KO", f"{ko} {KO}")] if ko else []):
        v = query(q)
        row[lang] = v["n"]
        samples += [(lang, s) for s in v["s"]]
        print(f"{name} [{lang}] {v['n']}件", flush=True)
        time.sleep(3)
    results[name] = {"counts": row, "samples": samples}

print("\n=== NITTER RANKING (直近ツイート, 上限20件/言語) ===")
for name, d in sorted(results.items(), key=lambda x: -sum(max(0, v) for v in x[1]["counts"].values())):
    total = sum(max(0, v) for v in d["counts"].values())
    print(f"{total:3d}  {name:22s} {d['counts']}")

with open(os.path.expanduser("~/nitter_hate_results.json"), "w") as f:
    json.dump(results, f, ensure_ascii=False, indent=1)
print("saved: ~/nitter_hate_results.json")
