package net.localhost.sniffer;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Rational;
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

    /** 再生中/停止と動画サイズの通知を受け取るコールバック */
    public interface PlayState {
        void onPlaying(boolean playing);
        /** 再生中動画の実寸（videoWidth/Height）。PiPの縦横比に使う */
        void onVideoSize(int w, int h);
    }

    /**
     * WebViewに再生状態ブリッジを仕込む。setupWeb時に1回だけ呼ぶ。
     * 実際の通知はinjectTracker()で挿すJSがmedia要素のplay/pauseを拾って行う。
     */
    @SuppressWarnings("AddJavascriptInterface")
    public static void track(WebView web, final PlayState cb) {
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void state(boolean playing) { cb.onPlaying(playing); }
            @JavascriptInterface
            public void dims(int w, int h) { cb.onVideoSize(w, h); }
        }, JS_IFACE);
    }

    /** ページ毎に呼ぶ。video/audioのplay/pauseを監視して再生状態をネイティブへ報告する */
    public static void injectTracker(WebView web) {
        web.evaluateJavascript(TRACK_JS, null);
    }

    private static final String TRACK_JS =
            "(function(){if(window.__snifferPBset)return;window.__snifferPBset=1;" +
            "function all(){return [].slice.call(document.querySelectorAll('video,audio'));}" +
            "function live(m){return !m.paused&&!m.ended&&m.readyState>2;}" +
            "function rep(){try{" +
            "var ms=all();" + JS_IFACE + ".state(ms.some(live));" +
            "var v=ms.filter(function(m){return m.videoWidth>0;});" +
            "var b=v.filter(live)[0]||v[0];" +
            "if(b)" + JS_IFACE + ".dims(b.videoWidth,b.videoHeight);" +
            "}catch(e){}}" +
            "['play','playing','pause','ended','emptied','loadedmetadata','resize']" +
            ".forEach(function(e){document.addEventListener(e,rep,true);});})();";

    // ---- PiP中の擬似全画面 ----
    // WebViewはPiP突入直後にHTML5全画面(customView)を強制解除してしまい、
    // 小窓にページ全体が縮小表示される。そこでPiP中は再生中のvideo要素を
    // CSSでビューポート全面に固定し、動画だけが見えるようにする。
    // (classベース: サイトJSがstyle属性を書き換えても効き続ける)

    private static final String PIP_ON_JS =
            "(function(){try{" +
            "var vs=[].slice.call(document.querySelectorAll('video')).filter(function(m){return m.videoWidth>0;});" +
            "if(!vs.length)return 'novideo';" +
            "var b=vs.filter(function(m){return !m.paused&&!m.ended;})[0]||vs[0];" +
            "var st=document.getElementById('__snifPipCss');" +
            "if(!st){st=document.createElement('style');st.id='__snifPipCss';" +
            "st.textContent='video.__snifPip{position:fixed!important;left:0!important;top:0!important;" +
            "width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;" +
            "min-width:0!important;min-height:0!important;margin:0!important;padding:0!important;" +
            "z-index:2147483647!important;background:#000!important;object-fit:contain!important;" +
            "transform:none!important;visibility:visible!important;display:block!important;}" +
            // 祖先にtransform等があるとposition:fixedの基準がずれるため打ち消す
            ".__snifPipA{transform:none!important;filter:none!important;perspective:none!important;" +
            "contain:none!important;will-change:auto!important;backdrop-filter:none!important;}" +
            "html,body{overflow:hidden!important;background:#000!important;}';" +
            "document.documentElement.appendChild(st);}" +
            "if(window.__snifPipV&&window.__snifPipV!==b)window.__snifPipV.classList.remove('__snifPip');" +
            "window.__snifPipV=b;b.classList.add('__snifPip');" +
            "window.__snifPipA=window.__snifPipA||[];" +
            "for(var p=b.parentElement;p&&p!==document.documentElement;p=p.parentElement){" +
            "p.classList.add('__snifPipA');window.__snifPipA.push(p);}" +
            "var cs=getComputedStyle(b),r=b.getBoundingClientRect();" +
            "return 'ok n='+vs.length+' pos='+cs.position+' z='+cs.zIndex+" +
            "' rect='+Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.width)+'x'+Math.round(r.height)+" +
            "' vp='+window.innerWidth+'x'+window.innerHeight;" +
            "}catch(e){return 'err:'+e;}})();";

    private static final String PIP_OFF_JS =
            "(function(){try{" +
            "if(window.__snifPipV){window.__snifPipV.classList.remove('__snifPip');delete window.__snifPipV;}" +
            "(window.__snifPipA||[]).forEach(function(p){try{p.classList.remove('__snifPipA');}catch(e){}});" +
            "window.__snifPipA=[];" +
            "var st=document.getElementById('__snifPipCss');if(st)st.remove();" +
            "return 'off';}catch(e){return 'err:'+e;}})();";

    /** PiP出入りで呼ぶ。on=trueで動画をビューポート全面固定、falseで復元 */
    public static void setPipLayout(final WebView web, final boolean on) {
        if (web == null) return;
        web.evaluateJavascript(on ? PIP_ON_JS : PIP_OFF_JS, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                Dbg.log(web.getContext(), "PiP css(" + (on ? "on" : "off") + "): " + v);
            }
        });
    }

    // ---- ネイティブPiP ----

    /**
     * 全画面動画をPiP小窓へ。Homeを押した時など onUserLeaveHint から呼ぶ。
     * w/h はJSブリッジで取った動画の実寸（videoWidth/Height）。
     * 全画面ビューのサイズは画面と同じで縦持ちだと縦長PiPになるため使わない。
     * @return PiPに入れたら true
     */
    public static boolean enterPip(Activity act, int w, int h) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                act.enterPictureInPictureMode(
                        new PictureInPictureParams.Builder()
                                .setAspectRatio(aspectOf(w, h))
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

    /** 動画の縦横比。取れなければ16:9。PiP許容域(0.42〜2.39)へクランプ */
    private static Rational aspectOf(int w, int h) {
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
