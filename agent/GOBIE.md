# gobie — 実ブラウザ操作コマンド（LLMエージェント用説明書）

あなた（LLM）はシェルコマンド `gobie` を通じて、Android実機上の本物のブラウザ
（WebView）を操作できる。curl と違い **TLS/JS/フィンガープリントが全部本物**
（`navigator.webdriver` も false）なので、bot検知・403で弾かれるサイトも普通に読める。
ブラウザはバックグラウンドで動き、ユーザーの画面操作とは競合しない。

## 基本ルール

- どのコマンドも**ブラウザ未起動なら自動起動**する。準備コマンドは不要
  （初回だけ起動に20秒ほどかかる）。
- 状態はブラウザ側に残る。コマンドを何度呼んでも同じページの続きから操作できる。
- 出力はすべて標準出力のプレーンテキスト。エラーは終了コード非0。

## 典型的な調査フロー

```bash
gobie search 手賀沼 大津川 生態     # Web検索 → 番号付き結果一覧が返る
gobie open 3                        # 結果の3番を開く
gobie text                          # 開いたページの本文テキストを読む
gobie back                          # 検索結果に戻る
gobie next                          # 検索結果の次ページ → 一覧が更新される
gobie open https://example.com      # URL直接指定でもよい
```

## コマンド一覧

### 検索・ナビゲーション

| コマンド | 説明 |
|---|---|
| `gobie search <キーワード...>` | Web検索して結果を `[番号] タイトル / URL` 形式で一覧表示 |
| `gobie list [正規表現]` | 現在ページのリンク一覧（番号付き）。引数でタイトル/URLを絞り込み |
| `gobie open <n>` | 直近の一覧の n 番のリンクへ遷移 |
| `gobie open <URL>` | URLへ遷移（`jump`/`goto` も同じ） |
| `gobie next` | 検索結果やページネーションの「次ページ」へ |
| `gobie back` / `gobie forward` | 履歴を戻る / 進む |
| `gobie scroll [down\|up\|top\|bottom\|<px>]` | スクロール。省略時 down。`y=現在/最大` を返す |

### ページ内容の取得

| コマンド | 説明 |
|---|---|
| `gobie text` | 本文の可視テキスト全文（まずこれを読む） |
| `gobie url` / `gobie title` | 現在のURL / ページタイトル |
| `gobie html` | DOM全体のouterHTML（textで足りない時だけ） |
| `gobie shot [path.png]` | スクリーンショット保存（レイアウト確認用） |

### ページ操作（フォーム・動的サイト）

| コマンド | 説明 |
|---|---|
| `gobie click <CSSセレクタ>` | 要素をクリック。`ok` / `not-found` |
| `gobie fill <CSSセレクタ> <値>` | 入力欄に値を入れる（input/changeイベント発火） |
| `gobie wait <CSSセレクタ>` | 要素が出るまで待つ（最大15秒）。`ok` / `timeout` |
| `gobie eval <JS式>` | 任意のJavaScriptを実行し結果をJSONで返す |

### 管理（普段は不要）

| コマンド | 説明 |
|---|---|
| `gobie status` | 起動状態と現在ページ |
| `gobie up` | 明示的に起動（他コマンドが自動でやる） |
| `gobie stop` | ブラウザ停止（使い終わったら呼ぶとメモリと安全性に良い） |

## コツ・注意

- **`search`→`open N`→`text` が基本形**。textが長すぎる時は `head` やgrepで絞ってよい。
- `list` は `search` の直後でなくても、どのページでも使える（例: ブログのトップで
  `gobie list 記事タイトルの一部` → `gobie open 2`）。
- 番号指定（`open 3`）は**直近の `search`/`list` の出力**に対して有効。
  ページを移動したら一覧は古くなるので、再度 `list` してから番号を使うこと。
- 動的サイトでは遷移直後に内容が空のことがある。`gobie wait <セレクタ>` で
  描画を待ってから `text` を読む。
- ログインが必要なサイトは `fill`/`click` でフォーム操作できる。CAPTCHAが出たら
  `shot` で確認し、人間に依頼する。
- 失敗したら同じコマンドをもう一度試す（起動直後のタイミング起因が多い）。

## 仕組み（参考）

```
gobie CLI ─ CDP(WebSocket) ─ adb forward tcp:9222 ─ Android WebView(常駐Service)
```

実装: `~/sniffer-browser/agent/gobie.py`（コア動詞は `cdp.py`、起動は `bridge.sh`）。
検索エンジンは環境変数 `GOBIE_SEARCH`（既定: Google）で差し替え可能。
