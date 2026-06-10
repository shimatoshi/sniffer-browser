package net.localhost.sniffer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import android.webkit.WebView;

import java.io.File;
import java.security.MessageDigest;

/**
 * オートスクレイプ（オフラインリーダー）の保存層。
 *
 * 遷移ごとにWebViewの saveWebArchive() でMHTML(1ページ=1ファイル)を
 * files/offline/ に保存し、台帳をBrowserDbのofflineテーブルに持つ。
 *
 * 安全設計:
 *  - 保存先はアプリ専用の通常ファイルのみ。Chromiumプロファイル(app_webview/)には
 *    一切触れないので、PWAのSW/Cache Storageを壊す経路が存在しない
 *  - 端末空き容量が1GB未満なら保存自体をスキップ（ストレージ逼迫経由の
 *    Chromium LRU追い出しでPWAキャッシュが巻き添えになるのを防ぐ）
 *  - 上限(MB/ページ数)超過分は古いものからLRU削除（.mhtのunlinkとDB行のみ）
 */
public class OfflineStore {

    private static final long MIN_FREE_BYTES = 1024L * 1024 * 1024; // 1GB
    private static final int MAX_PAGES = 1000;
    private static final long SAME_URL_COOLDOWN_MS = 60_000;

    private static OfflineStore instance;

    public static synchronized OfflineStore get(Context ctx) {
        if (instance == null) instance = new OfflineStore(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences sp;
    private final File dir;
    private volatile String lastUrl = "";
    private volatile long lastTime;

    private OfflineStore(Context app) {
        this.app = app;
        this.sp = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        this.dir = new File(app.getFilesDir(), "offline");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    // ---- 設定 ----

    public boolean autoOn() { return sp.getBoolean("autoScrape", false); } // デフォルトOFF
    public void setAuto(boolean on) { sp.edit().putBoolean("autoScrape", on).apply(); }

    public int maxMb() { return sp.getInt("offlineMaxMb", 500); }
    public void setMaxMb(int mb) { sp.edit().putInt("offlineMaxMb", mb).apply(); }

    // ---- 保存 ----

    /** onPageFinishedから呼ぶ。OFF/対象外URL/容量逼迫なら何もしない。 */
    public void autoSave(WebView web, BrowserDb db, String url, String title) {
        if (!autoOn() || !savable(url)) return;
        long now = System.currentTimeMillis();
        if (url.equals(lastUrl) && now - lastTime < SAME_URL_COOLDOWN_MS) return;
        if (freeBytes() < MIN_FREE_BYTES) return; // 端末逼迫時は手を出さない
        lastUrl = url;
        lastTime = now;

        final File f = new File(dir, hash(url) + ".mht");
        web.saveWebArchive(f.getAbsolutePath(), false, savedPath -> {
            if (savedPath == null || !f.exists()) return; // 保存失敗（黙って次へ）
            db.upsertOffline(url, title, f.getName(), f.length());
            prune(db);
        });
    }

    /** http/httpsの実ページだけが対象。ホーム画面・mht再表示・blank等は保存しない。 */
    private boolean savable(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /** 上限超過分を古いものから削除（.mhtファイル＋DB行のみ。WebView層には触れない） */
    private void prune(BrowserDb db) {
        long max = maxMb() * 1024L * 1024;
        for (BrowserDb.OfflinePage p : db.offlineOverflow(max, MAX_PAGES)) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, p.file).delete();
            db.deleteOffline(p.id);
        }
    }

    // ---- 一覧/復元/削除 ----

    public File fileOf(BrowserDb.OfflinePage p) { return new File(dir, p.file); }

    public void delete(BrowserDb db, BrowserDb.OfflinePage p) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir, p.file).delete();
        db.deleteOffline(p.id);
    }

    public void deleteAll(BrowserDb db) {
        for (BrowserDb.OfflinePage p : db.listOffline()) delete(db, p);
    }

    public long totalBytes(BrowserDb db) { return db.offlineTotalBytes(); }

    // ---- util ----

    private long freeBytes() {
        try {
            return new StatFs(app.getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Throwable t) {
            return Long.MAX_VALUE; // 取得失敗時は保存を止めない
        }
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(s.hashCode() & 0x7fffffffL);
        }
    }
}
