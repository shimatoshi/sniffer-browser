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
        ua = s.getUserAgentString();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);

        SnifferChrome.enableDownloads(this, web);
        SnifferChrome.enableImageSave(this, web);

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
                // PWAのスコープ外（別ホスト）は既定ブラウザへ逃がす
                Uri u = req.getUrl();
                String h = u != null ? u.getHost() : null;
                if (h != null && !h.equalsIgnoreCase(homeHost)) {
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
                for (String js : UserScripts.get(PwaActivity.this).forUrl(url, true))
                    view.evaluateJavascript(js, null);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                pageUrl = url;
                pageTitle = view.getTitle();
                for (String js : UserScripts.get(PwaActivity.this).forUrl(url, false))
                    view.evaluateJavascript(js, null);
            }
        });

        chrome = new SnifferChrome(this, web) {
            @Override public void onReceivedTitle(WebView view, String title) {
                pageTitle = title;
            }
            @Override protected void openUrl(String url) {
                // PWAスコープ外（別ホスト）への新規窓は既定ブラウザへ
                String h = null;
                try { h = Uri.parse(url).getHost(); } catch (Throwable ignore) {}
                if (h != null && !h.equalsIgnoreCase(homeHost)) {
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
