package net.localhost.sniffer;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
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

    private WebView web;
    private SnifferChrome chrome;
    private String homeHost = "";
    private volatile String pageUrl = "";
    private volatile String pageTitle = "";
    private volatile String ua = "";
    private volatile boolean mediaPlaying = false;
    private volatile int videoW, videoH;
    private boolean inPip = false;
    private boolean started = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent it = getIntent();
        String url = (it != null && it.getData() != null) ? it.getData().toString() : "about:blank";
        String title = it != null ? it.getStringExtra("pwa_title") : null;
        String theme = it != null ? it.getStringExtra("pwa_theme") : null;

        try { homeHost = Uri.parse(url).getHost(); } catch (Throwable ignore) {}
        if (homeHost == null) homeHost = "";
        pageUrl = url;

        web = new WebView(this);
        setContentView(web);
        setupWeb();

        // recents上で独立アプリらしく見せる
        int color = parseColor(theme, 0xFF263238);
        if (title == null || title.isEmpty()) title = homeHost;
        setTaskDescription(new ActivityManager.TaskDescription(title, null, color));
        getWindow().setStatusBarColor(color);

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
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(true); // window.open/target=_blank をonCreateWindowで受ける
        if (Build.VERSION.SDK_INT >= 21)
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        SnifferChrome.applyChromeUa(s);
        ua = s.getUserAgentString();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);

        SnifferChrome.enableDownloads(this, web);
        SnifferChrome.enableImageSave(this, web);

        // バックグラウンド再生: 再生状態を監視し、裏に回ったら前面サービスで延命する
        Media.track(web, new Media.PlayState() {
            @Override public void onPlaying(boolean playing) {
                mediaPlaying = playing;
                runOnUiThread(PwaActivity.this::syncPlaybackService);
            }
            @Override public void onVideoSize(int w, int h) {
                videoW = w; videoH = h;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                WebResourceResponse block = AdBlocker.get(PwaActivity.this).shouldBlock(req.getUrl());
                if (block != null) return block;
                Hits.sniff(req, pageUrl, pageTitle, ua);
                return null;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // PWAスコープ外（別サイト）は既定ブラウザへ逃がす。
                // 同一サイト(=同じ登録ドメイン)のバックエンドはスコープ内扱い→WebViewで開き、
                // 添付ファイルはDownloadListenerが拾う。これでDLが外部ブラウザに飛ぶのを防ぐ。
                Uri u = req.getUrl();
                String h = u != null ? u.getHost() : null;
                if (h != null && !inScope(h)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                        return true;
                    } catch (Throwable ignore) {}
                }
                return false;
            }
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap f) {
                pageUrl = url;
                SnifferChrome.injectBlobGuard(view); // blob DL救済(revoke遅延)
                for (String js : UserScripts.get(PwaActivity.this).forUrl(url, true))
                    view.evaluateJavascript(js, null);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                pageUrl = url;
                pageTitle = view.getTitle();
                Media.injectTracker(view);
                for (String js : UserScripts.get(PwaActivity.this).forUrl(url, false))
                    view.evaluateJavascript(js, null);
            }
        });

        chrome = new SnifferChrome(this, web) {
            @Override public void onReceivedTitle(WebView view, String title) {
                pageTitle = title;
            }
            @Override protected void openUrl(String url) {
                // PWAスコープ外（別サイト）への新規窓は既定ブラウザへ
                String h = null;
                try { h = Uri.parse(url).getHost(); } catch (Throwable ignore) {}
                if (h != null && !inScope(h)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        return;
                    } catch (Throwable ignore) {}
                }
                super.openUrl(url);
            }
        };
        web.setWebChromeClient(chrome);
    }

    private static int parseColor(String c, int fallback) {
        if (c == null || c.isEmpty()) return fallback;
        try { return Color.parseColor(c.trim()); } catch (Throwable ignore) { return fallback; }
    }

    /** 同一サイト(=同じ登録ドメイン)ならスコープ内。front/backendでホストが違っても拾う */
    private boolean inScope(String host) {
        return AdBlocker.site(host).equalsIgnoreCase(AdBlocker.site(homeHost));
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (chrome != null && chrome.exitFullscreen()) return true;
            if (web.canGoBack()) { web.goBack(); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }
}
