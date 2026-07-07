import json, time, urllib.request, urllib.parse, websocket

FIGHTERS = {
    "Jon Jones": ('"jon jones"', "jones|jon jones"),
    "Colby Covington": ('"colby covington"', "colby|covington"),
    "Conor McGregor": ('"conor mcgregor"', "conor|mcgregor"),
    "Ciryl Gane": ('"ciryl gane"', "gane|ciryl"),
    "Ian Garry": ('"ian garry"', "garry"),
    "Sean Strickland": ('"sean strickland"', "strickland"),
    "Paddy Pimblett": ('"paddy pimblett"', "paddy|pimblett"),
    "Israel Adesanya": ('"adesanya"', "adesanya|izzy"),
    "Bo Nickal": ('"bo nickal"', "nickal"),
    "Dricus du Plessis": ('"du plessis"', "dricus|du plessis|ddp"),
    "Jack Della Maddalena": ('"della maddalena"', "della|maddalena|jdm"),
    "Aljamain Sterling": ('"aljamain sterling"', "aljamain|sterling|aljo"),
    "Li Jingliang": ('"li jingliang"', "jingliang|leech"),
    "Song Yadong": ('"song yadong"', "yadong|song"),
    "Khamzat Chimaev": ('"chimaev"', "chimaev|khamzat|borz"),
    "Nassourdine Imavov": ('"imavov"', "imavov|nassourdine"),
    "Yoo Joosang": ('"joosang"', "joosang|yoo"),
}
N_THREADS = 5

# 多言語アンチ語彙（嫌い系＋卑怯系＋PED系）
HATE_RE = (r"(hate(s|d)?\b|fraud|cheat(er|ing|ed)?|disgrace|scum|garbage|trash\b|villain|"
           r"piece of shit|dickhead|douche|can'?t stand|overrated|coward|duck(s|ing|ed)?\b|"
           r"classless|clown|insufferable|unlikable|dislike|dirty fight|dirty\b|greas(y|e)|"
           r"eye ?poke|fence grab|quitter|fake\b|cringe|juic(ed|ing)|roid(s|ed)?|peds?\b|doping|steroid|"
           r"嫌い|卑怯|クズ|ダサ|反則|薬物|きらい|"
           r"싫어|비겁|사기꾼|쓰레기|약쟁이|"
           r"讨厌|卑鄙|作弊)")

def targets():
    return json.loads(urllib.request.urlopen('http://127.0.0.1:9222/json', timeout=5).read())

class CDP:
    def __init__(self):
        self.connect()
    def connect(self):
        ts = [x for x in targets() if x['type'] == 'page']
        t = next((x for x in ts if 'reddit.com' in x.get('url', '')), ts[0])
        self.ws = websocket.create_connection(t['webSocketDebuggerUrl'], timeout=45, suppress_origin=True)
        self.mid = 0
    def send(self, method, params=None):
        self.mid += 1
        self.ws.send(json.dumps({"id": self.mid, "method": method, "params": params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self.mid:
                return msg
    def js(self, expr, retry=2):
        for i in range(retry + 1):
            try:
                r = self.send("Runtime.evaluate", {"expression": expr, "returnByValue": True, "awaitPromise": True})
                if "exceptionDetails" in r.get("result", {}):
                    raise RuntimeError(str(r.get("result", {}).get("exceptionDetails"))[:200])
                return r["result"]["result"].get("value")
            except (websocket.WebSocketException, ConnectionError, TimeoutError, OSError):
                if i == retry: raise
                print("  [ws再接続]")
                try: self.ws.close()
                except Exception: pass
                time.sleep(3)
                self.connect()
                self.ensure_reddit()
    def ensure_reddit(self):
        self.send("Page.navigate", {"url": "https://www.reddit.com/r/MMA/"})
        time.sleep(10)
        self.define()
    def js_raw(self, expr):
        r = self.send("Runtime.evaluate", {"expression": expr, "returnByValue": True})
        return r["result"]["result"].get("value")
    def define(self):
        self.js_raw(r"""
window.HATE = new RegExp(""" + json.dumps(HATE_RE) + r""", 'i');
window.walkC = function(node, nameRe, acc){
  if(!node) return;
  if(node.kind==='t1'){
    const b=node.data.body||'';
    acc.total++;
    if(window.HATE.test(b)){ acc.hate++; if(nameRe.test(b)) acc.named++; }
  }
  const ch=node.data&&node.data.replies&&node.data.replies.data&&node.data.replies.data.children;
  if(ch) for(const c of ch) window.walkC(c,nameRe,acc);
};
window.F = function(url){
  return Promise.race([
    fetch(url),
    new Promise((_,rej)=>setTimeout(()=>rej(new Error('fetch timeout')),20000))
  ]);
};
window.tallyThread = async function(permalink, namePat){
  const r = await window.F('https://www.reddit.com'+permalink+'.json?limit=500&depth=10');
  if(!r.ok) return {total:0,hate:0,named:0,err:r.status};
  const j = await r.json();
  const acc={total:0,hate:0,named:0};
  const nameRe = new RegExp(namePat,'i');
  for(const c of j[1].data.children) window.walkC(c,nameRe,acc);
  return acc;
};
'ok'""")

cdp = CDP()
cdp.ensure_reddit()
print("on:", cdp.js_raw("location.href"))

results = {}
for name, (q, namepat) in FIGHTERS.items():
    url = f"https://www.reddit.com/r/MMA/search.json?q={urllib.parse.quote(q)}&restrict_sr=1&t=year&sort=comments&limit={N_THREADS}"
    search = cdp.js(f"window.F('{url}').then(r=>r.ok?r.json():{{err:r.status}}).then(j=>j.data?j.data.children.map(c=>({{p:c.data.permalink,t:c.data.title,n:c.data.num_comments}})):j).catch(e=>({{err:String(e)}}))")
    if not isinstance(search, list):
        print(f"{name}: search error {search}"); time.sleep(3); continue
    tot = {"total": 0, "hate": 0, "named": 0}
    threads = []
    for th in search:
        try:
            acc = cdp.js(f"tallyThread({json.dumps(th['p'])}, {json.dumps(namepat)})")
            if acc.get("err"):
                print(f"  {th['t'][:40]}: HTTP {acc['err']}"); time.sleep(5); continue
            for k in tot: tot[k] += acc[k]
            threads.append((th['t'], acc))
        except Exception as e:
            print("  thread err:", str(e)[:100])
        time.sleep(2.5)
    results[name] = (tot, threads)
    print(f"{name}: sampled={tot['total']} hate={tot['hate']} named={tot['named']}")

print("\n=== RANKING: アンチコメント率 ===")
for name, (tot, threads) in sorted(results.items(), key=lambda x: -(x[1][0]['hate']/max(1,x[1][0]['total']))):
    r = 100*tot['hate']/max(1,tot['total'])
    rn = 100*tot['named']/max(1,tot['total'])
    print(f"{r:5.1f}% (named {rn:4.1f}%)  {name:22s} hate={tot['hate']:4d}/{tot['total']:5d}")

with open('/data/data/com.termux/files/home/reddit_hate_results.json','w') as f:
    json.dump({k:{'tot':v[0],'threads':v[1]} for k,v in results.items()}, f, ensure_ascii=False, indent=1)
print("saved: ~/reddit_hate_results.json")
