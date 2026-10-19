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
 * IndexedDBはオリジン名ディレクトリ単位で「消えているもの＋空で作り直されたもの」だけ
 * 追加復元する（中身のある現存データをstaleなバックアップで上書きしない）。
 * evict後にPWAが先に起動すると空DBが作り直されてディレクトリ自体は存在するため、
 * 「存在するか」ではなく「.ldbを1つでも持つか」で実体の有無を判定する。
 *
 * バックアップはIndexedDBのみオリジン単位のマージ方式。SWキャッシュがevictで痩せた状態を
 * 「不健全」として全体を止めると、一度evictされた後は二度とバックアップが更新されなくなる
 * （自己ロック）ため、SWスナップショットの可否とIndexedDBの更新可否は切り離す。
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

    /**
     * leveldbディレクトリが実体を持つか。evict後にPWAが起動して作り直された直後のDBは
     * CURRENT/MANIFEST/LOGと空の.logだけを持ち、.ldb（実データのsstable）が存在しない。
     */
    private static boolean hasLdb(File originDir) {
        File[] ls = originDir.listFiles();
        if (ls == null) return false;
        for (File f : ls) if (f.getName().endsWith(".ldb")) return true;
        return false;
    }

    /**
     * オリジンディレクトリが実データを持つか。
     * IndexedDBは "<origin>.indexeddb.leveldb"（本体）と "<origin>.indexeddb.blob"（添付の実体）の
     * 2本立てで、blob側は.ldbを持たないのでファイルの有無で判定する。
     */
    private static boolean hasContent(File originDir) {
        if (!originDir.isDirectory()) return false;
        if (originDir.getName().endsWith(".blob")) return sizeRec(originDir) > 0;
        return hasLdb(originDir);
    }

    /**
     * 上書き前の退避先。復元が唯一の痕跡を消さないための保険。
     * 退避対象は「空で作り直されたDB」なので実質数十KBだが、逼迫時の保険が逼迫要因に
     * ならないよう世代を絞る。
     */
    private static final int MAX_QUARANTINE_GENS = 3;

    private static void pruneQuarantine(Context ctx) {
        File[] gens = new File(ctx.getFilesDir(), "sw_evicted").listFiles();
        if (gens == null || gens.length <= MAX_QUARANTINE_GENS) return;
        java.util.Arrays.sort(gens, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = MAX_QUARANTINE_GENS; i < gens.length; i++) deleteRec(gens[i]);
    }

    /** evict痕跡があればバックアップから復元。WebView初期化前に同期で呼ぶこと。 */
    static void maybeRestore(Context ctx) {
        try {
            File bakSw = new File(backupRoot(ctx), "Service Worker");
            if (!hasDb(bakSw)) return; // バックアップ無し
            long t0 = System.currentTimeMillis();
            File curSw = new File(profileDir(ctx), "Service Worker");
            int cur = cacheOrigins(curSw), bak = cacheOrigins(bakSw);
            if (!hasDb(curSw) || cur < bak) {
                Log.w(TAG, "evict検知(SW): 復元開始 (origins " + cur + " → " + bak + ")");
                deleteRec(curSw);
                copyRec(bakSw, curSw);
            }
            // IndexedDB: 消えたオリジン＋空で作り直されたオリジンを復元（実体のある現存は触らない）
            File bakIdb = new File(backupRoot(ctx), "IndexedDB");
            File curIdb = new File(profileDir(ctx), "IndexedDB");
            File[] ls = bakIdb.listFiles();
            int restored = 0;
            File qDir = new File(ctx.getFilesDir(), "sw_evicted/" + t0);
            if (ls != null) for (File f : ls) {
                if (f.getName().endsWith(".tmp")) continue;     // バックアップ中断の残骸は復元しない
                if (!hasContent(f)) continue;                  // 中身の無いバックアップでは上書きしない
                File dst = new File(curIdb, f.getName());
                if (hasContent(dst)) continue;                 // 実体があるなら現存を優先
                if (dst.exists()) {
                    // 空で作り直された痕跡は捨てずに退避してから差し替える
                    //noinspection ResultOfMethodCallIgnored
                    qDir.mkdirs();
                    if (!dst.renameTo(new File(qDir, f.getName()))) deleteRec(dst);
                }
                copyRec(f, dst);
                restored++;
                Log.w(TAG, "evict検知(IndexedDB): 復元 " + f.getName());
            }
            if (restored > 0 || cur < bak) {
                pruneQuarantine(ctx);
                Log.w(TAG, "復元完了 " + (System.currentTimeMillis() - t0) + "ms idb=" + restored);
            }
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
                long t0 = System.currentTimeMillis();
                File root = backupRoot(app);
                //noinspection ResultOfMethodCallIgnored
                root.mkdirs();
                File curSw = new File(profileDir(app), "Service Worker");
                File bakSw = new File(root, "Service Worker");

                // --- Service Worker: 現在が後退している（evict直後等）なら据え置き ---
                // ここで打ち切らずIndexedDBへ進むこと。一度evictされると二度と
                // バックアップが更新されない自己ロックを避ける。
                if (!hasDb(curSw) || cacheOrigins(curSw) < cacheOrigins(bakSw)) {
                    Log.i(TAG, "SWスナップショット据え置き: 現状が不健全 ("
                            + cacheOrigins(curSw) + " < " + cacheOrigins(bakSw) + ")");
                } else if (sizeRec(curSw) > MAX_BACKUP_BYTES) {
                    Log.w(TAG, "SWスナップショット据え置き: サイズ超過");
                } else {
                    File tmp = new File(app.getFilesDir(), "sw_backup.tmp");
                    deleteRec(tmp);
                    copyRec(curSw, tmp);
                    deleteRec(bakSw);
                    if (!tmp.renameTo(bakSw)) { deleteRec(tmp); Log.e(TAG, "SW rename失敗"); }
                }

                // --- IndexedDB: オリジン単位のマージ更新 ---
                // 実体(.ldb)を持つオリジンだけ差し替える。現行から消えたオリジンの
                // バックアップは残す（evictされただけかもしれないため）。
                int updated = 0;
                File curIdb = new File(profileDir(app), "IndexedDB");
                File bakIdb = new File(root, "IndexedDB");
                File[] origins = curIdb.listFiles();
                if (origins != null) for (File o : origins) {
                    if (!hasContent(o)) continue;
                    File dst = new File(bakIdb, o.getName());
                    File tmp = new File(bakIdb, o.getName() + ".tmp");
                    deleteRec(tmp);
                    copyRec(o, tmp);
                    deleteRec(dst);
                    if (tmp.renameTo(dst)) updated++;
                    else { deleteRec(tmp); Log.e(TAG, "idb rename失敗: " + o.getName()); }
                }
                sp.edit().putLong("last", System.currentTimeMillis()).apply();
                Log.i(TAG, "backup完了 " + (System.currentTimeMillis() - t0) + "ms sw_origins="
                        + cacheOrigins(bakSw) + " idb_updated=" + updated);
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
