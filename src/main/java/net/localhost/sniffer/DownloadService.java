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

    private static final String CH = "dl";
    private static final String ACTION_CANCEL = "net.localhost.sniffer.CANCEL";
    private static final AtomicInteger ID = new AtomicInteger(1000);
    /** 通知ID → 実行中DL。通知の「中止」ボタンから引く */
    private final Map<Integer, HlsDownloader> active = new ConcurrentHashMap<>();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        if (ACTION_CANCEL.equals(intent.getAction())) {
            HlsDownloader d = active.get(intent.getIntExtra("nid", -1));
            if (d != null) d.cancel();
            return START_NOT_STICKY;
        }

        final MediaHit hit = new MediaHit(intent.getStringExtra("url"), intent.getStringExtra("type"));
        hit.referer = intent.getStringExtra("referer");
        hit.cookie = intent.getStringExtra("cookie");
        hit.ua = intent.getStringExtra("ua");
        hit.title = intent.getStringExtra("title");
        hit.audioUrl = intent.getStringExtra("audio");
        hit.quality = intent.getStringExtra("quality");

        final int nid = ID.incrementAndGet();
        ensureChannel();
        startForeground(nid, build("ダウンロード開始", "準備中...", 0, true, nid));

        final File outDir = new File("/sdcard/Download");
        // 作業は内部ストレージ(/data)で行う。FUSE(/sdcard)経由だと激遅になるため。
        final File cache = getCacheDir();

        final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
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

        @Override public void run() {
            HlsDownloader dl = new HlsDownloader(hit, outDir, cache, this);
            active.put(nid, dl);
            try {
                dl.run();
            } finally {
                active.remove(nid);
            }
        }

        public void onProgress(int pct, String msg) {
            long now = System.currentTimeMillis();
            if (now - last < 500 && pct < 100 && pct >= 0) return;
            last = now;
            android.util.Log.d("Sniffer", "dl#" + nid + " " + pct + "% " + msg);
            nm.notify(nid, build(title(hit), msg, pct, pct < 0, nid));
        }
        public void onDone(File out) {
            scan(DownloadService.this, out);
            nm.notify(nid, done("✓ 保存: " + out.getName(), "Download/" + out.getName()));
            stopSelf();
        }
        public void onError(String msg) {
            if ("中止".equals(msg)) nm.cancel(nid);  // ユーザー中止は通知ごと消す
            else nm.notify(nid, done("✗ 失敗", String.valueOf(msg)));
            stopSelf();
        }
    }

    private String title(MediaHit h) {
        return (h.title != null && !h.title.isEmpty()) ? h.title : "ダウンロード";
    }

    @SuppressWarnings("deprecation")
    private Notification build(String title, String text, int pct, boolean indeterminate, int nid) {
        Notification.Builder b = builder()
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true);
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
            NotificationChannel ch = new NotificationChannel(CH, "ダウンロード", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
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
