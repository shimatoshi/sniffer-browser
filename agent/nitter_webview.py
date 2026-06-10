#!/data/data/com.termux/files/usr/bin/python3
"""
nitter_webview.py — sniffer-browser の実WebView(CDP)経由で Nitter からツイートを取得する。

curl/WebFetch は Nitter インスタンス側の bot 検知 / Cloudflare で弾かれがちだが、
実 WebView 経由なら navigator.webdriver=false・実機 GPU fingerprint で構造的に通る。

前提: bridge.sh が tcp:9222 に CDP を張っていること（張ってなければ自動で叩く）。

使い方:
  python3 nitter_webview.py user <username> [-n 件数]
  python3 nitter_webview.py search "<query>" [-n 件数]

出力: JSON 配列（{user, fullname, date, text, url, stats}）を stdout へ。
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# 試行するインスタンス（先頭優先）。死んでたら次へフォールバック。
INSTANCES = [
    "https://xcancel.com",
    "https://nitter.net",
    "https://nitter.privacyredirect.com",
    "https://nitter.poast.org",
]

# DOMからツイートを抜くJS。Nitterの .timeline-item を走査して構造化。
EXTRACT_JS = r"""
(function(){
  var out = [];
  var items = document.querySelectorAll('.timeline-item');
  for (var i=0; i<items.length; i++){
    var it = items[i];
    if (it.classList.contains('show-more')) continue;
    var c = it.querySelector('.tweet-content');
    if (!c) continue;
    var dateEl = it.querySelector('.tweet-date a');
    var userEl = it.querySelector('.username');
    var nameEl = it.querySelector('.fullname');
    var link = dateEl ? dateEl.getAttribute('href') : '';
    var stats = {};
    it.querySelectorAll('.tweet-stat').forEach(function(s){
      var ic = s.querySelector('.icon-container');
      if (!ic) return;
      var span = ic.querySelector('span');
      var cls = span ? span.className : '';
      var key = cls.replace('icon-','').replace(/ .*/,'') || 'stat';
      stats[key] = ic.textContent.trim();
    });
    out.push({
      user: userEl ? userEl.textContent.trim() : '',
      fullname: nameEl ? nameEl.textContent.trim() : '',
      date: dateEl ? (dateEl.getAttribute('title') || dateEl.textContent.trim()) : '',
      text: c.innerText.trim(),
      url: link,
      stats: stats
    });
  }
  var err = document.querySelector('.error-panel');
  return JSON.stringify({tweets: out, error: err ? err.innerText.trim() : null,
                         title: document.title});
})()
"""


def ensure_bridge(port=9222):
    """CDPが応答するか確認、なければbridge.shを起動する。"""
    url = "http://127.0.0.1:%d/json/version" % port
    try:
        urllib.request.urlopen(url, timeout=3).read()
        return True
    except Exception:
        pass
    subprocess.run([os.path.join(HERE, "bridge.sh")], cwd=HERE,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(6):
        try:
            urllib.request.urlopen(url, timeout=3).read()
            return True
        except Exception:
            time.sleep(0.5)
    return False


def fetch(path, n):
    """各インスタンスを順に試し、最初に取れたものを返す。"""
    from cdp import CDP
    last_err = None
    for base in INSTANCES:
        full = base + path
        try:
            c = CDP()
        except Exception as e:
            last_err = "CDP接続不可: %s" % e
            continue
        try:
            c.goto(full)
            # 動的描画待ち: timeline-item か error-panel が出るまで
            for _ in range(20):
                ready = c.eval(
                    "!!document.querySelector('.timeline-item, .error-panel')")
                if ready:
                    break
                time.sleep(0.4)
            raw = c.eval(EXTRACT_JS)
            data = json.loads(raw) if raw else {"tweets": [], "error": "empty"}
        except Exception as e:
            last_err = "%s: %s" % (base, e)
            c.close()
            continue
        finally:
            try:
                c.close()
            except Exception:
                pass
        tweets = data.get("tweets", [])
        if tweets:
            return {"instance": base, "tweets": tweets[:n],
                    "title": data.get("title")}
        # error-panel が出てる/0件 → 次のインスタンスへ
        last_err = "%s: %s" % (base, data.get("error") or "0 tweets")
    return {"instance": None, "tweets": [], "error": last_err}


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    mode = argv[0]
    n = 10
    rest = argv[1:]
    if "-n" in rest:
        i = rest.index("-n")
        try:
            n = int(rest[i + 1])
        except (IndexError, ValueError):
            pass
        rest = rest[:i] + rest[i + 2:]
    arg = " ".join(rest).strip()

    if not ensure_bridge():
        print(json.dumps({"error": "bridge張れず。手動で ./bridge.sh を確認"},
                         ensure_ascii=False))
        return 1

    if mode == "user":
        user = arg.lstrip("@")
        path = "/" + user
    elif mode == "search":
        import urllib.parse
        path = "/search?f=tweets&q=" + urllib.parse.quote(arg)
    else:
        print(json.dumps({"error": "mode は user|search"}, ensure_ascii=False))
        return 1

    result = fetch(path, n)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result.get("tweets") else 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
