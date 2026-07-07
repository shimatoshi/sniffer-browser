package net.localhost.sniffer;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * PWA standalone窓。ホームのショートカットから ACTION_VIEW + data URL で起動。
 * URLバー無し・theme_colorステータスバー・recentsで独立タスク（documentLaunchMode=intoExisting）。
 * 傍受(Hits)とCDPデバッグはMainActivityと共通で生きる。
 */
public class PwaActivity extends Activity {

    private SnifferWebView web;
    private SnifferChrome chrome;
    private FrameLayout browseOverlay; // window.open時に重ねる「枠付き別WebView」
    private SnifferWebView overlayWeb;
    private String homeHost = "";
    private volatile String pageUrl = "";
    private volatile String pageTitle = "";
    private volatile String ua = "";
    private volatile boolean mediaPlaying = false;
    private volatile int videoW, videoH;
    private boolean inPip = false;
    private boolean started = false;
    private boolean desktop = false; // PC版サイト表示（デスクトップUA）で開くPWA
    private int zoom = 100; // ページズーム%（PC版=viewport幅上書き、モバイル版=textZoom）

    // YouTube: www起点PWAがモバイルUAでm.youtube.comへ飛ばされるのを差し戻す用
    private int ytRewrites = 0;
    private long ytRewriteAt = 0;

    /**
     * www.youtube.com起点のPWAで m.youtube.com へ飛ばされたら www へ書き戻したURLを返す。
     * m.youtube.comのプレイヤーは裏に回ると強制pause＆全画面解除でPiP/バックグラウンド再生
     * 不能のため、wwwのプレイヤーに固定する。app=desktopを付けるとモバイルUAでも
     * mへ飛ばされず(302も502も回避)wwwプレイヤーが確定する。
     */
    private String unMobileYoutube(String url) {
        if (!"www.youtube.com".equalsIgnoreCase(homeHost)) return null;
        try {
            if (!"m.youtube.com".equalsIgnoreCase(Uri.parse(url).getHost())) return null;
            return withDesktopParam(url.replaceFirst("//m\\.youtube\\.com", "//www.youtube.com"));
        } catch (Throwable ignore) { return null; }
    }

    /** YouTube URLに app=desktop を付与（既にあればそのまま） */
    private static String withDesktopParam(String url) {
        if (url.contains("app=desktop")) return url;
        return url + (url.contains("?") ? "&" : "?") + "app=desktop";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent it = getIntent();
        String url = (it != null && it.getData() != null) ? it.getData().toString() : "about:blank";
        String title = it != null ? it.getStringExtra("pwa_title") : null;
        String theme = it != null ? it.getStringExtra("pwa_theme") : null;
        desktop = it != null && it.getBooleanExtra("pwa_desktop", false);
        zoom = it != null ? it.getIntExtra("pwa_zoom", 100) : 100;
        // 旧ショートカット(extra無し)からの起動は台帳のフラグで判定
        if (!desktop && zoom == 100) {
            try {
                BrowserDb.Pwa p = new BrowserDb(getApplicationContext()).getPwaByUrl(url);
                if (p != null) { desktop = p.desktop; zoom = p.zoom; }
            } catch (Throwable ignore) {}
        }

        try { homeHost = Uri.parse(url).getHost(); } catch (Throwable ignore) {}
        if (homeHost == null) homeHost = "";
        // YouTube www起点: app=desktopでwwwプレイヤー固定(PiP/バックグラウンド再生可能な方)
        if ("www.youtube.com".equalsIgnoreCase(homeHost)) url = withDesktopParam(url);
        pageUrl = url;

        web = new SnifferWebView(this); // バックグラウンド再生対応
        setContentView(web);
        setupWeb();

        // recents上で独立アプリらしく見せる
        int color = parseColor(theme, 0xFF263238);
        if (title == null || title.isEmpty()) title = homeHost;
        setTaskDescription(new ActivityManager.TaskDescription(title, null, color));
        getWindow().setStatusBarColor(color);
        net.localhost.debugnote.DebugNote.attach(this, "pwa-" + title);

        web.loadUrl(url);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWeb() {
        if (Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (desktop || zoom != 100) {
            // PC版PWA/ズーム指定PWA: 980px幅レイアウトやviewport幅上書き(applyPageZoom)を
            // 効かせるためwide viewport+ピンチズームを有効化
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
        } else {
            // wide viewport/overview無効: PWAはviewport meta持ち前提。有効だとYouTube www等で
            // 読み込み中に一瞬広がったコンテンツへoverviewがズームアウトしたまま固着し
            // (pageScale 0.897)、position:fixed要素の基準幅が438pxになって左右が見切れる
            s.setLoadWithOverviewMode(false);
            s.setUseWideViewPort(false);
        }
        s.setSupportMultipleWindows(true); // window.open/target=_blank をonCreateWindowで受ける
        if (Build.VERSION.SDK_INT >= 21)
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        if (desktop) SnifferChrome.applyUaMode(s, true); // 言語切替等のリロードでもPC版に留める
        else SnifferChrome.applyChromeUa(s);
        // ズームはロードフックのapplyPageZoomがページ種別(レスポンシブ/PC幅)で適用方式を決める
        SnifferChrome.applyGeolocation(this, s);
        ua = s.getUserAgentString();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);

        SnifferChrome.enableDownloads(this, web);
        SnifferChrome.enableLongPress(this, web, null); // PWA窓はタブ無し=リンクはコピーのみ

        // バックグラウンド再生: 再生状態を監視し、裏に回ったら前面サービスで延命する
        Media.track(web, new Media.PlayState() {
            @Override public void onPlaying(boolean playing) {
                mediaPlaying = playing;
                web.setKeepPlaying(playing); // 裏でもメディアを止めない(バックグラウンド再生)
                runOnUiThread(PwaActivity.this::syncPlaybackService);
            }
            @Override public void onVideoSize(int w, int h) {
                videoW = w; videoH = h;
            }
        });

        ClickTracker.install(web); // ポップアップ遮断判定(SnifferChrome)がクリック記録を参照する

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                WebResourceResponse block = AdBlocker.get(PwaActivity.this).shouldBlock(req.getUrl());
                if (block != null) {
                    // メインフレームの空レス化は白画面になるので説明ページを返す
                    return req.isForMainFrame()
                            ? AdBlocker.get(PwaActivity.this).blockedPage(req.getUrl().getHost())
                            : block;
                }
                Hits.sniff(req, pageUrl, pageTitle, ua);
                return null;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                // 非Webスキーム(独自スキーム/intent:/mailto:/tel:等)だけ外部アプリへ委譲。
                // ログイン連携の戻りcallback等はこれで外部に渡る。
                if (!isWeb(u)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Throwable ignore) {}
                    return true; // 未対応スキームでWebViewをERR_UNKNOWN_URL_SCHEMEにしない
                }
                // YouTube: m.youtube.comへのJS遷移をwwwへ差し戻す
                String w = unMobileYoutube(u.toString());
                if (w != null) { view.loadUrl(w); return true; }
                // http(s)はスコープ内外を問わずPWA内WebViewで開く。スコープ外を外部ブラウザへ
                // 蹴り出すと別タスクで真っ白になりPWAに戻れない(別オリジンのbackend/動画CDNで頻発)。
                // 添付ファイルDLはDownloadListenerが拾い、遷移はキャンセルされPWAが保持される。
                return false;
            }
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap f) {
                // YouTube: サーバー302でmへ飛ばされた場合もwwwへ差し戻す(ループ保険つき)
                String w = unMobileYoutube(url);
                if (w != null) {
                    long now = System.currentTimeMillis();
                    if (now - ytRewriteAt > 10000) ytRewrites = 0;
                    ytRewriteAt = now;
                    if (++ytRewrites <= 3) {
                        Dbg.log(PwaActivity.this, "PWA yt guard: " + url + " -> www (try " + ytRewrites + ")");
                        view.stopLoading();
                        view.loadUrl(w);
                        return;
                    }
                    Dbg.log(PwaActivity.this, "PWA yt guard: give up, stay on m");
                }
                pageUrl = url;
                SnifferChrome.injectClientHints(view); // userAgentDataのWebView申告をChrome偽装(OAuth承認ボタン無効化回避)
                SnifferChrome.injectBlobGuard(view); // blob DL救済(revoke遅延)
                ClickTracker.inject(view); // クリック地点記録（ポップアップ遮断判定用）
                SnifferChrome.injectYoutubeAdblock(view, url); // YouTube動画内広告の除去(YouTube PWA対応)
                SnifferChrome.injectYoutubeNarrowFix(view, url); // www狭幅のはみ出し修正
                if (zoom != 100) SnifferChrome.applyPageZoom(view, zoom);
                AdBlocker.get(PwaActivity.this).injectCosmetics(view, url); // 要素隠し早期注入
                for (String js : UserScripts.get(PwaActivity.this).forUrl(url, true))
                    view.evaluateJavascript(js, null);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                pageUrl = url;
                pageTitle = view.getTitle();
                Media.injectTracker(view);
                SnifferChrome.injectYoutubeNarrowFix(view, url); // started時は旧documentで消えるため再注入
                if (zoom != 100) SnifferChrome.applyPageZoom(view, zoom);
                AdBlocker.get(PwaActivity.this).injectCosmetics(view, url); // 動的挿入対策の上書き注入
                for (String js : UserScripts.get(PwaActivity.this).forUrl(url, false))
                    view.evaluateJavascript(js, null);
            }
        });

        chrome = new SnifferChrome(this, web) {
            @Override public void onReceivedTitle(WebView view, String title) {
                pageTitle = title;
            }
            @Override protected void openUrl(String url) {
                // window.open/target=_blank。非Webスキームは外部アプリへ、http(s)は枠付きオーバーレイで開く
                Uri u = null;
                try { u = Uri.parse(url); } catch (Throwable ignore) {}
                if (u != null && !isWeb(u)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Throwable ignore) {}
                    return;
                }
                openBrowseOverlay(url); // PWAを保持したまま別WebViewを重ねる
            }
        };
        web.setWebChromeClient(chrome);
    }

    private static int parseColor(String c, int fallback) {
        if (c == null || c.isEmpty()) return fallback;
        try { return Color.parseColor(c.trim()); } catch (Throwable ignore) { return fallback; }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    /**
     * window.open/target=_blank を「枠付きの別WebView」を重ねて開く。
     * 下のPWAはそのまま保持され、✕/BACKでいつでも元の画面へ戻れる。
     * ネイティブWebViewはiframeでないのでX-Frame-Options無関係にどのサイトも表示できる。
     */
    private void openBrowseOverlay(String url) {
        if (browseOverlay != null) { overlayWeb.loadUrl(url); return; } // 既に開いていれば遷移

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFF14151A);
        overlay.setClickable(true); // 下のPWAへのタップ貫通を防ぐ

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1E2025);
        bar.setPadding(dp(8), dp(4), dp(4), dp(4));

        final TextView host = new TextView(this);
        host.setTextColor(0xFF9A9EA8);
        host.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        host.setSingleLine(true);
        try { host.setText(Uri.parse(url).getHost()); } catch (Throwable ignore) {}

        Button close = new Button(this);
        close.setText("✕ 閉じる");
        close.setAllCaps(false);
        close.setTextColor(0xFFE23B2E);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        close.setBackgroundColor(0x00000000);
        close.setOnClickListener(v -> closeBrowseOverlay());

        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(host, hp);
        bar.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        overlayWeb = new SnifferWebView(this);
        setupOverlayWeb(overlayWeb, host);

        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(overlayWeb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        overlay.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ((FrameLayout) getWindow().getDecorView()).addView(overlay,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        browseOverlay = overlay;
        overlayWeb.loadUrl(url);
    }

    private void closeBrowseOverlay() {
        if (browseOverlay == null) return;
        ((FrameLayout) getWindow().getDecorView()).removeView(browseOverlay);
        if (overlayWeb != null) { try { overlayWeb.destroy(); } catch (Throwable ignore) {} overlayWeb = null; }
        browseOverlay = null;
    }

    /** オーバーレイWebViewの最小構成（sniff/広告ブロック/DL/Chrome UAは本体と共通の静的ヘルパを流用）。 */
    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupOverlayWeb(final SnifferWebView w, final TextView hostLabel) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false); // オーバーレイ内のwindow.openは同WebViewで開く
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        SnifferChrome.applyChromeUa(s);
        SnifferChrome.applyGeolocation(this, s);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(w, true);
        SnifferChrome.enableDownloads(this, w);
        final String[] ourl = { "" };
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                WebResourceResponse block = AdBlocker.get(PwaActivity.this).shouldBlock(req.getUrl());
                if (block != null)
                    return req.isForMainFrame()
                            ? AdBlocker.get(PwaActivity.this).blockedPage(req.getUrl().getHost())
                            : block;
                Hits.sniff(req, ourl[0], null, ua);
                return null;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!isWeb(u)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Throwable ignore) {}
                    return true;
                }
                return false;
            }
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap f) {
                ourl[0] = url;
                SnifferChrome.injectClientHints(view);
                AdBlocker.get(PwaActivity.this).injectCosmetics(view, url);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                ourl[0] = url;
                AdBlocker.get(PwaActivity.this).injectCosmetics(view, url);
                try { hostLabel.setText(Uri.parse(url).getHost()); } catch (Throwable ignore) {}
            }
        });
        w.setWebChromeClient(new WebChromeClient());
    }

    /** http/https か（それ以外=独自スキーム等は外部アプリへ委譲する） */
    private static boolean isWeb(Uri u) {
        if (u == null) return false;
        String s = u.getScheme();
        return "http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s);
    }

    // ---- PiP: 全画面動画中にHome/Recentsで小窓化 ----

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (chrome != null && chrome.isInFullscreen())
            Media.enterPip(this, videoW, videoH);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, android.content.res.Configuration cfg) {
        super.onPictureInPictureModeChanged(isInPip, cfg);
        inPip = isInPip;
        // WebViewはPiP突入直後にHTML5全画面を強制解除するため、CSSで動画を全面固定する
        Media.setPipLayout(web, isInPip);
        // PiP中はプロセスが生きているので前面サービスは不要。抜けたら再判定。
        syncPlaybackService();
    }

    // ---- バックグラウンド再生: 裏に回り再生中なら前面サービスで延命 ----

    @Override
    protected void onStop() {
        started = false;
        super.onStop();
        syncPlaybackService();
    }

    @Override
    protected void onStart() {
        started = true;
        super.onStart();
        syncPlaybackService();
    }

    @Override
    protected void onDestroy() {
        closeBrowseOverlay();
        Media.stopPlaybackService(this);
        super.onDestroy();
    }

    /** 「裏にいて・PiPでなく・再生中」のときだけ前面サービスを立てる。それ以外は畳む */
    private void syncPlaybackService() {
        if (mediaPlaying && !started && !inPip) {
            String t = (pageTitle == null || pageTitle.isEmpty()) ? homeHost : pageTitle;
            Media.startPlaybackService(this, t);
        } else {
            Media.stopPlaybackService(this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (chrome != null) chrome.onFileResult(requestCode, resultCode, data);
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == SnifferChrome.REQ_GEO && chrome != null)
            chrome.onGeoPermissionResult(requestCode, grantResults);
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (browseOverlay != null) { // オーバーレイ優先: 中で戻れれば戻り、無ければ閉じる
                if (overlayWeb != null && overlayWeb.canGoBack()) { overlayWeb.goBack(); return true; }
                closeBrowseOverlay();
                return true;
            }
            if (chrome != null && chrome.exitFullscreen()) return true;
            if (web.canGoBack()) { web.goBack(); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }
}
