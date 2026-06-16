package net.localhost.sniffer;

import java.io.ByteArrayOutputStream;
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
 * - 固定サイト(pins)をタイル表示し、開いた時に各サイトの新着記事を自動取得（オートスクレイプ）
 * - 取得: RSS/Atom直叩き → HTMLからautodiscovery → だめならアンカータグの見出しヒューリスティック
 * - 結果はメモリキャッシュ（10分）。キャッシュがあれば即描画→裏で更新→揃ったら再描画
 * - ページ内リンク gobie://unpin?id=N / gobie://refresh はMainActivityが拾う
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

    /** ホームHTMLを生成。fetchingは「裏で取得中」表示を出すか */
    public static String render(List<BrowserDb.Entry> pins, List<BrowserDb.Pwa> pwas, boolean fetching) {
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html><head><meta charset='utf-8'>")
                .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
                .append("<style>")
                .append("body{background:#1a1a1a;color:#ddd;font-family:sans-serif;margin:0;padding:12px}")
                .append("a{color:#8ab4f8;text-decoration:none}")
                .append("h1{font-size:18px;margin:4px 0 12px}")
                .append("h2{font-size:13px;color:#999;margin:4px 0 10px;font-weight:normal}")
                .append(".st{font-size:12px;color:#888;margin-bottom:12px}")
                .append(".card{background:#252525;border-radius:10px;padding:10px 12px;margin-bottom:12px}")
                .append(".site{display:flex;align-items:center;justify-content:space-between}")
                .append(".site a{font-size:15px;font-weight:bold;color:#fff}")
                .append(".x{color:#777;font-size:13px;padding:2px 8px}")
                .append("ul{list-style:none;margin:8px 0 0;padding:0}")
                .append("li{margin:7px 0;font-size:13px;line-height:1.4}")
                .append(".empty{color:#777;font-size:13px;margin-top:6px}")
                // PWAグリッド
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(72px,1fr));")
                .append("gap:6px 4px;margin:0 0 18px}")
                .append(".pwa{display:flex;flex-direction:column;align-items:center;text-align:center;")
                .append("padding:8px 2px;border-radius:12px;-webkit-tap-highlight-color:transparent;")
                .append("-webkit-touch-callout:none;user-select:none}")
                .append(".pwa:active{background:#333}")
                .append(".pwa img{width:52px;height:52px;border-radius:14px;object-fit:cover;background:#333}")
                .append(".pwa span{margin-top:6px;font-size:11px;color:#ccc;line-height:1.2;")
                .append("max-width:72px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
                .append("</style></head><body>");
        b.append("<h1>🏠 Gobie ホーム <a href='gobie://refresh' style='font-size:13px;font-weight:normal'>↻ 更新</a></h1>");
        if (pwas != null && !pwas.isEmpty()) {
            b.append("<h2>アプリ（長押しでメニュー）</h2><div class='grid'>");
            for (BrowserDb.Pwa p : pwas) {
                String src = (p.icon != null && !p.icon.isEmpty())
                        ? "data:image/png;base64," + p.icon : "";
                b.append("<a class='pwa' data-id='").append(p.id)
                        .append("' href='gobie://pwa-open?id=").append(p.id).append("'>")
                        .append("<img src='").append(src).append("' alt=''>")
                        .append("<span>").append(esc(p.label())).append("</span></a>");
            }
            b.append("</div>");
        }
        if (fetching) b.append("<div class='st'>新着記事を取得中...</div>");
        if (pins.isEmpty()) {
            b.append("<div class='card'>固定サイトがまだ無い。<br>")
                    .append("サイトを開いて ⋮メニュー →「🏠 ホームに固定」で追加すると、")
                    .append("ここに新着記事が並ぶ。</div>");
        }
        for (BrowserDb.Entry p : pins) {
            b.append("<div class='card'><div class='site'>")
                    .append("<a href='").append(esc(p.url)).append("'>")
                    .append(esc(p.label())).append("</a>")
                    .append("<a class='x' href='gobie://unpin?id=").append(p.id).append("'>✕</a>")
                    .append("</div>");
            CacheEnt ce = cache.get(p.url);
            if (ce == null || ce.arts.isEmpty()) {
                b.append("<div class='empty'>").append(ce == null ? "（未取得）" : "（記事を検出できず）").append("</div>");
            } else {
                b.append("<ul>");
                for (Article a : ce.arts)
                    b.append("<li><a href='").append(esc(a.url)).append("'>")
                            .append(esc(a.title)).append("</a></li>");
                b.append("</ul>");
            }
            b.append("</div>");
        }
        // PWAタイルの長押し→メニュー（gobie://pwa-menu）。直後のclickは抑止。
        // 長押しはWebViewがcontextmenuイベントを投げるのでそれを拾う（手製タイマーより堅牢）。
        // contextmenu→メニュー、直後のclickは抑止。短タップのclickは通常通りPWA起動。
        b.append("<script>(function(){")
                .append("var longp=false;")
                .append("document.querySelectorAll('.pwa').forEach(function(a){")
                .append("a.addEventListener('contextmenu',function(e){e.preventDefault();longp=true;")
                .append("if(navigator.vibrate)navigator.vibrate(20);")
                .append("location.href='gobie://pwa-menu?id='+a.getAttribute('data-id');")
                .append("setTimeout(function(){longp=false;},800);});")
                .append("a.addEventListener('click',function(e){if(longp){e.preventDefault();longp=false;}});")
                .append("});})();</script>");
        b.append("</body></html>");
        return b.toString();
    }

    /** 期限切れ/未取得のピンがあるか（=裏で取得を回すべきか） */
    public static boolean needsFetch(List<BrowserDb.Entry> pins, boolean force) {
        long now = System.currentTimeMillis();
        for (BrowserDb.Entry p : pins) {
            CacheEnt ce = cache.get(p.url);
            if (ce == null || force || now - ce.ts > TTL_MS) return true;
        }
        return false;
    }

    /** 全ピンの新着を取得（ブロッキング、ワーカースレッドで呼ぶ）。1件でも更新したらtrue */
    public static boolean fetchAll(List<BrowserDb.Entry> pins, boolean force, String ua) {
        long now = System.currentTimeMillis();
        boolean updated = false;
        for (BrowserDb.Entry p : pins) {
            CacheEnt ce = cache.get(p.url);
            if (ce != null && !force && now - ce.ts <= TTL_MS) continue;
            try {
                cache.put(p.url, new CacheEnt(fetchSite(p.url, ua)));
            } catch (Throwable e) {
                cache.put(p.url, new CacheEnt(new ArrayList<Article>()));
            }
            updated = true;
        }
        return updated;
    }

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
