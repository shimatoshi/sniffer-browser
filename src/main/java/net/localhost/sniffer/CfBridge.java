package net.localhost.sniffer;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * surrit.com等、Cloudflare Bot ManagementがTLS/HTTP2フィンガープリントで
 * com.android.okhttp(HttpURLConnection)の生アクセスを弾くCDN向けの救済経路。
 *
 * 実測(2026-07-21): 同一UA/Refererヘッダでも、curl(現代的なTLSスタック)は200、
 * アプリの HttpURLConnection は403。ヘッダの問題ではなくTLS層のクライアント判別。
 * 本物のWebView(Chromium)のfetch()を経由させれば同じ判別を通過できることを確認済み。
 *
 * 注意: evaluateJavascript()の戻り値はPromiseの解決を待たない（同期的な評価結果、
 * つまりPromiseオブジェクト自体のJSON化="{}"を即返す）。非同期結果は
 * addJavascriptInterface経由でJS側から能動的にコールバックさせて受け取る。
 *
 * HeadlessBrowserServiceと同じ「画面外オーバーレイWindow+VISIBLE」パターンを使う
 * （未アタッチの裸WebViewはレンダラが止まりJSタイマーが効かないことがあるため）。
 */
final class CfBridge {

    private final WebView web;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, AtomicReference<String>> results = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CountDownLatch> latches = new ConcurrentHashMap<>();
    private final AtomicInteger reqSeq = new AtomicInteger();

    final class JsBridge {
        @JavascriptInterface
        public void onResult(String id, String data) {
            AtomicReference<String> ref = results.get(id);
            if (ref != null) ref.set(data);
            CountDownLatch l = latches.get(id);
            if (l != null) l.countDown();
        }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    CfBridge(Context ctx, String pageUrl, long loadTimeoutMs) throws Exception {
        final CountDownLatch loaded = new CountDownLatch(1);
        final AtomicReference<WebView> webRef = new AtomicReference<>();
        final AtomicReference<WindowManager> wmRef = new AtomicReference<>();
        final Context app = ctx.getApplicationContext();

        ui.post(() -> {
            try {
                WebView w = new WebView(app);
                WebSettings s = w.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                SnifferChrome.applyChromeUa(s);
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(w, true);
                w.addJavascriptInterface(new JsBridge(), "AndroidBridge");
                w.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String url) { loaded.countDown(); }
                });
                w.setVisibility(android.view.View.VISIBLE);

                WindowManager m = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                DisplayMetrics dm = new DisplayMetrics();
                m.getDefaultDisplay().getMetrics(dm);
                int type = Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        dm.widthPixels, dm.heightPixels, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.OPAQUE);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = -dm.widthPixels;
                lp.y = 0;
                m.addView(w, lp);

                webRef.set(w);
                wmRef.set(m);
                w.loadUrl(pageUrl);
            } catch (Throwable t) {
                loaded.countDown(); // await側で失敗させる(webRefがnullのまま)
            }
        });

        if (!loaded.await(loadTimeoutMs, TimeUnit.MILLISECONDS) || webRef.get() == null)
            throw new Exception("CfBridge: ページ読込タイムアウト/失敗 " + pageUrl);
        this.web = webRef.get();
        this.wm = wmRef.get();
    }

    /** ブリッジページのJSコンテキストからfetch()でバイト列を取得する（同期化）。
     *  結果はAndroidBridge.onResult()経由でJSから能動的に返させる（evaluateJavascriptの
     *  戻り値はPromise未解決のまま即返るため使えない） */
    byte[] fetch(String url, long timeoutMs) throws Exception {
        String id = "r" + reqSeq.incrementAndGet();
        AtomicReference<String> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        results.put(id, out);
        latches.put(id, done);
        try {
            String js = "fetch(" + org.json.JSONObject.quote(url) + ")"
                    + ".then(function(r){if(!r.ok)throw 'http'+r.status;return r.arrayBuffer();})"
                    + ".then(function(buf){var b=new Uint8Array(buf);var s='';"
                    + "for(var i=0;i<b.length;i+=8192)s+=String.fromCharCode.apply(null,b.subarray(i,i+8192));"
                    + "AndroidBridge.onResult(" + org.json.JSONObject.quote(id) + ", btoa(s));})"
                    + ".catch(function(e){AndroidBridge.onResult(" + org.json.JSONObject.quote(id) + ", 'ERR:'+e);});";
            ui.post(() -> web.evaluateJavascript(js, null)); // 結果はonResult経由で受け取るので戻り値は無視

            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new Exception("CfBridge: fetch timeout " + url);
            String v = out.get();
            if (v == null) throw new Exception("CfBridge: fetch結果なし " + url);
            if (v.startsWith("ERR:")) throw new Exception("CfBridge: " + v + " " + url);
            return Base64.decode(v, Base64.DEFAULT);
        } finally {
            results.remove(id);
            latches.remove(id);
        }
    }

    void destroy() {
        ui.post(() -> {
            try { if (wm != null) wm.removeView(web); } catch (Throwable ignore) {}
            try { web.destroy(); } catch (Throwable ignore) {}
        });
    }
}
