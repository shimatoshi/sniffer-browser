package net.localhost.sniffer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** フォアグラウンドでDLを走らせ、進捗を通知に出す */
public class DownloadService extends Service {

    /** 旧"dl"は重要度LOWで、通知欄下部の「サイレント」に畳まれ進捗が見えなかった。
     *  チャンネルの重要度はアプリから上げられないので別IDで作り直す（音・振動なしのDEFAULT） */
    private static final String CH = "dl_progress";
    private static final String ACTION_CANCEL = "net.localhost.sniffer.CANCEL";
    private static final AtomicInteger ID = new AtomicInteger(1000);
    /** 通知ID → 実行中DL。通知の「中止」ボタンから引く */
    private final Map<Integer, HlsDownloader> active = new ConcurrentHashMap<>();
    /** 同時実行数。最後の1件が終わった時だけstopSelfする
     *  （複数DL中に1件終わるたびstopSelfしていた旧実装だと、進行中の他DLごとサービスが
     *  死んでforeground保護を失い、通知も進捗も突然消えるバグがあった） */
    private final AtomicInteger activeCount = new AtomicInteger(0);
    /** foreground状態に紐付ける固定の「ダウンロード中 n件」まとめ通知。各DLの進捗通知はこの下にグループ表示する。
     *  startForegroundService()で起動されるたびに数秒以内のstartForeground()が必須で、怠るとOSがアプリごと
     *  強制終了して進行中のDLが全部止まる。個別DLの通知IDをforegroundに使うと付け替えで巻き添えが起きるので分離 */
    private static final int SUMMARY_NID = 999;
    private static final String GROUP = "net.localhost.sniffer.DL";
    /** 画面オフ中もCPUとWi-Fiを起こしておく（無いとスリープで通信が止まりタイムアウト失敗する） */
    private android.os.PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        if (ACTION_CANCEL.equals(intent.getAction())) {
            HlsDownloader d = active.get(intent.getIntExtra("nid", -1));
            if (d != null) d.cancel();
            else if (activeCount.get() == 0) stopSelf(); // 古い通知から押された: 何もせず畳む
            return START_NOT_STICKY;
        }

        final MediaHit hit = new MediaHit(intent.getStringExtra("url"), intent.getStringExtra("type"));
        hit.referer = intent.getStringExtra("referer");
        hit.page = intent.getStringExtra("page");
        hit.cookie = intent.getStringExtra("cookie");
        hit.ua = intent.getStringExtra("ua");
        hit.title = intent.getStringExtra("title");
        hit.audioUrl = intent.getStringExtra("audio");
        hit.quality = intent.getStringExtra("quality");

        final int nid = ID.incrementAndGet();
        ensureChannel();
        final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        int n = activeCount.incrementAndGet();
        if (n == 1) acquireLocks();
        startForeground(SUMMARY_NID, summary(n)); // 起動のたびに必須（2件目以降も）
        nm.notify(nid, build("ダウンロード開始", "準備中...", 0, true, nid));

        // サイトごとにサブディレクトリを生成して振り分ける（shimatube等、DL先が
        // Download直下に混ざらないように。ホスト名が取れない場合は直下に置く）
        final String site = siteDirName(hit.page);
        final File outDir = site != null
                ? new File("/sdcard/Download", site)
                : new File("/sdcard/Download");
        outDir.mkdirs();
        // 作業は内部ストレージ(/data)で行う。FUSE(/sdcard)経由だと激遅になるため。
        final File cache = getCacheDir();

        new Worker(hit, outDir, cache, nm, nid).start();
        return START_NOT_STICKY;
    }

    /** DLを別スレッドで実行（lambda+匿名クラスのネストを避けるため明示クラス化） */
    private class Worker extends Thread implements HlsDownloader.Listener {
        private final MediaHit hit;
        private final File outDir, cache;
        private final NotificationManager nm;
        private final int nid;
        private long last = 0;

        Worker(MediaHit hit, File outDir, File cache, NotificationManager nm, int nid) {
            this.hit = hit; this.outDir = outDir; this.cache = cache; this.nm = nm; this.nid = nid;
        }

        @SuppressWarnings("deprecation")
        @Override public void run() {
            HlsDownloader dl = new HlsDownloader(DownloadService.this, hit, outDir, cache, this);
            active.put(nid, dl);
            try {
                dl.run();
            } finally {
                active.remove(nid);
                int left = activeCount.decrementAndGet();
                if (left == 0) {
                    // まとめ通知だけ消す（各DLの完了/失敗通知は別IDなので残る）
                    stopForeground(true);
                    releaseLocks();
                    stopSelf();
                } else {
                    nm.notify(SUMMARY_NID, summary(left));
                }
            }
        }

        public void onProgress(int pct, String msg) {
            long now = System.currentTimeMillis();
            // サイズ不明(pct<0)も含めて間引く。間引かないと64KBごとに通知更新が走り、
            // OSのレート制限で更新が捨てられて進捗バーが出ない/固まる
            if (now - last < 500 && pct < 100) return;
            last = now;
            android.util.Log.d("Sniffer", "dl#" + nid + " " + pct + "% " + msg);
            nm.notify(nid, build(title(hit), msg, pct, pct < 0, nid));
        }
        public void onDone(File out) {
            scan(DownloadService.this, out);
            String path = out.getAbsolutePath();
            String rel = path.startsWith("/sdcard/") ? path.substring("/sdcard/".length()) : out.getName();
            nm.notify(nid, done("✓ 保存: " + out.getName(), rel));
        }
        public void onError(String msg) {
            if ("中止".equals(msg)) nm.cancel(nid);  // ユーザー中止は通知ごと消す
            else nm.notify(nid, done("✗ 失敗", String.valueOf(msg)));
        }
    }

    private Notification summary(int n) {
        return builder()
                .setContentTitle("ダウンロード中 " + n + " 件")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .build();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Sniffer:Download");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6 * 60 * 60 * 1000L); // 解放漏れの安全弁（6時間）
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Sniffer:Download");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Throwable t) {
            android.util.Log.w("Sniffer", "dl lock failed: " + t);
        }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignore) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignore) {}
    }

    @Override public void onDestroy() { releaseLocks(); super.onDestroy(); }

    private String title(MediaHit h) {
        return (h.title != null && !h.title.isEmpty()) ? h.title : "ダウンロード";
    }

    /** DL元ページのホスト名からサブディレクトリ名を作る。取れなければnull（直下に保存）。 */
    private static String siteDirName(String pageUrl) {
        if (pageUrl == null || pageUrl.isEmpty()) return null;
        try {
            String host = new java.net.URI(pageUrl).getHost();
            if (host == null || host.isEmpty()) return null;
            host = host.replaceFirst("^www\\.", "");
            return host.replaceAll("[^A-Za-z0-9._-]", "_");
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private Notification build(String title, String text, int pct, boolean indeterminate, int nid) {
        Notification.Builder b = builder()
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setGroup(GROUP);
        if (pct >= 0) b.setProgress(100, pct, indeterminate);
        else b.setProgress(0, 0, true);
        Intent ci = new Intent(this, DownloadService.class)
                .setAction(ACTION_CANCEL).putExtra("nid", nid);
        PendingIntent pi = PendingIntent.getService(this, nid, ci,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "中止", pi);
        return b.build();
    }

    private Notification done(String title, String text) {
        return builder()
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                // グループに入れない: 最後のDL終了でまとめ通知を消すと、同グループの子も道連れで消えるため
                .setOngoing(false)
                .setAutoCancel(true)
                .build();
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder() {
        return Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CH) == null) {
                NotificationChannel ch = new NotificationChannel(CH, "ダウンロード", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
                nm.deleteNotificationChannel("dl");
            }
        }
    }

    static void scan(Context ctx, File f) {
        try {
            // Serviceは直後にstopSelf()するのでapplicationContextで接続を生かす
            MediaScannerConnection.scanFile(ctx.getApplicationContext(),
                    new String[]{f.getAbsolutePath()}, null,
                    (path, uri) -> android.util.Log.i("Sniffer", "media scanned: " + path + " -> " + uri));
        } catch (Throwable ignore) {}
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
