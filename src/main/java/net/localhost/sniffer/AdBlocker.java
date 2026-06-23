package net.localhost.sniffer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 広告ブロック＋ブロック系設定の置き場。
 *
 * - ドメイン単位ブロック: 内蔵シード + 280blockerドメインリスト（フィルター更新でDL）
 * - カスタムフィルター: URL部分一致（BrowserDbのfiltersテーブル）
 * - 設定トグル: adblock / popupBlock / redirectBlock（SharedPreferences "settings"）
 *
 * shouldBlock() はネットワークスレッドから呼ばれるので、参照の差し替えだけで
 * ロックなしに動くよう volatile なイミュータブル集合を持つ。
 */
public class AdBlocker {

    private static volatile AdBlocker inst;

    public static AdBlocker get(Context ctx) {
        if (inst == null) {
            synchronized (AdBlocker.class) {
                if (inst == null) inst = new AdBlocker(ctx.getApplicationContext());
            }
        }
        return inst;
    }

    /** リスト未取得でも最低限効く内蔵シード（グローバル+日本のメジャー広告/SSP） */
    private static final String[] SEED = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "google-analytics.com",
            "amazon-adsystem.com", "adnxs.com", "criteo.com", "criteo.net",
            "taboola.com", "outbrain.com", "popads.net", "popcash.net",
            "propellerads.com", "exoclick.com", "juicyads.com",
            "trafficjunky.net", "adsterra.com", "hilltopads.net",
            "i-mobile.co.jp", "adingo.jp", "fluct.jp", "impact-ad.jp",
            "microad.jp", "nend.net", "zucks.net", "gmossp-sp.jp",
            "socdm.com", "logly.co.jp", "deqwas.net",
    };

    private final Context app;
    private final SharedPreferences sp;
    private volatile Set<String> domains = new HashSet<>();
    private volatile List<String> customs = new ArrayList<>();

    private AdBlocker(Context app) {
        this.app = app;
        this.sp = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        // 3000行規模のblocklist解析＋DB openは重く、onCreate上で同期実行すると起動が固まる。
        // domains/customsはvolatileで空初期化済みなので、初回ロードは別スレッドへ逃がす
        // （ロード完了までの一瞬だけシードが未適用になるが、起動直後のホームはローカルdata）。
        new Thread(this::reload).start();
    }

    // ---- 設定トグル ----

    public boolean adblockOn()       { return sp.getBoolean("adblock", true); }
    public boolean popupBlockOn()    { return sp.getBoolean("popupBlock", true); }
    public boolean redirectBlockOn() { return sp.getBoolean("redirectBlock", false); }
    public void set(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }

    public int ruleCount() { return domains.size() + customs.size(); }

    // ---- 判定 ----

    /** ブロック対象なら空レスポンス、素通しなら null */
    public WebResourceResponse shouldBlock(Uri uri) {
        if (!adblockOn() || uri == null) return null;
        Set<String> d = domains;
        String h = uri.getHost();
        while (h != null && !h.isEmpty()) {
            if (d.contains(h)) return empty(h);
            int i = h.indexOf('.');
            if (i < 0) break;
            h = h.substring(i + 1);
        }
        List<String> c = customs;
        if (!c.isEmpty()) {
            String s = uri.toString();
            for (String p : c)
                if (!p.isEmpty() && s.contains(p)) return empty(p);
        }
        return null;
    }

    private WebResourceResponse empty(String why) {
        Log.d("AdBlock", "block " + why);
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }

    /** eTLD+1近似（co.jp等の属性型ドメインは3ラベル）。リダイレクトブロックの同一サイト判定用 */
    public static String site(String host) {
        if (host == null) return "";
        String[] p = host.split("\\.");
        int n = p.length;
        if (n <= 2) return host;
        String last2 = p[n - 2] + "." + p[n - 1];
        if (last2.matches("(co|ne|or|ac|go|ed|lg)\\.jp")
                || last2.matches("(com|net|org|gov|edu|ac|co)\\.[a-z]{2}"))
            return p[n - 3] + "." + last2;
        return last2;
    }

    // ---- リスト管理 ----

    private File listFile() { return new File(app.getFilesDir(), "blocklist.txt"); }

    /** シード+DLリスト+カスタムフィルターを読み直して差し替える */
    public synchronized void reload() {
        Set<String> d = new HashSet<>(Arrays.asList(SEED));
        File f = listFile();
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String h = parseLine(line);
                    if (h != null) d.add(h);
                }
            } catch (IOException e) {
                Log.e("AdBlock", "blocklist読み込み失敗: " + e);
            }
        }
        List<String> c;
        try (BrowserDb db = new BrowserDb(app)) {
            c = db.listFilterPatterns();
        }
        domains = d;
        customs = c;
    }

    /** hosts形式/素のドメイン列挙の両対応で1行をドメインに正規化 */
    private static String parseLine(String line) {
        int sharp = line.indexOf('#');
        if (sharp >= 0) line = line.substring(0, sharp);
        line = line.trim();
        if (line.isEmpty() || line.startsWith("!")) return null;
        String[] tok = line.split("\\s+");
        String h = tok[tok.length - 1].toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.equals("0.0.0.0") || h.equals("127.0.0.1")) return null;
        if (!h.contains(".")) return null;
        return h;
    }

    /** 280blockerドメインリストをDLして反映。戻り値=反映後ルール数 */
    public int update() throws IOException {
        String ym = new SimpleDateFormat("yyyyMM", Locale.ROOT).format(new Date());
        String[] urls = {
                "https://280blocker.net/files/280blocker_domain.txt",
                "https://280blocker.net/files/280blocker_domain_" + ym + ".txt",
        };
        IOException last = null;
        for (String su : urls) {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(su).openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(30000);
                con.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile");
                if (con.getResponseCode() != 200)
                    throw new IOException("HTTP " + con.getResponseCode() + " " + su);
                File tmp = new File(listFile().getPath() + ".tmp");
                try (InputStream in = con.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (!tmp.renameTo(listFile()))
                    throw new IOException("rename失敗");
                reload();
                return ruleCount();
            } catch (IOException e) {
                last = e;
            } finally {
                if (con != null) con.disconnect();
            }
        }
        throw last != null ? last : new IOException("更新失敗");
    }
}
