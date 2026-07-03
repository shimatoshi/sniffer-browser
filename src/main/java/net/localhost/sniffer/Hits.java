package net.localhost.sniffer;

import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 検出URLのグローバルストア（URLでdedup、検出順を保持） */
public class Hits {
    public interface Listener { void onCount(int n); }

    private static final Map<String, MediaHit> map = new LinkedHashMap<>();
    private static Listener listener;

    public static synchronized void setListener(Listener l) { listener = l; }

    public static synchronized boolean add(MediaHit h) {
        if (h == null || h.url == null) return false;
        if (map.containsKey(h.url)) return false;
        map.put(h.url, h);
        if (listener != null) listener.onCount(map.size());
        return true;
    }

    public static synchronized List<MediaHit> all() {
        return new ArrayList<>(map.values());
    }

    /**
     * 表示中ページと同一ホストで検出したヒットだけ返す（DL欄のサイト追従用）。
     * pageUrlが非http（ホーム等）の時は全件。
     */
    public static synchronized List<MediaHit> forPage(String pageUrl) {
        String host = hostOf(pageUrl);
        if (host == null) return new ArrayList<>(map.values());
        List<MediaHit> out = new ArrayList<>();
        for (MediaHit h : map.values())
            if (host.equals(hostOf(h.page))) out.add(h);
        return out;
    }

    public static int countFor(String pageUrl) { return forPage(pageUrl).size(); }

    private static String hostOf(String url) {
        if (url == null || !url.startsWith("http")) return null;
        try {
            String h = Uri.parse(url).getHost();
            return (h == null || h.isEmpty()) ? null : h;
        } catch (Throwable e) {
            return null;
        }
    }

    public static synchronized int size() { return map.size(); }

    public static synchronized void clear() {
        map.clear();
        if (listener != null) listener.onCount(0);
    }

    /** shouldInterceptRequest から呼ぶ共通傍受。MainActivity/PwaActivity 兼用（非UIスレッド可） */
    public static void sniff(WebResourceRequest req, String pageUrl, String pageTitle, String fallbackUa) {
        try {
            Uri u = req.getUrl();
            if (u == null) return;
            String url = u.toString();
            String path = u.getPath();
            if (path == null) path = url;
            String low = path.toLowerCase();
            String type;
            if (low.contains(".m3u8")) type = "m3u8";
            else if (low.contains(".mp4")) type = "mp4";
            else return;

            MediaHit h = new MediaHit(url, type);
            Map<String, String> hdr = req.getRequestHeaders();
            h.referer = hdr.get("Referer");
            if (h.referer == null) h.referer = pageUrl;
            h.ua = hdr.get("User-Agent");
            if (h.ua == null) h.ua = fallbackUa;
            try { h.cookie = CookieManager.getInstance().getCookie(url); } catch (Throwable ignore) {}
            h.title = pageTitle;
            h.page = pageUrl;
            add(h);
        } catch (Throwable ignore) {}
    }
}
