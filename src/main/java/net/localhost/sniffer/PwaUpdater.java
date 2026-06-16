package net.localhost.sniffer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * PWAの「アップデート」。
 *
 * WebViewのHTTPキャッシュを全消し（clearCache）＋ 対象オリジンのService Worker登録解除と
 * Cache Storage全削除を不可視WebView内のJSで実行し、リロードで新バージョンを取り直す。
 * SWが消えた状態で再ロード→SWが再登録され最新ビルドをprecacheし直すので実質アップデート。
 */
public class PwaUpdater {
    private static final String TAG = "PwaUpdater";
    private static final long CLEAR_WAIT_MS = 2500;  // 登録解除/caches削除の完了待ち
    private static final long DWELL_MS = 12_000;      // 再ロード後、SW再precacheの猶予
    private static final long TIMEOUT_MS = 45_000;    // 全体の保険

    // 対象オリジンのSW登録解除 + Cache Storage全削除
    private static final String CLEAR_JS =
            "(async function(){"
            + "try{if(navigator.serviceWorker){var rs=await navigator.serviceWorker.getRegistrations();"
            + "for(var i=0;i<rs.length;i++){try{await rs[i].unregister();}catch(e){}}}}catch(e){}"
            + "try{if(window.caches){var ks=await caches.keys();"
            + "for(var j=0;j<ks.length;j++){try{await caches.delete(ks[j]);}catch(e){}}}}catch(e){}"
            + "return 'ok';})()";

    /** UI/任意スレッドから呼んでよい。完了時にトースト。 */
    @SuppressWarnings("SetJavaScriptEnabled")
    static void update(Context ctx, String url, String name) {
        final Context app = ctx.getApplicationContext();
        final String label = (name == null || name.isEmpty()) ? url : name;
        final Handler ui = new Handler(Looper.getMainLooper());
        ui.post(() -> {
            Toast.makeText(app, "🔄 " + label + " をアップデート中…", Toast.LENGTH_SHORT).show();
            final WebView web = new WebView(app);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(true);
            web.clearCache(true); // アプリ共有HTTPキャッシュを破棄（旧アセットを引かせない）

            final boolean[] done = {false};
            final int[] phase = {0};
            final Runnable finish = new Runnable() {
                @Override public void run() {
                    if (done[0]) return;
                    done[0] = true;
                    ui.removeCallbacksAndMessages(null);
                    try { web.loadUrl("about:blank"); web.destroy(); } catch (Throwable ignore) {}
                    Toast.makeText(app, "✓ " + label + " をアップデートしました", Toast.LENGTH_SHORT).show();
                    Log.i(TAG, "update done: " + url);
                }
            };

            web.setWebViewClient(new WebViewClient() {
                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(
                        WebView v, android.webkit.WebResourceRequest req) {
                    return AdBlocker.get(app).shouldBlock(req.getUrl());
                }
                @Override
                public void onPageFinished(WebView v, String u) {
                    if (done[0]) return;
                    if (phase[0] == 0) {
                        phase[0] = 1;
                        // 1回目: SW登録解除 + Cache Storage削除 → 少し待ってリロード
                        v.evaluateJavascript(CLEAR_JS, null);
                        ui.postDelayed(() -> { if (!done[0]) v.reload(); }, CLEAR_WAIT_MS);
                    } else if (phase[0] == 1) {
                        phase[0] = 2;
                        // 2回目: 新SWのprecacheを待ってから後始末
                        ui.postDelayed(finish, DWELL_MS);
                    }
                }
            });

            ui.postDelayed(finish, TIMEOUT_MS); // 保険
            Log.i(TAG, "update start: " + url);
            web.loadUrl(url);
        });
    }
}
