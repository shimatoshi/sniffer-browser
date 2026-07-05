package net.localhost.sniffer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.net.http.SslError;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String HOME = HomePage.URL_HOME;
    static final String ACTION_FOCUS_URL = "net.localhost.sniffer.FOCUS_URL";
    static final String ACTION_SEARCH = "net.localhost.sniffer.SEARCH";
    static final String EXTRA_QUERY = "query";

    private PullRefreshContainer webContainer;
    private EditText urlBar;
    private Button btnDl, btnTabs;
    private ProgressBar progress;
    private BrowserDb db;

    private final List<Tab> tabs = new ArrayList<>();
    private int cur = -1;
    private String ua = "";
    private AdBlocker ad;
    private volatile boolean mediaPlaying = false;
    private volatile int videoW, videoH;
    private boolean inPip = false;
    private boolean started = false;

    /** 1タブ = 1WebView。sniff用のページ状態もタブごとに保持 */
    private class Tab {
        WebView web;
        SnifferChrome chrome;
        volatile String pageUrl = "";
        volatile String pageTitle = "";
        // 復元直後のバックグラウンドタブの未読込URL。スイッチ時に初めてloadUrlする（起動高速化）。
        volatile String pendingUrl = null;
        // WiFiログイン(キャプティブポータル)呼び出し中。リダイレクトブロックを一時的に素通しさせる。
        volatile boolean captiveProbe = false;
        // 外部アプリのリンクから開いたタブ。BACKで履歴が尽きたら呼び出し元アプリへ戻る。
        volatile boolean external = false;
        // PC版サイト表示（デスクトップUA）中か。タブ単位で切替。
        volatile boolean desktop = false;
        // ページズーム%（100=等倍）。PC版はviewport幅上書き、モバイル版はtextZoomで適用。
        volatile int zoom = 100;
    }

    private Tab curTab() { return (cur >= 0 && cur < tabs.size()) ? tabs.get(cur) : null; }
    private WebView curWeb() { Tab t = curTab(); return t != null ? t.web : null; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        net.localhost.debugnote.DebugNote.attach(this, "sniffer-browser");

        webContainer = findViewById(R.id.webContainer);
        webContainer.setOnRefreshListener(() -> {
            WebView w = curWeb();
            if (w != null) w.reload();
        });
        urlBar = findViewById(R.id.url);
        btnDl = findViewById(R.id.btnDl);
        btnTabs = findViewById(R.id.btnTabs);
        progress = findViewById(R.id.progress);
        db = new BrowserDb(this);
        ad = AdBlocker.get(this);

        requestStorage();

        Hits.setListener(n -> runOnUiThread(this::updateDlBtn));
        btnDl.setOnClickListener(v -> showHits(false));
        btnDl.setOnLongClickListener(v -> { showHits(true); return true; });
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            WebView w = curWeb();
            if (w != null && w.canGoBack()) w.goBack();
        });
        findViewById(R.id.btnFwd).setOnClickListener(v -> {
            WebView w = curWeb();
            if (w != null && w.canGoForward()) w.goForward();
        });
        findViewById(R.id.btnHome).setOnClickListener(v -> {
            Tab t = curTab();
            if (t != null) goHome(t, false);
        });
        btnTabs.setOnClickListener(v -> showTabs());
        findViewById(R.id.btnMenu).setOnClickListener(this::showMenu);

        urlBar.setSelectAllOnFocus(true);
        urlBar.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                    (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                go(urlBar.getText().toString());
                return true;
            }
            return false;
        });

        // 前回セッションのタブを復元
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        String saved = sp.getString("session", null);
        if (saved != null) {
            try {
                JSONArray arr = new JSONArray(saved);
                for (int i = 0; i < arr.length(); i++) createTab(arr.getString(i), false);
            } catch (Throwable ignore) {}
        }

        Intent it = getIntent();
        if (it != null && it.getData() != null) {
            createTab(it.getData().toString(), true).external = true;
        } else if (tabs.isEmpty()) {
            createTab(HOME, true);
        } else {
            switchTab(Math.max(0, Math.min(sp.getInt("cur", 0), tabs.size() - 1)));
        }
        if (it != null && ACTION_FOCUS_URL.equals(it.getAction())) focusUrlBar();
        else if (it != null && ACTION_SEARCH.equals(it.getAction())) {
            String q = it.getStringExtra(EXTRA_QUERY);
            if (q != null && !q.trim().isEmpty()) go(q.trim());
        }

        // PWAキャッシュ自動リウォーム（24hに1回、起動の邪魔をしないよう遅延）
        urlBar.postDelayed(() -> PwaWarmer.maybeWarm(getApplicationContext()), 8000);
    }

    /** ウィジェット経由: アドレス欄にフォーカスして全選択+IME表示 */
    private void focusUrlBar() {
        urlBar.post(() -> {
            urlBar.requestFocus();
            urlBar.selectAll();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(urlBar, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        if (intent.getData() != null)
            createTab(intent.getData().toString(), true).external = true;
        else if (ACTION_FOCUS_URL.equals(intent.getAction()))
            focusUrlBar();
        else if (ACTION_SEARCH.equals(intent.getAction())) {
            String q = intent.getStringExtra(EXTRA_QUERY);
            if (q != null && !q.trim().isEmpty()) go(q.trim());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // セッション保存（タブのURL一覧＋選択中タブ）
        JSONArray arr = new JSONArray();
        for (Tab t : tabs) {
            String u = t.web.getUrl();
            if (u == null) u = t.pendingUrl; // まだ開いてない復元タブはpendingUrlを引き継ぐ
            if (u != null && !u.equals("about:blank")) arr.put(u);
        }
        getPreferences(MODE_PRIVATE).edit()
                .putString("session", arr.toString())
                .putInt("cur", cur)
                .apply();
    }

    @Override
    protected void onStart() {
        started = true;
        super.onStart();
        syncPlaybackService();
    }

    @Override
    protected void onStop() {
        started = false;
        super.onStop();
        syncPlaybackService();
    }

    @Override
    protected void onDestroy() {
        Media.stopPlaybackService(this);
        for (Tab t : tabs) t.web.destroy();
        tabs.clear();
        super.onDestroy();
    }

    // ---- PiP / バックグラウンド再生 ----

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        Tab t = curTab();
        boolean fs = t != null && t.chrome.isInFullscreen();
        Dbg.log(this, "PiP userLeaveHint: fullscreen=" + fs + " playing=" + mediaPlaying + " v=" + videoW + "x" + videoH);
        if (fs)
            Media.enterPip(this, videoW, videoH);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, android.content.res.Configuration cfg) {
        super.onPictureInPictureModeChanged(isInPip, cfg);
        inPip = isInPip;
        Dbg.log(this, "PiP changed: inPip=" + isInPip);
        // PiP小窓にはWebView(全画面動画)だけを出す。URLバー等が残るとミニチュアの
        // ブラウザ画面になってしまうため、PiP中はブラウザUIを畳む
        int vis = isInPip ? View.GONE : View.VISIBLE;
        findViewById(R.id.topBar).setVisibility(vis);
        findViewById(R.id.bottomBar).setVisibility(vis);
        if (isInPip) progress.setVisibility(View.GONE);
        // WebViewはPiP突入直後にHTML5全画面を強制解除するため、CSSで動画を全面固定する
        Tab t = curTab();
        if (t != null) Media.setPipLayout(t.web, isInPip);
        syncPlaybackService();
    }

    /** 「裏にいて・PiPでなく・再生中」のときだけ前面サービスを立てる */
    private void syncPlaybackService() {
        if (mediaPlaying && !started && !inPip) {
            Tab t = curTab();
            String title = (t != null && t.pageTitle != null && !t.pageTitle.isEmpty())
                    ? t.pageTitle : "Sniffer";
            Media.startPlaybackService(this, title);
        } else {
            Media.stopPlaybackService(this);
        }
    }

    // ---- タブ管理 ----

    private Tab createTab(String url, boolean switchTo) {
        Tab t = new Tab();
        setupWeb(t);
        webContainer.addView(t.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.web.setVisibility(View.GONE);
        tabs.add(t);
        if (url != null) {
            if (url.startsWith("gobie://")) goHome(t, false); // セッション復元含む
            else if (switchTo) t.web.loadUrl(url);
            else t.pendingUrl = url; // バックグラウンド復元タブはスイッチ時まで読込を遅延
        }
        if (switchTo) switchTab(tabs.size() - 1);
        else updateTabBtn();
        return t;
    }

    private void switchTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        Tab prev = curTab();
        if (prev != null) prev.web.setVisibility(View.GONE);
        cur = i;
        Tab t = tabs.get(i);
        t.web.setVisibility(View.VISIBLE);
        if (t.pendingUrl != null) {           // 遅延復元タブを初回表示時にロード
            String p = t.pendingUrl;
            t.pendingUrl = null;
            t.web.loadUrl(p);
        }
        String u = t.web.getUrl();
        if (u == null) u = t.pendingUrl;
        urlBar.setText(u != null ? u : "");
        progress.setVisibility(View.GONE);
        updateTabBtn();
        updateDlBtn();
    }

    /** 📥カウントを現在タブの表示中サイトで検出した件数に更新（DL欄のサイト追従） */
    private void updateDlBtn() {
        Tab t = curTab();
        btnDl.setText("📥" + Hits.countFor(t != null ? t.pageUrl : null));
    }

    private void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        Tab t = tabs.remove(i);
        webContainer.removeView(t.web);
        t.web.destroy();
        if (tabs.isEmpty()) { cur = -1; createTab(HOME, true); return; }
        int next = (i == cur) ? Math.min(i, tabs.size() - 1)
                              : (i < cur ? cur - 1 : cur);
        cur = -1; // switchTabに前タブを隠させない（破棄済み）
        switchTab(next);
    }

    private void updateTabBtn() {
        btnTabs.setText("❐" + tabs.size());
    }

    private void showTabs() {
        ListView lv = new ListView(this);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("タブ")
                .setView(lv)
                .setPositiveButton("＋ 新規タブ", (d, w) -> createTab(HOME, true))
                .setNegativeButton("閉じる", null)
                .create();
        BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return tabs.size(); }
            @Override public Object getItem(int p) { return tabs.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View cv, ViewGroup parent) {
                Tab t = tabs.get(p);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(24, 16, 8, 16);

                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView title = new TextView(MainActivity.this);
                String tt = t.web.getTitle();
                if ((tt == null || tt.isEmpty()) && t.pendingUrl != null) tt = t.pendingUrl;
                title.setText((p == cur ? "▶ " : "") + (tt == null || tt.isEmpty() ? "(無題)" : tt));
                title.setSingleLine(true);
                title.setTextSize(15);
                TextView url = new TextView(MainActivity.this);
                String uu = t.web.getUrl();
                if (uu == null) uu = t.pendingUrl;
                url.setText(uu == null ? "" : uu);
                url.setSingleLine(true);
                url.setTextSize(11);
                col.addView(title);
                col.addView(url);
                row.addView(col, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView close = new TextView(MainActivity.this);
                close.setText("✕");
                close.setTextSize(18);
                close.setPadding(32, 8, 32, 8);
                close.setOnClickListener(v -> {
                    closeTab(p);
                    dlg.setTitle("タブ");
                    notifyDataSetChanged();
                });
                row.addView(close);
                return row;
            }
        };
        lv.setAdapter(ad);
        lv.setOnItemClickListener((parent, v, p, id) -> {
            switchTab(p);
            dlg.dismiss();
        });
        dlg.show();
    }

    // ---- メニュー ----

    private void showMenu(View anchor) {
        Tab t = curTab();
        if (t == null) return;
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 2, 0, "⟳ 再読み込み");
        m.add(0, 12, 0, t.desktop ? "📱 モバイル版に戻す" : "🖥 PC版サイト");
        m.add(0, 17, 0, "🔍 ズーム (" + t.zoom + "%)");
        // YouTube動画ページなら動画DL項目を出す
        final String ytId = SnifferChrome.youtubeVideoId(t.web.getUrl());
        if (ytId != null) {
            m.add(0, 15, 0, "⬇ この動画をDL (ShimaTube)");
            m.add(0, 16, 0, "♪ 音声のみDL (ShimaTube)");
        }
        boolean bm = db.isBookmarked(t.web.getUrl());
        m.add(0, 3, 0, bm ? "★ ブックマーク解除" : "☆ ブックマークに追加");
        m.add(0, 4, 0, "📑 ブックマーク");
        m.add(0, 5, 0, "🕘 履歴");
        m.add(0, 13, 0, "📱 アプリ（PWA）");
        m.add(0, 14, 0, "📌 固定サイト");
        m.add(0, 11, 0, "📶 WiFiログイン");
        m.add(0, 9, 0, "📌 固定サイトに追加");
        m.add(0, 6, 0, "📱 PWAとしてホームに追加");
        m.add(0, 10, 0, "📦 オフライン");
        m.add(0, 8, 0, "📜 ユーザースクリプト");
        m.add(0, 7, 0, "⚙ 設定");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 2: t.web.reload(); return true;
                case 3:
                    String u = t.web.getUrl();
                    if (u == null) return true;
                    if (bm) {
                        db.removeBookmark(u);
                        Toast.makeText(this, "ブックマーク解除", Toast.LENGTH_SHORT).show();
                    } else {
                        db.addBookmark(u, t.web.getTitle());
                        Toast.makeText(this, "★ 追加した", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case 4: showBookmarks(); return true;
                case 5: showHistory(); return true;
                case 6: showPwaDialog(t); return true;
                case 7: showSettings(); return true;
                case 8: showScripts(); return true;
                case 9: showPinDialog(t); return true;
                case 10: showOffline(); return true;
                case 11: openCaptivePortal(t); return true;
                case 12:
                    t.desktop = !t.desktop;
                    SnifferChrome.applyUaMode(t.web.getSettings(), t.desktop);
                    t.web.reload(); // ズームはロードフックのapplyPageZoomがページ種別で付け替える
                    Toast.makeText(this, t.desktop ? "🖥 PC版で表示" : "📱 モバイル版で表示",
                            Toast.LENGTH_SHORT).show();
                    return true;
                case 13: showPwaList(); return true;
                case 14: showPins(); return true;
                case 15:
                    if (ytId != null)
                        SnifferChrome.downloadYoutube(this, ytId, t.web.getTitle(), false);
                    return true;
                case 16:
                    if (ytId != null)
                        SnifferChrome.downloadYoutube(this, ytId, t.web.getTitle(), true);
                    return true;
                case 17: showZoomDialog(t); return true;
            }
            return false;
        });
        pm.show();
    }

    /** WiFiログイン画面（キャプティブポータル）を強制的に呼び出す。
     *  ポータルは平文HTTPリクエストにしか割り込めない。HTTPSや古いキャッシュを開くと
     *  割り込めず「Wi-Fi接続が利用しづらい状態です/busy」等の古い画面が出やすい。
     *  既知のHTTP probeをキャッシュ無しで叩き、最新のログインページへ302で飛ばさせる。 */
    private void openCaptivePortal(Tab t) {
        if (t == null) return;
        t.web.clearCache(true);      // busy画面など古いポータル応答を捨てて取り直す
        t.captiveProbe = true;        // 続くポータルへの無操作リダイレクトを素通しさせる
        // 平文HTTPの軽量probe。ポータル配下では302でログイン画面へリダイレクトされる。
        String probe = "http://captive.apple.com/hotspot-detect.html";
        t.web.loadUrl(probe);
        if (t == curTab()) urlBar.setText(probe);
        Toast.makeText(this, "WiFiログイン画面を呼び出し中…", Toast.LENGTH_SHORT).show();
    }

    // ---- ホーム（gobie://home、ロゴ＋検索バー＋背景画像） ----

    private void goHome(Tab t, boolean force) {
        t.web.loadDataWithBaseURL(HomePage.URL_HOME,
                HomePage.render(HomePage.bgDataUrl(this), ENGINES[searchEngineIndex()][0]),
                "text/html", "utf-8", HomePage.URL_HOME);
        if (t == curTab()) urlBar.setText(HomePage.URL_HOME);
    }

    /** ホームを開いたままのタブを再描画（背景画像/検索エンジン変更後）。 */
    private void rerenderHome() {
        if (isFinishing() || isDestroyed()) return;
        for (Tab x : tabs)
            if (HomePage.URL_HOME.equals(x.web.getUrl())) goHome(x, false);
    }

    /** ホーム内リンク gobie://search?q=... （旧ホームのpwa-open等も互換で残す） */
    private void handleGobie(Tab t, android.net.Uri u) {
        String host = u.getHost();
        if ("search".equals(host)) {
            String q = u.getQueryParameter("q");
            if (q != null && !q.trim().isEmpty()) go(q.trim());
        } else if ("pwa-open".equals(host)) {
            openPwa(pwaParam(u));
        } else if ("pwa-menu".equals(host)) {
            showPwaItemMenu(pwaParam(u));
        } else {
            goHome(t, false);
        }
    }

    private BrowserDb.Pwa pwaParam(android.net.Uri u) {
        try { return db.getPwa(Long.parseLong(u.getQueryParameter("id"))); }
        catch (Throwable e) { return null; }
    }

    /** ホームのPWAタイルをタップ → standalone窓（PwaActivity）で起動。 */
    private void openPwa(BrowserDb.Pwa p) {
        if (p == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(p.url),
                    this, PwaActivity.class);
            i.putExtra("pwa_title", p.label());
            if (p.theme != null) i.putExtra("pwa_theme", p.theme);
            if (p.desktop) i.putExtra("pwa_desktop", true);
            if (p.zoom != 100) i.putExtra("pwa_zoom", p.zoom);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(this, "起動できません: " + e, Toast.LENGTH_SHORT).show();
        }
    }

    /** PWA一覧（⋮メニューから）。タップで起動、長押しで個別メニュー */
    private void showPwaList() {
        // ランチャー側で作られたピン留めPWAを台帳へ取り込んでから一覧を出す
        new Thread(() -> {
            PwaInstaller.syncFromShortcuts(getApplicationContext());
            final List<BrowserDb.Pwa> pwas = db.listPwas();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (pwas.isEmpty()) {
                    Toast.makeText(this, "PWAなし（⋮→PWAとしてホームに追加）",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] items = new String[pwas.size()];
                for (int i = 0; i < pwas.size(); i++) items[i] = pwas.get(i).label();
                AlertDialog dlg = new AlertDialog.Builder(this)
                        .setTitle("📱 アプリ（長押しでメニュー）")
                        .setItems(items, (d, w) -> openPwa(pwas.get(w)))
                        .setNegativeButton("閉じる", null)
                        .create();
                dlg.show();
                dlg.getListView().setOnItemLongClickListener((av, v, pos, id) -> {
                    dlg.dismiss();
                    showPwaItemMenu(pwas.get(pos));
                    return true;
                });
            });
        }).start();
    }

    /** 固定サイト一覧（⋮メニューから）。タップで開く、長押しで解除 */
    private void showPins() {
        final List<BrowserDb.Entry> pins = db.listPins();
        if (pins.isEmpty()) {
            Toast.makeText(this, "固定サイトなし（⋮→固定サイトに追加）", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("📌 固定サイト（長押しで解除）")
                .setAdapter(entryAdapter(pins), (d, w) -> openInCurrent(pins.get(w).url))
                .setNegativeButton("閉じる", null)
                .create();
        dlg.getListView().setOnItemLongClickListener((av, v, pos, id) -> {
            BrowserDb.Entry e = pins.get(pos);
            new AlertDialog.Builder(this)
                    .setMessage("固定を解除する？\n" + e.label())
                    .setPositiveButton("解除", (d2, w2) -> {
                        db.removePin(e.id);
                        dlg.dismiss();
                        showPins();
                    })
                    .setNegativeButton("やめる", null)
                    .show();
            return true;
        });
        dlg.show();
    }

    /** PWA個別メニュー: 開く / アップデート / 削除。 */
    private void showPwaItemMenu(BrowserDb.Pwa p) {
        if (p == null) return;
        final String[] items = {"開く", "🔄 アップデート（キャッシュ全消し）", "🗑 削除"};
        new AlertDialog.Builder(this)
                .setTitle(p.label())
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            openPwa(p);
                            break;
                        case 1:
                            new AlertDialog.Builder(this)
                                    .setTitle("アップデート")
                                    .setMessage(p.label() + " のキャッシュを全消しして"
                                            + "最新バージョンを取り直す？")
                                    .setPositiveButton("実行", (dd, ww) ->
                                            PwaUpdater.update(this, p.url, p.label()))
                                    .setNegativeButton("やめる", null)
                                    .show();
                            break;
                        case 2:
                            db.removePwa(p.id);
                            Toast.makeText(this, "削除（ランチャーのアイコンは手動で）",
                                    Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    /** WebView自身がロードできるスキームか。これ以外は外部アプリへ委譲する。 */
    private static boolean isWebScheme(String s) {
        return "http".equals(s) || "https".equals(s) || "file".equals(s)
                || "data".equals(s) || "blob".equals(s) || "about".equals(s)
                || "javascript".equals(s) || "ws".equals(s) || "wss".equals(s);
    }

    /**
     * 独自スキームのURLを対応アプリ（ACTION_VIEW）へ渡す。
     * 任天堂アカウント連携の npf<id>:// コールバックをポケポケ本体へ戻すのが主用途。
     * intent:// は parseUri で復元し、未解決時は browser_fallback_url へ。
     * 食えた場合は true（WebViewにロードさせない）。
     */
    private boolean openExternal(WebView view, String url, boolean userGesture) {
        try {
            Intent intent = url.startsWith("intent://")
                    ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            // intent://の悪用防止: 明示コンポーネント/セレクタを剥がしBROWSABLE扱いに限定
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            android.content.ComponentName target = intent.resolveActivity(getPackageManager());
            if (target != null) {
                // ユーザー操作なしのPlayストア誘導(広告のmarket://, intent://...package=)は黙殺。
                // npf://等のアプリ連携コールバックは操作有無に関わらず通す。
                String sc = android.net.Uri.parse(url).getScheme();
                boolean toStore = "market".equals(sc)
                        || "com.android.vending".equals(target.getPackageName());
                if (!userGesture && toStore) return true;
                startActivity(intent);
                return true;
            }
            if (url.startsWith("intent://")) {
                String fb = intent.getStringExtra("browser_fallback_url");
                if (fb != null) { view.loadUrl(fb); return true; }
            }
            // 未解決時: ユーザーが実際にタップした時だけ通知する。
            // ページ読み込み時に自動発火する deep link (instagram:// 等で本体未導入) は
            // 無操作リダイレクトなので黙殺し、トースト連発を防ぐ。
            if (userGesture) {
                String sc = android.net.Uri.parse(url).getScheme();
                Toast.makeText(this, "対応アプリが見つかりません: " + sc + "://",
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable e) {
            if (userGesture)
                Toast.makeText(this, "外部リンクを開けません", Toast.LENGTH_SHORT).show();
        }
        return true; // WebViewに渡してもscheme resolve errorになるだけなので食う
    }

    private void showPinDialog(Tab t) {
        String u0 = t.web.getUrl();
        if (u0 == null || u0.startsWith("gobie://")) {
            Toast.makeText(this, "固定したいサイトを開いてから", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 8, 24, 8);
        final EditText name = new EditText(this);
        name.setHint("表示名");
        name.setSingleLine(true);
        name.setText(t.web.getTitle());
        root.addView(name);
        final EditText url = new EditText(this);
        url.setHint("URL（トップページに削ると記事ウィジェットの新着取得が安定）");
        url.setSingleLine(true);
        url.setText(u0);
        root.addView(url);
        new AlertDialog.Builder(this)
                .setTitle("固定サイトに追加")
                .setView(root)
                .setPositiveButton("固定", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (u.isEmpty()) return;
                    db.addPin(u, name.getText().toString().trim());
                    Toast.makeText(this, "📌 固定した（⋮→固定サイト）", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    // ---- 設定（カテゴリ別） ----

    private void showSettings() {
        final String[] cats = {"🔍 検索エンジン", "🛡 アドブロック", "🏠 ホーム画面", "📦 オフライン"};
        new AlertDialog.Builder(this)
                .setTitle("⚙ 設定")
                .setItems(cats, (d, w) -> {
                    if (w == 0) showSearchEngineSettings();
                    else if (w == 1) showAdblockSettings();
                    else if (w == 2) showHomeSettings();
                    else showOfflineSettings();
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    // ---- 設定: ホーム画面（背景画像） ----

    private static final int REQ_BG = 73;

    private void showHomeSettings() {
        boolean hasBg = HomePage.bgFile(this).exists();
        final String[] items = hasBg
                ? new String[]{"🖼 背景画像を変更", "背景画像を削除"}
                : new String[]{"🖼 背景画像を選ぶ"};
        new AlertDialog.Builder(this)
                .setTitle("🏠 ホーム画面")
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType("image/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        try {
                            startActivityForResult(Intent.createChooser(i, "ホーム背景画像"), REQ_BG);
                        } catch (Throwable e) {
                            Toast.makeText(this, "画像ピッカーを開けない: " + e, Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        HomePage.bgFile(this).delete();
                        HomePage.invalidateBg();
                        rerenderHome();
                        Toast.makeText(this, "背景を削除した", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    /** 選択画像を画面サイズ程度に縮小してJPEG保存 → ホーム背景に反映 */
    private void importHomeBg(final android.net.Uri uri) {
        new Thread(() -> {
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    android.graphics.BitmapFactory.decodeStream(in, null, o);
                }
                int maxDim = 1440; // 端末画面より少し大きい程度に抑えてHTML埋め込みを軽くする
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2;
                android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                o2.inSampleSize = sample;
                android.graphics.Bitmap bm;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    bm = android.graphics.BitmapFactory.decodeStream(in, null, o2);
                }
                if (bm == null) throw new IllegalStateException("decode失敗");
                java.io.File out = HomePage.bgFile(this);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos);
                }
                bm.recycle();
                HomePage.invalidateBg();
                runOnUiThread(() -> {
                    rerenderHome();
                    Toast.makeText(this, "🖼 背景を設定した", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "背景の取り込み失敗: " + e, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ---- 設定: 検索エンジン（デフォルトはGoogle、切替でDuckDuckGo/Bing） ----

    private void showSearchEngineSettings() {
        final String[] names = new String[ENGINES.length];
        for (int i = 0; i < ENGINES.length; i++) names[i] = ENGINES[i][0];
        new AlertDialog.Builder(this)
                .setTitle("検索エンジン")
                .setSingleChoiceItems(names, searchEngineIndex(), (d, w) -> {
                    getSharedPreferences("settings", MODE_PRIVATE)
                            .edit().putInt("searchEngine", w).apply();
                    Toast.makeText(this, "検索: " + names[w], Toast.LENGTH_SHORT).show();
                    rerenderHome(); // ホームのロゴも切替える
                    d.dismiss();
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    // ---- 設定: アドブロック（ブロック系トグル＋フィルター管理） ----

    private void showAdblockSettings() {
        final String[] names = {
                "広告ブロック",
                "要素隠し（広告枠をCSSで非表示）",
                "ポップアップブロック（無操作のwindow.open遮断）",
                "リダイレクトブロック（無操作の別サイト遷移遮断）"};
        final String[] keys = {"adblock", "cosmetic", "popupBlock", "redirectBlock"};
        final boolean[] st = {ad.adblockOn(), ad.cosmeticOn(), ad.popupBlockOn(), ad.redirectBlockOn()};
        new AlertDialog.Builder(this)
                .setTitle("アドブロック（ドメイン" + ad.ruleCount()
                        + "件・要素隠し" + ad.cosmeticCount() + "件）")
                .setMultiChoiceItems(names, st, (d, w, checked) -> ad.set(keys[w], checked))
                .setPositiveButton("閉じる", null)
                .setNeutralButton("カスタムフィルター", (d, w) -> showFilters())
                .setNegativeButton("フィルター更新", (d, w) -> updateFilterList())
                .show();
    }

    // ---- 設定: オフライン（オートスクレイプ） ----

    private void showOfflineSettings() {
        final OfflineStore os = OfflineStore.get(this);
        long used = os.totalBytes(db);
        String title = String.format(java.util.Locale.US,
                "オフライン（%d ページ・%.1f / %d MB）",
                db.offlineCount(), used / 1048576.0, os.maxMb());
        final String[] names = {"オートスクレイプ（遷移したページを自動保存）"};
        final boolean[] st = {os.autoOn()};
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMultiChoiceItems(names, st, (d, w, checked) -> {
                    os.setAuto(checked);
                    Toast.makeText(this, checked
                            ? "📦 オートスクレイプON（⋮→オフラインに溜まっていく）"
                            : "オートスクレイプOFF", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("閉じる", null)
                .setNeutralButton("上限: " + os.maxMb() + "MB", (d, w) -> {
                    final int[] choices = {200, 500, 1024};
                    final String[] labels = {"200 MB", "500 MB", "1 GB"};
                    new AlertDialog.Builder(this)
                            .setTitle("保存上限（超過分は古い順に自動削除）")
                            .setItems(labels, (dd, ww) -> {
                                os.setMaxMb(choices[ww]);
                                showOfflineSettings();
                            })
                            .show();
                })
                .setNegativeButton("全削除", (d, w) -> new AlertDialog.Builder(this)
                        .setTitle("保存した全オフラインページを削除する？")
                        .setMessage("PWAやサイトのキャッシュには影響しない（.mhtファイルのみ削除）")
                        .setPositiveButton("削除", (dd, ww) -> {
                            os.deleteAll(db);
                            Toast.makeText(this, "全削除した", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("やめる", null)
                        .show())
                .show();
    }

    // ---- オフライン一覧（タップで復元表示・長押しで個別削除） ----

    private void showOffline() {
        final List<BrowserDb.OfflinePage> pages = db.listOffline();
        if (pages.isEmpty()) {
            Toast.makeText(this, OfflineStore.get(this).autoOn()
                    ? "まだ保存なし（ブラウジングすると溜まる）"
                    : "オートスクレイプがOFF（⚙設定→オフラインでON）", Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[pages.size()];
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.US);
        for (int i = 0; i < pages.size(); i++) {
            BrowserDb.OfflinePage p = pages.get(i);
            items[i] = p.label() + "\n" + fmt.format(new java.util.Date(p.ts))
                    + " ・ " + String.format(java.util.Locale.US, "%.1fMB", p.size / 1048576.0);
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("📦 オフライン " + pages.size() + " 件")
                .setItems(items, (d, which) -> {
                    java.io.File f = OfflineStore.get(this).fileOf(pages.get(which));
                    if (!f.exists()) {
                        Toast.makeText(this, "ファイルが消えてる（削除する）", Toast.LENGTH_SHORT).show();
                        db.deleteOffline(pages.get(which).id);
                        return;
                    }
                    openInCurrent("file://" + f.getAbsolutePath());
                })
                .setNegativeButton("閉じる", null)
                .create();
        dlg.show();
        dlg.getListView().setOnItemLongClickListener((av, v, pos, id) -> {
            OfflineStore.get(this).delete(db, pages.get(pos));
            Toast.makeText(this, "削除した", Toast.LENGTH_SHORT).show();
            dlg.dismiss();
            showOffline();
            return true;
        });
    }

    private void updateFilterList() {
        Toast.makeText(this, "フィルターリストを取得中...（5ソース・数MB）", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final String msg = ad.update();
                runOnUiThread(() -> Toast.makeText(this,
                        "フィルター更新OK: " + msg, Toast.LENGTH_LONG).show());
            } catch (Throwable e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "更新失敗: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showFilters() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 8, 24, 8);
        final EditText in = new EditText(this);
        in.setHint("URLに含まれる文字列（例: ads.example.com, /banner/）");
        in.setSingleLine(true);
        root.addView(in);
        final ListView lv = new ListView(this);
        root.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().density * 280)));

        final List<String> pats = new ArrayList<>(db.listFilterPatterns());
        final android.widget.ArrayAdapter<String> la = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, pats);
        lv.setAdapter(la);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("カスタムフィルター（長押しで削除）")
                .setView(root)
                .setPositiveButton("追加", null) // 閉じないようshow後に差し替え
                .setNegativeButton("閉じる", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String p = in.getText().toString().trim();
            if (p.isEmpty()) return;
            db.addFilter(p);
            ad.reload();
            in.setText("");
            pats.clear();
            pats.addAll(db.listFilterPatterns());
            la.notifyDataSetChanged();
        });
        lv.setOnItemLongClickListener((parent, v, p, id) -> {
            final String pat = pats.get(p);
            new AlertDialog.Builder(this)
                    .setMessage("削除する？\n" + pat)
                    .setPositiveButton("削除", (d2, w2) -> {
                        db.removeFilter(pat);
                        ad.reload();
                        pats.clear();
                        pats.addAll(db.listFilterPatterns());
                        la.notifyDataSetChanged();
                    })
                    .setNegativeButton("やめる", null)
                    .show();
            return true;
        });
    }

    /** ページズーム選択。PC版はviewport幅上書き（Chromeのページズーム相当）、モバイル版はtextZoom。 */
    private void showZoomDialog(Tab t) {
        final int[] levels = {75, 90, 100, 110, 125, 150, 175, 200};
        String[] labels = new String[levels.length];
        int checked = 2;
        for (int i = 0; i < levels.length; i++) {
            labels[i] = levels[i] + "%";
            if (levels[i] == t.zoom) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("🔍 ページズーム")
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    t.zoom = levels[w];
                    SnifferChrome.applyPageZoom(t.web, t.zoom);
                    // PC幅ページはviewport幅変更後の再フィットが要るのでリロードで確定させる
                    t.web.reload();
                    d.dismiss();
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void showPwaDialog(Tab t) {
        new AlertDialog.Builder(this)
                .setTitle("PWAとしてホームに追加")
                .setMessage((t.pageTitle == null || t.pageTitle.isEmpty() ? t.pageUrl : t.pageTitle)
                        + "\n\nこのサイトをstandaloneアプリ化する？")
                .setPositiveButton("追加", (d, w) -> PwaInstaller.install(this, t.web, t.pageUrl, t.pageTitle, t.desktop, t.zoom))
                .setNegativeButton("やめる", null)
                .show();
    }

    // ---- ユーザースクリプト ----

    private void showScripts() {
        final UserScripts us = UserScripts.get(this);
        final List<UserScripts.Script> items = new ArrayList<>(us.all());
        ListView lv = new ListView(this);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("ユーザースクリプト（タップで編集、長押しで削除）")
                .setView(lv)
                .setPositiveButton("＋ 新規", (d, w) -> editScript(null))
                .setNegativeButton("閉じる", null)
                .create();
        BaseAdapter ad2 = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int p) { return items.get(p); }
            @Override public long getItemId(int p) { return items.get(p).id; }
            @Override public View getView(int p, View cv, ViewGroup parent) {
                final UserScripts.Script s = items.get(p);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(8, 8, 8, 8);
                android.widget.CheckBox cb = new android.widget.CheckBox(MainActivity.this);
                cb.setChecked(s.enabled);
                cb.setOnCheckedChangeListener((b, checked) -> {
                    db.setScriptEnabled(s.id, checked);
                    s.enabled = checked;
                    us.reload();
                });
                row.addView(cb);
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView name = new TextView(MainActivity.this);
                name.setText(s.name == null || s.name.isEmpty() ? "(無名)" : s.name);
                name.setTextSize(15);
                name.setSingleLine(true);
                TextView match = new TextView(MainActivity.this);
                match.setText((s.matches == null || s.matches.isEmpty() ? "全ページ" : s.matches)
                        + ("start".equals(s.runat) ? "  [start]" : ""));
                match.setTextSize(11);
                match.setSingleLine(true);
                col.addView(name);
                col.addView(match);
                row.addView(col, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return row;
            }
        };
        lv.setAdapter(ad2);
        lv.setOnItemClickListener((parent, v, p, id) -> {
            dlg.dismiss();
            editScript(items.get(p));
        });
        lv.setOnItemLongClickListener((parent, v, p, id) -> {
            final UserScripts.Script s = items.get(p);
            new AlertDialog.Builder(this)
                    .setMessage("削除する？\n" + s.name)
                    .setPositiveButton("削除", (d2, w2) -> {
                        db.removeScript(s.id);
                        us.reload();
                        dlg.dismiss();
                        showScripts();
                    })
                    .setNegativeButton("やめる", null)
                    .show();
            return true;
        });
        dlg.show();
    }

    private void editScript(UserScripts.Script s0) {
        final UserScripts.Script s = s0 != null ? s0 : new UserScripts.Script();
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 8, 24, 8);
        sc.addView(root);

        final EditText name = new EditText(this);
        name.setHint("名前（空=コードの@nameから補完）");
        name.setSingleLine(true);
        name.setText(s.name);
        root.addView(name);

        final EditText match = new EditText(this);
        match.setHint("URLパターン（*可・空白区切り・空=@match補完→全ページ）");
        match.setSingleLine(true);
        match.setText(s.matches);
        root.addView(match);

        final EditText code = new EditText(this);
        code.setHint("// JavaScriptコード（GreasemonkeyヘッダもOK）");
        code.setTypeface(android.graphics.Typeface.MONOSPACE);
        code.setTextSize(12);
        code.setGravity(android.view.Gravity.TOP);
        code.setMinLines(10);
        code.setText(s.code);
        root.addView(code, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().density * 240)));

        new AlertDialog.Builder(this)
                .setTitle(s0 == null ? "スクリプト追加" : "スクリプト編集")
                .setView(sc)
                .setPositiveButton("保存", (d, w) -> {
                    s.code = code.getText().toString();
                    if (s.code.trim().isEmpty()) {
                        Toast.makeText(this, "コードが空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    s.name = name.getText().toString().trim();
                    s.matches = match.getText().toString().trim();
                    UserScripts.fillFromMeta(s);
                    db.saveScript(s);
                    UserScripts.get(this).reload();
                    Toast.makeText(this, "保存した: " + s.name, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    // ---- 履歴・ブックマーク ----

    private void showHistory() {
        final List<BrowserDb.Entry> items = db.listHistory(300);
        if (items.isEmpty()) {
            Toast.makeText(this, "履歴なし", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("履歴")
                .setAdapter(entryAdapter(items), (d, w) -> openInCurrent(items.get(w).url))
                .setNeutralButton("全消去", (d, w) -> new AlertDialog.Builder(this)
                        .setMessage("履歴を全部消す？")
                        .setPositiveButton("消す", (d2, w2) -> {
                            db.clearHistory();
                            Toast.makeText(this, "消した", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("やめる", null)
                        .show())
                .setNegativeButton("閉じる", null)
                .create();
        dlg.show();
    }

    private void showBookmarks() {
        final List<BrowserDb.Entry> items = db.listBookmarks();
        if (items.isEmpty()) {
            Toast.makeText(this, "ブックマークなし（⋮→☆追加）", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("ブックマーク（長押しで削除）")
                .setAdapter(entryAdapter(items), (d, w) -> openInCurrent(items.get(w).url))
                .setNegativeButton("閉じる", null)
                .create();
        dlg.getListView().setOnItemLongClickListener((parent, v, p, id) -> {
            BrowserDb.Entry e = items.get(p);
            new AlertDialog.Builder(this)
                    .setMessage("削除する？\n" + e.label())
                    .setPositiveButton("削除", (d2, w2) -> {
                        db.removeBookmark(e.url);
                        dlg.dismiss();
                        showBookmarks();
                    })
                    .setNegativeButton("やめる", null)
                    .show();
            return true;
        });
        dlg.show();
    }

    /** タイトル＋URLの2行リスト */
    private BaseAdapter entryAdapter(final List<BrowserDb.Entry> items) {
        return new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int p) { return items.get(p); }
            @Override public long getItemId(int p) { return items.get(p).id; }
            @Override public View getView(int p, View cv, ViewGroup parent) {
                BrowserDb.Entry e = items.get(p);
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(24, 14, 24, 14);
                TextView title = new TextView(MainActivity.this);
                title.setText(e.label());
                title.setSingleLine(true);
                title.setTextSize(15);
                TextView url = new TextView(MainActivity.this);
                url.setText(e.url);
                url.setSingleLine(true);
                url.setTextSize(11);
                col.addView(title);
                col.addView(url);
                return col;
            }
        };
    }

    private void openInCurrent(String url) {
        WebView w = curWeb();
        if (w != null) w.loadUrl(url);
    }

    // ---- WebViewセットアップ（タブごと） ----

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWeb(final Tab t) {
        // CDP制御チャネル: 外部LLMエージェントが devtools socket 経由でこのWebViewを駆動できる
        if (Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(true);
        final SnifferWebView web = new SnifferWebView(this); // バックグラウンド再生対応
        t.web = web;
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true); // オフライン保存した .mht の復元表示に必要
        s.setSupportMultipleWindows(true); // window.open/target=_blank をonCreateWindowで受ける
        if (Build.VERSION.SDK_INT >= 21)
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 表示域の少し外まで先行ラスタライズ → スクロール時の白抜け/カクつきを軽減
        // (setOffscreenPreRaster はビルドSDKに無いのでリフレクション経由で呼ぶ)
        if (Build.VERSION.SDK_INT >= 23) {
            try { WebView.class.getMethod("setOffscreenPreRaster", boolean.class).invoke(web, true); }
            catch (Throwable ignore) {}
        }
        SnifferChrome.applyChromeUa(s);
        SnifferChrome.applyGeolocation(this, s);
        ua = s.getUserAgentString();

        SnifferChrome.enableDownloads(this, web);
        SnifferChrome.enableLongPress(this, web, url -> createTab(url, true));

        // バックグラウンド再生: 再生状態を監視し、裏で再生中なら前面サービスで延命
        Media.track(web, new Media.PlayState() {
            @Override public void onPlaying(boolean playing) {
                mediaPlaying = playing;
                web.setKeepPlaying(playing); // 裏でもメディアを止めない(バックグラウンド再生)
                runOnUiThread(MainActivity.this::syncPlaybackService);
            }
            @Override public void onVideoSize(int w, int h) {
                videoW = w; videoH = h;
            }
        });

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);

        // 証明書エラーで「続行」したホスト（タブ単位）と確認ダイアログ多重表示ガード
        final java.util.Set<String> sslOk = new java.util.HashSet<>();
        final boolean[] sslPrompting = {false};
        // 直近のネイティブタッチ時刻。ClickTrackerのJSが未動作なページでの
        // フォールバック判定（旧来のジェスチャ猶予）にのみ使う
        final long[] lastGestureMs = {0};
        web.setOnTouchListener((v, ev) -> { lastGestureMs[0] = System.currentTimeMillis(); return false; });
        ClickTracker.install(web); // クリック地点がリンクか否かの記録（リダイレクトブロック用）
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                WebResourceResponse block = ad.shouldBlock(req.getUrl());
                if (block != null) return block;
                Hits.sniff(req, t.pageUrl, t.pageTitle, ua);
                return null; // 通常ロードを妨げない
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if ("gobie".equals(req.getUrl().getScheme())) {
                    handleGobie(t, req.getUrl()); // ホーム内の固定解除/更新リンク
                    return true;
                }
                // http(s)等のWebスキーム以外（npf<id>://=任天堂アカウント連携の戻り,
                // intent://, market:, mailto:, tel: 等）は外部アプリへ委譲。
                // WebViewに渡すとERR_UNKNOWN_URL_SCHEME(=scheme resolve error)で停止する。
                String scheme = req.getUrl().getScheme();
                if (scheme != null && !isWebScheme(scheme)) {
                    return openExternal(view, req.getUrl().toString(), req.hasGesture());
                }
                // ユーザーがリンク/フォームを操作したらキャプティブ素通しモードを解除
                if (req.hasGesture()) { t.captiveProbe = false; }
                // リダイレクトブロック: リンク/フォーム以外を起点とする別サイトへの
                // メインフレーム遷移を遮断。hasGesture()は全面オーバーレイの
                // クリックハイジャックでもtrueになるため判定に使わない。
                // ClickTrackerの記録: リンククリック→中間ページ→JSリダイレクトの連鎖は
                // 猶予内なら正規の続きとして通す。サーバー302は開始時点で判定済みなので通す。
                // WiFiログイン呼び出し中はポータルへの無操作リダイレクトを通す。
                boolean isServerRedirect = Build.VERSION.SDK_INT >= 24 && req.isRedirect();
                if (ad.redirectBlockOn() && req.isForMainFrame()
                        && !isServerRedirect && !t.captiveProbe) {
                    String curUrl = view.getUrl();
                    String from = curUrl != null
                            ? AdBlocker.site(android.net.Uri.parse(curUrl).getHost()) : "";
                    String to = AdBlocker.site(req.getUrl().getHost());
                    String clicked = ClickTracker.recentClickHref(view);
                    // null=JS報告なし: JS未動作ページを壊さないよう旧来のタッチ猶予で判定
                    boolean allow = clicked != null ? !clicked.isEmpty()
                            : (req.hasGesture()
                               || System.currentTimeMillis() - lastGestureMs[0] < 5000);
                    // fromが空（file://等）でも別サイトへの遷移は同様に判定する
                    if (!from.equals(to) && !allow) {
                        Toast.makeText(MainActivity.this,
                                "リダイレクトをブロック: " + req.getUrl().getHost()
                                        + "\n（⋮→設定で解除可）", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                }
                return false; // WebView内で開く
            }
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap f) {
                t.pageUrl = url;
                if (t == curTab()) runOnUiThread(() -> { urlBar.setText(url); updateDlBtn(); });
                SnifferChrome.injectClientHints(view); // userAgentDataのWebView申告をChrome偽装(OAuth承認ボタン無効化回避)
                SnifferChrome.injectBlobGuard(view); // blob DL救済(revoke遅延)
                ClickTracker.inject(view); // クリック地点記録（リダイレクトブロック判定用）
                SnifferChrome.injectYoutubeAdblock(view, url); // YouTube動画内広告の除去(早期注入でJSON.parseフック)
                SnifferChrome.injectYoutubeNarrowFix(view, url); // www狭幅のはみ出し修正
                if (t.zoom != 100) SnifferChrome.applyPageZoom(view, t.zoom);
                ad.injectCosmetics(view, url); // 要素隠しCSSを早期注入（描画前に広告枠を潰す）
                for (String js : UserScripts.get(MainActivity.this).forUrl(url, true))
                    view.evaluateJavascript(js, null);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                t.pageUrl = url;
                t.pageTitle = view.getTitle();
                Media.injectTracker(view);
                ClickTracker.inject(view); // started時に注入失敗したページの保険
                SnifferChrome.injectYoutubeNarrowFix(view, url); // started時は旧documentで消えるため再注入
                if (t.zoom != 100) SnifferChrome.applyPageZoom(view, t.zoom);
                ad.injectCosmetics(view, url); // 動的挿入対策に読み込み完了時も上書き注入
                db.addHistory(url, t.pageTitle);
                OfflineStore.get(MainActivity.this).autoSave(view, db, url, t.pageTitle);
                for (String js : UserScripts.get(MainActivity.this).forUrl(url, false))
                    view.evaluateJavascript(js, null);
            }
            // 証明書エラーの取り扱い。デフォルト挙動は無言cancel=真っ白なので、
            // WiFiキャプティブポータル（証明書を横取り/自己署名する事が多い）等で
            // ページが開けない。許可済みホストは素通し、未判断なら一度だけ確認する。
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                String host = android.net.Uri.parse(error.getUrl()).getHost();
                if (host != null && sslOk.contains(host)) { handler.proceed(); return; }
                // WiFiログイン呼び出し中は、ポータルの自己署名証明書を確認なしで通す
                if (t.captiveProbe) { if (host != null) sslOk.add(host); handler.proceed(); return; }
                handler.cancel(); // 承認するまでは止める
                if (sslPrompting[0]) return; // ダイアログ多重表示を防ぐ
                sslPrompting[0] = true;
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("証明書エラー")
                        .setMessage((host != null ? host : error.getUrl())
                                + "\nの接続は安全でない可能性があります"
                                + "（WiFiログイン画面ではよくあります）。続行しますか？")
                        .setPositiveButton("続行", (d, w) -> {
                            sslPrompting[0] = false;
                            if (host != null) sslOk.add(host);
                            view.reload();
                        })
                        .setNegativeButton("キャンセル", (d, w) -> sslPrompting[0] = false)
                        .setOnCancelListener(d -> sslPrompting[0] = false)
                        .show());
            }
        });

        t.chrome = new SnifferChrome(this, web) {
            @Override public void onProgressChanged(WebView view, int p) {
                if (t != curTab()) return;
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                progress.setProgress(p);
                if (p >= 100) webContainer.setRefreshing(false);
            }
            @Override public void onReceivedTitle(WebView view, String title) {
                t.pageTitle = title;
            }
            @Override protected void openUrl(String url) {
                createTab(url, true); // window.open/target=_blank は新規タブで開く
            }
        };
        web.setWebChromeClient(t.chrome);
    }

    // ---- 動画検出・DL（従来どおり） ----

    /** DL候補一覧。通常は現在タブのサイトで検出した分だけ、allSites=true（📥長押し）で全件 */
    private void showHits(boolean allSites) {
        Tab t = curTab();
        final List<MediaHit> hits = allSites ? Hits.all()
                : Hits.forPage(t != null ? t.pageUrl : null);
        if (hits.isEmpty()) {
            int total = Hits.size();
            Toast.makeText(this, total > 0
                    ? "このサイトでは検出なし（📥長押しで全サイト " + total + " 件）"
                    : "まだ検出なし（動画を再生してみて）", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[hits.size()];
        for (int i = 0; i < hits.size(); i++) items[i] = hits.get(i).display();

        new AlertDialog.Builder(this)
                .setTitle("検出 " + hits.size() + " 件"
                        + (allSites ? "（全サイト）" : "（このサイト・📥長押しで全部）"))
                .setItems(items, (d, which) -> startDownload(hits.get(which)))
                .setNeutralButton("クリア", (d, w) -> Hits.clear())
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void startDownload(MediaHit h) {
        WebView w = curWeb();
        if ((h.title == null || h.title.isEmpty()) && w != null) h.title = w.getTitle();
        if (!"m3u8".equals(h.type)) { launchDownload(h); return; }
        // m3u8はまずplaylistを覗き、masterで複数画質あれば選択ダイアログを出す
        Toast.makeText(this, "画質を確認中...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String master = null;
            List<HlsDownloader.Variant> vars = null;
            try {
                master = HlsDownloader.fetchText(h.url, h);
                vars = HlsDownloader.listVariants(master, h.url);
            } catch (Throwable ignore) {} // 取得失敗→従来どおり自動選択でDL開始
            final String fMaster = master;
            final List<HlsDownloader.Variant> fVars = vars;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fVars == null || fVars.size() < 2) { launchDownload(h); return; }
                showQualityDialog(h, fMaster, fVars);
            });
        }).start();
    }

    private void showQualityDialog(MediaHit h, String master, List<HlsDownloader.Variant> vars) {
        java.util.Collections.sort(vars, (a, b) -> Long.compare(b.bw, a.bw)); // 高画質順
        String[] items = new String[vars.size() + 1];
        items[0] = "自動（最高画質）";
        for (int i = 0; i < vars.size(); i++) items[i + 1] = vars.get(i).label();
        new AlertDialog.Builder(this)
                .setTitle("画質選択")
                .setItems(items, (d, w) -> {
                    if (w == 0) { launchDownload(h); return; }
                    HlsDownloader.Variant v = vars.get(w - 1);
                    MediaHit h2 = new MediaHit(v.url, "m3u8");
                    h2.referer = h.referer; h2.cookie = h.cookie; h2.ua = h.ua; h2.title = h.title;
                    h2.quality = v.label();
                    if (v.audioGroup != null)
                        h2.audioUrl = HlsDownloader.findAudioUri(master, h.url, v.audioGroup);
                    launchDownload(h2);
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    private void launchDownload(MediaHit h) {
        Intent it = new Intent(this, DownloadService.class);
        it.putExtra("url", h.url);
        it.putExtra("type", h.type);
        it.putExtra("referer", h.referer);
        it.putExtra("cookie", h.cookie);
        it.putExtra("ua", h.ua);
        it.putExtra("title", h.title);
        it.putExtra("audio", h.audioUrl);
        it.putExtra("quality", h.quality);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(it);
        else startService(it);
        Toast.makeText(this, "DL開始: " + (h.quality != null ? h.quality + " " : "") + h.display(),
                Toast.LENGTH_SHORT).show();
    }

    // 検索エンジン: {表示名, 検索URLテンプレート}。デフォルト(index 0)はGoogle。
    static final String[][] ENGINES = {
            {"Google", "https://www.google.com/search?q="},
            {"DuckDuckGo Lite", "https://lite.duckduckgo.com/lite/?q="},
            {"Bing", "https://www.bing.com/search?q="},
    };

    private int searchEngineIndex() {
        int i = getSharedPreferences("settings", MODE_PRIVATE).getInt("searchEngine", 0);
        return (i >= 0 && i < ENGINES.length) ? i : 0;
    }

    private void go(String q) {
        String url;
        if (q.matches("(?i)^https?://.*")) url = q;
        else if (q.contains(".") && !q.contains(" ")) url = "https://" + q;
        else {
            String base = ENGINES[searchEngineIndex()][1];
            try { url = base + java.net.URLEncoder.encode(q, "UTF-8"); }
            catch (Exception e) { url = base; }
        }
        openInCurrent(url);
    }

    private void requestStorage() {
        // targetSdk28 + requestLegacyExternalStorage により API29でも/sdcard直書き可能
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 29) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_BG) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                importHomeBg(data.getData());
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        // ファイルピッカーは開いたタブのchromeだけがfileCbを持つので全タブへ中継して問題ない
        for (Tab t : tabs) t.chrome.onFileResult(requestCode, resultCode, data);
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // geolocationの権限要求結果を、要求したタブのchromeへ中継する
        if (requestCode == SnifferChrome.REQ_GEO)
            for (Tab t : tabs) t.chrome.onGeoPermissionResult(requestCode, grantResults);
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Tab t = curTab();
            if (t != null) {
                if (t.chrome.exitFullscreen()) return true;
                if (t.web.canGoBack()) { t.web.goBack(); return true; }
                if (t.external) {
                    // 外部アプリから開いたタブ: 履歴が尽きたらタブを閉じて呼び出し元アプリへ戻る。
                    // タブ閉じの連鎖に入るとアプリへ帰れないので、ここで明示的に抜ける。
                    t.external = false;
                    boolean root = isTaskRoot();
                    closeTab(cur); // セッションに外部リンクを残さない（空なら新ホームタブが立つ）
                    if (root) moveTaskToBack(true); // 自タスク起動: ブラウザは生かしたまま裏へ
                    else finish();                 // 呼び出し元タスク上に居る: 閉じて戻す
                    return true;
                }
                if (tabs.size() > 1) { closeTab(cur); return true; }
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
