package net.localhost.sniffer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PersistableBundle;
import android.util.Log;

import java.util.Calendar;

/**
 * PWAごとの定期取得スケジュール。
 *
 * PWA単位で複数の時刻（"HH:mm"）を設定でき、各時刻にAlarmManagerで起床する。
 *  - 起床時にオンラインなら即座にPwaWarmer.warmOneで再ウォーム（HeadlessBrowserService経由）
 *  - オフラインならqueueOfflineが有効な場合のみJobScheduler（NETWORK_TYPE_ANY制約）に積み、
 *    復帰時に自動発火させる。無効ならその回はスキップ
 *  - 起床のたびに同じ枠を翌日分として即座に再アームする（daily repeat）
 */
public class PwaScheduler {
    private static final String TAG = "PwaScheduler";
    private static final int MAX_SLOTS = 8; // 1PWAあたりの最大登録時刻数
    private static final int JOB_ID_BASE = 500000; // 他のJobScheduler利用との衝突回避オフセット

    private static final String EXTRA_PWA_ID = "pwa_id";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_HH = "hh";
    private static final String EXTRA_MM = "mm";
    private static final String EXTRA_QUEUE_OFFLINE = "queue_offline";
    private static final String EXTRA_SLOT = "slot";

    /** 全PWAのスケジュールを読み直して再アーム。起動時・再起動後の安全網。 */
    static void rescheduleAll(Context ctx) {
        try (BrowserDb db = new BrowserDb(ctx)) {
            for (BrowserDb.Pwa p : db.listPwas()) {
                if (hasSchedule(p)) rescheduleForPwa(ctx, p);
                else cancelForPwa(ctx, p.id);
            }
        } catch (Throwable t) {
            Log.e(TAG, "rescheduleAll失敗: " + t);
        }
    }

    static boolean hasSchedule(BrowserDb.Pwa p) {
        return p.schedule != null && !p.schedule.trim().isEmpty();
    }

    /** 1PWA分を再アーム（既存の登録は一旦全解除してから張り直す）。 */
    static void rescheduleForPwa(Context ctx, BrowserDb.Pwa p) {
        cancelForPwa(ctx, p.id);
        if (!hasSchedule(p)) return;
        String[] parts = p.schedule.split(",");
        int slot = 0;
        for (String part : parts) {
            if (slot >= MAX_SLOTS) {
                Log.i(TAG, "スロット上限(" + MAX_SLOTS + ")超過、以降は無視: " + p.url);
                break;
            }
            int[] hm = parseTime(part.trim());
            if (hm == null) continue;
            armSlot(ctx, p.id, p.url, hm[0], hm[1], p.queueOffline, slot, nextOccurrence(hm[0], hm[1]));
            slot++;
        }
    }

    /** 指定PWAの全アラーム枠を解除。 */
    static void cancelForPwa(Context ctx, long pwaId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode(pwaId, slot),
                    new Intent(ctx, FireReceiver.class), flags(PendingIntent.FLAG_NO_CREATE));
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(JOB_ID_BASE + (int) pwaId);
    }

    private static void armSlot(Context ctx, long pwaId, String url, int hh, int mm,
            boolean queueOffline, int slot, long atMillis) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(ctx, FireReceiver.class);
        i.putExtra(EXTRA_PWA_ID, pwaId);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_HH, hh);
        i.putExtra(EXTRA_MM, mm);
        i.putExtra(EXTRA_QUEUE_OFFLINE, queueOffline);
        i.putExtra(EXTRA_SLOT, slot);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode(pwaId, slot), i,
                flags(PendingIntent.FLAG_UPDATE_CURRENT));
        // 端末スリープ中でも起床させる（正確さより「必ず来る」を優先、精密実行権限は要求しない）
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi);
    }

    private static int requestCode(long pwaId, int slot) {
        return (int) (pwaId * MAX_SLOTS + slot);
    }

    private static int flags(int base) {
        return Build.VERSION.SDK_INT >= 23 ? base | PendingIntent.FLAG_IMMUTABLE : base;
    }

    /** 次に来るhh:mmの発火時刻（今日分が既に過ぎていれば翌日）。 */
    private static long nextOccurrence(int hh, int mm) {
        Calendar cal = Calendar.getInstance();
        long now = cal.getTimeInMillis();
        cal.set(Calendar.HOUR_OF_DAY, hh);
        cal.set(Calendar.MINUTE, mm);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= now) cal.add(Calendar.DATE, 1);
        return cal.getTimeInMillis();
    }

    /** "H:mm"/"HH:mm"を{hh,mm}にパース。不正なら null。 */
    static int[] parseTime(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(s);
        if (!m.matches()) return null;
        int hh = Integer.parseInt(m.group(1));
        int mm = Integer.parseInt(m.group(2));
        if (hh < 0 || hh > 23 || mm < 0 || mm > 59) return null;
        return new int[]{hh, mm};
    }

    /** アラーム発火の受け口。次回分の再アーム→オンライン即実行/オフラインキューを行う。 */
    public static class FireReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            long pwaId = intent.getLongExtra(EXTRA_PWA_ID, -1);
            String url = intent.getStringExtra(EXTRA_URL);
            int hh = intent.getIntExtra(EXTRA_HH, 0);
            int mm = intent.getIntExtra(EXTRA_MM, 0);
            boolean queueOffline = intent.getBooleanExtra(EXTRA_QUEUE_OFFLINE, true);
            int slot = intent.getIntExtra(EXTRA_SLOT, 0);
            if (pwaId < 0 || url == null) return;

            // 今回の成否に関わらず、この枠は明日も繰り返す
            armSlot(ctx, pwaId, url, hh, mm, queueOffline, slot, nextOccurrence(hh, mm));

            if (PwaWarmer.isOnline(ctx)) {
                Log.i(TAG, "定刻オンライン、即実行: " + url);
                Intent svc = new Intent(ctx, HeadlessBrowserService.class);
                svc.setAction(HeadlessBrowserService.ACTION_WARM_ONE);
                svc.putExtra("url", url);
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
                else ctx.startService(svc);
            } else if (queueOffline) {
                Log.i(TAG, "定刻オフライン、復帰待ちキューへ: " + url);
                queueForReconnect(ctx, pwaId, url);
            } else {
                Log.i(TAG, "定刻オフライン、キュー無効のためスキップ: " + url);
            }
        }
    }

    private static void queueForReconnect(Context ctx, long pwaId, String url) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        PersistableBundle extras = new PersistableBundle();
        extras.putString("url", url);
        ComponentName comp = new ComponentName(ctx, PwaScheduleJobService.class);
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID_BASE + (int) pwaId, comp)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .setPersisted(true); // 再起動を跨いでも復帰時に発火させる
        js.schedule(b.build());
    }

    /** 復帰待ちキューの実処理。JobScheduler自体がネットワーク接続を保証してから起動する。 */
    public static class PwaScheduleJobService extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            String url = params.getExtras().getString("url");
            if (url == null) return false;
            PwaWarmer.warmOne(this, url, () -> jobFinished(params, false));
            return true; // warmOneの完了はコールバック経由の非同期
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return true; // 制約が崩れたら次の復帰を待って再試行
        }
    }

    /** 端末再起動でアラームは消えるので、起動完了時に読み直す。 */
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            rescheduleAll(ctx.getApplicationContext());
        }
    }
}
