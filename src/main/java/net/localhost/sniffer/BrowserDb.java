package net.localhost.sniffer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** 履歴・ブックマークの永続化（SQLite）。 */
public class BrowserDb extends SQLiteOpenHelper {

    private static final int HISTORY_CAP = 1000;

    public static class Entry {
        public final long id;
        public final String url, title;
        public final long ts;
        Entry(long id, String url, String title, long ts) {
            this.id = id; this.url = url; this.title = title; this.ts = ts;
        }
        public String label() {
            return (title == null || title.isEmpty()) ? url : title;
        }
    }

    /** 記事ウィジェット1行分（どの固定サイト由来かのラベル付き）。 */
    public static class FeedItem {
        public final String site, title, url;
        public FeedItem(String site, String title, String url) {
            this.site = site; this.title = title; this.url = url;
        }
    }

    /** ホームに並ぶインストール済みPWA1件分。 */
    public static class Pwa {
        public final long id;
        public final String url, name, theme, icon; // icon = PNGのbase64（dataURI本体）
        public final boolean desktop; // PC版サイト表示（デスクトップUA）で開く
        public final int zoom; // ページズーム%（100=等倍）
        Pwa(long id, String url, String name, String theme, String icon, boolean desktop, int zoom) {
            this.id = id; this.url = url; this.name = name;
            this.theme = theme; this.icon = icon; this.desktop = desktop;
            this.zoom = zoom > 0 ? zoom : 100;
        }
        public String label() {
            return (name == null || name.isEmpty()) ? url : name;
        }
    }

    public BrowserDb(Context ctx) {
        super(ctx, "browser.db", null, 9);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " url TEXT NOT NULL, title TEXT, ts INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " url TEXT NOT NULL UNIQUE, title TEXT, ts INTEGER NOT NULL)");
        createFilters(db);
        createScripts(db);
        createPins(db);
        createOffline(db);
        createFeed(db);
        createPwas(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) createFilters(db);
        if (oldV < 3) createScripts(db);
        if (oldV < 4) createPins(db);
        if (oldV < 5) createOffline(db);
        if (oldV < 6) createFeed(db);
        if (oldV < 7) createPwas(db);
        if (oldV < 8) {
            try { db.execSQL("ALTER TABLE pwas ADD COLUMN desktop INTEGER NOT NULL DEFAULT 0"); }
            catch (Throwable ignore) {} // v7で作り直した直後などで既にある場合
        }
        if (oldV < 9) {
            try { db.execSQL("ALTER TABLE pwas ADD COLUMN zoom INTEGER NOT NULL DEFAULT 100"); }
            catch (Throwable ignore) {}
        }
    }

    private void createPwas(SQLiteDatabase db) {
        // ホームに並べるインストール済みPWA（start_url / 表示名 / theme_color / アイコンpng）
        db.execSQL("CREATE TABLE IF NOT EXISTS pwas (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " url TEXT NOT NULL UNIQUE, name TEXT, theme TEXT, icon TEXT,"
                + " desktop INTEGER NOT NULL DEFAULT 0, zoom INTEGER NOT NULL DEFAULT 100,"
                + " ts INTEGER NOT NULL)");
    }

    private void createFeed(SQLiteDatabase db) {
        // ホーム固定サイトの新着記事キャッシュ（記事ウィジェット用に永続化）
        db.execSQL("CREATE TABLE IF NOT EXISTS feed (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " site TEXT, title TEXT NOT NULL, url TEXT NOT NULL UNIQUE,"
                + " seq INTEGER NOT NULL, ts INTEGER NOT NULL)");
    }

    private void createOffline(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS offline (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " url TEXT NOT NULL UNIQUE, title TEXT, file TEXT NOT NULL,"
                + " size INTEGER NOT NULL, ts INTEGER NOT NULL)");
    }

    private void createFilters(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS filters (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " pattern TEXT NOT NULL UNIQUE, ts INTEGER NOT NULL)");
    }

    private void createScripts(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS userscripts (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " name TEXT, matches TEXT, code TEXT NOT NULL,"
                + " enabled INTEGER NOT NULL DEFAULT 1,"
                + " runat TEXT NOT NULL DEFAULT 'end', ts INTEGER NOT NULL)");
    }

    private void createPins(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pins (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " url TEXT NOT NULL UNIQUE, title TEXT, ts INTEGER NOT NULL)");
    }

    // ---- ホーム固定サイト ----

    public void addPin(String url, String title) {
        if (url == null || url.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "pins", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void removePin(long id) {
        getWritableDatabase().delete("pins", "id=?", new String[]{String.valueOf(id)});
    }

    public List<Entry> listPins() {
        List<Entry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, url, title, ts FROM pins ORDER BY id", null);
        while (c.moveToNext())
            out.add(new Entry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)));
        c.close();
        return out;
    }

    // ---- インストール済みPWA（ホーム表示） ----

    /** PWA追加/再追加時。同一start_urlは最新の名前・アイコンで上書き。 */
    public void upsertPwa(String url, String name, String theme, String iconBase64, boolean desktop, int zoom) {
        if (url == null || url.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("name", name);
        v.put("theme", theme);
        v.put("icon", iconBase64);
        v.put("desktop", desktop ? 1 : 0);
        v.put("zoom", zoom > 0 ? zoom : 100);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "pwas", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 既存ショートカットからのバックフィル用。すでに登録済みなら触らない（実アイコンを潰さない）。 */
    public void insertPwaIfAbsent(String url, String name, String theme, String iconBase64, boolean desktop, int zoom) {
        if (url == null || url.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("name", name);
        v.put("theme", theme);
        v.put("icon", iconBase64);
        v.put("desktop", desktop ? 1 : 0);
        v.put("zoom", zoom > 0 ? zoom : 100);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "pwas", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void removePwa(long id) {
        getWritableDatabase().delete("pwas", "id=?", new String[]{String.valueOf(id)});
    }

    public Pwa getPwa(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, url, name, theme, icon, desktop, zoom FROM pwas WHERE id=?",
                new String[]{String.valueOf(id)});
        Pwa p = c.moveToFirst()
                ? new Pwa(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5) != 0, c.getInt(6))
                : null;
        c.close();
        return p;
    }

    /** PwaActivityが旧ショートカット（pwa_desktop/pwa_zoom extra無し）から起動された時のフォールバック照会。 */
    public Pwa getPwaByUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, url, name, theme, icon, desktop, zoom FROM pwas WHERE url=?",
                new String[]{url});
        Pwa p = c.moveToFirst()
                ? new Pwa(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5) != 0, c.getInt(6))
                : null;
        c.close();
        return p;
    }

    public List<Pwa> listPwas() {
        List<Pwa> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, url, name, theme, icon, desktop, zoom FROM pwas ORDER BY id", null);
        while (c.moveToNext())
            out.add(new Pwa(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5) != 0, c.getInt(6)));
        c.close();
        return out;
    }

    // ---- 記事ウィジェット用フィード ----

    /** 取得した新着記事一覧でfeedテーブルを総入れ替え（順序はリスト順）。 */
    public void replaceFeed(List<FeedItem> items) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("feed", null, null);
            long now = System.currentTimeMillis();
            int seq = 0;
            for (FeedItem it : items) {
                if (it.url == null || it.title == null) continue;
                ContentValues v = new ContentValues();
                v.put("site", it.site);
                v.put("title", it.title);
                v.put("url", it.url);
                v.put("seq", seq++);
                v.put("ts", now);
                db.insertWithOnConflict("feed", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<FeedItem> listFeed() {
        List<FeedItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT site, title, url FROM feed ORDER BY seq", null);
        while (c.moveToNext())
            out.add(new FeedItem(c.getString(0), c.getString(1), c.getString(2)));
        c.close();
        return out;
    }

    // ---- ユーザースクリプト ----

    public void saveScript(UserScripts.Script s) {
        ContentValues v = new ContentValues();
        v.put("name", s.name);
        v.put("matches", s.matches);
        v.put("code", s.code);
        v.put("enabled", s.enabled ? 1 : 0);
        v.put("runat", s.runat);
        v.put("ts", System.currentTimeMillis());
        if (s.id >= 0)
            getWritableDatabase().update("userscripts", v, "id=?",
                    new String[]{String.valueOf(s.id)});
        else
            s.id = getWritableDatabase().insert("userscripts", null, v);
    }

    public void removeScript(long id) {
        getWritableDatabase().delete("userscripts", "id=?",
                new String[]{String.valueOf(id)});
    }

    public void setScriptEnabled(long id, boolean en) {
        ContentValues v = new ContentValues();
        v.put("enabled", en ? 1 : 0);
        getWritableDatabase().update("userscripts", v, "id=?",
                new String[]{String.valueOf(id)});
    }

    public List<UserScripts.Script> listScripts() {
        List<UserScripts.Script> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, name, matches, code, enabled, runat FROM userscripts ORDER BY id", null);
        while (c.moveToNext()) {
            UserScripts.Script s = new UserScripts.Script();
            s.id = c.getLong(0);
            s.name = c.getString(1);
            s.matches = c.getString(2);
            s.code = c.getString(3);
            s.enabled = c.getInt(4) != 0;
            s.runat = c.getString(5);
            out.add(s);
        }
        c.close();
        return out;
    }

    // ---- カスタムフィルター（URL部分一致パターン） ----

    public void addFilter(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("pattern", pattern.trim());
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "filters", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void removeFilter(String pattern) {
        getWritableDatabase().delete("filters", "pattern=?", new String[]{pattern});
    }

    public List<String> listFilterPatterns() {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT pattern FROM filters ORDER BY id", null);
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    // ---- 履歴 ----

    /** ページ表示完了時に呼ぶ。直前と同じURLならタイトル/時刻だけ更新（リロード連打で増殖しない） */
    public void addHistory(String url, String title) {
        if (url == null || url.isEmpty() || url.equals("about:blank")
                || url.startsWith("gobie://") || url.startsWith("file://")) return;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        Cursor c = db.rawQuery("SELECT id, url FROM history ORDER BY id DESC LIMIT 1", null);
        long lastId = -1; String lastUrl = null;
        if (c.moveToFirst()) { lastId = c.getLong(0); lastUrl = c.getString(1); }
        c.close();
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("ts", now);
        if (url.equals(lastUrl)) {
            db.update("history", v, "id=?", new String[]{String.valueOf(lastId)});
        } else {
            v.put("url", url);
            db.insert("history", null, v);
            db.execSQL("DELETE FROM history WHERE id <= (SELECT MAX(id) FROM history) - " + HISTORY_CAP);
        }
    }

    public List<Entry> listHistory(int limit) {
        return list("history", limit);
    }

    public void clearHistory() {
        getWritableDatabase().delete("history", null, null);
    }

    // ---- ブックマーク ----

    public void addBookmark(String url, String title) {
        if (url == null || url.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "bookmarks", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void removeBookmark(String url) {
        getWritableDatabase().delete("bookmarks", "url=?", new String[]{url});
    }

    public boolean isBookmarked(String url) {
        if (url == null) return false;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM bookmarks WHERE url=?", new String[]{url});
        boolean r = c.moveToFirst();
        c.close();
        return r;
    }

    public List<Entry> listBookmarks() {
        return list("bookmarks", -1);
    }

    // ---- オフラインページ（オートスクレイプ台帳） ----

    public static class OfflinePage {
        public final long id;
        public final String url, title, file;
        public final long size, ts;
        OfflinePage(long id, String url, String title, String file, long size, long ts) {
            this.id = id; this.url = url; this.title = title;
            this.file = file; this.size = size; this.ts = ts;
        }
        public String label() {
            return (title == null || title.isEmpty()) ? url : title;
        }
    }

    /** 同一URLは上書き（最新版だけ保持） */
    public void upsertOffline(String url, String title, String file, long size) {
        ContentValues v = new ContentValues();
        v.put("url", url);
        v.put("title", title);
        v.put("file", file);
        v.put("size", size);
        v.put("ts", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "offline", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void deleteOffline(long id) {
        getWritableDatabase().delete("offline", "id=?", new String[]{String.valueOf(id)});
    }

    public List<OfflinePage> listOffline() {
        return offlineQuery("SELECT id,url,title,file,size,ts FROM offline ORDER BY ts DESC");
    }

    public long offlineTotalBytes() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(size),0) FROM offline", null);
        long r = c.moveToFirst() ? c.getLong(0) : 0;
        c.close();
        return r;
    }

    public int offlineCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM offline", null);
        int r = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return r;
    }

    /** 上限(バイト/件数)を超えてはみ出した古い分を返す（新しい方から数えて超過したもの） */
    public List<OfflinePage> offlineOverflow(long maxBytes, int maxCount) {
        List<OfflinePage> out = new ArrayList<>();
        long acc = 0;
        int n = 0;
        for (OfflinePage p : listOffline()) { // ts降順
            acc += p.size;
            n++;
            if (acc > maxBytes || n > maxCount) out.add(p);
        }
        return out;
    }

    private List<OfflinePage> offlineQuery(String sql) {
        List<OfflinePage> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        while (c.moveToNext())
            out.add(new OfflinePage(c.getLong(0), c.getString(1), c.getString(2),
                    c.getString(3), c.getLong(4), c.getLong(5)));
        c.close();
        return out;
    }

    private List<Entry> list(String table, int limit) {
        List<Entry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, url, title, ts FROM " + table + " ORDER BY ts DESC"
                        + (limit > 0 ? " LIMIT " + limit : ""), null);
        while (c.moveToNext())
            out.add(new Entry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)));
        c.close();
        return out;
    }
}
