package net.localhost.sniffer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自作PWAの一括登録。新しい端末をセットアップするときに、ブラウザで1件ずつ
 * 「PWAとしてホームに追加」して回らずに済ませるための入口。
 *
 * adbから:
 *   一覧をlogcatへ:  am start -n net.localhost.sniffer/.PwaImportActivity
 *   一括登録:        am start -n net.localhost.sniffer/.PwaImportActivity \
 *                      --es file /sdcard/Download/pwas.json [--ez pin true]
 *
 * JSONは配列。各要素は {url, name, icon?, theme?, desktop?, zoom?}。
 * icon はPNG/JPEGのURL（省略時は頭文字アイコン）。
 * pin=true のときだけランチャーへのピン留めも要求する（ランチャーの確認ダイアログが
 * 件数分出る）。既定は台帳(pwas)への登録のみで、メニューの「アプリ（PWA）」に並ぶ。
 *
 * 冪等性: 台帳のキーは url 完全一致なので、末尾スラッシュ違い等で同じサイトが二重登録
 * されうる。そこで「ホスト+パス先頭1階層」を同一性の基準にして、
 *  - 台帳内の重複は古い方(id小)を残して削除
 *  - すでに同じサイトがある行はユーザーが付けた名前を尊重してスキップ
 * とする。何度流し込んでも増殖しない。
 */
public class PwaImportActivity extends Activity {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 8.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";
    private static final String TAG = "PwaImport";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent it = getIntent();
        final String path = it != null ? it.getStringExtra("file") : null;
        final String inline = it != null ? it.getStringExtra("json") : null;
        final boolean pin = it != null && it.getBooleanExtra("pin", false);

        new Thread(() -> {
            BrowserDb db = new BrowserDb(getApplicationContext());
            Map<String, BrowserDb.Pwa> bySite = dedupe(db);

            String body = inline;
            if (body == null && path != null) body = readFile(path);
            if (body == null) {
                // 引数なし = 現在の台帳をlogcatへ出すだけ（確認用）
                for (BrowserDb.Pwa p : db.listPwas()) log("ledger: " + p.id + " | " + p.label() + " | " + p.url);
                log("ledger: 合計" + db.listPwas().size() + "件");
                db.close();
                finish();
                return;
            }

            int added = 0, skipped = 0, failed = 0;
            try {
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) { failed++; continue; }
                    String url = o.optString("url", "");
                    if (url.isEmpty()) { failed++; continue; }

                    String key = siteKey(url);
                    BrowserDb.Pwa exist = bySite.get(key);
                    if (exist != null) {
                        // 台帳にはもうある（ユーザーが付けた名前を尊重してそのまま）。
                        // pin指定のときはピン留めだけ既存の内容で行う。
                        skipped++;
                        if (pin) { pinExisting(exist); sleepBetweenPins(); }
                        continue;
                    }

                    String name = o.optString("name", "");
                    if (name.isEmpty()) {
                        try { name = Uri.parse(url).getHost(); } catch (Throwable ignore) {}
                        if (name == null || name.isEmpty()) name = "PWA";
                    }
                    String theme = o.has("theme") ? o.optString("theme", null) : null;
                    boolean desktop = o.optBoolean("desktop", false);
                    int zoom = o.optInt("zoom", 100);

                    Bitmap icon = null;
                    String iconUrl = o.optString("icon", "");
                    if (!iconUrl.isEmpty()) icon = PwaInstaller.httpGetBitmap(iconUrl, UA);
                    icon = icon != null ? PwaInstaller.fitIcon(icon) : PwaInstaller.letterIcon(name);

                    try {
                        db.upsertPwa(url, name, theme, PwaInstaller.pngBase64(icon), desktop, zoom);
                        added++;
                    } catch (Throwable e) { failed++; continue; }

                    BrowserDb.Pwa saved = db.getPwaByUrl(url);
                    if (saved != null) bySite.put(key, saved);

                    if (pin) {
                        final String fUrl = url, fName = name, fTheme = theme;
                        final Bitmap fIcon = icon;
                        final boolean fDesk = desktop;
                        final int fZoom = zoom;
                        runOnUiThread(() -> PwaInstaller.pin(this, fUrl, fName, fTheme, fIcon, fDesk, fZoom, true));
                        sleepBetweenPins();
                    }
                }
            } catch (Throwable e) {
                log("一括登録に失敗: " + e);
                toast("PWA一括登録に失敗: " + e);
                db.close();
                finish();
                return;
            }
            String msg = "PWA一括登録: 追加" + added + " / 既存スキップ" + skipped + " / 失敗" + failed
                    + " (台帳計" + db.listPwas().size() + ")";
            log(msg);
            toast(msg);
            db.close();
            finish();
        }).start();
    }

    /** 既に台帳にある行を、その内容のままランチャーへピン留め要求する。 */
    private void pinExisting(BrowserDb.Pwa p) {
        Bitmap icon = null;
        try {
            if (p.icon != null && !p.icon.isEmpty()) {
                byte[] raw = android.util.Base64.decode(p.icon, android.util.Base64.DEFAULT);
                icon = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
            }
        } catch (Throwable ignore) {}
        final Bitmap fIcon = icon != null ? PwaInstaller.fitIcon(icon) : PwaInstaller.letterIcon(p.label());
        runOnUiThread(() -> PwaInstaller.pin(this, p.url, p.label(), p.theme, fIcon, p.desktop, p.zoom, true));
    }

    /** ランチャーの確認ダイアログが重ならないよう間隔を空ける */
    private void sleepBetweenPins() {
        try { Thread.sleep(1500); } catch (InterruptedException ignore) {}
    }

    /** 台帳内の同一サイト重複を、古い方(id小)を残して掃除し、サイトキー→残った行 を返す。 */
    private Map<String, BrowserDb.Pwa> dedupe(BrowserDb db) {
        Map<String, BrowserDb.Pwa> keep = new HashMap<>();
        List<BrowserDb.Pwa> all = db.listPwas(); // id昇順
        for (BrowserDb.Pwa p : all) {
            String key = siteKey(p.url);
            BrowserDb.Pwa prev = keep.get(key);
            if (prev == null) {
                keep.put(key, p);
            } else {
                log("重複削除: " + p.id + " | " + p.label() + " | " + p.url + " (残す: " + prev.id + ")");
                db.removePwa(p.id);
            }
        }
        return keep;
    }

    /** 同一サイト判定キー: ホスト + パス先頭1階層（末尾スラッシュ/クエリ差を吸収）。 */
    static String siteKey(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return url;
            String path = u.getPath();
            String seg = "";
            if (path != null) {
                String[] parts = path.split("/");
                for (String s : parts) { if (!s.isEmpty()) { seg = s; break; } }
                // index.html のようなファイル名はディレクトリ階層ではないので同一視する
                if (seg.contains(".")) seg = "";
            }
            return host.toLowerCase() + "/" + seg.toLowerCase();
        } catch (Throwable e) { return url; }
    }

    private String readFile(String path) {
        try (InputStream in = new FileInputStream(new File(path))) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable e) {
            log("読み込み失敗: " + path + " (" + e + ")");
            return null;
        }
    }

    private void log(String msg) { android.util.Log.i(TAG, msg); }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
