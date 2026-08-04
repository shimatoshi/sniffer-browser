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
 * 旧ブラウザホーム（gobie://home）の残骸。
 * ホームは本物のGoogleに置き換えたため自作スタートページ(render/背景画像)は廃止。
 * - URL_HOME は旧セッション/ウィジェットIntentの互換用に残す
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
