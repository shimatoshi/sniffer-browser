#!/data/data/com.termux/files/usr/bin/python3
"""
gobie — Gobieブラウザ（実WebView）をターミナルから操作するCLI。

実機の本物のWebViewを叩くため webdriver=false / 実UA / 実GPU で
bot検知を構造的に回避する。バックグラウンドServiceで動くので
ユーザーの画面操作と競合しない。

使い方の詳細は GOBIE.md を参照。`gobie help` でも一覧が出る。
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

AGENT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AGENT_DIR)
import cdp  # noqa: E402
from cdp import CDP, CDP_HTTP  # noqa: E402

STATE_DIR = os.path.expanduser("~/.cache/gobie")
STATE_FILE = os.path.join(STATE_DIR, "state.json")
BRIDGE = os.path.join(AGENT_DIR, "bridge.sh")
PKG = "net.localhost.sniffer"
SU = os.path.expanduser("~/.sbin/su")
ADB = ["adb", "-P", "5038", "-s", "127.0.0.1:5555"]

SEARCH_URL = os.environ.get(
    "GOBIE_SEARCH", "https://www.google.com/search?q={}")


# ---------- 接続まわり ----------

def cdp_alive():
    try:
        urllib.request.urlopen(CDP_HTTP + "/json/version", timeout=2)
        return True
    except Exception:
        return False


def has_page():
    """ヘッドレスのpage targetが実在するか。stop後はプロセス（=CDPソケット）が
    生きたままWebViewだけ無いことがあるので、aliveだけでは不十分。
    ユーザータブだけある状態もNG（誤操作防止のためヘッドレスを厳密に探す）。"""
    return cdp.find_headless_target() is not None


def ensure_bridge(quiet=True):
    """CDPが応答しなければ bridge.sh で起動・張り直す。
    応答してもpage targetが無ければServiceを再始動してWebViewを作らせる。"""
    if not cdp_alive():
        r = subprocess.run([BRIDGE], capture_output=True, text=True)
        if not quiet:
            sys.stderr.write(r.stdout + r.stderr)
        if not cdp_alive():
            sys.exit("error: CDPに接続できない。bridge.sh の出力:\n"
                     + r.stdout + r.stderr)
    if not has_page():
        subprocess.run(
            [SU, "-c",
             f"am start-foreground-service -n {PKG}/.HeadlessBrowserService"
             f" --es url about:blank"],
            capture_output=True)
        deadline = time.time() + 15
        while time.time() < deadline:
            if has_page():
                return
            time.sleep(0.5)
        sys.exit("error: WebView(page target)が出てこない")


def connect():
    ensure_bridge()
    # 起動直後はWebView初期化中で page target が未露出のことがある
    deadline = time.time() + 10
    while True:
        try:
            return CDP()
        except Exception:
            if time.time() >= deadline:
                raise
            time.sleep(0.5)


# ---------- list 状態の永続化 ----------

def save_state(d):
    os.makedirs(STATE_DIR, exist_ok=True)
    with open(STATE_FILE, "w") as f:
        json.dump(d, f, ensure_ascii=False)


def load_state():
    try:
        with open(STATE_FILE) as f:
            return json.load(f)
    except Exception:
        return {}


# ---------- リンク抽出 ----------

# 可視で意味のあるリンクを文書順に集める。Google SERPでは a>h3 を優先。
LINKS_JS = r"""
(function(){
  function vis(e){var r=e.getBoundingClientRect();
    return r.width>0&&r.height>0||e.offsetParent!==null;}
  var out=[],seen={};
  // Google SERP: 内部リンク（タブ/ログイン/共有等）を捨て、外部ホストの
  // テキスト付きアンカー＝実結果だけを文書順に拾う（モバイル版はh3を使わない）
  if(/(^|\.)google\.[a-z.]+$/.test(location.hostname)){
    document.querySelectorAll('a[href]').forEach(function(a){
      var t=(a.innerText||'').trim().replace(/\s+/g,' ');
      if(t.length<2)return;
      try{var host=new URL(a.href).hostname;}catch(e){return;}
      if(/(^|\.)(google\.[a-z.]+|goo\.gl|gstatic\.com|googleusercontent\.com)$/.test(host))return;
      if(seen[a.href])return;seen[a.href]=1;
      out.push({t:t.slice(0,120),u:a.href});});
    return JSON.stringify({mode:'serp',links:out});
  }
  document.querySelectorAll('a[href]').forEach(function(a){
    var u=a.href,t=(a.innerText||'').trim().replace(/\s+/g,' ');
    if(!u||u.startsWith('javascript:'))return;
    if(u.split('#')[0]===location.href.split('#')[0])return;
    if(t.length<2)return;
    if(!vis(a))return;
    if(seen[u])return;seen[u]=1;
    out.push({t:t.slice(0,120),u:u});});
  return JSON.stringify({mode:'page',links:out});
})()
"""

NEXT_JS = r"""
(function(){
  var e=document.querySelector('#pnnext')
    ||document.querySelector('a[rel=next]')
    ||document.querySelector('link[rel=next]');
  if(e&&e.href)return e.href;
  var pat=/^(次のページ|次へ?|next\s*[›>»]?|[›>»]|more)$/i;
  var as=document.querySelectorAll('a[href]');
  for(var i=0;i<as.length;i++){
    var t=(as[i].innerText||'').trim();
    if(pat.test(t))return as[i].href;}
  // Google検索: ページネーションUIが無くても start= で次ページに行ける
  if(/(^|\.)google\.[a-z.]+$/.test(location.hostname)
     && location.pathname=='/search'){
    var u=new URL(location.href);
    var s=parseInt(u.searchParams.get('start')||'0',10);
    u.searchParams.set('start', s+10);
    return u.href;
  }
  return null;
})()
"""


def do_list(c, filt=None):
    raw = c.eval(LINKS_JS)
    d = json.loads(raw) if raw else {"mode": "page", "links": []}
    links = d["links"]
    if filt:
        pat = re.compile(filt, re.I)
        links = [l for l in links if pat.search(l["t"]) or pat.search(l["u"])]
    save_state({"url": c.url(), "links": links})
    for i, l in enumerate(links, 1):
        print(f"[{i}] {l['t']}\n    {l['u']}")
    if not links:
        print("(リンクなし)")
    return 0


def resolve_target(arg):
    """数字なら直近listのn番のURL、それ以外はURLとして返す。"""
    if arg.isdigit():
        st = load_state()
        links = st.get("links", [])
        n = int(arg)
        if not links:
            sys.exit("error: list 未実行（番号指定の前に gobie list）")
        if not 1 <= n <= len(links):
            sys.exit(f"error: 番号は 1〜{len(links)}")
        return links[n - 1]["u"]
    if not re.match(r"^[a-z]+://", arg):
        arg = "https://" + arg
    return arg


# ---------- サービス管理 ----------

def do_stop():
    subprocess.run(
        [SU, "-c",
         f"am start-foreground-service -n {PKG}/.HeadlessBrowserService"
         f" -a {PKG}.HEADLESS_STOP"],
        capture_output=True)
    subprocess.run(ADB + ["forward", "--remove", "tcp:9222"],
                   capture_output=True)
    print("stopped")


def do_status():
    if not cdp_alive():
        print("down (gobie up で起動)")
        return 1
    c = CDP()
    try:
        print(f"up  {c.title()}\n    {c.url()}")
    finally:
        c.close()
    return 0


HELP = """\
gobie — 実WebViewブラウザのCLI操作（bot検知回避・バックグラウンド動作）

  gobie up                  ブラウザ起動＋ブリッジ確立（他コマンドが自動でやるので通常不要）
  gobie status              起動状態と現在ページ
  gobie stop                ブラウザ停止・ポート閉鎖

  gobie search <キーワード>  Web検索して結果一覧を表示（=searchbox）
  gobie list [正規表現]      現在ページのリンク一覧（番号付き）。引数で絞り込み
  gobie open <n|URL>        listのn番 or URLへ遷移（=jump）
  gobie next                検索結果/ページネーションの次ページへ
  gobie back / forward      履歴を戻る / 進む
  gobie scroll [down|up|top|bottom|<px>]   スクロール（既定:down）

  gobie text                本文テキスト全文
  gobie url / title         現在URL / タイトル
  gobie html                DOM全体(outerHTML)
  gobie eval <JS式>          JavaScript実行（結果をJSONで返す）
  gobie click <CSSセレクタ>
  gobie fill <CSSセレクタ> <値>
  gobie wait <CSSセレクタ>   要素の出現を待つ
  gobie shot [path.png]     スクリーンショット
"""


def main(argv):
    if not argv or argv[0] in ("help", "-h", "--help"):
        print(HELP)
        return 0
    cmd, args = argv[0], argv[1:]

    # 接続不要のコマンド
    if cmd == "stop":
        do_stop()
        return 0
    if cmd == "status":
        return do_status()
    if cmd == "up":
        ensure_bridge(quiet=False)
        return do_status()

    c = connect()
    try:
        if cmd in ("goto", "jump", "open"):
            url = resolve_target(args[0])
            print(c.goto(url))
        elif cmd in ("search", "searchbox"):
            q = " ".join(args)
            if not q:
                sys.exit("error: 検索キーワードが必要")
            c.goto(SEARCH_URL.format(urllib.parse.quote(q)))
            c.wait("a h3", timeout=10)
            return do_list(c)
        elif cmd == "list":
            return do_list(c, args[0] if args else None)
        elif cmd == "next":
            href = c.eval(NEXT_JS)
            if not href:
                print("next-not-found")
                return 1
            print(c.goto(href))
        elif cmd == "back":
            c.eval("history.back()")
            time.sleep(1.0)
            print(c.url())
        elif cmd == "forward":
            c.eval("history.forward()")
            time.sleep(1.0)
            print(c.url())
        elif cmd == "scroll":
            arg = args[0] if args else "down"
            # window が直接スクロールしないページがあるので scrollingElement 経由
            if arg == "down":
                js = "innerHeight*0.85"
            elif arg == "up":
                js = "-innerHeight*0.85"
            elif arg == "top":
                js = "-1e9"
            elif arg == "bottom":
                js = "1e9"
            else:
                js = str(int(arg))
            pos = c.eval(
                "(function(d){var s=document.scrollingElement"
                "||document.documentElement;"
                f"s.scrollTop+=({js});"
                "return Math.round(s.scrollTop)+'/'"
                "+Math.round(s.scrollHeight-innerHeight);})()")
            time.sleep(0.3)
            print(f"y={pos}")
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
            sys.exit(f"unknown command: {cmd}（gobie help で一覧）")
    finally:
        c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
