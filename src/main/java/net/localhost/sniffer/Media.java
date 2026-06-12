package net.localhost.sniffer;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Rational;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * 動画まわりの共通処理（PWA/通常ブラウザ両方で使う）。
 * - ネイティブPiP（全画面再生中にHome→小窓化）
 * - 再生状態のJSブリッジ（バックグラウンド再生の前面サービス制御に使う）
 * Chrome本体のPiP Web APIはWebViewに無いので、全画面のcustomViewをそのままPiPに乗せる。
 */
public final class Media {

    static final String JS_IFACE = "__snifferPB";

    private Media() {}

    /** 再生中/停止の通知を受け取るコールバック */
    public interface PlayState { void onPlaying(boolean playing); }

    /**
     * WebViewに再生状態ブリッジを仕込む。setupWeb時に1回だけ呼ぶ。
     * 実際の通知はinjectTracker()で挿すJSがmedia要素のplay/pauseを拾って行う。
     */
    @SuppressWarnings("AddJavascriptInterface")
    public static void track(WebView web, final PlayState cb) {
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void state(boolean playing) { cb.onPlaying(playing); }
        }, JS_IFACE);
    }

    /** ページ毎に呼ぶ。video/audioのplay/pauseを監視して再生状態をネイティブへ報告する */
    public static void injectTracker(WebView web) {
        web.evaluateJavascript(TRACK_JS, null);
    }

    private static final String TRACK_JS =
            "(function(){if(window.__snifferPBset)return;window.__snifferPBset=1;" +
            "function any(){return [].slice.call(document.querySelectorAll('video,audio'))" +
            ".some(function(m){return !m.paused&&!m.ended&&m.readyState>2;});}" +
            "function rep(){try{" + JS_IFACE + ".state(any());}catch(e){}}" +
            "['play','playing','pause','ended','emptied'].forEach(function(e){" +
            "document.addEventListener(e,rep,true);});})();";

    // ---- ネイティブPiP ----

    /**
     * 全画面動画(view)をPiP小窓へ。Homeを押した時など onUserLeaveHint から呼ぶ。
     * @return PiPに入れたら true
     */
    public static boolean enterPip(Activity act, View video) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                act.enterPictureInPictureMode(
                        new PictureInPictureParams.Builder()
                                .setAspectRatio(aspectOf(video))
                                .build());
            } else {
                //noinspection deprecation
                act.enterPictureInPictureMode();
            }
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 動画Viewの縦横比。取れなければ16:9。PiP許容域(0.42〜2.39)へクランプ */
    private static Rational aspectOf(View v) {
        int w = v != null ? v.getWidth() : 0;
        int h = v != null ? v.getHeight() : 0;
        if (w <= 0 || h <= 0) { w = 16; h = 9; }
        double r = (double) w / h;
        if (r < 0.42) { w = 42; h = 100; }
        else if (r > 2.39) { w = 239; h = 100; }
        return new Rational(w, h);
    }

    // ---- バックグラウンド再生（前面サービスでプロセス凍結を防ぐ） ----

    public static void startPlaybackService(Context c, String title) {
        Intent it = new Intent(c, PlaybackService.class).putExtra("title", title);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(it);
        else c.startService(it);
    }

    public static void stopPlaybackService(Context c) {
        try { c.stopService(new Intent(c, PlaybackService.class)); } catch (Throwable ignore) {}
    }
}
