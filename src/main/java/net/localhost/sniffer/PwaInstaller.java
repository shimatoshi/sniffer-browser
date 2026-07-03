package net.localhost.sniffer;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 現在ページをPWAとしてホームにピン留めする。
 * manifest.json から name/icons/start_url/theme_color を取り、PwaActivity 起動ショートカットを作る。
 * manifestが無いページでも タイトル+favicon でフォールバック。
 */
public class PwaInstaller {

    /** UIスレッドから呼ぶこと（evaluateJavascript使用）。desktop=PC版サイト表示のまま開くPWAにする */
    public static void install(Activity act, WebView web, String pageUrl, String pageTitle, boolean desktop) {
        final Bitmap favicon = web.getFavicon(); // UIスレッドで先に確保
        final String uaStr = web.getSettings().getUserAgentString();
        web.evaluateJavascript(
                "(function(){var l=document.querySelector('link[rel~=\"manifest\"]');return l?l.href:'';})()",
                value -> {
                    String manifestUrl = unquote(value);
                    new Thread(() -> buildAndPin(act, manifestUrl, pageUrl, pageTitle, favicon, uaStr, desktop)).start();
                });
    }

    private static void buildAndPin(Activity act, String manifestUrl, String pageUrl,
                                    String pageTitle, Bitmap favicon, String ua, boolean desktop) {
        String name = pageTitle;
        String start = pageUrl;
        String theme = null;
        Bitmap icon = favicon;
        try {
            if (manifestUrl != null && !manifestUrl.isEmpty()) {
                JSONObject m = new JSONObject(httpGetText(manifestUrl, ua));
                String n = m.optString("short_name", "");
                if (n.isEmpty()) n = m.optString("name", "");
                if (!n.isEmpty()) name = n;
                String su = m.optString("start_url", "");
                if (!su.isEmpty()) start = resolve(manifestUrl, su);
                String tc = m.optString("theme_color", "");
                if (!tc.isEmpty()) theme = tc;
                String iconUrl = pickIcon(m.optJSONArray("icons"), manifestUrl);
                if (iconUrl != null) {
                    Bitmap b = httpGetBitmap(iconUrl, ua);
                    if (b != null) icon = b;
                }
            }
        } catch (Throwable ignore) {} // manifest無し/壊れててもフォールバックで続行

        if (name == null || name.isEmpty()) {
            try { name = Uri.parse(start).getHost(); } catch (Throwable ignore) {}
            if (name == null || name.isEmpty()) name = "PWA";
        }
        final String fName = name, fStart = start, fTheme = theme;
        final Bitmap fIcon = icon != null ? fitIcon(icon) : letterIcon(name);
        // ブラウザホームに並べるため台帳へ保存（start_url一意・アイコン込み）
        try {
            new BrowserDb(act.getApplicationContext())
                    .upsertPwa(fStart, fName, fTheme, pngBase64(fIcon), desktop);
        } catch (Throwable ignore) {}
        act.runOnUiThread(() -> pin(act, fStart, fName, fTheme, fIcon, desktop));
    }

    /**
     * ランチャーにピン留め済みの自アプリPWAショートカットを台帳へ取り込む（既存PWA救済）。
     * すでに台帳にあるURLは触らない。アイコンは取り出せないので頭文字アイコンで埋める。
     * ワーカースレッドから呼ぶこと。
     */
    static void syncFromShortcuts(android.content.Context ctx) {
        if (Build.VERSION.SDK_INT < 25) return;
        try {
            ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            BrowserDb db = new BrowserDb(ctx.getApplicationContext());
            for (ShortcutInfo si : sm.getPinnedShortcuts()) {
                Intent it = si.getIntent();
                if (it == null || it.getComponent() == null || it.getData() == null) continue;
                if (!PwaActivity.class.getName().equals(it.getComponent().getClassName())) continue;
                String url = it.getData().toString();
                CharSequence lbl = si.getShortLabel();
                String name = lbl != null ? lbl.toString() : "";
                String theme = it.getStringExtra("pwa_theme");
                boolean desktop = it.getBooleanExtra("pwa_desktop", false);
                db.insertPwaIfAbsent(url, name, theme, pngBase64(letterIcon(name)), desktop);
            }
        } catch (Throwable ignore) {}
    }

    /** Bitmap → PNGのbase64文字列（data URIの本体）。 */
    static String pngBase64(Bitmap b) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private static void pin(Activity act, String start, String name, String theme, Bitmap icon, boolean desktop) {
        Intent launch = new Intent(Intent.ACTION_VIEW, Uri.parse(start), act, PwaActivity.class);
        launch.putExtra("pwa_title", name);
        if (theme != null) launch.putExtra("pwa_theme", theme);
        if (desktop) launch.putExtra("pwa_desktop", true);
        // NEW_DOCUMENTのみ（MULTIPLE_TASKは付けない）。documentLaunchMode=intoExistingと組で
        // 同一URLのPWAは既存タスクを再利用→再起動でタスクが増殖しない。別URLは別タスクのまま。
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ShortcutManager sm = act.getSystemService(ShortcutManager.class);
                if (sm != null && sm.isRequestPinShortcutSupported()) {
                    ShortcutInfo si = new ShortcutInfo.Builder(act,
                            "pwa-" + Integer.toHexString(start.hashCode()))
                            .setShortLabel(name)
                            .setIcon(Icon.createWithBitmap(icon))
                            .setIntent(launch)
                            .build();
                    sm.requestPinShortcut(si, null);
                    Toast.makeText(act, "PWA追加: " + name, Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            // API25以下 or ピン非対応ランチャー: 旧ブロードキャスト方式
            Intent add = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            add.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch);
            add.putExtra(Intent.EXTRA_SHORTCUT_NAME, name);
            add.putExtra(Intent.EXTRA_SHORTCUT_ICON, icon);
            act.sendBroadcast(add);
            Toast.makeText(act, "PWA追加(legacy): " + name, Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(act, "ショートカット作成失敗: " + e, Toast.LENGTH_LONG).show();
        }
    }

    // ---- manifest helpers ----

    /** icons[] から最大サイズのものを選ぶ（src は manifestUrl 基準で解決） */
    private static String pickIcon(JSONArray icons, String baseUrl) {
        if (icons == null) return null;
        String best = null;
        int bestSize = -1;
        for (int i = 0; i < icons.length(); i++) {
            JSONObject ic = icons.optJSONObject(i);
            if (ic == null) continue;
            String src = ic.optString("src", "");
            if (src.isEmpty()) continue;
            int size = 0;
            String sizes = ic.optString("sizes", "");
            try { size = Integer.parseInt(sizes.split("[xX]")[0].trim()); } catch (Throwable ignore) {}
            if (size >= bestSize) {
                bestSize = size;
                best = resolve(baseUrl, src);
            }
        }
        return best;
    }

    private static String resolve(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); }
        catch (Throwable e) { return rel; }
    }

    private static HttpURLConnection open(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", ua);
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
        } catch (Throwable ignore) {}
        return c;
    }

    private static String httpGetText(String url, String ua) throws Exception {
        HttpURLConnection c = open(url, ua);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static Bitmap httpGetBitmap(String url, String ua) {
        try {
            HttpURLConnection c = open(url, ua);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally { c.disconnect(); }
        } catch (Throwable e) { return null; }
    }

    // ---- icon helpers ----

    /** ランチャー/binder向けに常識的なサイズに収める */
    private static Bitmap fitIcon(Bitmap b) {
        int max = 256;
        if (b.getWidth() <= max && b.getHeight() <= max) return b;
        float r = Math.min((float) max / b.getWidth(), (float) max / b.getHeight());
        return Bitmap.createScaledBitmap(b,
                Math.max(1, (int) (b.getWidth() * r)),
                Math.max(1, (int) (b.getHeight() * r)), true);
    }

    /** アイコンが取れなかったとき: 頭文字1字の生成アイコン */
    private static Bitmap letterIcon(String name) {
        int sz = 192;
        Bitmap b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(0xFF455A64);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(sz * 0.55f);
        p.setTextAlign(Paint.Align.CENTER);
        String ch = name.isEmpty() ? "P" : name.substring(0, 1).toUpperCase();
        float y = sz / 2f - (p.descent() + p.ascent()) / 2f;
        cv.drawText(ch, sz / 2f, y, p);
        return b;
    }

    /** evaluateJavascript の戻り値("..."形式 or "null")を素のStringへ */
    private static String unquote(String v) {
        if (v == null || v.equals("null")) return "";
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\""))
            return v.substring(1, v.length() - 1);
        return v;
    }
}
