package net.localhost.sniffer;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Message;
import android.webkit.GeolocationPermissions;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * MainActivity/PwaActivity共通のWebChromeClient。
 * - onShowCustomView: 動画の全画面再生（decorViewに重ねてイマーシブ化）
 * - onShowFileChooser: <input type=file> → システムのファイルピッカー
 * - onCreateWindow: target=_blank / window.open をメインWebViewで開く（非ジェスチャは広告ポップアップとして遮断）
 * 各Activityは onActivityResult → onFileResult、BACKキー → exitFullscreen を中継すること。
 */
public class SnifferChrome extends WebChromeClient {

    public static final int REQ_FILE = 71;
    public static final int REQ_GEO  = 72;

    private final Activity act;
    private final WebView mainWeb;

    private View customView;
    private CustomViewCallback customCb;
    private int savedUiVisibility;
    private ValueCallback<Uri[]> fileCb;

    // geolocation: OS権限が無い時のランタイム要求の結果待ち
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCb;

    public SnifferChrome(Activity act, WebView mainWeb) {
        this.act = act;
        this.mainWeb = mainWeb;
    }

    // ---- 全画面再生 ----

    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        if (customView != null) { callback.onCustomViewHidden(); return; }
        customView = view;
        customCb = callback;
        FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
        decor.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        savedUiVisibility = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
    }

    @Override
    public void onHideCustomView() {
        exitFullscreen();
    }

    /** 全画面動画中か（PiP判定に使う） */
    public boolean isInFullscreen() { return customView != null; }

    /** BACKキーから呼ぶ。全画面中だったらtrue（処理済み） */
    public boolean exitFullscreen() {
        if (customView == null) return false;
        FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
        decor.removeView(customView);
        decor.setSystemUiVisibility(savedUiVisibility);
        act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        customView = null;
        if (customCb != null) { try { customCb.onCustomViewHidden(); } catch (Throwable ignore) {} }
        customCb = null;
        return true;
    }

    // ---- <input type=file> ----

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                     FileChooserParams params) {
        if (fileCb != null) fileCb.onReceiveValue(null);
        fileCb = callback;
        try {
            act.startActivityForResult(params.createIntent(), REQ_FILE);
            return true;
        } catch (Throwable e) {
            fileCb = null;
            Toast.makeText(act, "ファイルピッカーを開けない: " + e, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** Activity#onActivityResult から中継する */
    public void onFileResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE || fileCb == null) return;
        fileCb.onReceiveValue(FileChooserParams.parseResult(resultCode, data));
        fileCb = null;
    }

    // ---- 位置情報（geolocation）----

    /**
     * Webページが現在地を要求した時に呼ばれる。WebViewは既定で拒否するため未実装だと
     * navigator.geolocation がタイムアウト/PERMISSION_DENIEDになる。
     * アプリがOS権限を持つなら許可、無ければランタイム要求してから応答する。
     */
    @Override
    public void onGeolocationPermissionsShowPrompt(String origin,
                                                   GeolocationPermissions.Callback callback) {
        Dbg.log(act, "geo prompt: origin=" + origin
                + " osPerm=" + hasLocationPermission() + " act=" + act.getClass().getSimpleName());
        // retain=true: originごとの許可をWebView側に永続化。毎回prompt扱いだと
        // watchPositionを使うサイト(Googleマップ等)で応答待ちタイムアウトが出る
        if (hasLocationPermission()) {
            callback.invoke(origin, true, true); // (origin, allow, retain)
            return;
        }
        if (Build.VERSION.SDK_INT < 23) { // 旧端末はインストール時許可済み
            callback.invoke(origin, true, true);
            return;
        }
        // OS権限が無ければ要求し、onGeoPermissionResult で応答する
        pendingGeoOrigin = origin;
        pendingGeoCb = callback;
        act.requestPermissions(new String[]{
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return act.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || act.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /** Activity#onRequestPermissionsResult から中継する */
    public void onGeoPermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != REQ_GEO || pendingGeoCb == null) return;
        boolean granted = false;
        for (int r : grantResults) if (r == PackageManager.PERMISSION_GRANTED) granted = true;
        Dbg.log(act, "geo perm result: origin=" + pendingGeoOrigin + " granted=" + granted);
        pendingGeoCb.invoke(pendingGeoOrigin, granted, granted); // 許可時のみ永続化
        if (!granted) Toast.makeText(act,
                "位置情報の権限が無いため現在地を渡せません（設定→アプリ→権限で許可してください）",
                Toast.LENGTH_LONG).show();
        pendingGeoCb = null;
        pendingGeoOrigin = null;
    }

    /** WebSettingsにgeolocationを有効化する。各Activityの設定時に呼ぶ。 */
    public static void applyGeolocation(Context ctx, android.webkit.WebSettings s) {
        s.setGeolocationEnabled(true);
        // API24未満はDB保存先が無いとgeolocationが機能しない
        try { s.setGeolocationDatabasePath(ctx.getFilesDir().getPath()); }
        catch (Throwable ignore) {}
    }

    // ---- window.open / target=_blank ----

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        // 非ジェスチャのポップアップ＝広告は無視（設定でオフにできる）
        if (!isUserGesture && AdBlocker.get(act).popupBlockOn()) return false;
        // 仮WebViewでURLだけ受け取り、メインWebViewで開く（シングルウィンドウ運用）
        WebView temp = new WebView(view.getContext());
        temp.setWebViewClient(new WebViewClient() {
            // 注意: 未アタッチViewのpost()は実行されない(API24+)。Handlerで確実に破棄する
            private void consume(WebView v, String url) {
                openUrl(url);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(v::destroy);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                consume(v, req.getUrl().toString());
                return true;
            }
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                consume(v, url);
                return true;
            }
        });
        WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
        t.setWebView(temp);
        resultMsg.sendToTarget();
        return true;
    }

    /** 新規窓のURLの開き方。PwaActivityはスコープ外を既定ブラウザへ逃がすためoverride可 */
    protected void openUrl(String url) {
        mainWeb.loadUrl(url);
    }

    // ---- UA ----

    /**
     * WebView識別子(「; wv」と「Version/4.0 」)を落としたChrome相当UAを設定する。
     * GoogleはWebView UAを見るとOAuth/GISスクリプトにHTMLエラーを返し
     * ERR_BLOCKED_BY_ORBで「Googleでログイン」が無反応になるため必須。
     */
    public static void applyChromeUa(android.webkit.WebSettings s) {
        String ua = s.getUserAgentString();
        if (ua == null) return;
        s.setUserAgentString(ua.replace("; wv", "").replace("Version/4.0 ", ""));
    }

    /**
     * PC版サイト表示のUA切替。desktop=trueでデスクトップChrome相当のUAへ、
     * falseでWebView既定に戻してからWebView識別子を落とす（=通常のモバイルUA）。
     * Chromeのメジャーバージョンは実UAから引き継ぐのでWebView更新でもずれない。
     */
    public static void applyUaMode(android.webkit.WebSettings s, boolean desktop) {
        if (desktop) {
            String ua = s.getUserAgentString();
            String major = "120";
            if (ua != null) {
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(ua);
                if (m.find()) major = m.group(1);
            }
            s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major
                    + ".0.0.0 Safari/537.36");
        } else {
            s.setUserAgentString(null); // WebView既定へリセット
            applyChromeUa(s);
        }
    }

    /**
     * 「埋め込みWebViewであること」を隠してデスクトップ/モバイルChrome相当に見せる偽装スクリプト。
     * claude.ai のOAuth同意画面は、埋め込みWebViewを検出すると「承認」ボタンを
     * disabled のままにする（= Claude Codeログインの承認ボタンがグレーアウトして
     * 押せない原因）。WebViewを露呈する主な手掛かりを2つ潰す:
     *   1) window.chrome が存在しない（本物のChromeにはある）→ 定番のWebView判定
     *   2) navigator.userAgentData.brands が「Android WebView」を申告する
     *      （UA文字列から「; wv」を消してもこの構造化APIは残る）
     * Chromeバージョンは実UAから動的に拾うのでWebView更新でもずれない。
     */
    static final String UA_CLIENT_HINTS_JS =
            "(function(){if(window.__snifferUaCh)return;window.__snifferUaCh=1;try{"
            // 1) window.chrome を生やす（本物Chrome相当の最小スタブ）
            + "if(!window.chrome){var noop=function(){};window.chrome={"
            + "app:{isInstalled:false,InstallState:{DISABLED:'disabled',INSTALLED:'installed',NOT_INSTALLED:'not_installed'},RunningState:{CANNOT_RUN:'cannot_run',READY_TO_RUN:'ready_to_run',RUNNING:'running'}},"
            + "runtime:{OnInstalledReason:{},PlatformArch:{},connect:noop,sendMessage:noop},"
            + "loadTimes:function(){return {requestTime:Date.now()/1000,startLoadTime:Date.now()/1000,commitLoadTime:Date.now()/1000,finishLoadTime:Date.now()/1000};},"
            + "csi:function(){return {onloadT:Date.now(),pageT:Date.now(),startE:Date.now(),tran:15};}};}"
            // 2) navigator.userAgentData を Google Chrome 相当へ
            //    （PC版サイト表示=デスクトップUAの時は Windows/非mobile を申告）
            + "var ua=navigator.userAgent||'';"
            + "var isMob=/Android/i.test(ua);"
            + "var m=ua.match(/Chrome\\/(\\d+)(\\.[\\d.]+)?/);"
            + "var major=m?m[1]:'149';"
            + "var full=m?(m[1]+(m[2]||'.0.0.0')):'149.0.0.0';"
            + "var plat=isMob?'Android':'Windows';"
            + "var brands=[{brand:'Chromium',version:major},{brand:'Google Chrome',version:major},{brand:'Not)A;Brand',version:'24'}];"
            + "var fvl=[{brand:'Chromium',version:full},{brand:'Google Chrome',version:full},{brand:'Not)A;Brand',version:'24.0.0.0'}];"
            + "var d={brands:brands,mobile:isMob,platform:plat,"
            + "getHighEntropyValues:function(h){return Promise.resolve({architecture:isMob?'arm':'x86',bitness:'64',brands:brands,fullVersionList:fvl,mobile:isMob,model:'',platform:plat,platformVersion:isMob?'10.0.0':'15.0.0',uaFullVersion:full,wow64:false});},"
            + "toJSON:function(){return {brands:brands,mobile:isMob,platform:plat};}};"
            + "Object.defineProperty(Navigator.prototype,'userAgentData',{get:function(){return d;},configurable:true});"
            + "}catch(e){}})();";

    /** onPageStartedから呼ぶ。埋め込みWebViewであることを隠してChrome相当に見せる。 */
    public static void injectClientHints(WebView web) {
        web.evaluateJavascript(UA_CLIENT_HINTS_JS, null);
    }

    // ---- YouTube広告ブロック ----

    /**
     * YouTube専用の広告除去スクリプト。ドメイン/CSSブロックでは消せない
     * 「動画内広告(プレロール/ミッドロール)」に対応する（本編と同一ドメイン・
     * 同一プレイヤー配信のためURL遮断もCSS隠しも効かない）。Brave/uBlock相当:
     *   1) JSON.parse をフックし player応答の adPlacements/playerAds 等を除去
     *   2) fetch(/youtubei/v1/player) 応答からも広告フィールドを除去（SPA遷移対策）
     *   3) 再生中の広告(.ad-showing)は動画を終端へ飛ばし、スキップボタンを自動クリック
     *   4) フィード/オーバーレイ広告のDOM要素を除去
     * document_start相当(onPageStarted)で早期注入し、1回だけ仕掛ける。
     */
    static final String YOUTUBE_ADBLOCK_JS =
            "(function(){if(window.__gobieYtAb)return;window.__gobieYtAb=1;try{"
            + "var AD=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams','playerLegacyDesktopWatchAdsRenderer'];"
            + "function prune(o){try{if(o&&typeof o==='object'){for(var i=0;i<AD.length;i++){if(o[AD[i]]!=null)delete o[AD[i]];}}}catch(e){}return o;}"
            // 0) 初回HTML内の ytInitialPlayerResponse を getter/setter でガード（inline代入時にprune）
            + "try{var _r=undefined;Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,"
            + "get:function(){return _r;},set:function(v){_r=prune(v);}});}catch(e){}"
            // 1) JSON.parse フック
            + "var _p=JSON.parse;JSON.parse=function(){var r=_p.apply(this,arguments);return prune(r);};"
            // 2) fetch(/youtubei/v1/player) 応答フック
            + "var _f=window.fetch;if(_f){window.fetch=function(){var a=arguments;return _f.apply(this,a).then(function(resp){try{"
            + "var u=(a[0]&&a[0].url)||a[0]||'';"
            + "if(typeof u==='string'&&u.indexOf('/youtubei/v1/player')>=0){"
            + "return resp.clone().json().then(function(j){prune(j);"
            + "return new Response(JSON.stringify(j),{status:resp.status,statusText:resp.statusText,headers:{'content-type':'application/json'}});"
            + "}).catch(function(){return resp;});}"
            + "}catch(e){}return resp;});};}"
            // 3+4) 再生中広告スキップ＋広告DOM除去
            + "var RM=['.ytp-ad-overlay-slot','.ytp-ad-message-container','.ytp-ad-overlay-container',"
            + "'ytd-action-companion-ad-renderer','ytd-display-ad-renderer','ytd-ad-slot-renderer',"
            + "'ytd-in-feed-ad-layout-renderer','ytd-banner-promo-renderer','ytd-statement-banner-renderer',"
            + "'ytm-companion-ad-renderer','ad-slot-renderer','ytm-promoted-video-renderer'];"
            + "function tick(){try{"
            + "var ad=document.querySelector('.ad-showing,.ytp-ad-player-overlay,.ytp-ad-player-overlay-layout');"
            + "var v=document.querySelector('video');"
            + "if(ad&&v&&isFinite(v.duration)&&v.duration>0){v.currentTime=v.duration;}"
            + "var sk=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');"
            + "if(sk)sk.click();"
            + "for(var i=0;i<RM.length;i++){var es=document.querySelectorAll(RM[i]);for(var j=0;j<es.length;j++)es[j].remove();}"
            + "}catch(e){}}"
            + "setInterval(tick,400);"
            + "}catch(e){}})();";

    /** YouTube系URLか（youtube.com / m.youtube.com / youtube-nocookie.com） */
    public static boolean isYoutube(String url) {
        if (url == null) return false;
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return false;
            h = h.toLowerCase();
            return h.endsWith("youtube.com") || h.endsWith("youtube-nocookie.com")
                    || h.endsWith("youtubei.googleapis.com");
        } catch (Throwable e) {
            return false;
        }
    }

    /** onPageStarted/Finishedから呼ぶ。YouTubeなら広告除去スクリプトを注入する。 */
    public static void injectYoutubeAdblock(WebView web, String url) {
        if (!AdBlocker.get(web.getContext()).adblockOn()) return;
        if (isYoutube(url)) web.evaluateJavascript(YOUTUBE_ADBLOCK_JS, null);
    }

    // ---- 通常ダウンロード（静的ヘルパ） ----

    static final String BLOB_IFACE = "__snifferBlob";

    /** PDF/zip/画像長押し等の通常DLをDownloadManagerへ。Cookie/UA/Referer引き継ぎ */
    @SuppressWarnings("AddJavascriptInterface")
    public static void enableDownloads(Activity act, WebView web) {
        // blob: をJSでfetch→base64化してJava側に戻すためのブリッジ
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void save(String dataUrl, String mime) {
                act.runOnUiThread(() -> saveDataUrl(act, dataUrl));
            }
            @JavascriptInterface
            public void fail(String msg) {
                act.runOnUiThread(() ->
                        Toast.makeText(act, "blob DL失敗: " + msg, Toast.LENGTH_SHORT).show());
            }
        }, BLOB_IFACE);
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                downloadUrl(act, web, url, contentDisposition, mimetype));
    }

    /**
     * blob: ダウンロード救済ガード。各ページ読み込み時に注入する。
     * 多くのサイト(例: createObjectURL→a.click()→revokeObjectURL)はclick直後に
     * 同期でrevokeObjectURLを呼ぶため、DownloadListener経由で後追いするfetchBlobが
     * fetch('blob:')する頃にはblobが破棄され "TypeError: Failed to fetch" になる。
     * revokeを60秒遅延させてblobを生かし、fetchBlobが間に合うようにする。
     */
    static final String BLOB_GUARD_JS =
            "(function(){if(window.__gobieBlobGuard)return;window.__gobieBlobGuard=1;"
            + "var R=URL.revokeObjectURL.bind(URL);"
            + "URL.revokeObjectURL=function(u){setTimeout(function(){try{R(u);}catch(e){}},60000);};"
            + "})();";

    /** onPageStartedから呼ぶ。blob revokeを遅延させてDL救済する。 */
    public static void injectBlobGuard(WebView web) {
        web.evaluateJavascript(BLOB_GUARD_JS, null);
    }

    /** http(s)/data: URLをDownloadフォルダへ保存。enableDownloadsと画像長押しの共通経路 */
    static void downloadUrl(Activity act, WebView web, String url,
                            String contentDisposition, String mimetype) {
        if (url.startsWith("data:")) { saveDataUrl(act, url); return; }
        if (url.startsWith("blob:")) { fetchBlob(act, web, url); return; }
        // 診断ログ: DownloadListenerが実際に拾ったURL/ヘッダ
        android.util.Log.i("SnifferDL", "downloadUrl url=" + url
                + " cd=" + contentDisposition + " mime=" + mimetype);
        // 再生ストリームの誤発火ガード:
        // 動画の<video>再生(progressive)が失敗するとWebViewがそのsrcをDownloadListenerに流し、
        // 同じ動画が無限にダウンロードされる。実DLボタンは必ず ?dl=1 を付けるので、
        // /stream/ を含むのに dl=1 が無いURLは「再生用」と判断して保存しない。
        if (url.contains("/stream/") && !url.contains("dl=1")) {
            android.util.Log.w("SnifferDL", "skip playback stream (no dl=1): " + url);
            return;
        }
        try {
            // /stream のDLでサーバが application/octet-stream を返すと、Content-Disposition の
            // filename が *.mp4 でも guessFileName が mime を優先して拡張子を .bin にしてしまう。
            // format=audio なら m4a(audio/mp4)、それ以外は mp4(video/mp4) に補正する。
            String effMime = mimetype;
            if (url.contains("/stream/") && (effMime == null || effMime.isEmpty()
                    || effMime.equals("application/octet-stream"))) {
                effMime = url.contains("format=audio") ? "audio/mp4" : "video/mp4";
            }
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(effMime);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            String ua = web.getSettings().getUserAgentString();
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            String ref = web.getUrl();
            if (ref != null) r.addRequestHeader("Referer", ref);
            // Content-Dispositionの filename*=UTF-8''<タイトル> を優先（旧URLUtilはこれを無視しvideoId名になる）
            String fn = fileNameFromDisposition(contentDisposition);
            if (fn == null) fn = URLUtil.guessFileName(url, contentDisposition, effMime);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn);
            r.setTitle(fn);
            // 進捗バー＋完了通知を出す（VISIBLE_NOTIFY_COMPLETEDは進捗バーを出さない）
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            ((DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(act, "DL開始: " + fn, Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(act, "DL失敗: " + e, Toast.LENGTH_SHORT).show();
        }
    }

    /** Content-Dispositionの RFC5987 filename*=UTF-8''… をデコードして本来のタイトル名を得る。無ければnull */
    private static String fileNameFromDisposition(String cd) {
        if (cd == null) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("filename\\*\\s*=\\s*UTF-8''([^;\\r\\n]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(cd);
            if (!m.find()) return null;
            String name = java.net.URLDecoder.decode(m.group(1).trim(), "UTF-8");
            name = name.replaceAll("[/\\\\\\r\\n\\t:*?\"<>|]", "_").trim();
            if (name.isEmpty()) return null;
            // FSのファイル名上限(255B)対策：拡張子を保って短縮
            if (name.getBytes("UTF-8").length > 200) {
                String ext = "";
                int dot = name.lastIndexOf('.');
                if (dot > 0 && name.length() - dot <= 6) { ext = name.substring(dot); name = name.substring(0, dot); }
                while (name.getBytes("UTF-8").length > 200 - ext.length() && name.length() > 1)
                    name = name.substring(0, name.length() - 1);
                name = name + ext;
            }
            return name;
        } catch (Throwable ignore) {
            return null;
        }
    }

    // ---- 長押しメニュー（リンク/画像） ----

    /** 「新しいタブで開く」の開き先。MainActivityはcreateTab、PwaActivityはnull（項目非表示） */
    public interface LinkOpener { void open(String url); }

    /**
     * 長押し → リンク（新しいタブで開く/URLコピー）・画像（保存/URLコピー）のメニュー。
     * リンク付き画像はアンカーhrefを requestFocusNodeHref で非同期に取ってから出す。
     */
    public static void enableLongPress(Activity act, WebView web, LinkOpener opener) {
        web.setOnLongClickListener(v -> {
            // ホーム(gobie://)では横取りせずページのcontextmenu(PWA長押しメニュー)に渡す
            String cur = ((WebView) v).getUrl();
            if (cur != null && cur.startsWith("gobie://")) return false;
            WebView.HitTestResult hit = ((WebView) v).getHitTestResult();
            int type = hit.getType();
            final String extra = hit.getExtra();
            if (extra == null || extra.isEmpty()) return false;
            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                showLongPressMenu(act, web, extra, null, opener);
                return true;
            }
            if (type == WebView.HitTestResult.IMAGE_TYPE) {
                showLongPressMenu(act, web, null, extra, opener);
                return true;
            }
            if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                // extra=画像URL。リンク先hrefはmsgの"url"で返る
                android.os.Message msg = new android.os.Handler(
                        android.os.Looper.getMainLooper(), mm -> {
                            showLongPressMenu(act, web, mm.getData().getString("url"), extra, opener);
                            return true;
                        }).obtainMessage();
                ((WebView) v).requestFocusNodeHref(msg);
                return true;
            }
            return false;
        });
    }

    private static void showLongPressMenu(Activity act, WebView web,
                                          String link, String img, LinkOpener opener) {
        final java.util.List<String> items = new java.util.ArrayList<>();
        final java.util.List<Runnable> actions = new java.util.ArrayList<>();
        boolean linkOk = link != null && (link.startsWith("http://") || link.startsWith("https://"));
        if (linkOk && opener != null) {
            final String l = link;
            items.add("↗ 新しいタブで開く");
            actions.add(() -> opener.open(l));
        }
        if (linkOk) {
            final String l = link;
            items.add("リンクURLをコピー");
            actions.add(() -> copyText(act, l));
        }
        if (img != null && !img.isEmpty()) {
            final String i = img;
            items.add("画像を保存");
            actions.add(() -> downloadUrl(act, web, i, null, guessImageMime(i)));
            items.add("画像URLをコピー");
            actions.add(() -> copyText(act, i));
        }
        if (items.isEmpty()) return;
        String head = linkOk ? link : img;
        String label = head.startsWith("data:")
                ? "data:画像" : (head.length() > 120 ? head.substring(0, 120) + "…" : head);
        new android.app.AlertDialog.Builder(act)
                .setTitle(label)
                .setItems(items.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .show();
    }

    private static void copyText(Activity act, String s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                act.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", s));
        Toast.makeText(act, "コピーした", Toast.LENGTH_SHORT).show();
    }

    private static String guessImageMime(String url) {
        String u = url.toLowerCase();
        if (u.startsWith("data:")) {
            int c = u.indexOf(';'); int s = u.indexOf(':');
            if (c > s) return u.substring(s + 1, c);
        }
        if (u.contains(".png")) return "image/png";
        if (u.contains(".gif")) return "image/gif";
        if (u.contains(".webp")) return "image/webp";
        if (u.contains(".svg")) return "image/svg+xml";
        return "image/jpeg";
    }

    /**
     * blob: をWebView内のJSでfetchし、FileReaderでdata:URL化してJava側(BLOB_IFACE)へ戻す。
     * blobの実体はWebViewメモリにしか無くDownloadManager非対応なので、この経路でしか落とせない。
     */
    private static void fetchBlob(Activity act, WebView web, String url) {
        Toast.makeText(act, "blob取得中…", Toast.LENGTH_SHORT).show();
        String safe = url.replace("\\", "\\\\").replace("'", "\\'");
        String js = "(function(){"
                + "fetch('" + safe + "').then(function(r){return r.blob();})"
                + ".then(function(b){var fr=new FileReader();"
                + "fr.onload=function(){" + BLOB_IFACE + ".save(fr.result, b.type||'');};"
                + "fr.onerror=function(){" + BLOB_IFACE + ".fail('read');};"
                + "fr.readAsDataURL(b);})"
                + ".catch(function(e){" + BLOB_IFACE + ".fail(''+e);});"
                + "})();";
        web.evaluateJavascript(js, null);
    }

    /** MIMEから拡張子を推定。MimeTypeMap優先、ダメなら画像/動画/PDFを手当て、最後はbin */
    private static String extForMime(String mime) {
        if (mime == null || mime.isEmpty()) return "jpg";
        String e = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mime);
        if (e != null) return e;
        if (mime.contains("png")) return "png";
        if (mime.contains("gif")) return "gif";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("svg")) return "svg";
        if (mime.startsWith("image/")) return "jpg";
        if (mime.contains("mp4")) return "mp4";
        if (mime.startsWith("video/")) return "mp4";
        if (mime.contains("mpeg") || mime.contains("mp3")) return "mp3";
        if (mime.startsWith("audio/")) return "m4a";
        if (mime.contains("pdf")) return "pdf";
        return "bin";
    }

    /** data:image/...;base64,xxx をデコードしてDownloadへ直書き（targetSdk28なので直書き可） */
    private static void saveDataUrl(Activity act, String url) {
        try {
            int comma = url.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("data:URLが不正");
            String meta = url.substring(5, comma); // image/png;base64
            byte[] bytes = meta.endsWith(";base64")
                    ? android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)
                    : java.net.URLDecoder.decode(url.substring(comma + 1), "UTF-8").getBytes("UTF-8");
            String mime = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
            String ext = extForMime(mime);
            boolean img = mime.startsWith("image/") || mime.isEmpty();
            java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            dir.mkdirs();
            java.io.File out = new java.io.File(dir,
                    (img ? "img_" : "dl_") + System.currentTimeMillis() + "." + ext);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(bytes);
            }
            // ギャラリー等に反映させる
            act.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(out)));
            Toast.makeText(act, "保存した: Download/" + out.getName(), Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(act, "保存失敗: " + e, Toast.LENGTH_SHORT).show();
        }
    }
}
