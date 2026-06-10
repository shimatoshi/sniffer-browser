package net.localhost.sniffer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * PWAキャッシュの自動リウォーム。
 *
 * WebViewはdurable storage未実装で navigator.storage.persist() が常にfalse
 * （AwPermissionManagerがDURABLE_STORAGEを無条件DENY、アプリ側フックも無い）。
 * そのためストレージ逼迫時にChromiumのQuotaManagerがSWキャッシュをLRU evictしうる。
 * evict自体は防げないので「evictされても自動回復」に倒す：
 * ピン留め済みPWAを不可視WebViewで順番に巡回 → キャッシュが消えていれば
 * 各サイトのSWが再登録→re-precacheして自己修復する。
 */
public class PwaWarmer {
    private static final String TAG = "PwaWarmer";
    private static final long COOLDOWN_MS = 24L * 3600 * 1000;
    private static final long DWELL_MS = 12_000;   // onPageFinished後、SWのinstall/precacheを待つ猶予
    private static final long TIMEOUT_MS = 35_000; // onPageFinishedが来ない場合の保険
    private static boolean running;

    /** 24hクールダウン付き巡回。MainActivity起動時に呼ぶ */
    static void maybeWarm(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("pwa_warmer", Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - sp.getLong("last", 0) < COOLDOWN_MS) return;
        warmNow(ctx, null);
    }

    /** クールダウン無視で即巡回。完了時にdone（null可）。 */
    static void warmNow(Context ctx, Runnable done) {
        final Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (running) { if (done != null) done.run(); return; }
            ArrayList<String> urls = collectPwaUrls(app);
            if (urls.isEmpty() || !isOnline(app)) {
                Log.i(TAG, "skip: urls=" + urls.size() + " online=" + isOnline(app));
                if (done != null) done.run();
                return;
            }
            running = true;
            app.getSharedPreferences("pwa_warmer", Context.MODE_PRIVATE)
                    .edit().putLong("last", System.currentTimeMillis()).apply();
            Log.i(TAG, "warm start: " + urls.size() + " PWAs");
            new Session(app, urls, done).next();
        });
    }

    /** ランチャーにピン留め済みの自アプリPWAショートカットからURLを収集 */
    private static ArrayList<String> collectPwaUrls(Context ctx) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (Build.VERSION.SDK_INT >= 25) {
            try {
                ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
                for (ShortcutInfo si : sm.getPinnedShortcuts()) {
                    Intent it = si.getIntent();
                    if (it != null && it.getComponent() != null && it.getData() != null
                            && PwaActivity.class.getName().equals(it.getComponent().getClassName()))
                        out.add(it.getData().toString());
                }
            } catch (Throwable t) {
                Log.e(TAG, "shortcut列挙失敗: " + t);
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean isOnline(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 1個の非表示WebViewでURLリストを順番に踏むセッション */
    private static class Session {
        final Context app;
        final ArrayList<String> urls;
        final Runnable done;
        final Handler ui = new Handler(Looper.getMainLooper());
        final Runnable advance = this::next;
        WebView web;
        int i = -1;

        @SuppressWarnings("SetJavaScriptEnabled")
        Session(Context app, ArrayList<String> urls, Runnable done) {
            this.app = app;
            this.urls = urls;
            this.done = done;
            web = new WebView(app);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(true); // 巡回中に動画が鳴らないように
            web.setWebViewClient(new WebViewClient() {
                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(
                        WebView v, android.webkit.WebResourceRequest req) {
                    return AdBlocker.get(app).shouldBlock(req.getUrl());
                }
                @Override
                public void onPageFinished(WebView v, String url) {
                    // SWのinstall/precacheはロード完了後に走るので少し置いてから次へ
                    ui.removeCallbacks(advance);
                    ui.postDelayed(advance, DWELL_MS);
                }
            });
        }

        void next() {
            ui.removeCallbacks(advance);
            i++;
            if (i >= urls.size()) { finish(); return; }
            Log.i(TAG, "warm [" + (i + 1) + "/" + urls.size() + "] " + urls.get(i));
            ui.postDelayed(advance, TIMEOUT_MS);
            web.loadUrl(urls.get(i));
        }

        void finish() {
            Log.i(TAG, "warm done: " + urls.size() + " PWAs");
            try {
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignore) {}
            web = null;
            running = false;
            // 巡回直後＝全PWAのキャッシュが健全な状態なのでスナップショット更新の好機
            SwBackup.maybeBackup(app);
            if (done != null) done.run();
        }
    }
}
