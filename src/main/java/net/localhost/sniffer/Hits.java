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
        MediaHit old = map.get(h.url);
        if (old != null) {
            // 別ページで同じ動画URLを再検出したら、最新のページに付け替える
            // （古いページの所属のままだと今のページの一覧に出てこない）
            if (h.page == null || h.page.equals(old.page)) return false;
            map.remove(h.url);
        }
        map.put(h.url, h);
        if (listener != null) listener.onCount(map.size());
        return true;
    }

    public static synchronized List<MediaHit> all() {
        return new ArrayList<>(map.values());
    }

    /**
     * 表示中ページで検出したヒットだけ返す（DL欄のページ追従用）。
     * 同一ホストで絞ると同じサイトの前のページの動画が残り続けるため、ページURL単位で絞る。
     * pageUrlが非http（ホーム等）の時は全件。
     */
    public static synchronized List<MediaHit> forPage(String pageUrl) {
        if (hostOf(pageUrl) == null) return new ArrayList<>(map.values());
        String key = pageKey(pageUrl);
        List<MediaHit> out = new ArrayList<>();
        for (MediaHit h : map.values())
            if (key.equals(pageKey(h.page))) out.add(h);
        return out;
    }

    /**
     * ページのタイトル確定時に、そのページで検出済みのヒットへタイトルを入れる。
     * 動画リクエストはタイトル受信より先に飛ぶことが多く、検出時点のタイトルは
     * 空か前のページのものになっているため、ここで正しい保存名に直す。
     */
    public static synchronized void retitle(String pageUrl, String title) {
        if (title == null || title.trim().isEmpty() || hostOf(pageUrl) == null) return;
        // 読み込み中はタイトル未設定でURLがタイトル代わりに返ることがある
        if (title.startsWith("http://") || title.startsWith("https://")) return;
        String key = pageKey(pageUrl);
        for (MediaHit h : map.values())
            if (key.equals(pageKey(h.page))) h.title = title;
    }

    /** ページ同一判定用のキー（#以降は同じページ内の位置なので無視） */
    private static String pageKey(String url) {
        if (url == null) return "";
        int i = url.indexOf('#');
        return i >= 0 ? url.substring(0, i) : url;
    }

    public static int countFor(String pageUrl) { return forPage(pageUrl).size(); }

    private static String headerCI(Map<String, String> hdr, String key) {
        String v = hdr.get(key);
        if (v != null) return v;
        for (Map.Entry<String, String> e : hdr.entrySet())
            if (key.equalsIgnoreCase(e.getKey())) return e.getValue();
        return null;
    }

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
            // WebViewのRequestHeadersはAndroidバージョン/リクエスト種別でキーの大小文字が
            // 揺れることがある（例: "referer"）。完全一致のgetだけだと拾い損ねて
            // CDN側のホットリンク判定(Referer検証)に落ちるので大小文字無視で探す
            h.referer = headerCI(hdr, "Referer");
            if (h.referer == null) h.referer = pageUrl;
            h.ua = headerCI(hdr, "User-Agent");
            if (h.ua == null) h.ua = fallbackUa;
            try { h.cookie = CookieManager.getInstance().getCookie(url); } catch (Throwable ignore) {}
            h.title = pageTitle;
            h.page = pageUrl;
            add(h);
        } catch (Throwable ignore) {}
    }
}
