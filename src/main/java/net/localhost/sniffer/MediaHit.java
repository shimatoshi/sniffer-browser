package net.localhost.sniffer;

/** 検出した1件のメディアURLと、DLに必要なヘッダ群 */
public class MediaHit {
    public final String url;
    public final String type;     // "m3u8" or "mp4"
    public String referer;
    public String cookie;
    public String ua;
    public String title;
    public String audioUrl; // 画質選択時に確定した別トラック音声playlist（null=自動解決）
    public String quality;  // 画質選択時の表示ラベル（null=自動）

    public MediaHit(String url, String type) {
        this.url = url;
        this.type = type;
    }

    public String display() {
        String u = url.split("\\?")[0];
        int slash = u.lastIndexOf('/');
        String tail = slash >= 0 ? u.substring(slash + 1) : u;
        return "[" + type + "] " + (tail.isEmpty() ? url : tail);
    }
}
