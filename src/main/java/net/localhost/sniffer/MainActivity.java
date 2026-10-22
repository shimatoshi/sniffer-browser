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
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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

    // ホームは本物のGoogle（自作ホームはデバッグノート指摘で廃止。gobie://homeは旧互換で残る）
    private static final String HOME = "https://www.google.com/";
    static final String ACTION_FOCUS_URL = "net.localhost.sniffer.FOCUS_URL";
    static final String ACTION_SEARCH = "net.localhost.sniffer.SEARCH";
    static final String EXTRA_QUERY = "query";

    private PullRefreshContainer webContainer;
    private EditText urlBar;
    private Button btnTabs;
    private TextView btnDlBadge;
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

    /** 1タブ = 1WebView。sniff用のページ状態もタブごとに保持。
     *  ただし復元タブの web は null のまま置き、実際に表示される時まで生成しない。 */
    private class Tab {
        // 未表示の復元タブでは null。参照は web(t) 経由にして、そこで初めて実体化する。
        WebView web;
        SnifferChrome chrome;
        volatile String pageUrl = "";
        volatile String pageTitle = "";
        // 復元直後のバックグラウンドタブの未読込URL。スイッチ時に初めてloadUrlする（起動高速化）。
        volatile String pendingUrl = null;
        // 未生成タブをタブ一覧に出すための表示名（WebViewが無いとgetTitleできないため）。
        volatile String pendingTitle = null;
        // タブ一覧用の縮小スクリーンショット。WebView破棄後も残す（取得失敗時はnull）。
        Bitmap thumb;
        // ドメイン別PC版指定でUA差し替え→再読込を撃ったURL。同一URLで二重に撃たないための番人。
        volatile String uaReloadedFor = null;
        // 破棄したWebViewの履歴・スクロール位置。再表示時に restoreState で復元する。
        volatile Bundle savedState = null;
        // 最後に表示した時刻。どのタブを破棄するか（最近見ていない順）の判定に使う。
        volatile long lastShown = 0;
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
    private WebView curWeb() { Tab t = curTab(); return t != null ? web(t) : null; }

    /** タブのWebViewを取り出す。未生成（復元直後で一度も表示されていない）ならここで作る。
     *  復元タブぶんのWebViewを起動時に全部作ると、開いてもいないタブが常駐してメモリを食う。 */
    private WebView web(Tab t) {
        if (t.web == null) materialize(t);
        return t.web;
    }

    /** 外部エージェント(CDP)が掴むための専用WebView。タブ一覧には出さず、常に1個だけ生かす。
     *  これが無いと、ハーネスは「空タブ」を探して見つからず、ユーザーが見ているタブを乗っ取る。 */
    private WebView bridgeWeb;

    /** CDPのターゲット一覧でこのWebViewを一意に見分けるための目印（title）。 */
    static final String BRIDGE_TITLE = "sniffer-cdp-bridge";

    private void setupBridgeWeb() {
        if (bridgeWeb != null) return;
        if (Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(true);
        bridgeWeb = new WebView(this);
        WebSettings s = bridgeWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        SnifferChrome.applyChromeUa(s);
        bridgeWeb.setWebViewClient(new WebViewClient()); // 外部ブラウザへ逃がさない
        // GONEだと描画が止まりJS実行までスロットルされるため、1x1pxで最背面に置く。
        webContainer.addView(bridgeWeb, 0, new FrameLayout.LayoutParams(1, 1));
        bridgeWeb.loadDataWithBaseURL(null,
                "<html><head><title>" + BRIDGE_TITLE + "</title></head><body></body></html>",
                "text/html", "utf-8", null);
    }

    /** 同時に生かしておくWebViewの上限。これを超えた分は最近見ていない順に破棄する。
     *  空のWebViewは1個0.4MB程度と安いが、描画済みのページは1枚で数百MB食うため、
     *  効くのは「開いた事があるタブ」を裏で持ち続けない事。 */
    private static final int MAX_LIVE_WEBVIEWS = 3;

    /** 表示中でないタブのWebViewを上限まで減らす。破棄前に履歴とスクロール位置を退避する。 */
    private void trimLiveWebViews() {
        List<Tab> live = new ArrayList<>();
        for (Tab t : tabs) if (t.web != null) live.add(t);
        if (live.size() <= MAX_LIVE_WEBVIEWS) return;
        java.util.Collections.sort(live, (a, b) -> Long.compare(b.lastShown, a.lastShown));
        Tab keep = curTab();
        for (int i = MAX_LIVE_WEBVIEWS; i < live.size(); i++) {
            Tab t = live.get(i);
            if (t != keep) discard(t);
        }
    }

    /** 表示中WebViewを1/5に縮小描画してタブ一覧のサムネイルにする。失敗しても無視。 */
    private void captureThumb(Tab t) {
        WebView w = t.web;
        if (w == null || w.getWidth() <= 0 || w.getHeight() <= 0 || w.getVisibility() != View.VISIBLE) return;
        try {
            float sc = 0.2f;
            Bitmap bm = Bitmap.createBitmap(Math.max(1, (int) (w.getWidth() * sc)),
                    Math.max(1, (int) (w.getHeight() * sc)), Bitmap.Config.RGB_565);
            android.graphics.Canvas c = new android.graphics.Canvas(bm);
            c.scale(sc, sc);
            w.draw(c);
            t.thumb = bm;
        } catch (Throwable ignore) {}
    }

    /** タブのWebViewを捨てて、URL＋saveStateだけの状態に戻す。タブ自体は残る。 */
    private void discard(Tab t) {
        if (t.web == null) return;
        captureThumb(t);
        Bundle b = new Bundle();
        try {
            t.web.saveState(b);   // 履歴とスクロール位置。戻った時に読み直しても同じ場所に出る
            t.savedState = b;
        } catch (Throwable ignore) { t.savedState = null; }
        String u = t.web.getUrl();
        if (u != null && !u.equals("about:blank")) t.pendingUrl = u; // restoreState失敗時の保険
        t.pendingTitle = (t.pageTitle != null && !t.pageTitle.isEmpty()) ? t.pageTitle : t.pendingUrl;
        webContainer.removeView(t.web);
        t.web.destroy();
        t.web = null;
    }

    /** タブのWebViewを実体化してコンテナに載せる（非表示状態） */
    private void materialize(Tab t) {
        setupWeb(t); // 中で t.web に代入される
        webContainer.addView(t.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.web.setVisibility(View.GONE);
        if (t.desktop) SnifferChrome.applyUaMode(t.web.getSettings(), true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        net.localhost.debugnote.DebugNote.attach(this, "sniffer-browser");

        webContainer = findViewById(R.id.webContainer);
        setupBridgeWeb(); // CDP用の常設タブ。タブ復元より先に置いて必ず1個確保する

        // 上スクロールのつもりが最上部での下方向ドラッグと誤検知され意図せずリロードされる
        // という報告により撤去。listener==nullだとPullRefreshContainer側で
        // ジェスチャー捕捉自体を行わなくなる。代わりにURLバー横のボタンでリロードする
        urlBar = findViewById(R.id.url);
        View btnDl = findViewById(R.id.btnDl);
        btnDlBadge = findViewById(R.id.btnDlBadge);
        btnTabs = findViewById(R.id.btnTabs);
        progress = findViewById(R.id.progress);
        db = new BrowserDb(this);
        ad = AdBlocker.get(this);

        requestStorage();

        Hits.setListener(n -> runOnUiThread(this::updateDlBtn));
        findViewById(R.id.btnReload).setOnClickListener(v -> {
            WebView w = curWeb();
            if (w != null) w.reload();
        });
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

        // タップしたら現在URLを全選択状態にする(コピペしやすいように消さない)。
        // フォーカス中はページ遷移コールバックで表示を上書きしない。
        urlBar.setSelectAllOnFocus(false);
        urlBar.setOnFocusChangeListener((v, focused) -> {
            if (focused) urlBar.selectAll();
        });
        // フォーカス済みの状態での再タップはonFocusChangeが発火せず選択が外れるだけなので、
        // クリックの度に明示selectAllして常に全選択状態を保証する。
        urlBar.setOnClickListener(v -> urlBar.selectAll());
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
            String u = (t.web != null) ? t.web.getUrl() : null;
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
        for (Tab t : tabs) if (t.web != null) t.web.destroy();
        tabs.clear();
        if (bridgeWeb != null) { bridgeWeb.destroy(); bridgeWeb = null; }
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
        if (t != null) Media.setPipLayout(web(t), isInPip);
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
        tabs.add(t);
        if (url != null && !switchTo && !url.startsWith("gobie://")) {
            // セッション復元のバックグラウンドタブ。URLだけ覚えて WebView は作らない。
            // 実体化は switchTab（＝実際に表示される時）まで遅らせる。
            t.pendingUrl = url;
            t.pendingTitle = url;
        } else {
            materialize(t);
            if (url != null) {
                if (url.startsWith("gobie://")) goHome(t, false); // セッション復元含む
                else web(t).loadUrl(url);
            }
        }
        if (switchTo) switchTab(tabs.size() - 1);
        else updateTabBtn();
        return t;
    }

    private void switchTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        Tab prev = curTab();
        if (prev != null && prev.web != null) { captureThumb(prev); prev.web.setVisibility(View.GONE); }
        cur = i;
        Tab t = tabs.get(i);
        t.lastShown = System.currentTimeMillis();
        boolean fresh = (t.web == null);       // ここで初めて実体化されるタブか
        WebView w = web(t);
        w.setVisibility(View.VISIBLE);
        if (fresh) prepareUa(t, t.pendingUrl); // 読み込む前にPC版UAを決める
        if (t.savedState != null) {            // 破棄済みタブ: 履歴とスクロール位置ごと復元
            Bundle b = t.savedState;
            t.savedState = null;
            if (w.restoreState(b) == null && t.pendingUrl != null) w.loadUrl(t.pendingUrl);
            t.pendingUrl = null;
        } else if (t.pendingUrl != null) {     // 遅延復元タブを初回表示時にロード
            String p = t.pendingUrl;
            t.pendingUrl = null;
            w.loadUrl(p);
        }
        String u = w.getUrl();
        if (u == null) u = t.pendingUrl;
        setUrlBar(u != null ? u : "");
        progress.setVisibility(View.GONE);
        updateTabBtn();
        updateDlBtn();
        trimLiveWebViews(); // 裏に回ったタブのWebViewを上限まで減らす
    }

    /** DLバッジの件数を現在タブの表示中サイトで検出した件数に更新（DL欄のサイト追従） */
    private void updateDlBtn() {
        Tab t = curTab();
        int n = Hits.countFor(t != null ? t.pageUrl : null);
        btnDlBadge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        btnDlBadge.setText(String.valueOf(n));
        // 新しいメディアが見つかった瞬間だけ、バッジを一度ふくらませて気づかせる
        if (n > lastBadgeCount && n > 0) {
            btnDlBadge.animate().cancel();
            btnDlBadge.setScaleX(1f); btnDlBadge.setScaleY(1f);
            btnDlBadge.animate().scaleX(1.5f).scaleY(1.5f).setDuration(160)
                    .withEndAction(() -> btnDlBadge.animate().scaleX(1f).scaleY(1f).setDuration(160));
        }
        lastBadgeCount = n;
    }

    private int lastBadgeCount = 0;
    /** 直近に閉じたタブのURL（タブ一覧から「閉じたタブを開く」で復活させる） */
    private String lastClosedUrl = null;

    private void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        Tab t = tabs.remove(i);
        String closedUrl = (t.web != null) ? t.web.getUrl() : t.pendingUrl;
        if (closedUrl != null && !closedUrl.isEmpty() && !closedUrl.equals("about:blank")
                && !closedUrl.startsWith("data:")) lastClosedUrl = closedUrl;
        if (t.web != null) { webContainer.removeView(t.web); t.web.destroy(); }
        if (tabs.isEmpty()) { cur = -1; createTab(HOME, true); return; }
        int next = (i == cur) ? Math.min(i, tabs.size() - 1)
                              : (i < cur ? cur - 1 : cur);
        cur = -1; // switchTabに前タブを隠させない（破棄済み）
        switchTab(next);
    }

    private void updateTabBtn() {
        btnTabs.setText(String.valueOf(tabs.size())); // Chrome風: 枠付きスクエア(bg)内に数字だけ
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    private void showTabs() {
        Tab shown = curTab();
        if (shown != null) captureThumb(shown);
        android.widget.GridView lv = new android.widget.GridView(this);
        lv.setNumColumns(2);
        lv.setHorizontalSpacing(dp(8));
        lv.setVerticalSpacing(dp(8));
        lv.setPadding(dp(12), dp(8), dp(12), dp(8));
        lv.setClipToPadding(false);
        AlertDialog.Builder tb = new AlertDialog.Builder(this)
                .setTitle("タブ")
                .setView(lv)
                .setPositiveButton("＋ 新規タブ", (d, w) -> createTab(HOME, true))
                .setNegativeButton("閉じる", null);
        if (lastClosedUrl != null) {
            final String reopen = lastClosedUrl;
            tb.setNeutralButton("↩ 閉じたタブ", (d, w) -> { lastClosedUrl = null; createTab(reopen, true); });
        }
        AlertDialog dlg = tb.create();
        BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return tabs.size(); }
            @Override public Object getItem(int p) { return tabs.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View cv, ViewGroup parent) {
                Tab t = tabs.get(p);
                LinearLayout card = new LinearLayout(MainActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackgroundColor(p == cur ? 0xFF4A6FA5 : 0xFF3C4043);
                card.setPadding(dp(2), dp(2), dp(2), dp(2));

                // ヘッダ行: タイトル + ✕
                LinearLayout head = new LinearLayout(MainActivity.this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(android.view.Gravity.CENTER_VERTICAL);
                head.setPadding(dp(8), 0, 0, 0);
                TextView title = new TextView(MainActivity.this);
                String tt = (t.web != null) ? t.web.getTitle() : t.pendingTitle;
                String uu = (t.web != null) ? t.web.getUrl() : null;
                if (uu == null) uu = t.pendingUrl;
                if ((tt == null || tt.isEmpty()) && uu != null) tt = uu;
                title.setText(tt == null || tt.isEmpty() ? "(無題)" : tt);
                title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                title.setTextSize(13);
                title.setTextColor(0xFFE8EAED);
                head.addView(title, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView close = new TextView(MainActivity.this);
                close.setText("✕");
                close.setTextSize(16);
                close.setTextColor(0xFFE8EAED);
                close.setGravity(android.view.Gravity.CENTER);
                close.setMinWidth(dp(48));
                close.setMinHeight(dp(48));
                close.setContentDescription("タブを閉じる");
                close.setOnClickListener(v -> {
                    closeTab(p);
                    notifyDataSetChanged();
                });
                head.addView(close);
                card.addView(head);

                android.widget.ImageView th = new android.widget.ImageView(MainActivity.this);
                th.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                th.setBackgroundColor(0xFF303134);
                if (t.thumb != null) th.setImageBitmap(t.thumb);
                th.setContentDescription("タブのプレビュー");
                card.addView(th, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
                return card;
            }
        };
        lv.setAdapter(ad);
        lv.setOnItemClickListener((parent, v, p, id) -> {
            switchTab(p);
            dlg.dismiss();
        });
        // 長押しでドラッグ → 別カード上でドロップして並べ替え（Chrome同様）
        lv.setOnItemLongClickListener((parent, v, p, id) -> {
            v.startDrag(android.content.ClipData.newPlainText("tab", String.valueOf(p)),
                    new View.DragShadowBuilder(v), Integer.valueOf(p), 0);
            v.setAlpha(0.4f);
            return true;
        });
        lv.setOnDragListener((v, ev) -> {
            switch (ev.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case android.view.DragEvent.ACTION_DROP: {
                    int from = (Integer) ev.getLocalState();
                    int to = lv.pointToPosition((int) ev.getX(), (int) ev.getY());
                    if (to >= 0 && to != from && from < tabs.size() && to < tabs.size()) {
                        Tab keep = curTab();
                        tabs.add(to, tabs.remove(from));
                        cur = tabs.indexOf(keep);
                    }
                    return true;
                }
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    ad.notifyDataSetChanged(); // 半透明の戻しと並び順の反映
                    return true;
            }
            return true;
        });
        dlg.show();
    }

    // ---- ページ内検索 ----

    private void showFindDialog(Tab t) {
        final WebView w = web(t);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText et = new EditText(this);
        et.setHint("ページ内を検索");
        et.setSingleLine(true);
        et.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        TextView count = new TextView(this);
        count.setTextSize(12);
        box.addView(et);
        box.addView(count);
        w.setFindListener((active, total, done) -> {
            if (!done) return;
            count.setText(total == 0 ? "見つかりません"
                    : (active + 1) + " / " + total);
        });
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                if (e.length() == 0) { w.clearMatches(); count.setText(""); }
                else w.findAllAsync(e.toString());
            }
        });
        et.setOnEditorActionListener((v, id, ev) -> { w.findNext(true); return true; });
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("🔍 ページ内検索")
                .setView(box)
                .setPositiveButton("次へ ▼", null)
                .setNeutralButton("▲ 前へ", null)
                .setNegativeButton("閉じる", null)
                .create();
        d.setOnDismissListener(x -> { w.clearMatches(); w.setFindListener(null); });
        d.show();
        // 押しても閉じないよう、show後にリスナを差し替える
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> w.findNext(true));
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> w.findNext(false));
        et.requestFocus();
        d.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private void sharePage(Tab t) {
        String u = web(t).getUrl();
        if (u == null || u.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, u);
        String title = web(t).getTitle();
        if (title != null) i.putExtra(Intent.EXTRA_SUBJECT, title);
        startActivity(Intent.createChooser(i, "共有"));
    }

    // ---- メニュー ----

    private void showMenu(View anchor) {
        Tab t = curTab();
        if (t == null) return;
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        // 3グループ: ①このページへの操作 ②保存したものの一覧 ③追加・ツール・設定
        m.add(1, 2, 0, "⟳ 再読み込み");
        m.add(1, 12, 0, t.desktop ? "📱 モバイル版に戻す" : "🖥 PC版サイト");
        m.add(1, 17, 0, "🔍 ズーム (" + t.zoom + "%)");
        m.add(1, 18, 0, "🔎 ページ内検索");
        m.add(1, 19, 0, "↗ 共有");
        // YouTube動画ページなら動画DL項目を出す
        final String ytId = SnifferChrome.youtubeVideoId(web(t).getUrl());
        if (ytId != null) {
            m.add(1, 15, 0, "⬇ この動画をDL (ShimaTube)");
            m.add(1, 16, 0, "♪ 音声のみDL (ShimaTube)");
        }
        boolean bm = db.isBookmarked(web(t).getUrl());
        m.add(1, 3, 0, bm ? "★ ブックマーク解除" : "☆ ブックマークに追加");
        m.add(2, 4, 0, "📑 ブックマーク");
        m.add(2, 5, 0, "🕘 履歴");
        m.add(2, 14, 0, "📌 固定サイト");
        m.add(2, 13, 0, "📱 アプリ（PWA）");
        m.add(3, 20, 0, "＋ このページを追加 ▸");
        m.add(3, 21, 0, "🧰 ツール ▸");
        m.add(3, 7, 0, "⚙ 設定");
        if (Build.VERSION.SDK_INT >= 28) m.setGroupDividerEnabled(true);
        final PopupMenu.OnMenuItemClickListener[] lis = new PopupMenu.OnMenuItemClickListener[1];
        lis[0] = item -> {
            switch (item.getItemId()) {
                case 20: {
                    PopupMenu sub = new PopupMenu(this, anchor);
                    sub.getMenu().add(0, 9, 0, "📌 固定サイトに追加");
                    sub.getMenu().add(0, 6, 0, "📱 PWAとしてホーム画面に追加");
                    sub.setOnMenuItemClickListener(lis[0]);
                    sub.show();
                    return true;
                }
                case 21: {
                    PopupMenu sub = new PopupMenu(this, anchor);
                    sub.getMenu().add(0, 10, 0, "📦 オフライン保存");
                    sub.getMenu().add(0, 11, 0, "📶 WiFiログイン");
                    sub.getMenu().add(0, 8, 0, "📜 ユーザースクリプト");
                    sub.setOnMenuItemClickListener(lis[0]);
                    sub.show();
                    return true;
                }
                case 2: web(t).reload(); return true;
                case 3:
                    String u = web(t).getUrl();
                    if (u == null) return true;
                    if (bm) {
                        db.removeBookmark(u);
                        Toast.makeText(this, "ブックマーク解除", Toast.LENGTH_SHORT).show();
                    } else {
                        db.addBookmark(u, web(t).getTitle());
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
                    SnifferChrome.applyUaMode(web(t).getSettings(), t.desktop);
                    // このドメインでは以後自動でPC版にする（ズームと同じ扱い）。
                    // 手動で戻せば行が消えるので、覚えさせたくない時は戻せばよい。
                    db.setDomainDesktop(hostOf(t.pageUrl), t.desktop);
                    t.uaReloadedFor = t.pageUrl; // 直後のonPageStartedで二重に撃たせない
                    // 「非対応ブラウザ」ページに飛ばされている場合、reloadでは同じページに戻るだけ。
                    // PC版UAを持った状態でサイトのトップから入り直す。
                    String reentry = t.desktop && isUnsupportedLanding(t.pageUrl)
                            ? originOf(t.pageUrl) : null;
                    if (reentry != null) web(t).loadUrl(reentry);
                    else web(t).reload(); // ズームはロードフックのapplyPageZoomがページ種別で付け替える
                    Toast.makeText(this, t.desktop ? "🖥 PC版で表示（このサイトは以後PC版）"
                                    : "📱 モバイル版で表示",
                            Toast.LENGTH_SHORT).show();
                    return true;
                case 13: showPwaList(); return true;
                case 14: showPins(); return true;
                case 15:
                    if (ytId != null)
                        SnifferChrome.downloadYoutube(this, ytId, web(t).getTitle(), false);
                    return true;
                case 16:
                    if (ytId != null)
                        SnifferChrome.downloadYoutube(this, ytId, web(t).getTitle(), true);
                    return true;
                case 17: showZoomDialog(t); return true;
                case 18: showFindDialog(t); return true;
                case 19: sharePage(t); return true;
            }
            return false;
        };
        pm.setOnMenuItemClickListener(lis[0]);
        pm.show();
    }

    /** WiFiログイン画面（キャプティブポータル）を強制的に呼び出す。
     *  ポータルは平文HTTPリクエストにしか割り込めない。HTTPSや古いキャッシュを開くと
     *  割り込めず「Wi-Fi接続が利用しづらい状態です/busy」等の古い画面が出やすい。
     *  既知のHTTP probeをキャッシュ無しで叩き、最新のログインページへ302で飛ばさせる。 */
    private void openCaptivePortal(Tab t) {
        if (t == null) return;
        web(t).clearCache(true);      // busy画面など古いポータル応答を捨てて取り直す
        t.captiveProbe = true;        // 続くポータルへの無操作リダイレクトを素通しさせる
        // 平文HTTPの軽量probe。ポータル配下では302でログイン画面へリダイレクトされる。
        String probe = "http://captive.apple.com/hotspot-detect.html";
        web(t).loadUrl(probe);
        if (t == curTab()) setUrlBar(probe);
        Toast.makeText(this, "WiFiログイン画面を呼び出し中…", Toast.LENGTH_SHORT).show();
    }

    // ---- ホーム（本物のGoogle） ----

    private void goHome(Tab t, boolean force) {
        web(t).loadUrl(HOME);
        if (t == curTab()) setUrlBar(HOME);
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

    /** PWA個別メニュー: 開く / アップデート / スケジュール / 削除。 */
    private void showPwaItemMenu(BrowserDb.Pwa p) {
        if (p == null) return;
        final String[] items = {
                "開く", "🔄 アップデート（キャッシュ全消し）",
                "⏰ スケジュール" + (PwaScheduler.hasSchedule(p) ? "（設定済）" : ""),
                "🗑 削除"};
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
                            showPwaScheduleDialog(p);
                            break;
                        case 3:
                            db.removePwa(p.id);
                            PwaScheduler.cancelForPwa(getApplicationContext(), p.id);
                            Toast.makeText(this, "削除（ランチャーのアイコンは手動で）",
                                    Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    /**
     * PWA個別の定期取得スケジュール設定。
     * 時刻を複数（カンマ区切りHH:mm）指定でき、定刻オンラインなら即実行、
     * オフラインならチェックボックスで「復帰時に自動実行」を選べる（既定ON）。
     */
    private void showPwaScheduleDialog(BrowserDb.Pwa p) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(16), dp(48), dp(0));

        final EditText times = new EditText(this);
        times.setHint("例: 7:00,12:30,22:00（空=無効）");
        times.setSingleLine(true);
        times.setText(p.schedule != null ? p.schedule : "");
        root.addView(times);

        final CheckBox queueOffline = new CheckBox(this);
        queueOffline.setText("定刻オフラインなら復帰時に自動実行");
        queueOffline.setChecked(p.queueOffline);
        root.addView(queueOffline);

        new AlertDialog.Builder(this)
                .setTitle("⏰ " + p.label() + " のスケジュール")
                .setView(root)
                .setPositiveButton("保存", (d, w) -> {
                    StringBuilder norm = new StringBuilder();
                    for (String part : times.getText().toString().split(",")) {
                        part = part.trim();
                        if (part.isEmpty()) continue;
                        int[] hm = PwaScheduler.parseTime(part);
                        if (hm == null) {
                            Toast.makeText(this, "時刻の形式が不正: " + part, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (norm.length() > 0) norm.append(",");
                        norm.append(String.format(java.util.Locale.US, "%02d:%02d", hm[0], hm[1]));
                    }
                    String schedule = norm.length() > 0 ? norm.toString() : null;
                    db.updateSchedule(p.id, schedule, queueOffline.isChecked());
                    BrowserDb.Pwa updated = db.getPwa(p.id);
                    PwaScheduler.rescheduleForPwa(getApplicationContext(), updated);
                    Toast.makeText(this, schedule != null ? "スケジュールを保存しました" : "スケジュールを解除しました",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("やめる", null)
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
        String u0 = web(t).getUrl();
        if (u0 == null || u0.startsWith("gobie://")) {
            Toast.makeText(this, "固定したいサイトを開いてから", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(8), dp(24), dp(8));
        final EditText name = new EditText(this);
        name.setHint("表示名");
        name.setSingleLine(true);
        name.setText(web(t).getTitle());
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
        final String[] cats = {"🔍 検索エンジン", "🛡 アドブロック", "📦 オフライン"};
        new AlertDialog.Builder(this)
                .setTitle("⚙ 設定")
                .setItems(cats, (d, w) -> {
                    if (w == 0) showSearchEngineSettings();
                    else if (w == 1) showAdblockSettings();
                    else showOfflineSettings();
                })
                .setNegativeButton("閉じる", null)
                .show();
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
        root.setPadding(dp(24), dp(8), dp(24), dp(8));
        final EditText in = new EditText(this);
        in.setHint("URLに含まれる文字列（例: ads.example.com, /banner/）");
        in.setSingleLine(true);
        root.addView(in);
        final ListView lv = new ListView(this);
        root.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(280)));

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
                    SnifferChrome.applyPageZoom(web(t), t.zoom);
                    db.setDomainZoom(hostOf(t.pageUrl), t.zoom); // このドメインでは以後自動適用
                    // PC幅ページはviewport幅変更後の再フィットが要るのでリロードで確定させる
                    web(t).reload();
                    d.dismiss();
                })
                .setNegativeButton("閉じる", null)
                .show();
    }

    /** URLからホスト名を取り出す（ドメイン別ズームのキー）。取れなければnull。 */
    private static String hostOf(String url) {
        try { return android.net.Uri.parse(url).getHost(); } catch (Throwable e) { return null; }
    }

    /** ページ読込時: このドメインの保存済みズームがあればそれを、無ければ既定100%を返す。 */
    private int domainZoomOrDefault(String url) {
        int z = db.getDomainZoom(hostOf(url));
        return z > 0 ? z : 100;
    }

    private void showPwaDialog(Tab t) {
        new AlertDialog.Builder(this)
                .setTitle("PWAとしてホームに追加")
                .setMessage((t.pageTitle == null || t.pageTitle.isEmpty() ? t.pageUrl : t.pageTitle)
                        + "\n\nこのサイトをstandaloneアプリ化する？")
                .setPositiveButton("追加", (d, w) -> PwaInstaller.install(this, web(t), t.pageUrl, t.pageTitle, t.desktop, t.zoom))
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
                row.setPadding(dp(8), dp(8), dp(8), dp(8));
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
        root.setPadding(dp(24), dp(8), dp(24), dp(8));
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
                dp(240)));

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
                col.setPadding(dp(24), dp(14), dp(24), dp(14));
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
        Tab t = curTab();
        if (t == null) return;
        WebView w = web(t);
        prepareUa(t, url); // 送出前にUAを決める（送出後に直してもサーバは既にモバイル判定済み）
        w.loadUrl(url);
    }

    /** これから開くURLのドメインがPC版指定なら、リクエストを出す前にUAを差し替えておく。
     *  Teamsのように「モバイルUAならエラーページへリダイレクト」するサイトは、
     *  読み込んでからUAを直しても手遅れで、エラーページを読み直すだけになる。 */
    private void prepareUa(Tab t, String url) {
        if (t == null || t.web == null || t.desktop) return;
        if (!db.isDomainDesktop(hostOf(url))) return;
        t.desktop = true;
        t.uaReloadedFor = url; // 直後のonPageStartedで二重に撃たない
        SnifferChrome.applyUaMode(t.web.getSettings(), true);
    }

    /** モバイル判定で飛ばされた「非対応ブラウザ」系の袋小路URLか。
     *  ここに居る時はreloadしても同じページなので、サイトのトップから入り直す必要がある。 */
    private static boolean isUnsupportedLanding(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(java.util.Locale.US);
        return u.contains("unsupported-browser") || u.contains("unsupportedbrowser")
                || u.contains("/unsupported");
    }

    /** そのURLのスキーム+ホストだけを取り出す（袋小路から入り直す先） */
    private static String originOf(String url) {
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            if (u.getScheme() == null || u.getHost() == null) return null;
            return u.getScheme() + "://" + u.getHost() + "/";
        } catch (Throwable e) { return null; }
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
                if (block != null) {
                    // メインフレームを空レスにすると白画面になる。通常は
                    // shouldOverrideUrlLoadingで遷移ごと止まるので、ここに来るのは
                    // 302連鎖の着地等。説明ページを返して「壊れた」を防ぐ
                    return req.isForMainFrame()
                            ? ad.blockedPage(req.getUrl().getHost()) : block;
                }
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
                // 広告ドメインへのページ遷移そのもの（クリックハイジャック等）は
                // ロード後に空レス化するのでなくナビゲーション自体を止めて
                // 現ページに留まる（空レス化＝白画面の主因だった）
                if (req.isForMainFrame() && ad.shouldBlock(req.getUrl()) != null) {
                    Toast.makeText(MainActivity.this,
                            "広告ドメインをブロック: " + req.getUrl().getHost(),
                            Toast.LENGTH_SHORT).show();
                    return true;
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
                    // null=JS報告なし: JS未動作ページを壊さないよう旧来のタッチ猶予で判定。
                    // リンククリック済みでも、飛び先がクリックしたリンクのサイトと
                    // 一致しなければタップ便乗のハイジャック遷移として遮断する
                    // （旧判定「リンクをクリックしてれば何処でも許可」が広告素通りの穴だった。
                    //   サーバー302連鎖は上のisServerRedirectで既に通している）
                    boolean allow = clicked != null
                            ? !clicked.isEmpty() && to.equals(ClickTracker.clickedSite(view))
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
                if (t == curTab()) runOnUiThread(() -> { setUrlBar(url); updateDlBtn(); });
                SnifferChrome.injectBlobGuard(view); // blob DL救済(revoke遅延)
                ClickTracker.inject(view); // クリック地点記録（リダイレクトブロック判定用）
                SnifferChrome.injectYoutubeAdblock(view, url); // YouTube動画内広告の除去(早期注入でJSON.parseフック)
                SnifferChrome.injectYoutubeNarrowFix(view, url); // www狭幅のはみ出し修正
                // ドメイン別に保存済みのズームがあれば自動適用（無ければ既定100%へ戻す）
                t.zoom = domainZoomOrDefault(url);
                SnifferChrome.applyPageZoom(view, t.zoom);
                // ドメイン別にPC版指定があればUAを入れ替えて読み直す。
                // UAはリクエスト送出時に決まるため、モバイルUAで取得済みのこのページは捨てるしかない。
                // 同一URLで2度目に入った時は再読込しない（リダイレクトとの往復で無限ループになるため）。
                if (!t.desktop && db.isDomainDesktop(hostOf(url)) && !url.equals(t.uaReloadedFor)) {
                    t.uaReloadedFor = url;
                    t.desktop = true;
                    SnifferChrome.applyUaMode(view.getSettings(), true);
                    String re = isUnsupportedLanding(url) ? originOf(url) : null;
                    if (re != null) view.loadUrl(re); // 袋小路ならトップから入り直す
                    else view.reload();
                    return;
                }
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
                SnifferChrome.applyPageZoom(view, t.zoom); // startedで消える場合の保険（同一値を再適用）
                ad.injectCosmetics(view, url); // 動的挿入対策に読み込み完了時も上書き注入
                db.addHistory(url, t.pageTitle);
                OfflineStore.get(MainActivity.this).autoSave(view, db, url, t.pageTitle);
                for (String js : UserScripts.get(MainActivity.this).forUrl(url, false))
                    view.evaluateJavascript(js, null);
            }
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // missav等のSPAはpushState/replaceStateで動画ページへ遷移し、onPageStarted/Finishedが
                // 発火しない（フルナビゲーションではないため）。t.pageUrlが直前の一覧ページ等で
                // 固着したままだと、動画URLをsniffした際のReferer代替値(Hits.sniff)が間違ったページを
                // 指し、CDN側のホットリンク判定(Referer検証)に落ちてDLが403で失敗する。
                // doUpdateVisitedHistoryはSPA遷移でも呼ばれるのでここでも追従させる。
                t.pageUrl = url;
                if (t == curTab()) runOnUiThread(() -> { setUrlBar(url); updateDlBtn(); });
            }
            // メインフレームの通信失敗は、WebView標準の素のエラー表示ではなく
            // 原因と「再試行」を示す画面にする（base URLを失敗URLにして再読込・履歴を保つ）
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req == null || !req.isForMainFrame()) return;
                int code = err.getErrorCode();
                if (code != WebViewClient.ERROR_HOST_LOOKUP && code != WebViewClient.ERROR_CONNECT
                        && code != WebViewClient.ERROR_TIMEOUT && code != WebViewClient.ERROR_IO) return;
                String failUrl = req.getUrl().toString();
                String why = code == WebViewClient.ERROR_HOST_LOOKUP
                        ? "サーバーが見つかりません。URLの綴りか、ネットワーク接続を確認してください。"
                        : code == WebViewClient.ERROR_TIMEOUT
                        ? "接続がタイムアウトしました。電波の良い場所で再試行してください。"
                        : "サイトに接続できません。ネットワーク接続を確認してください。";
                String html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                        + "<body style='background:#202124;color:#E8EAED;font-family:sans-serif;padding:32px 24px'>"
                        + "<h2 style='font-weight:500'>ページを開けません</h2>"
                        + "<p style='color:#9AA0A6;line-height:1.6'>" + why + "</p>"
                        + "<p style='color:#9AA0A6;font-size:12px;word-break:break-all'>"
                        + android.text.TextUtils.htmlEncode(failUrl) + "<br>" + android.text.TextUtils.htmlEncode(String.valueOf(err.getDescription())) + "</p>"
                        + "<button onclick='location.replace(" + org.json.JSONObject.quote(failUrl).replace("</", "<\\/") + ")' "
                        + "style='margin-top:16px;padding:0 24px;min-height:48px;border:0;border-radius:24px;background:#8AB4F8;color:#202124;font-size:16px;font-weight:600'>再試行</button>";
                view.loadDataWithBaseURL(failUrl, html, "text/html", "utf-8", failUrl);
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
                    h2.page = h.page;
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
        it.putExtra("page", h.page);
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

    /** ページ側のURL更新。ユーザーが入力中なら、その入力を絶対に上書きしない。 */
    private void setUrlBar(String url) {
        if (urlBar == null || urlBar.hasFocus()) return;
        urlBar.setText(url != null ? url : "");
    }

    private void go(String q) {
        q = q == null ? "" : q.trim();
        if (q.isEmpty()) return;
        String url;
        // IME/コピペ起因でスキームのコロンだけ落ちる入力(https//example.com)を
        // 素のドメインと誤認して "https://" を二重付加しないよう先に補正する
        if (q.matches("(?i)^ht{3}ps?://.*")) {
            // よくある入力ミス(htttp/htttps)を、意図したスキームへ戻す。
            q = q.replaceFirst("(?i)^ht+tps?://", q.toLowerCase(java.util.Locale.US).startsWith("htttps") ? "https://" : "http://");
        } else if (q.matches("(?i)^https?//.*")) q = q.replaceFirst("(?i)^(https?)//", "$1://");
        else if (q.matches("(?i)^https?:/[^/].*")) q = q.replaceFirst("(?i)^(https?):/", "$1://");
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
        // ファイルピッカーは開いたタブのchromeだけがfileCbを持つので全タブへ中継して問題ない
        // ただしタブ生成中（chrome未設定）の一瞬に結果が届くとNPEになるためnullガード必須
        for (Tab t : tabs) if (t.chrome != null) t.chrome.onFileResult(requestCode, resultCode, data);
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // geolocationの権限要求結果を、要求したタブのchromeへ中継する
        if (requestCode == SnifferChrome.REQ_GEO)
            for (Tab t : tabs) if (t.chrome != null) t.chrome.onGeoPermissionResult(requestCode, grantResults);
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Tab t = curTab();
            if (t != null) {
                if (t.chrome.closePopup()) return true; // 認証ポップアップ表示中
                if (t.chrome.exitFullscreen()) return true;
                if (web(t).canGoBack()) { web(t).goBack(); return true; }
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
