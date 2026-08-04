#!/usr/bin/env python3
"""app-store-releases/manifest.json の pwa 型エントリを PwaImportActivity 用のJSONに変換する。

使い方:
  python3 tools/gen_pwas_json.py > /tmp/pwas.json
  dev <name> push /tmp/pwas.json /sdcard/Download/pwas.json
  dev <name> shell am start -n net.localhost.sniffer/.PwaImportActivity \
      --es file /sdcard/Download/pwas.json --ez pin true

pin=true にするとランチャーの「ホーム画面に追加」確認ダイアログが件数分出る。
adbから自動で潰すなら uiautomator dump で「自動的に追加」ボタンの bounds を取って input tap する。
"""
import json, os, sys

MANIFEST = os.path.expanduser("~/app-store-releases/manifest.json")

def main():
    d = json.load(open(MANIFEST))
    apps = d["apps"] if isinstance(d, dict) else d
    out = [{"url": a["url"], "name": a["name"], "icon": a.get("icon", "")}
           for a in apps if a.get("type") == "pwa" and a.get("url")]
    json.dump(out, sys.stdout, ensure_ascii=False, indent=1)
    print()

if __name__ == "__main__":
    main()
