#!/data/data/com.termux/files/usr/bin/bash
# bridge.sh — sniffer-browser の devtools abstract socket を TCP:9222 に橋渡しする。
# adbd(root) が abstract socket に到達できるので adb forward を使う。
# 既定で HeadlessBrowserService（画面外常駐・背面でも殺されない）を起動して張る。
#   ./bridge.sh            # headless常駐モードで起動・張り直し
#   ./bridge.sh 9222       # ポート指定
set -uo pipefail

PKG="net.localhost.sniffer"
PORT="${1:-9222}"
SU="/data/data/com.termux/files/home/.sbin/su"
ADB="adb -P 5038 -s 127.0.0.1:5555"

pid() { $SU -c "pidof $PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

# overlay権限（初回のみ必要、毎回入れても無害）
$SU -c "appops set $PKG SYSTEM_ALERT_WINDOW allow" >/dev/null 2>&1

PID="$(pid)"
if [ -z "$PID" ]; then
  echo "未起動 → HeadlessBrowserService を起動する"
  $SU -c "am start-foreground-service -n $PKG/.HeadlessBrowserService --es url about:blank" >/dev/null 2>&1
  for i in $(seq 1 10); do sleep 0.5; PID="$(pid)"; [ -n "$PID" ] && break; done
fi
[ -z "$PID" ] && { echo "起動失敗"; exit 1; }

SOCK="webview_devtools_remote_$PID"
# devtoolsソケットが実際に露出するまで待つ
for i in $(seq 1 10); do
  $SU -c "cat /proc/net/unix" 2>/dev/null | grep -q "@$SOCK" && break
  sleep 0.5
done

$ADB forward --remove tcp:$PORT >/dev/null 2>&1
$ADB forward tcp:$PORT localabstract:$SOCK >/dev/null 2>&1 || { echo "forward失敗"; exit 1; }

VER="$(curl -s -m 3 http://127.0.0.1:$PORT/json/version)"
if echo "$VER" | grep -q "$PKG"; then
  echo "bridge OK  pid=$PID  port=$PORT"
  echo "$VER" | python3 -c "import sys,json;d=json.load(sys.stdin);print(' ',d['Browser'])" 2>/dev/null
else
  echo "bridge張ったがCDP応答なし。再確認: curl http://127.0.0.1:$PORT/json/version"
  exit 1
fi
