package net.localhost.sniffer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * SWストレージのローカルスナップショット&オフライン復元。
 *
 * WebViewはdurable storage未対応のため、ストレージ逼迫時にChromiumのQuotaManagerが
 * PWAのSWキャッシュをLRU evictしうる。PwaWarmer（オンライン巡回）だけでは
 * 「evict→そのまま圏外」のケースで復活できない。
 * そこで app_webview/Default/Service Worker（登録DB+ScriptCache+CacheStorage）を
 * files/sw_backup/ にスナップショットし、起動時にevict痕跡（CacheStorageオリジン減少 or
 * 登録DB消失）を検知したらネット不要で書き戻す。バックアップはChromiumのクォータ管理外
 * なのでevict対象にならない。
 *
 * IndexedDBはオリジン名ディレクトリ単位で「消えているものだけ」追加復元する
 * （現存データをstaleなバックアップで上書きしない）。
 *
 * 復元はWebView初期化前に走る必要があるため、App.onCreate（メインプロセスのみ）から呼ぶ。
 */
public class SwBackup {
    private static final String TAG = "SwBackup";
    private static final long BACKUP_COOLDOWN_MS = 12L * 3600 * 1000;
    private static final long MAX_BACKUP_BYTES = 200L * 1024 * 1024; // 肥大化ガード

    private static File profileDir(Context ctx) {
        return new File(ctx.getApplicationInfo().dataDir, "app_webview/Default");
    }

    private static File backupRoot(Context ctx) {
        return new File(ctx.getFilesDir(), "sw_backup");
    }

    private static int cacheOrigins(File swDir) {
        File[] ls = new File(swDir, "CacheStorage").listFiles();
        return ls != null ? ls.length : 0;
    }

    private static boolean hasDb(File swDir) {
        File[] ls = new File(swDir, "Database").listFiles();
        return ls != null && ls.length > 0;
    }

    /** evict痕跡があればバックアップから復元。WebView初期化前に同期で呼ぶこと。 */
    static void maybeRestore(Context ctx) {
        try {
            File bakSw = new File(backupRoot(ctx), "Service Worker");
            if (!hasDb(bakSw)) return; // バックアップ無し
            File curSw = new File(profileDir(ctx), "Service Worker");
            int cur = cacheOrigins(curSw), bak = cacheOrigins(bakSw);
            boolean damaged = !hasDb(curSw) || cur < bak;
            if (!damaged) return;
            Log.w(TAG, "evict検知: 復元開始 (origins " + cur + " → " + bak + ")");
            long t0 = System.currentTimeMillis();
            deleteRec(curSw);
            copyRec(bakSw, curSw);
            // IndexedDB: 消えたオリジンのディレクトリだけ追加復元（現存は触らない）
            File bakIdb = new File(backupRoot(ctx), "IndexedDB");
            File curIdb = new File(profileDir(ctx), "IndexedDB");
            File[] ls = bakIdb.listFiles();
            if (ls != null) for (File f : ls) {
                File dst = new File(curIdb, f.getName());
                if (!dst.exists()) copyRec(f, dst);
            }
            Log.w(TAG, "復元完了 " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            Log.e(TAG, "restore失敗: " + t);
        }
    }

    /** 健全な状態なら非同期でスナップショット更新（12hクールダウン） */
    static void maybeBackup(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                SharedPreferences sp = app.getSharedPreferences("sw_backup", Context.MODE_PRIVATE);
                if (System.currentTimeMillis() - sp.getLong("last", 0) < BACKUP_COOLDOWN_MS) return;
                File curSw = new File(profileDir(app), "Service Worker");
                File root = backupRoot(app);
                File bakSw = new File(root, "Service Worker");
                // 現在が後退している（evict直後等）状態を正としてスナップショットしない
                if (!hasDb(curSw) || cacheOrigins(curSw) < cacheOrigins(bakSw)) {
                    Log.i(TAG, "backupスキップ: 現状が不健全");
                    return;
                }
                if (sizeRec(curSw) > MAX_BACKUP_BYTES) {
                    Log.w(TAG, "backupスキップ: サイズ超過");
                    return;
                }
                long t0 = System.currentTimeMillis();
                File tmp = new File(app.getFilesDir(), "sw_backup.tmp");
                deleteRec(tmp);
                copyRec(curSw, new File(tmp, "Service Worker"));
                File curIdb = new File(profileDir(app), "IndexedDB");
                if (curIdb.isDirectory()) copyRec(curIdb, new File(tmp, "IndexedDB"));
                deleteRec(root);
                if (!tmp.renameTo(root)) { Log.e(TAG, "backup rename失敗"); return; }
                sp.edit().putLong("last", System.currentTimeMillis()).apply();
                Log.i(TAG, "backup完了 " + (System.currentTimeMillis() - t0) + "ms origins=" + cacheOrigins(new File(root, "Service Worker")));
            } catch (Throwable t) {
                Log.e(TAG, "backup失敗: " + t);
            }
        }, "sw-backup").start();
    }

    private static void copyRec(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("mkdir失敗: " + dst);
            File[] ls = src.listFiles();
            if (ls != null) for (File f : ls) copyRec(f, new File(dst, f.getName()));
        } else {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] ls = f.listFiles();
            if (ls != null) for (File c : ls) deleteRec(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static long sizeRec(File f) {
        if (f.isDirectory()) {
            long s = 0;
            File[] ls = f.listFiles();
            if (ls != null) for (File c : ls) s += sizeRec(c);
            return s;
        }
        return f.length();
    }
}
