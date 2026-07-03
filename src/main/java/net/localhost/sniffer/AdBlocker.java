package net.localhost.sniffer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import org.json.JSONObject;

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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 広告ブロック＋ブロック系設定の置き場。
 *
 * - ドメイン単位ブロック: 内蔵シード + 複数フィルターリスト（フィルター更新でDL）
 *   対応形式: hosts / 素のドメイン列挙 / ABPの ||domain^（オプション無しのみ）
 * - コスメティックフィルタ（要素隠し）: EasyList等の ##セレクタ を<style>注入で適用
 * - カスタムフィルター: URL部分一致（BrowserDbのfiltersテーブル）
 * - 設定トグル: adblock / popupBlock / redirectBlock / cosmetic（SharedPreferences "settings"）
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

    /**
     * フィルターリストのソース。{ファイル名, URL, フォールバックURL...}。
     * どれかが落ちても他ソースで続行する（280blockerはUA/回線次第で403が出る）。
     * easylist/adguard-jpはネットワークルールのドメイン抽出に加え##コスメティックも取り込む。
     */
    private static final String[][] SOURCES = {
            {"280blocker.txt", "https://280blocker.net/files/280blocker_domain.txt"},
            {"adguard-dns.txt", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"},
            {"hagezi-light.txt", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/light.txt"},
            {"easylist.txt", "https://easylist.to/easylist/easylist.txt"},
            {"adguard-jp.txt", "https://filters.adtidy.org/extension/ublock/filters/7.txt"},
    };

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

    /** 巨大リスト対策: 汎用(全サイト適用)セレクタの上限。超過分は捨てる */
    private static final int MAX_GENERIC = 15000;
    /** CSSルール1本あたりのセレクタ数。不正セレクタ1個の巻き添えをチャンク内に留める */
    private static final int CSS_CHUNK = 50;

    private final Context app;
    private final SharedPreferences sp;
    private volatile Set<String> domains = new HashSet<>();
    private volatile List<String> customs = new ArrayList<>();

    // ---- コスメティック（要素隠し）----
    private volatile List<String> genericSel = new ArrayList<>();          // 全サイト適用セレクタ
    private volatile String genericCss = "";                               // ↑を連結したCSS
    private volatile String genericJs = null;                              // ↑の注入JS（遅延キャッシュ）
    private volatile Map<String, List<String>> siteSel = new HashMap<>();  // ドメイン→追加セレクタ
    private volatile Map<String, Set<String>> siteUnhide = new HashMap<>();// ドメイン→除外(#@#)セレクタ
    private volatile int cosmeticCount = 0;

    private AdBlocker(Context app) {
        this.app = app;
        this.sp = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        // 数十万行のリスト解析＋DB openは重く、onCreate上で同期実行すると起動が固まる。
        // 各集合はvolatileで空初期化済みなので、初回ロードは別スレッドへ逃がす
        // （ロード完了までの一瞬だけシードが未適用になるが、起動直後のホームはローカルdata）。
        new Thread(this::reload).start();
    }

    // ---- 設定トグル ----

    public boolean adblockOn()       { return sp.getBoolean("adblock", true); }
    public boolean popupBlockOn()    { return sp.getBoolean("popupBlock", true); }
    public boolean redirectBlockOn() { return sp.getBoolean("redirectBlock", false); }
    public boolean cosmeticOn()      { return sp.getBoolean("cosmetic", true); }
    public void set(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }

    public int ruleCount() { return domains.size() + customs.size(); }
    public int cosmeticCount() { return cosmeticCount; }

    // ---- 判定（ネットワークルール）----

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

    // ---- コスメティック（要素隠し）の適用 ----

    /**
     * onPageStarted/onPageFinishedから呼ぶ。ページに適用すべき要素隠しCSSを<style>で注入する。
     * 二重呼び出しはid付きstyleの上書きになるだけで無害（SPA遷移でも効き続ける）。
     */
    public void injectCosmetics(WebView web, String url) {
        if (!adblockOn() || !cosmeticOn()) return;
        String js = cosmeticJsFor(url);
        if (js != null) web.evaluateJavascript(js, null);
    }

    private String cosmeticJsFor(String url) {
        String host = null;
        try { host = Uri.parse(url).getHost(); } catch (Throwable ignore) {}
        if (host == null || host.isEmpty()) return null;

        // サイト固有の追加/除外ルールをホストのサフィックス（sub.a.com→a.com→com）で収集
        List<String> extra = null;
        Set<String> unhide = null;
        Map<String, List<String>> ss = siteSel;
        Map<String, Set<String>> su = siteUnhide;
        String h = host;
        while (h != null && !h.isEmpty()) {
            List<String> s = ss.get(h);
            if (s != null) {
                if (extra == null) extra = new ArrayList<>();
                extra.addAll(s);
            }
            Set<String> u = su.get(h);
            if (u != null) {
                if (unhide == null) unhide = new HashSet<>();
                unhide.addAll(u);
            }
            int i = h.indexOf('.');
            if (i < 0) break;
            h = h.substring(i + 1);
        }

        if (extra == null && unhide == null) {
            // 大多数のサイト: 事前連結済みの汎用CSSをそのまま（注入JSもキャッシュ）
            if (genericCss.isEmpty()) return null;
            String js = genericJs;
            if (js == null) genericJs = js = wrapCssJs(genericCss);
            return js;
        }
        // サイト固有ルールあり: 汎用（除外を差し引き）＋固有分を合成
        StringBuilder css = new StringBuilder();
        if (unhide == null) {
            css.append(genericCss);
        } else {
            List<String> filtered = new ArrayList<>();
            for (String s : genericSel) if (!unhide.contains(s)) filtered.add(s);
            css.append(buildCss(filtered));
        }
        if (extra != null) {
            if (unhide != null) {
                List<String> f2 = new ArrayList<>();
                for (String s : extra) if (!unhide.contains(s)) f2.add(s);
                extra = f2;
            }
            css.append(buildCss(extra));
        }
        if (css.length() == 0) return null;
        return wrapCssJs(css.toString());
    }

    /** CSS文字列を id付き<style> として注入するJSに包む */
    private static String wrapCssJs(String css) {
        return "(function(){try{var id='__gobieCosmetic';"
                + "var st=document.getElementById(id);"
                + "if(!st){st=document.createElement('style');st.id=id;"
                + "(document.head||document.documentElement).appendChild(st);}"
                + "st.textContent=" + JSONObject.quote(css) + ";}catch(e){}})();";
    }

    /** セレクタ群を display:none のCSSへ。チャンク分けで不正セレクタの巻き添えを局所化 */
    private static String buildCss(List<String> sels) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < sels.size(); i += CSS_CHUNK) {
            int end = Math.min(i + CSS_CHUNK, sels.size());
            for (int j = i; j < end; j++) {
                if (j > i) b.append(',');
                b.append(sels.get(j));
            }
            b.append("{display:none!important}\n");
        }
        return b.toString();
    }

    // ---- リスト管理 ----

    private File legacyListFile() { return new File(app.getFilesDir(), "blocklist.txt"); }
    private File filterDir() { return new File(app.getFilesDir(), "filters"); }

    /** 解析中の一時置き場。完了後にvolatile参照へ一括差し替えする */
    private static class Acc {
        final Set<String> domains = new HashSet<>();
        final List<String> generic = new ArrayList<>();
        final Set<String> genericUnhide = new HashSet<>();
        final Map<String, List<String>> site = new HashMap<>();
        final Map<String, Set<String>> unhide = new HashMap<>();
    }

    /** シード+DLリスト+カスタムフィルターを読み直して差し替える */
    public synchronized void reload() {
        Acc acc = new Acc();
        acc.domains.addAll(Arrays.asList(SEED));
        parseFile(legacyListFile(), acc); // 旧単一ファイル（280blocker）互換
        File[] files = filterDir().listFiles();
        if (files != null) for (File f : files) parseFile(f, acc);

        List<String> c;
        try (BrowserDb db = new BrowserDb(app)) {
            c = db.listFilterPatterns();
        }

        // 汎用セレクタ: グローバル除外(#@#)を差し引き、上限で切ってCSSを事前連結
        List<String> gen = new ArrayList<>();
        for (String s : acc.generic) {
            if (acc.genericUnhide.contains(s)) continue;
            gen.add(s);
            if (gen.size() >= MAX_GENERIC) break;
        }
        int siteCount = 0;
        for (List<String> v : acc.site.values()) siteCount += v.size();

        domains = acc.domains;
        customs = c;
        genericSel = gen;
        genericCss = buildCss(gen);
        genericJs = null; // 次回注入時に再キャッシュ
        siteSel = acc.site;
        siteUnhide = acc.unhide;
        cosmeticCount = gen.size() + siteCount;
        Log.i("AdBlock", "reload: domains=" + acc.domains.size()
                + " generic=" + gen.size() + " site=" + siteCount);
        Dbg.log(app, "AdBlock reload: domains=" + acc.domains.size()
                + " generic=" + gen.size() + " site=" + siteCount
                + " customs=" + c.size());
    }

    private static void parseFile(File f, Acc acc) {
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f), 65536)) {
            String line;
            while ((line = r.readLine()) != null) parseLine(line, acc);
        } catch (IOException e) {
            Log.e("AdBlock", "リスト読み込み失敗 " + f.getName() + ": " + e);
        }
    }

    /** 1行をドメインブロック or コスメティックルールとして取り込む */
    static void parseLine(String line, Acc acc) {
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("!") || s.startsWith("[")) return;

        // コスメティック系（#@# / #?# / #$# / #%# は ## より先に判定）
        int i;
        if (s.contains("#?#") || s.contains("#$#") || s.contains("#%#")) return; // 拡張CSS/scriptletは非対応
        if ((i = s.indexOf("#@#")) >= 0) { addCosmetic(s.substring(0, i), s.substring(i + 3), true, acc); return; }
        if ((i = s.indexOf("##")) >= 0)  { addCosmetic(s.substring(0, i), s.substring(i + 2), false, acc); return; }

        if (s.startsWith("@@")) return; // 例外ルールはドメイン粒度では危険なので非対応
        String h = parseDomain(s);
        if (h != null) acc.domains.add(h);
    }

    /** uBlock/AdGuard拡張の擬似セレクタ（素のCSSとして不正、チャンクごと無効化される） */
    private static final String[] EXT_PSEUDO = {
            ":style(", ":has-text(", ":contains(", ":matches-", ":upward(", ":xpath(",
            ":remove(", ":remove-", ":nth-ancestor(", ":min-text-length(", ":watch-attr(",
            ":-abp-", ":others(",
    };

    /** ##/#@# ルールを取り込む。domainsPart空=全サイト適用 */
    private static void addCosmetic(String domainsPart, String sel, boolean isUnhide, Acc acc) {
        sel = sel.trim();
        // scriptlet(+js)・スタイル注入・壊れたセレクタは捨てる
        if (sel.isEmpty() || sel.length() > 250 || sel.startsWith("+js")
                || sel.indexOf('{') >= 0 || sel.indexOf('}') >= 0) return;
        for (String p : EXT_PSEUDO) if (sel.contains(p)) return;
        domainsPart = domainsPart.trim();
        if (domainsPart.isEmpty()) {
            if (isUnhide) acc.genericUnhide.add(sel);
            else acc.generic.add(sel);
            return;
        }
        for (String d : domainsPart.split(",")) {
            d = d.trim().toLowerCase(Locale.ROOT);
            if (d.isEmpty() || d.startsWith("~") || !d.contains(".")) continue; // 否定条件は非対応
            if (isUnhide) {
                Set<String> set = acc.unhide.get(d);
                if (set == null) acc.unhide.put(d, set = new HashSet<>());
                set.add(sel);
            } else {
                List<String> l = acc.site.get(d);
                if (l == null) acc.site.put(d, l = new ArrayList<>());
                l.add(sel);
            }
        }
    }

    /** hosts形式/素のドメイン列挙/ABPの||domain^ の3対応で1行をドメインに正規化 */
    static String parseDomain(String line) {
        // ABP: ||example.com^ （パス・オプション付きは誤爆するので丸ごとドメインには採らない）
        if (line.startsWith("||")) {
            int caret = line.indexOf('^');
            if (caret < 0 || caret != line.length() - 1) return null;
            String h = line.substring(2, caret).toLowerCase(Locale.ROOT);
            return validDomain(h) ? h : null;
        }
        int sharp = line.indexOf('#');
        if (sharp >= 0) line = line.substring(0, sharp);
        line = line.trim();
        if (line.isEmpty()) return null;
        String[] tok = line.split("\\s+");
        String h = tok[tok.length - 1].toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.equals("0.0.0.0") || h.equals("127.0.0.1")) return null;
        return validDomain(h) ? h : null;
    }

    private static boolean validDomain(String h) {
        if (h.isEmpty() || !h.contains(".")) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!(c == '.' || c == '-' || c == '_'
                    || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    /**
     * 全ソースをDLして反映。1ソースでも成功すれば続行し、結果サマリを返す。
     * 280blockerはUA/回線次第で403が出るため、当月版URLへのフォールバックも試す。
     */
    public String update() throws IOException {
        File dir = filterDir();
        dir.mkdirs();
        int ok = 0;
        StringBuilder fails = new StringBuilder();
        for (String[] src : SOURCES) {
            List<String> urls = new ArrayList<>();
            for (int u = 1; u < src.length; u++) urls.add(src[u]);
            if (src[0].startsWith("280blocker")) {
                String ym = new SimpleDateFormat("yyyyMM", Locale.ROOT).format(new Date());
                urls.add("https://280blocker.net/files/280blocker_domain_" + ym + ".txt");
            }
            Throwable last = null;
            boolean done = false;
            for (String su : urls) {
                try {
                    download(su, new File(dir, src[0]));
                    done = true;
                    break;
                } catch (Throwable e) {
                    last = e;
                }
            }
            if (done) {
                ok++;
                Dbg.log(app, "AdBlock update OK: " + src[0]
                        + " (" + new File(dir, src[0]).length() + "B)");
            } else {
                Log.w("AdBlock", "取得失敗 " + src[0] + ": " + last);
                Dbg.log(app, "AdBlock update FAIL: " + src[0] + " : " + last);
                if (fails.length() > 0) fails.append(", ");
                fails.append(src[0].replace(".txt", ""));
            }
        }
        if (ok == 0) throw new IOException("全ソース取得失敗: " + fails);
        reload();
        String msg = "ドメイン " + domains.size() + "件 / 要素隠し " + cosmeticCount + "件"
                + "（" + ok + "/" + SOURCES.length + "ソース）";
        if (fails.length() > 0) msg += "\n取得失敗: " + fails;
        return msg;
    }

    private static void download(String su, File out) throws IOException {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(su).openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(60000);
            con.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Mobile");
            if (con.getResponseCode() != 200)
                throw new IOException("HTTP " + con.getResponseCode() + " " + su);
            File tmp = new File(out.getPath() + ".tmp");
            try (InputStream in = con.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            if (!tmp.renameTo(out)) throw new IOException("rename失敗");
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
