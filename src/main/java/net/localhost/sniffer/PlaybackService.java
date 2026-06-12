package net.localhost.sniffer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * バックグラウンド再生の延命用フォアグラウンドサービス。
 * Activityがバックグラウンドへ回り、かつ動画/音声が再生中のときだけ起動する。
 * プロセスをforeground重要度に保ち、cached化(=レンダラ凍結)で音が止まるのを防ぐ。
 * 再生UIは持たない。前面に戻ったら(=onStart)Activity側がstopする。
 */
public class PlaybackService extends Service {

    private static final String CH = "playback";
    private static final int NID = 0x50;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
        String title = intent != null ? intent.getStringExtra("title") : null;
        if (title == null || title.isEmpty()) title = "バックグラウンド再生中";
        startForeground(NID, build(title));
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Notification build(String title) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        return b.setContentTitle(title)
                .setContentText("Snifferで再生を継続中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CH, "再生", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
