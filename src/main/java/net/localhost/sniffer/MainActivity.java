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
import android.webkit.CookieManager;
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
    }

    private Tab curTab() { return (cur >= 0 && cur < tabs.size()) ? tabs.get(cur) : null; }
    private WebView curWeb() { Tab t = curTab(); return t != null ? t.web : null; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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

        Hits.setListener(n -> runOnUiThread(() -> btnDl.setText("📥" + n)));
        btnDl.setOnClickListener(v -> showHits());
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
            createTab(it.getData().toString(), true);
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
            createTab(intent.getData().toString(), true);
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
        if (t != null && t.chrome.isInFullscreen())
            Media.enterPip(this, videoW, videoH);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, android.content.res.Configuration cfg) {
        super.onPictureInPictureModeChanged(isInPip, cfg);
        inPip = isInPip;
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
            else t.web.loadUrl(url);
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
        String u = t.web.getUrl();
        urlBar.setText(u != null ? u : "");
        progress.setVisibility(View.GONE);
        updateTabBtn();
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
                title.setText((p == cur ? "▶ " : "") + (tt == null || tt.isEmpty() ? "(無題)" : tt));
                title.setSingleLine(true);
                title.setTextSize(15);
                TextView url = new TextView(MainActivity.this);
                String uu = t.web.getUrl();
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
        boolean bm = db.isBookmarked(t.web.getUrl());
        m.add(0, 3, 0, bm ? "★ ブックマーク解除" : "☆ ブックマークに追加");
        m.add(0, 4, 0, "📑 ブックマーク");
        m.add(0, 5, 0, "🕘 履歴");
        m.add(0, 9, 0, "🏠 ホームに固定");
        m.add(0, 6, 0, "📌 PWAとしてホームに追加");
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
            }
            return false;
        });
        pm.show();
    }

    // ---- ホーム（gobie://home、固定サイト＋新着オートスクレイプ） ----

    private void goHome(Tab t, boolean force) {
        List<BrowserDb.Entry> pins = db.listPins();
        List<BrowserDb.Pwa> pwas = db.listPwas();
        boolean fetch = HomePage.needsFetch(pins, force);
        t.web.loadDataWithBaseURL(HomePage.URL_HOME, HomePage.render(pins, pwas, fetch),
                "text/html", "utf-8", HomePage.URL_HOME);
        if (t == curTab()) urlBar.setText(HomePage.URL_HOME);
        // 既存のピン留めPWAを台帳へ取り込み、増えていたらホームを描き直す
        new Thread(() -> {
            int before = pwas.size();
            PwaInstaller.syncFromShortcuts(getApplicationContext());
            List<BrowserDb.Pwa> after = db.listPwas();
            if (after.size() != before) runOnUiThread(() -> rerenderHome());
        }).start();
        if (!fetch) return;
        new Thread(() -> {
            HomePage.fetchAll(pins, force, ua);
            runOnUiThread(this::rerenderHome);
        }).start();
    }

    /** ホームを開いたままのタブだけ最新の固定サイト/PWAで再描画。 */
    private void rerenderHome() {
        if (isFinishing() || isDestroyed()) return;
        List<BrowserDb.Entry> pins = db.listPins();
        List<BrowserDb.Pwa> pwas = db.listPwas();
        for (Tab x : tabs)
            if (HomePage.URL_HOME.equals(x.web.getUrl()))
                x.web.loadDataWithBaseURL(HomePage.URL_HOME,
                        HomePage.render(pins, pwas, false),
                        "text/html", "utf-8", HomePage.URL_HOME);
    }

    /** ホーム内リンク gobie://unpin?id=N / gobie://refresh / gobie://pwa-open|pwa-menu?id=N */
    private void handleGobie(Tab t, android.net.Uri u) {
        String host = u.getHost();
        if ("unpin".equals(host)) {
            try { db.removePin(Long.parseLong(u.getQueryParameter("id"))); } catch (Throwable ignore) {}
            goHome(t, false);
        } else if ("refresh".equals(host)) {
            goHome(t, true);
        } else if ("pwa-open".equals(host)) {
            openPwa(pwaParam(u));
        } else if ("pwa-menu".equals(host)) {
            showPwaItemMenu(t, pwaParam(u));
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
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
            startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(this, "起動できません: " + e, Toast.LENGTH_SHORT).show();
        }
    }

    /** PWAタイル長押しメニュー: 開く / アップデート / 削除。 */
    private void showPwaItemMenu(Tab t, BrowserDb.Pwa p) {
        if (p == null) return;
        final String[] items = {"開く", "🔄 アップデート（キャッシュ全消し）", "🗑 ホームから削除"};
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
                            Toast.makeText(this, "ホームから削除（ランチャーのアイコンは手動で）",
                                    Toast.LENGTH_SHORT).show();
                            goHome(t, false);
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
    private boolean openExternal(WebView view, String url) {
        try {
            Intent intent = url.startsWith("intent://")
                    ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            // intent://の悪用防止: 明示コンポーネント/セレクタを剥がしBROWSABLE扱いに限定
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                return true;
            }
            if (url.startsWith("intent://")) {
                String fb = intent.getStringExtra("browser_fallback_url");
                if (fb != null) { view.loadUrl(fb); return true; }
            }
            String sc = android.net.Uri.parse(url).getScheme();
            Toast.makeText(this, "対応アプリが見つかりません: " + sc + "://",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
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
        url.setHint("URL（トップページに削ると新着取得が安定）");
        url.setSingleLine(true);
        url.setText(u0);
        root.addView(url);
        new AlertDialog.Builder(this)
                .setTitle("ホームに固定")
                .setView(root)
                .setPositiveButton("固定", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (u.isEmpty()) return;
                    db.addPin(u, name.getText().toString().trim());
                    Toast.makeText(this, "🏠 固定した", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    // ---- 設定（カテゴリ別） ----

    private void showSettings() {
        final String[] cats = {"🛡 アドブロック", "📦 オフライン"};
        new AlertDialog.Builder(this)
                .setTitle("⚙ 設定")
                .setItems(cats, (d, w) -> {
                    if (w == 0) showAdblockSettings();
                    else showOfflineSettings();
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    // ---- 設定: アドブロック（ブロック系トグル＋フィルター管理） ----

    private void showAdblockSettings() {
        final String[] names = {
                "広告ブロック",
                "ポップアップブロック（無操作のwindow.open遮断）",
                "リダイレクトブロック（無操作の別サイト遷移遮断）"};
        final String[] keys = {"adblock", "popupBlock", "redirectBlock"};
        final boolean[] st = {ad.adblockOn(), ad.popupBlockOn(), ad.redirectBlockOn()};
        new AlertDialog.Builder(this)
                .setTitle("アドブロック（ルール " + ad.ruleCount() + "件）")
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
        Toast.makeText(this, "280blockerリストを取得中...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final int n = ad.update();
                runOnUiThread(() -> Toast.makeText(this,
                        "フィルター更新OK: " + n + "件", Toast.LENGTH_LONG).show());
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

    private void showPwaDialog(Tab t) {
        new AlertDialog.Builder(this)
                .setTitle("PWAとしてホームに追加")
                .setMessage((t.pageTitle == null || t.pageTitle.isEmpty() ? t.pageUrl : t.pageTitle)
                        + "\n\nこのサイトをstandaloneアプリ化する？")
                .setPositiveButton("追加", (d, w) -> PwaInstaller.install(this, t.web, t.pageUrl, t.pageTitle))
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
        WebView web = new WebView(this);
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
        SnifferChrome.applyChromeUa(s);
        ua = s.getUserAgentString();

        SnifferChrome.enableDownloads(this, web);
        SnifferChrome.enableImageSave(this, web);

        // バックグラウンド再生: 再生状態を監視し、裏で再生中なら前面サービスで延命
        Media.track(web, new Media.PlayState() {
            @Override public void onPlaying(boolean playing) {
                mediaPlaying = playing;
                runOnUiThread(MainActivity.this::syncPlaybackService);
            }
            @Override public void onVideoSize(int w, int h) {
                videoW = w; videoH = h;
            }
        });

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);

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
                    return openExternal(view, req.getUrl().toString());
                }
                // リダイレクトブロック: ユーザー操作なしの別サイトへのメインフレーム遷移を遮断
                if (ad.redirectBlockOn() && req.isForMainFrame() && !req.hasGesture()) {
                    String curUrl = view.getUrl();
                    String from = curUrl != null
                            ? AdBlocker.site(android.net.Uri.parse(curUrl).getHost()) : "";
                    String to = AdBlocker.site(req.getUrl().getHost());
                    // fromが空（file://等）でも別サイトへの無操作遷移は遮断する
                    if (!from.equals(to)) {
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
                if (t == curTab()) runOnUiThread(() -> urlBar.setText(url));
                for (String js : UserScripts.get(MainActivity.this).forUrl(url, true))
                    view.evaluateJavascript(js, null);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                t.pageUrl = url;
                t.pageTitle = view.getTitle();
                Media.injectTracker(view);
                db.addHistory(url, t.pageTitle);
                OfflineStore.get(MainActivity.this).autoSave(view, db, url, t.pageTitle);
                for (String js : UserScripts.get(MainActivity.this).forUrl(url, false))
                    view.evaluateJavascript(js, null);
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

    private void showHits() {
        final List<MediaHit> hits = Hits.all();
        if (hits.isEmpty()) {
            Toast.makeText(this, "まだ検出なし（動画を再生してみて）", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[hits.size()];
        for (int i = 0; i < hits.size(); i++) items[i] = hits.get(i).display();

        new AlertDialog.Builder(this)
                .setTitle("検出 " + hits.size() + " 件")
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

    private void go(String q) {
        String url;
        if (q.matches("(?i)^https?://.*")) url = q;
        else if (q.contains(".") && !q.contains(" ")) url = "https://" + q;
        else {
            try { url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"); }
            catch (Exception e) { url = "https://www.google.com"; }
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
        // ファイルピッカーは開いたタブのchromeだけがfileCbを持つので全タブへ中継して問題ない
        for (Tab t : tabs) t.chrome.onFileResult(requestCode, resultCode, data);
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Tab t = curTab();
            if (t != null) {
                if (t.chrome.exitFullscreen()) return true;
                if (t.web.canGoBack()) { t.web.goBack(); return true; }
                if (tabs.size() > 1) { closeTab(cur); return true; }
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
