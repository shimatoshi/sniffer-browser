package net.localhost.sniffer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * ヘッドレス常駐ブラウザ。
 *
 * WebViewをActivityではなくフォアグラウンドServiceが持つWindowManagerオーバーレイに置く。
 *  - フォアグラウンドService → 背面でもLMKに殺されない（Activity版は3aで即kill）
 *  - 画面外(x=-width)のオーバーレイ → ユーザーの別作業を邪魔しない
 *  - VISIBLE維持 → レンダラが動き続けるのでCDPのnavigate/eval/captureScreenshotが効く
 *  - PARTIAL_WAKE_LOCK保持 → 画面消灯(Doze/Asleep)でもCPUを起こし続け、外部からのadb画面起こしに頼らず
 *    バックグラウンドのままnavigate/evalが効くようにする（画面は点けない＝ユーザー作業は邪魔しない）
 *
 * 外部からはCDP(devtools socket)経由でcdp.pyが駆動する。このServiceは「殺されない器」を提供するだけ。
 */
public class HeadlessBrowserService extends Service {

    private static final String CH = "headless";
    private static final int NID = 7777;
    private static final long WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L; // 万一解放漏れしても30分で自然失効する安全弁
    public static final String ACTION_STOP = "net.localhost.sniffer.HEADLESS_STOP";
    public static final String ACTION_WARM = "net.localhost.sniffer.WARM_PWAS";
    public static final String ACTION_WARM_ONE = "net.localhost.sniffer.WARM_ONE_PWA";
    // PwaActivityが生きている間だけ前面サービスに相乗りしてプロセスをLMKから守る
    // （このServiceと同一プロセスなので、web/wmは一切触らずforeground状態だけ間借りする）
    public static final String ACTION_PWA_ATTACH = "net.localhost.sniffer.PWA_ATTACH";
    public static final String ACTION_PWA_DETACH = "net.localhost.sniffer.PWA_DETACH";

    private WebView web;
    private WindowManager wm;
    private PowerManager.WakeLock wakeLock;
    private int pwaAttachCount = 0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sniffer:HeadlessBrowser");
            wakeLock.setReferenceCounted(false);
        }
        // acquire(timeout)は毎回呼んでも良い（保持中なら期限を延長するだけ）
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ---- PwaActivity生存中の延命（前面サービスへの相乗り） ----

    public static void attachPwa(Context c) {
        Intent it = new Intent(c, HeadlessBrowserService.class).setAction(ACTION_PWA_ATTACH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(it);
        else c.startService(it);
    }

    public static void detachPwa(Context c) {
        try {
            c.startService(new Intent(c, HeadlessBrowserService.class).setAction(ACTION_PWA_DETACH));
        } catch (Throwable ignore) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // startForegroundService経由で来るので、先にstartForegroundしないと
            // RemoteServiceException（5秒ルール違反）でプロセスごとクラッシュする
            ensureChannel();
            startForeground(NID, build());
            if (pwaAttachCount == 0) {
                releaseWakeLock();
                stopForeground(true);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_WARM.equals(intent.getAction())) {
            // PWAリウォームの外部トリガー（adb/CLI用）。巡回中はフォアグラウンドで
            // プロセスを守り、ヘッドレス本体が動いていなければ終了時に畳む
            ensureChannel();
            startForeground(NID, build());
            acquireWakeLock();
            PwaWarmer.warmNow(this, () -> {
                if (web == null && pwaAttachCount == 0) { releaseWakeLock(); stopForeground(true); stopSelf(); }
            });
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_WARM_ONE.equals(intent.getAction())) {
            // PwaScheduler（PWA個別スケジュール）が定刻オンライン時に呼ぶ単発ウォーム
            ensureChannel();
            startForeground(NID, build());
            acquireWakeLock();
            PwaWarmer.warmOne(this, intent.getStringExtra("url"), () -> {
                if (web == null && pwaAttachCount == 0) { releaseWakeLock(); stopForeground(true); stopSelf(); }
            });
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_PWA_ATTACH.equals(intent.getAction())) {
            // 表示中のPWAタブ数ぶんカウントするだけ。webは触らずforeground状態だけ間借りする
            ensureChannel();
            startForeground(NID, build());
            pwaAttachCount++;
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_PWA_DETACH.equals(intent.getAction())) {
            if (pwaAttachCount > 0) pwaAttachCount--;
            if (pwaAttachCount == 0 && web == null) {
                releaseWakeLock();
                stopForeground(true);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        ensureChannel();
        startForeground(NID, build());
        acquireWakeLock();

        if (web == null) ui.post(() -> setupWeb(
                intent != null ? intent.getStringExtra("url") : null));
        else if (intent != null && intent.getStringExtra("url") != null) {
            final String u = intent.getStringExtra("url");
            ui.post(() -> web.loadUrl(u));
        }
        return START_STICKY; // 殺されても復活させる
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWeb(String url) {
        if (Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(true);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setDatabaseEnabled(true);
        if (Build.VERSION.SDK_INT >= 21)
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        SnifferChrome.applyChromeUa(s);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() { // WebView内で完結
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView v, android.webkit.WebResourceRequest req) {
                // gobie経由のスクレイピングでも広告を落として軽くする
                return AdBlocker.get(getApplicationContext()).shouldBlock(req.getUrl());
            }
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                // cdp.pyがユーザータブと区別するためのマーカー。
                // ナビゲーションごとにJSコンテキストが作り直されるので毎回打つ
                v.evaluateJavascript("window.__gobieHeadless=true", null);
            }
        });
        web.setVisibility(android.view.View.VISIBLE);

        // 画面サイズで実寸レンダリング（モバイルサイトに正しいviewportを見せる）
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);

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
        lp.x = -dm.widthPixels;   // 画面外（左）に逃がす：見えないがレンダラは動く
        lp.y = 0;
        try {
            wm.addView(web, lp);
        } catch (Throwable t) {
            // SYSTEM_ALERT_WINDOW未許可など。通知に出して落ちないようにする
            android.util.Log.e("Headless", "addView失敗: " + t);
        }

        web.loadUrl(url != null ? url : "about:blank");
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CH) == null) {
                NotificationChannel c = new NotificationChannel(
                        CH, "Headless Browser", NotificationManager.IMPORTANCE_MIN);
                nm.createNotificationChannel(c);
            }
        }
    }

    private Notification build() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        boolean headlessActive = web != null;
        String title = headlessActive ? "Headless browser 稼働中" : "PWA 表示中";
        String text = headlessActive ? "CDPで外部駆動可能 (tcp:9222)" : "バックグラウンドでも維持中";
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        if (web != null && wm != null) {
            try { wm.removeView(web); } catch (Throwable ignore) {}
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
