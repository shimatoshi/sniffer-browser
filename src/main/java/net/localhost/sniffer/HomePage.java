package net.localhost.sniffer;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ブラウザホーム（gobie://home）。
 *
 * - 検索エンジンのロゴ＋検索バー＋背景画像のミニマルなスタートページ
 *   （検索は gobie://search?q=... でMainActivityのgo()へ流す）
 * - 背景画像は filesDir/home_bg.jpg（設定→ホーム画面で選択）。base64で埋め込む
 * - PWA・固定サイトの一覧はホームから⋮メニューへ移動した
 * - 固定サイトの新着オートスクレイプ(collectAll)は記事ウィジェット用に残す
 */
public class HomePage {

    public static final String URL_HOME = "gobie://home";
    private static final int PER_SITE = 5;
    private static final long TTL_MS = 10 * 60 * 1000;
    private static final int FETCH_LIMIT = 512 * 1024;

    public static class Article {
        final String title, url;
        Article(String title, String url) { this.title = title; this.url = url; }
    }

    private static class CacheEnt {
        final List<Article> arts;
        final long ts;
        CacheEnt(List<Article> a) { arts = a; ts = System.currentTimeMillis(); }
    }

    private static final Map<String, CacheEnt> cache = new ConcurrentHashMap<>();

    /**
     * ホームHTMLを生成。ロゴ＋検索バー＋背景画像のみのミニマル構成。
     * 検索エンジンがGoogleならGoogle風4色ロゴ、他はエンジン名を表示。
     */
    public static String render(String bgDataUrl, String engineName) {
        boolean hasBg = bgDataUrl != null && !bgDataUrl.isEmpty();
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
                .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
                .append("<style>")
                .append("html,body{height:100%}")
                .append("body{margin:0;font-family:sans-serif;background:#1a1a1a")
                .append(hasBg ? ";background-image:url(" + bgDataUrl + ");"
                        + "background-size:cover;background-position:center" : "")
                .append("}")
                .append(".wrap{min-height:100%;display:flex;flex-direction:column;")
                .append("align-items:center;justify-content:center;padding:0 16px;")
                .append("box-sizing:border-box;margin-top:-6vh}")
                .append(".logo{font-size:52px;font-weight:bold;letter-spacing:-2px;")
                .append("margin-bottom:26px;user-select:none")
                .append(hasBg ? ";text-shadow:0 1px 8px rgba(0,0,0,.7)" : "")
                .append("}")
                .append(".logo span.w{color:#eee}")
                .append("#q{width:100%;max-width:480px;box-sizing:border-box;")
                .append("padding:13px 22px;font-size:16px;border:none;border-radius:26px;")
                .append("background:rgba(255,255,255,.96);color:#111;outline:none;")
                .append("box-shadow:0 2px 10px rgba(0,0,0,.4)}")
                .append("form{width:100%;display:flex;justify-content:center}")
                .append("</style></head><body><div class='wrap'>");
        b.append("<div class='logo'>");
        if ("Google".equals(engineName)) {
            // Google風4色ロゴ
            String[] cols = {"#4285F4", "#EA4335", "#FBBC05", "#4285F4", "#34A853", "#EA4335"};
            String s = "Google";
            for (int i = 0; i < s.length(); i++)
                b.append("<span style='color:").append(cols[i]).append("'>")
                        .append(s.charAt(i)).append("</span>");
        } else {
            b.append("<span class='w'>").append(esc(engineName)).append("</span>");
        }
        b.append("</div>");
        b.append("<form id='f' action='#'>")
                .append("<input id='q' type='search' placeholder='検索またはURL' ")
                .append("autocomplete='off' autocapitalize='off'></form>");
        b.append("</div><script>")
                .append("document.getElementById('f').addEventListener('submit',function(e){")
                .append("e.preventDefault();var v=document.getElementById('q').value.trim();")
                .append("if(v)location.href='gobie://search?q='+encodeURIComponent(v);});")
                .append("</script></body></html>");
        return b.toString();
    }

    // ---- ホーム背景画像（filesDir/home_bg.jpg をbase64埋め込み、メモリキャッシュ） ----

    private static volatile String bgCache;
    private static volatile long bgMtime = -2; // -2=未読込

    public static File bgFile(Context ctx) { return new File(ctx.getFilesDir(), "home_bg.jpg"); }

    /** 背景画像のdata:URL。未設定ならnull。ファイル更新はmtimeで検知 */
    public static String bgDataUrl(Context ctx) {
        File f = bgFile(ctx);
        long mt = f.exists() ? f.lastModified() : -1;
        if (mt == bgMtime) return bgCache;
        String v = null;
        if (mt != -1) {
            try {
                byte[] buf = new byte[(int) f.length()];
                try (java.io.DataInputStream in = new java.io.DataInputStream(
                        new java.io.FileInputStream(f))) {
                    in.readFully(buf);
                }
                v = "data:image/jpeg;base64," + Base64.encodeToString(buf, Base64.NO_WRAP);
            } catch (Throwable e) {
                v = null;
            }
        }
        bgCache = v;
        bgMtime = mt;
        return v;
    }

    public static void invalidateBg() { bgMtime = -2; bgCache = null; }

    /**
     * 全固定サイトの新着を取得し、サイトラベル付きの一本のリストにまとめて返す（記事ウィジェット用）。
     * ブロッキングなのでワーカースレッドから呼ぶこと。メモリキャッシュも併せて更新する。
     */
    public static List<BrowserDb.FeedItem> collectAll(List<BrowserDb.Entry> pins, String ua) {
        List<BrowserDb.FeedItem> out = new ArrayList<>();
        for (BrowserDb.Entry p : pins) {
            List<Article> arts;
            try {
                arts = fetchSite(p.url, ua);
            } catch (Throwable e) {
                arts = new ArrayList<>();
            }
            cache.put(p.url, new CacheEnt(arts));
            for (Article a : arts) out.add(new BrowserDb.FeedItem(p.label(), a.title, a.url));
        }
        return out;
    }

    // ---- 1サイト分の新着取得 ----

    private static List<Article> fetchSite(String url, String ua) throws Exception {
        String body = http(url, ua);
        if (body == null) return new ArrayList<>();
        if (looksLikeFeed(body)) return parseFeed(body, url);
        // HTMLならRSS autodiscovery
        String feedUrl = findFeedLink(body, url);
        if (feedUrl != null) {
            try {
                String feed = http(feedUrl, ua);
                if (feed != null && looksLikeFeed(feed)) {
                    List<Article> a = parseFeed(feed, feedUrl);
                    if (!a.isEmpty()) return a;
                }
            } catch (Throwable ignore) {}
        }
        return parseAnchors(body, url);
    }

    private static boolean looksLikeFeed(String s) {
        String head = s.substring(0, Math.min(600, s.length()));
        return head.contains("<rss") || head.contains("<feed") || head.contains("<rdf:RDF");
    }

    /** RSS2.0(<item>) / Atom(<entry>) / RSS1.0(rdf item) をざっくりパース */
    static List<Article> parseFeed(String xml, String baseUrl) {
        List<Article> out = new ArrayList<>();
        Matcher item = Pattern.compile("<(item|entry)[\\s>](.*?)</\\1>", Pattern.DOTALL).matcher(xml);
        while (item.find() && out.size() < PER_SITE) {
            String chunk = item.group(2);
            String title = first(chunk, "<title[^>]*>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</title>");
            String link = first(chunk, "<link[^>]*href=[\"']([^\"']+)[\"']"); // Atom
            if (link == null) link = first(chunk, "<link[^>]*>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</link>"); // RSS
            if (title == null || link == null) continue;
            title = unescape(strip(title)).trim();
            link = resolve(baseUrl, link.trim());
            if (!title.isEmpty() && link != null) out.add(new Article(title, link));
        }
        return out;
    }

    private static String findFeedLink(String html, String baseUrl) {
        Matcher m = Pattern.compile(
                "<link[^>]+type=[\"']application/(?:rss|atom)\\+xml[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            String href = first(m.group(), "href=[\"']([^\"']+)[\"']");
            if (href != null) return resolve(baseUrl, unescape(href));
        }
        return null;
    }

    /** フィードが無いサイト向け: 同一ホストへの長文テキストリンクを記事と見なす */
    static List<Article> parseAnchors(String html, String baseUrl) {
        List<Article> out = new ArrayList<>();
        String host = hostOf(baseUrl);
        List<String> seen = new ArrayList<>();
        Matcher m = Pattern.compile("<a\\s[^>]*href=[\"']([^\"'#]+)[\"'][^>]*>(.*?)</a>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find() && out.size() < PER_SITE) {
            String href = resolve(baseUrl, unescape(m.group(1).trim()));
            String text = unescape(strip(m.group(2))).trim().replaceAll("\\s+", " ");
            if (href == null || text.length() < 12) continue;
            if (!host.equals(hostOf(href))) continue;
            // トップ自身・カテゴリ/タグ/ページャっぽいのは除外
            if (href.equals(baseUrl) || href.matches(".*/(category|tag|page|archives?)/.*\\d*/?")) continue;
            if (seen.contains(href)) continue;
            seen.add(href);
            out.add(new Article(text, href));
        }
        return out;
    }

    // ---- 小物 ----

    private static String http(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", ua != null ? ua : "Mozilla/5.0 (Linux; Android 12) Chrome/120 Mobile");
        c.setRequestProperty("Accept", "*/*");
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() >= 400) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0 && total < FETCH_LIMIT) {
                bo.write(buf, 0, n);
                total += n;
            }
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String first(String s, String re) {
        Matcher m = Pattern.compile(re, Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String strip(String s) {
        return s.replaceAll("<[^>]*>", "");
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    private static String resolve(String base, String href) {
        try {
            return new URI(base).resolve(href).toString();
        } catch (Throwable e) {
            return href.startsWith("http") ? href : null;
        }
    }

    private static String hostOf(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? "" : h.startsWith("www.") ? h.substring(4) : h;
        } catch (Throwable e) {
            return "";
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
