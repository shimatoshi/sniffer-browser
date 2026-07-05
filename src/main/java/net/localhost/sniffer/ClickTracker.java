package net.localhost.sniffer;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * 直近クリックが本物のリンク(<a href>)/フォーム上だったかをJSで記録する。
 *
 * リダイレクトブロック強化用: アダルト系のクリックハイジャックは
 * 全面オーバーレイでタップを奪うため hasGesture()=true になり、
 * ジェスチャ有無だけでは広告遷移と正規遷移を区別できない。
 * そこで pointerdown/click をcaptureで拾い「クリック地点の最寄りの
 * a[href] の飛び先(無ければform action、どちらも無ければ空)」を
 * ネイティブへ報告し、遷移許可の判定材料にする。
 *
 * 記録の3状態:
 *  - null  … 猶予内にJSからの報告なし（JS未動作 or クリック自体なし）
 *  - ""    … リンク外クリック（オーバーレイ等）→ クロスサイト遷移は遮断対象
 *  - href  … リンク/フォームのクリック → 遷移・その後の連鎖を許可
 */
public final class ClickTracker {

    private static final long GRACE_MS = 5000;
    static final String JS_IFACE = "__snifferClick";

    private static final java.util.Map<WebView, ClickTracker> map =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<WebView, ClickTracker>());

    private volatile long clickMs;
    private volatile String href = "";

    private ClickTracker() {}

    /** WebView生成時に1回だけ呼ぶ（addJavascriptInterfaceはロード前必須） */
    @SuppressWarnings("AddJavascriptInterface")
    public static void install(WebView web) {
        final ClickTracker ct = new ClickTracker();
        map.put(web, ct);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void click(String href) {
                ct.clickMs = System.currentTimeMillis();
                ct.href = href == null ? "" : href;
            }
        }, JS_IFACE);
    }

    /** ページ毎に呼ぶ（onPageStarted/Finished両方でOK、二重注入は自己ガード） */
    public static void inject(WebView web) {
        web.evaluateJavascript(JS, null);
    }

    /**
     * 猶予内の最後のクリックのリンク先。
     * null=報告なし / ""=リンク外クリック / それ以外=a[href]またはform action
     */
    public static String recentClickHref(WebView web) {
        ClickTracker ct = map.get(web);
        if (ct == null || System.currentTimeMillis() - ct.clickMs > GRACE_MS) return null;
        return ct.href;
    }

    /** 猶予内クリックがリンク/フォーム上だったか */
    public static boolean anchorClicked(WebView web) {
        String h = recentClickHref(web);
        return h != null && !h.isEmpty();
    }

    /** 猶予内クリックのリンク先サイト(eTLD+1近似)。無ければ "" */
    public static String clickedSite(WebView web) {
        String h = recentClickHref(web);
        if (h == null || h.isEmpty()) return "";
        try { return AdBlocker.site(android.net.Uri.parse(h).getHost()); }
        catch (Throwable ignore) { return ""; }
    }

    // window captureで最速フックし、サイト側のstopPropagationより先に記録する。
    // touchstart/pointerdownも拾うのは、ハイジャッカーがclickを握り潰す対策と
    // スクロール開始タッチ(リンク外=空記録)で古いリンク記録を消すため。
    // submitはEnterキー送信(クリック無しのフォームGET遷移)を許可するのに必要。
    private static final String JS =
            "(function(){if(window.__snifferCTset)return;window.__snifferCTset=1;" +
            "function rec(e){try{" +
            "var t=e.target,h='';" +
            "if(t&&t.closest){" +
            "var a=t.closest('a[href]');" +
            "if(a&&a.href&&a.protocol!=='javascript:')h=a.href;" +
            "else{var f=t.closest('form');if(f&&f.action)h=f.action;}}" +
            JS_IFACE + ".click(h);" +
            "}catch(_){}}" +
            "['pointerdown','mousedown','touchstart','click','submit']" +
            ".forEach(function(n){window.addEventListener(n,rec,true);});})();";
}
