# agent — LLMエージェント直結 CDP ドライバ

sniffer-browser の**実WebView**を、このTermuxターミナルから CDP (Chrome DevTools Protocol)
で外部ドライブする。実ブラウザを叩くので TLS/JS challenge/Canvas fingerprint が全部本物になり、
curl/wget が 403 で弾かれる bot 検知を構造的に回避できる。

## なぜ強いか
- **`navigator.webdriver` が false** — ChromeDriver/WebDriver と違いデバッグ有効化ではこのフラグが立たない。最大の自動化検知ベクトルを最初から回避。
- 実 Pixel 3a の WebView そのもの。実GPU・実UA・touch対応・ja-JP・実コア数。ヘッドレスChromeより区別困難。
- インタラクティブCAPTCHAは `click` で**逆に解ける**。

## 構成
```
LLM ─ cdp.py(高レベル動詞) ─ CDP/WS ─ adb forward ─ @webview_devtools_remote_<pid> ─ WebView
```

### ヘッドレス常駐モード（既定・推奨）
`HeadlessBrowserService` が WebView を**フォアグラウンドServiceのWindowManagerオーバーレイ（画面外）**に持つ。
- **背面でも殺されない**: Activity版WebViewは3a(4GB)で背面化＝LMK即kill。Serviceなら生存。
- **画面を奪わない**: x=-width の画面外配置。別作業しながら同じ端末でCLI駆動できる。
- **VISIBLE維持でレンダラ稼働**: navigate/eval/text/click/fill/**screenshotすべて前面化なしで動く**。
- 初回のみ overlay 権限が要る → `appops set net.localhost.sniffer SYSTEM_ALERT_WINDOW allow`（bridge.sh が自動でやる）。

`MainActivity`（手動ブラウズ用GUI）も残っているが、CLIエージェント用途は Service が本命。

## 使い方

**推奨: `gobie` コマンド**（このディレクトリの gobie.py、`~/.local/bin/gobie` でPATH導入済み）。
search/list/open/next/scroll のサブコマンド体系＋自動起動。LLM向け説明書は [GOBIE.md](GOBIE.md)。

```bash
gobie search 検索語        # 起動からブリッジまで全自動
gobie open 3 && gobie text
```

低レベルに直接叩く場合:
```bash
./bridge.sh            # アプリ起動＋ adb forward tcp:9222 を自動セットアップ（毎回最初に1回）
python3 cdp.py goto https://example.com
python3 cdp.py text                 # 本文テキスト全文
python3 cdp.py html                 # 現在のDOM
python3 cdp.py eval "document.title"
python3 cdp.py wait "div.result"    # セレクタ出現待ち
python3 cdp.py click "button.login"
python3 cdp.py fill "input#q" "検索語"
python3 cdp.py shot out.png         # スクショ（撮影時のみ自動で前面化）
python3 cdp.py url
```

## 制約・注意
- ヘッドレス常駐モードでは **screenshot 含む全動詞が前面化なしで動く**（画面外オーバーレイがVISIBLEでレンダラが動き続けるため）。
- PID が変わる（プロセス再生成）と forward が外れる → `./bridge.sh` を再実行すれば張り直す。
- 停止: `am start-foreground-service -n net.localhost.sniffer/.HeadlessBrowserService -a net.localhost.sniffer.HEADLESS_STOP` または force-stop。
- **セキュリティ**: tcp:9222 は 127.0.0.1 バインドだが、localhost 上の他プロセスからこのブラウザ（＝ログインセッション）を操作できる穴になる。常用しないなら使い終わりに `adb -P 5038 -s 127.0.0.1:5555 forward --remove tcp:9222` で閉じる。
- requires: `websocket-client`（導入済）, root (`~/.sbin/su`), adb (`-P 5038`).
